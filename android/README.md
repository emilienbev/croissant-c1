# The app

Kotlin and Compose. One app that is the whole device: Device Owner, HOME and lock task.
Push to talk is heard by the Mac's Whisper (`server/`) or a cloud speech model, and Ask's
model answers from the C1 itself.
Targets the R1's 480x640 panel. `./croissant install` builds, installs and provisions it; this
page is for doing it by hand and for changing the code.

## Prerequisites

- Java 17 or newer to start the Gradle wrapper ([SDKMAN!](https://sdkman.io) is the
  easy way: `sdk install java`). `gradle/gradle-daemon-jvm.properties` pins the daemon to
  Java 25, which Gradle downloads on first use; `JAVA_HOME` does not change it. The APK
  targets Java 17 bytecode.
- The Android SDK: `platforms;android-37.0` and `build-tools;36.0.0`, and for the emulator
  the `google_apis / arm64-v8a` API 33 image. `./croissant install` offers to install the
  build's packages when they are missing (installer/README.md).
- NDK 28.2.13676358, for `lpac-jni` (the eSIM library, `lpac-jni/README.md`):
  `sdkmanager --install "ndk;28.2.13676358"`.
- The offline Translator's two AARs (sherpa-onnx and Foxlet, not on Maven Central, not
  in git): run `libs/fetch.sh` once. It downloads both and checks their SHA-256.

The APK is filtered to `arm64-v8a`, the only ABI the R1 has.

## Build and install

```bash
cd android
./gradlew :app:assembleRelease
adb -s <serial> install -r app/build/outputs/apk/release/app-release.apk
adb -s <serial> shell cmd package compile -m speed -f com.bleural.croissant
adb -s <serial> shell su -c 'killall com.bleural.croissant'
```

- Always the release build. From source it is signed with the debug key, unless the
  release key is given ("Releases" below). The app is Device Owner, and an APK signed with
  another key cannot replace it without an uninstall, which removes the owner.
- The compile line matters. A fresh install runs interpreted until the nightly dexopt,
  and a debug build can never be compiled ahead of time on this GSI, so it runs three to
  five times slower.
- The last line restarts the app (HOME relaunches it) so it runs the compiled code.
- The APK carries no font. `ui/Theme.kt` loads Power Grotesk from the app's
  device-protected `files/fonts/` (`/data/user_de/0/<package>`, readable before the first
  unlock; put there by `./croissant font`) and uses the system font without it.

`adb devices` lists serials. First install only:

```bash
adb -s <serial> shell pm grant com.bleural.croissant android.permission.RECORD_AUDIO
adb -s <serial> shell pm grant com.bleural.croissant android.permission.POST_NOTIFICATIONS
```

Before the app is Device Owner the lock screen sits in front of it:

```bash
adb -s <serial> shell wm dismiss-keyguard
adb -s <serial> shell am start -n com.bleural.croissant/.MainActivity
```

Unit tests (the stores and the date logic): `./gradlew :app:testDebugUnitTest`.

## Device Owner

Once, with no account on the device:

```bash
adb -s <serial> shell dpm set-device-owner com.bleural.croissant/.AdminReceiver
```

Launch the app once so `Kiosk.apply` sets the policies, then reboot. The app is then
pinned (lock task), the keyguard and status bar are off, HOME is the app, the screen
sleeps after the idle time in Settings, the microphone and notification permissions are
granted, and SMS is off. Without Device Owner every `Kiosk` call does nothing, so the
emulator is unaffected.

Settings > Power holds Sleep, Restart, Power off and Exit kiosk. Exit kiosk releases
Device Owner so the app can be uninstalled; `dpm remove-active-admin` does not work for
this app. adb keeps working throughout and is the way back if the app ever wedges.

**Root policy row.** Power off, the Tailscale screen and the eSIM page use `su`, and
Magisk's prompt cannot show under lock task. Insert the grant once, with the app's uid
from `adb shell dumpsys package com.bleural.croissant | grep userId`:

```bash
adb -s <serial> shell 'su -c "magisk --sqlite \"insert into policies (uid,policy,until,logging,notification) values(<uid>,2,0,0,0)\""'
```

If the quoting fights you, put the line in a script, push it to `/data/local/tmp` and run
it with `su -c sh`.

