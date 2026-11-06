# Signing your own builds

A C1 only takes updates signed with the key it was installed with. The releases here are
signed with this repository's key, so a build of your own cannot update a C1 installed from
them, and moving between the two means uninstalling the app, which erases the C1's data.

With a fork and a key of your own, you build and release from your fork, your C1 updates
from it, and it keeps its data.

Every command below works on macOS and Linux (WSL2 too), in bash or zsh. You need git,
Java 17+ for `keytool` (building the app needs it anyway, and `./croissant install` offers
it) and the GitHub CLI: `brew install gh` on macOS, or your distribution's package on Linux
([cli.github.com](https://cli.github.com)). Then sign in once:

```bash
gh auth login
```

## 1. Fork and clone

```bash
gh repo fork emilienbev/croissant-c1 --clone
cd croissant-c1
ME=$(gh api user -q .login)
```

`ME` is your GitHub name, used by the steps below. Set it again in a new terminal.

## 2. Take updates from your fork

```bash
sed -i.bak "s|^croissant\.updateRepo=.*|croissant.updateRepo=$ME/croissant-c1|" android/gradle.properties && rm android/gradle.properties.bak
git commit -am "build: take updates from my fork"
git push
```

Leave `croissant.applicationId` in that file as it is. A new id is a new app.

## 3. Make your key

```bash
mkdir -p ~/croissant-keys
keytool -genkeypair -v -keystore ~/croissant-keys/release.jks -alias croissant \
    -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=$ME"
```

keytool asks for a password twice. Back up `release.jks` and its password somewhere safe.
Without them your C1 can never take your updates again without being erased. Keep the key
out of the repository.

## 4. Builds on your computer

```bash
printf 'Keystore password: '; read -rs PW; echo
mkdir -p ~/.gradle
cat >> ~/.gradle/gradle.properties <<EOF
CROISSANT_KEYSTORE=$HOME/croissant-keys/release.jks
CROISSANT_KEYSTORE_PASSWORD=$PW
CROISSANT_KEY_ALIAS=croissant
CROISSANT_KEY_PASSWORD=$PW
EOF
chmod 600 ~/.gradle/gradle.properties
unset PW
```

Gradle reads this file for every build, so `./croissant install` and
`./croissant app install .` now sign with your key.

## 5. Builds on GitHub

```bash
base64 < ~/croissant-keys/release.jks | gh secret set CROISSANT_KEYSTORE_B64 --repo "$ME/croissant-c1"
gh secret set CROISSANT_KEYSTORE_PASSWORD --repo "$ME/croissant-c1"
gh secret set CROISSANT_KEY_ALIAS --repo "$ME/croissant-c1" --body croissant
gh secret set CROISSANT_KEY_PASSWORD --repo "$ME/croissant-c1"
```

gh asks for each password without showing it. GitHub keeps Actions off in a new fork:
open your fork's Actions tab on github.com and enable them.

## 6. Move your C1 to your key

Once, and only for a C1 that runs a release from here. It erases the C1's data, so save
what you want to keep first: the send key in Photos, and on each transcribed tape.

On the C1, open Settings > power > exit kiosk. Then, with it on USB:

```bash
adb uninstall com.bleural.croissant
./croissant install
```

Pick the app built from source, and keep your fork when it asks where updates come from.
It skips what is already done, like the flash, and installs your build in place of the old
one.

A new R1 needs only `./croissant install`, with the same two answers.

## 7. Release

```bash
scripts/bump-version.sh 0.1.1
git add VERSION server/pyproject.toml server/uv.lock installer/pyproject.toml installer/uv.lock
git commit -m "Version 0.1.1"
git tag -a v0.1.1 -m "What changed"
git push origin main v0.1.1
```

Each release needs a version above the one on your C1. Your fork's Actions build it and
publish it on your Releases page, and your C1 offers it in Settings > update, where it
looks once a day. The first three lines of the tag's message are what the C1 shows.

The CLI installs from your releases too:

```bash
curl -fsSL "https://github.com/$ME/croissant-c1/releases/latest/download/install.sh" | sh
```

## Taking in changes from here

```bash
git pull --no-tags upstream main
git push
```

`--no-tags` keeps this repository's version tags from clashing with yours.