**Privacy indicators.** The Wi-Fi screen needs the location grant, and Android would
flash a location chip on every scan, and a camera chip while the camera is open. Turn
both off, after the GSI's SimpleDeviceConfig, which would put the location one back at every
boot. The installer does this:

```bash
adb -s <serial> shell pm disable-user --user 0 org.protonaosp.deviceconfig
adb -s <serial> shell device_config put privacy location_indicators_enabled false
adb -s <serial> shell device_config put privacy camera_mic_icons_enabled false
```

## Side button and wheel

`device/keylayout/mtk-kpd.kl` maps the side button to F12 and
`device/keylayout/och1970_holl_key.kl` maps the wheel to F11 (up) and F10 (down), since
the framework eats DPAD keys before an app sees them. Install both with
`device/keylayout/install.sh` and reboot. `MainActivity` takes the key events and `Core`
decides what a press means: hold to talk, short press to sleep (or the shutter on the
camera's live preview), any press wakes. A hold from sleep is read from the kernel with
root (`KernelButton`), because the framework drops that press.

## Code layout

One rule: **nothing under `com.bleural.croissant` outside `ui/` imports
`com.bleural.croissant.ui`**. That keeps the design swappable.

- `Core.kt` is the device: the service and its state, the stores, the settings, what the
  side button means. One instance, owned by the activity.
- `UiShell.kt` is the seam: `Content(core)` plus the calls the hardware makes on
  whatever design is installed (`cutToAsk`, `toggleCamera`, `shutterPress`, `wheel`,
  `openEsimAdd`, `openUpdate`, `settle`).
- `MainActivity.kt` is the host: the window, the permissions, key events into
  `core.pttDown/pttUp` and `ui.wheel`. It wears the design `ui/Designs.kt` resolves.
- `ui/` holds `Designs.kt` and the kit every design shares: the screen stack, the wheel,
  the pull (`ui/Pull.kt`), the sheet, the motion, the glyphs, the theme (`ui/Theme.kt`).
- `ui/beam/` is Beam, the one design that ships. Another goes beside it and never imports
  it.
- `audio/` capture, cues and ringtone; `data/` the JSON stores; `net/` the server
  protocol; `setup/` the first-run onboarding; `wifi/`, `bluetooth/`, `tailscale/`,
  `esim/` the device pages' back ends; `offline/` the offline Translator; `update/` updates
  from GitHub releases; `atelier/` Atelier's apps (docs/atelier.md): each app's `app.js`
  in a QuickJS of its own (`AppRuntime.kt`, with `assets/atelier/prelude.js`), the pieces
  it returns (`Pieces.kt`) for the design to draw, the installed apps, adding one from
  GitHub, and the bridge to the chat model and the internet. An app's glyph is a Lucide icon
  (`assets/lucide/`, written by `scripts/lucide.py`).
- Ask's prompt is the device's (server/README.md, "The prompt"). `assets/ask/catalog.json`
  holds Ask's built-in tools (the device's, the server's two, `"runs": "server"`, then `tape_ask`), the
  seed variants (`prompts`), a photo hold's rules by the same names (`photo`), the fixed
  parts of a prompt (`parts`: the two list heads and the clock line), the seed a model name
  gets (`suggest`) and the model test's cases (`cases`). `data/AskCatalog.kt` reads it,
  `data/Prompts.kt` (`PromptsStore`) keeps the variants and the pick per chat model, and
  `data/AskPrompt.kt` puts each hold's prompt and tools together, the enabled Atelier apps'
  included, and tags it for the log. The server's `tools/c1_prompt.py` does the same in
  Python; both are held to `src/test/resources/ask/prompts.golden.json` (`AskPromptTest`
  owns it, the server's `tests/test_c1_prompt.py` checks against it).
- Every model call is the C1's. A hold on the Mac's speech model opens the server's
  socket for its words alone (`"mode":"dictate"`, server/README.md "The protocol"); on a
  cloud one (`Core.holdSpeech`) no socket opens: the audio is kept on the C1
  and heard at the release by `ask/Speech.kt` (no live words), so a cloud chat model with cloud
  speech needs no Mac at all. A tape goes to the server (`"mode":"transcribe"`) or, on a cloud
  model, from the C1 a piece at a time. Once the words are in, `VoiceService` runs the rest in
  its own scope, to its end whatever the page does: Ask's loop (`ask/Loop.kt`) on the chat
  model picked (`Core.holdChat`), its tools here (the agenda, the timers, an Atelier app's,
  `tape_ask`), `web_search` by the server's `GET /search`, and a picture by the Mac's model
  (the `generate` frame, `net/Generations.kt`) or a cloud one (`Core.holdPictures`,
  `ask/Pictures.kt`), which lands on the shelf from the C1 directly, its row marked `c1` so the
  server is never asked for it; one cut short by a restart says so and has Try again. A photo
  hold's photo goes to the chat model and never to the server. The Transform's description pass
  (only for the Mac's picture model), the Translator and an app's `ask()` are `ask/Calls.kt`;
  the model test is `ask/Test.kt`, through the same loop.
- `ask/` holds the three chat clients (`Chat.kt`: Ollama's own API, reached at the server's
  host on 11434, OpenAI-compatible, Gemini's own), the C1's own providers (`Providers.kt`: the
  presets, one's own OpenAI-compatible server), their keys (`Keys.kt`: sealed by the Android
  keystore) and what each lists (`Listing.kt`), kept together by `AskProviders.kt`, which tries
  a key or an address before it keeps it; and what every call the C1 makes takes and costs
  (`Ledger.kt`, `Prices.kt` from models.dev, `Costs.kt`), added in Settings > usage to what the
  server counted of its own. Settings > ask picks speech, pictures and the transform among the
  Mac's own models (`GET /providers`, its local ones) and the C1's providers'; Settings > server
  shows the helper and what it runs. `served/` is the page a phone edits them on (`assets/served/`, port 8767): one
  address, the tailnet's unless Wi-Fi is picked, a one-time code in the QR, the JSON in
  `ConfigApi.kt`, up only while the R1's "on a phone" page is open.

**Adding a design:** a package under `ui/` with a class implementing `UiShell`, and an
entry in `ui/Designs.kt`. Nothing in `Core` moves. Settings > Device > Design shows itself
once there are two looks to pick from. A new feature goes in `Core` first, then into each
design's screens, in separate commits.

## Emulator

An AVD shaped like the R1: 480x640 at 280 dpi, microphone on. Good for layout and type;
it has no side button and no wheel. The emulator reaches the server at `10.0.2.2`.

## Data on the device

Prefs and the stores live in device-protected storage
(`/data/user_de/0/com.bleural.croissant/files/`), because the app starts before the user is
unlocked: `reminders.json`, `events.json`, `timers.json`, `translations.json`,
`conversations.json`, `prompts.json` (Ask's prompt variants), `scores.json` (the model test's last scores), `providers.json`, `keys.json` (sealed) and
`listings.json` (the C1's providers), `usage.jsonl` and `prices.json` (its count and prices), and the `photos/`, `generated/`, `recordings/` and `atelier/apps/<id>/`
folders (an Atelier app's `croissant.json`, `croissant.js`, `source.json`, `state.json`, in a folder named by its id). Each
store is a JSON file rewritten through a temp file and a rename. The offline
Translator's models are in `/data/data/com.bleural.croissant/files/offline/`, pushed by
`device/offline-models/push.sh`.

Read a file with `adb shell su -c cat <path>`. Root can write there only if each file
and folder is then chowned to the app's uid and given the app folder's SELinux label
(`chcon $(stat -c %C <app folder>)`), as the installer does for the font and
`croissant atelier run` for an app.

## Releases

**Version.** `VERSION` at the repo root is the one version of the app, the server and the
installer. `versionName` is `X.Y.Z` and `versionCode` is `X*10000 + Y*100 + Z` (0.1.0 is
100), so minor and patch stay below 100. `scripts/bump-version.sh X.Y.Z` sets it
everywhere; `scripts/check-version.sh` checks it. `croissant.updateRepo` in
`gradle.properties` (`BuildConfig.UPDATE_REPO`) names the GitHub repo releases come from;
a fork sets its own.

**Signing.** `assembleRelease` signs with the release key when `CROISSANT_KEYSTORE` (an
absolute path), `CROISSANT_KEYSTORE_PASSWORD`, `CROISSANT_KEY_ALIAS` and
`CROISSANT_KEY_PASSWORD` are all set, as environment variables or Gradle properties, and
with the debug key when none are. Only the release workflow
(`.github/workflows/release.yml`) needs the key. An install only takes updates signed
with its own key: a release cannot replace a build from source or the reverse without an
uninstall, which unbinds Device Owner.

**The release key**, made once by the maintainer. keytool asks for the password without
echoing it; a PKCS12 keystore has one password, so the key password is the same:

```bash
keytool -genkeypair -v -keystore croissant-release.jks -alias croissant \
    -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Croissant"
```

Back up `croissant-release.jks` and its password offline, in two places. Lost, no release
can ever update the devices installed from releases. Never commit it (`*.jks` is ignored).

**The repository secrets**, from the directory holding the keystore. `gh secret set`
without `--body` asks for the value and does not echo it:

```bash
base64 < croissant-release.jks | gh secret set CROISSANT_KEYSTORE_B64 --repo emilienbev/croissant-c1
gh secret set CROISSANT_KEYSTORE_PASSWORD --repo emilienbev/croissant-c1
gh secret set CROISSANT_KEY_ALIAS --repo emilienbev/croissant-c1 --body croissant
gh secret set CROISSANT_KEY_PASSWORD --repo emilienbev/croissant-c1
```

**Cutting a release**, from a clean `main` that CI passes:

```bash
scripts/bump-version.sh X.Y.Z
git add VERSION server/pyproject.toml server/uv.lock installer/pyproject.toml installer/uv.lock
git commit -m "Version X.Y.Z"
git tag -a vX.Y.Z -m "Croissant X.Y.Z: what changed"
git push origin main vX.Y.Z
```

The tag runs `release.yml`: it checks the tag against `VERSION`, stops if a secret is
missing or the APK carries the debug key, and publishes `croissant-X.Y.Z.apk`,
`ortload-arm64` (the offline Translator's loader, `device/offline-models/build-ortload.sh`),
the CLI (`croissant-cli-X.Y.Z.tar.gz` and its `install.sh`, `scripts/build-cli.sh`, installed
once by the release job before it is published) and their `.sha256`, with the tag's message as the notes (generated notes without one).
Settings > update on the C1 shows the first three lines of the notes, list marks dropped:
write those for the device.

**Updates on the C1** (`update/Updater.kt`, Settings > update in Beam). The C1 looks at
`releases/latest` of its update source once a day (on a wake, at most daily, while check
daily is on) and when the page opens, unauthenticated, so the source must be public.
- **The source** is `croissant.updateRepo` until the installer or Settings > update >
  source sets another. The installer leaves its answer as `files/update-source` in the
  app's device-protected storage, which the app takes into its settings at its next start
  (installer/README.md, "Where updates come from").
- **The key:** a release only installs over a C1 signed with the same key. The app checks
  the downloaded APK's signer before installing, since Android stops the app before it
  checks the signer itself, and says so when it differs.
- **Download** is its own step and never locks anything: it goes on behind every page and
  while the C1 sleeps, over Wi-Fi only unless download on esim is on (the download watches
  the networks itself, since the status the pages read stops during sleep), into the app's
  own storage, resumed where it stopped after a drop or a restart, retried with growing
  waits, and checked when done (size, the sha256 GitHub publishes, signer, a newer
  version). It then waits, downloaded, until the user installs it.
- **Install** needs 50 % of battery, or 25 % on the charger, and is the only step that
  locks. The app installs itself as Device Owner (no prompt, no permission). From the tap
  until the new version is up, nothing interrupts it: the side button, the wheel, the
  edges and power off are refused, and the screen stays on. The locked page lists four
  steps (checking the download, installing, compiling, restarting) with the time left,
  timed from this C1's last install and compile (`update_install_ms`, `update_compile_ms`
  in prefs), since Android reports no progress. Android kills the app to replace it; a
  marker in prefs brings the next process back onto the same steps, which compiles the
  new code as root (`cmd package compile -m speed -f`, about 35 s on the R1) and restarts
  once more, then shows "updated from" the old version with the notes. A marker that does
  not match the version that comes up, or is ten minutes old, means the install failed:
  the page says why when Android did and keeps the file.

## Known issues

- The first-run onboarding is drawn only by Beam. It opens by itself until it has been
  finished once (Wi-Fi, Tailscale, the server and the time can each be skipped), and
  again from Settings > Device > setup.
- The audio ring buffer is not cleared between utterances, so a fast second press can
  start with up to 1.4 s of the previous one.
- On the front camera against a bright ceiling the caption is white on white.
- Mobile data on a free eSIM test profile passes HTTP but has no working DNS, so the
  tailnet cannot run on it.
