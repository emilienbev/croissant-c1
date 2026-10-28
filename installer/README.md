# croissant, the installer

Takes a Rabbit R1 from rabbitOS to Croissant in one command: unlock, LineageOS GSI,
Magisk, tailscaled, key layouts, boot screens, the app as Device Owner. The app's first
setup does the rest: Wi-Fi, the tailnet login, the server, the time zone.

## Before

- **rabbit's permission to unlock.** In rabbithole: void the warranty and enable
  developer mode (settings > r1, with the R1's IMEI), then settings > r1 > developer >
  device modification > unlock, with the R1 on and online. That only allows the unlock.
  The installer does the unlock itself.
- macOS or Linux, and a USB cable that carries data.
- The CLI, installed from a release (below), or a checkout of this repo, where it is
  `./croissant`.
- [uv](https://docs.astral.sh/uv/). Without it, install.sh puts it (pinned) in
  `~/.croissant/cache/uv`, and `./croissant` in a checkout offers to put it in `cache/uv`;
  neither touches the PATH for uv.
- The app, one of three ways, asked during the install:
  - **Built from source**, signed with your own `~/.android/debug.keystore`. Needs Java 17+
    and the Android SDK with NDK 28.2.13676358. When either is missing the installer says
    what and offers it: Java through [SDKMAN!](https://sdkman.io) (the recommended way to
    install and switch JDKs), the SDK where Android Studio keeps it (`~/Library/Android/sdk`,
    `~/Android/Sdk` on Linux, about 3 GB), with Google's licences shown to accept. Not
    possible on Linux arm64, which has no NDK.
  - **The latest release** of the repository the C1 updates from (`--apk latest`),
    checked against its `.sha256`.
  - **An APK file** (`--apk FILE`).

  The CLI installed from a release (below) has no source to build: it offers the latest
  release, an APK file, or a checkout of the source elsewhere (`--apk DIR`).

  Every later update must be signed with the same key: an uninstall unbinds Device Owner.
- **On Linux**, access to the R1 over USB. The installer checks for a udev rule for its two
  USB vendors (0e8d MediaTek, 18d1 Google) and offers to write
  `/etc/udev/rules.d/52-croissant.rules` with sudo, which also keeps ModemManager off the
  preloader's serial port.
- **Power Grotesk**, the app's typeface, is required. It is commercial (free for personal
  use), so nothing here ships it: download the zip from
  [BeFonts](https://befonts.com/power-grotesk-font.html) in a browser. The installer's font
  step asks for the zip (offering the newest `power-grotesk-font*.zip` in `~/Downloads`),
  checks Light, Regular and Medium against pinned sha256s (`installer/croissant/font.py`),
  and keeps them in `cache/fonts`, where the boot screen, charger and server tools read
  them too. Once kept, it is not asked for again.

## Installed from a release

```bash
curl -fsSL https://github.com/emilienbev/croissant-c1/releases/latest/download/install.sh | sh
```

Each release publishes the CLI beside the app (release.yml): `croissant-cli-X.Y.Z.tar.gz`, what
the CLI needs of the repo (this folder without its tests, `device/`, `scripts/`, the launcher,
`android/gradle.properties` and Lucide's glyphs) with `install.json` naming the repository and
the version; its `.sha256`; and `install.sh`, with both written in. `scripts/build-cli.sh`
makes all three from a checkout, into `dist/`.

install.sh checks it runs on macOS or Linux, x86_64 or arm64, with curl or wget, and
sha256sum or shasum, then:

- downloads the tarball and checks it against its `.sha256`, changing nothing if they differ;
- unpacks it in `~/.croissant/versions/X.Y.Z` and has uv make its environment there (uv put
  in `~/.croissant/cache/uv` first if the machine has none);
- starts it once, then points `~/.croissant/current` at it and writes
  `~/.croissant/bin/croissant`, which runs it. The version before stays, older ones go;
- adds `~/.croissant/bin` to the PATH, once, in the shell's startup file: `~/.zshrc` (or
  `$ZDOTDIR`'s), `~/.bashrc` on Linux, the first of `~/.bash_profile`, `~/.bash_login` and
  `~/.profile` for bash on macOS, fish's `conf.d/croissant.fish`, else `~/.profile`. Two
  lines, marked: remove them to undo.

| Variable | |
|---|---|
| `CROISSANT_HOME` | another folder than `~/.croissant` |
| `CROISSANT_VERSION=X.Y.Z` | another release than the one whose install.sh it is |
| `CROISSANT_NO_MODIFY_PATH=1` | no startup file changed |
| `CROISSANT_DIST=DIR` | the three files from a folder, not GitHub (`scripts/build-cli.sh DIR` makes them) |

Installed, the CLI keeps its cache in `~/.croissant/cache`, across upgrades, and has no source
to build the app from: `croissant install` takes the latest release unless given an APK or a
checkout (`--apk FILE|DIR`). `--offline-models` needs a checkout. Everything else is the same.

- `croissant upgrade`: the latest release of the repository it came from, by running that
  release's install.sh; nothing when it is already the latest. `--to X.Y.Z` for another,
  older ones too. A checkout says to `git pull` instead.
- To remove it: `rm -rf ~/.croissant` and the two marked lines in the startup file.

## Run

```bash
./croissant install
```

It asks, with arrow-key menus, for what it needs: whether rabbit has allowed the unlock
(and how to get that if not), the font, the name on the boot screens, which LineageOS (the
proven build, the current ones on SourceForge, or any other by URL or file), where the C1
gets its updates, where the app comes from (built from source, that repository's latest
release, or an APK file), which interface sounds (each pack plays its demo on this machine as
the arrows move onto it: afplay on a Mac, ffplay elsewhere), and extras. Then it shows the plan and asks to go ahead. It ends with "Done", and the R1's own
setup takes over.

- Erases the R1 (after you type `erase`). Everything else it asks for is physical: turn
  the R1 off, unplug and replug it when told, keep it plugged in and hold the side button
  about 15 s after the preloader read, and scroll the wheel up if the R1 asks to confirm
  the unlock.
- Everything it keeps stays in this checkout, in `cache/` (gitignored, `CROISSANT_CACHE`
  moves it): about 1.1 GB downloaded once (the GSI, Magisk, tailscale, mtkclient, and
  platform-tools when this machine has no recent adb), 3.3 GB with the GSI unpacked, plus
  the font, the stock boot images, each R1's work files, the boot screens, a downloaded
  release, and uv and Google's sdkmanager when it installed them. Every download is
  hash-checked (a URL given without a checksum is warned about), and mtkclient is pinned to
  a commit. Outside the checkout it uses what is already yours: the Android SDK, the
  `~/.android/debug.keystore` a build signs with, and uv's own cache. What it can add
  there each needs your yes first: SDKMAN! and its Java, the SDK's packages, and on Linux
  the udev rule.
- Stopped halfway, or something failed? Run it again: each stage checks before it acts,
  the flash is skipped once the R1 runs a GSI (unless you choose to flash again), and the
  answers given before are the defaults.

Every question has a flag, and `--no-input` (or no terminal) asks nothing:

| Flag | Default | |
|---|---|---|
| `--name NAME` | `croissant` | the boot logo and animation (the default is the committed pair) |
| `--gsi proven\|URL\|FILE` | proven | LineageOS 20, 2025-10-21. Anything else is untried on an R1 |
| `--gsi-checksum HEX` | | sha256 or md5 for a `--gsi` URL |
| `--apk FILE\|DIR\|latest` | asked, else built here (installed: `latest`) | an APK of the app, a checkout of the source to build, or `latest`: the update repository's latest release |
| `--update-repo OWNER/REPO` | the last answer, else `croissant.updateRepo` | where the C1 gets its updates (below) |
| `--font PATH` | kept before | the Power Grotesk zip, or a folder holding its TTFs (needed with `--no-input` until kept) |
| `--magisk FILE\|latest\|TAG` | v30.7 | another Magisk |
| `--tailscale latest\|VERSION` | latest | another tailscale (the Tailscale page upgrades it later) |
| `--offline-models` | off | the offline Translator (1.4 GB, needs the NDK) |
| `--reflash` | off | flash LineageOS again on an R1 that already runs it (erases it) |
| `--serial S` | the only one | which R1 |
| `--yes` | | erase without asking (needed with `--no-input`) |

Other commands:

- `./croissant doctor`: every check, nothing changed. It reports Java, the Android SDK, the
  udev rule on Linux, the font here and on the R1, and whether the R1's stock boot images
  are kept.
- `./croissant app install APK|DIR`: a chosen app onto an installed R1, in place of the one
  there: an `.apk`, or a checkout of the source (its root or `android/`) built first. The C1
  otherwise updates itself over the air (Settings > update).
- `./croissant boot-screens NAME`: the logo and animation, to look at, in `cache/boot-screens`.
- `./croissant fetch --seed <dir>`: download ahead, mtkclient included. `--seed` reuses
  files already on this machine.
- `./croissant font [--font PATH]`: the same font step, then the font onto an installed
  R1 and the app restarted.
- `./croissant sounds [PACK]`: the interface sounds onto an installed R1, picked by ear as
  in the install (or named: bubble, grove, kalimba, shrine, switch), and the app restarted.
  Asleep, the R1 takes them when it wakes. sounds/README.md says what each pack is. On
  the R1 itself, Settings > Device > Tones does the same.
- `./croissant atelier new [NAME]`: a new Atelier app (docs/atelier.md) to start from,
  asked for in order: its name (the folder and the GitHub repository, made lowercase letters,
  digits and dashes, which is also its id on the C1), its GitHub owner (offered from `gh`'s
  login, else `git config github.user`; empty for none yet), its name on the C1, a line about
  it, its hue (Beam's neons as swatches, or any `#RRGGBB`), its glyph (a word searched in
  Lucide's names and tags, the matches drawn in the terminal, picked by number) and what it
  needs: the network, the chat model, your words, a tool for Ask (its name, what it does and
  a sentence that calls it). It makes the folder beside NAME (refused if it holds anything):
  `croissant.json`, `croissant.js` (a count the wheel and the keys move, with each feature
  asked for in use), a README with the QR code, `qr.svg` and `qr.png`, and a `.gitignore`,
  as a git repository on `main` with a first commit in your own git identity. Last it offers,
  default no, to publish it with `gh repo create OWNER/NAME --public --source DIR --push`.
  Every question has a flag (`--owner`, `--title`, `--description`, `--hue`, `--glyph`,
  `--uses network,model,words`, `--tool`, `--tool-description`, `--tool-example`, `--no-git`);
  with `--no-input` the rest take their defaults and nothing calls `gh`.
- `./croissant atelier qr [FOLDER|OWNER/REPO]`: an app's QR code again, `qr.svg` and
  `qr.png`. For a folder, its hue and glyph from `croissant.json`, its repository from the
  folder's git remote (else the folder's name with `--owner`), and the code put in its README
  (under the title when it has none yet); `--readme` makes the whole README again from
  `croissant.json`, keeping its first paragraph (or `--description`) as the app's line. For `OWNER/REPO`, `--hue` (and `--glyph`) say how it looks, into the current folder
  or `--out`.
- `./croissant atelier run FOLDER`: an Atelier app (docs/atelier.md) from a folder onto the
  C1, again on every save, with the app's log and errors as they come. Its id there is the
  folder's name, lowercase letters, digits and dashes. `--fresh` drops its saved state on
  the C1, `--once` pushes once.

### An app's QR code

It holds the repository's link, `https://github.com/OWNER/REPO`: what the C1's add page takes
(Atelier, +, the scan key) and what a phone opens. It is drawn as Beam draws: black dots on the
app's hue, the hue a rounded tile on a black card, the finders softly rounded squares, and the
app's glyph in its hue on a black disc in the middle, as the Atelier board draws an app. The hue
is the one the C1 shows: a dark pick is lifted as the C1 lifts it (`liftHue`), so black reads on
it. Error correction H carries the middle; four modules of hue round the code are its quiet
zone. No words in it, since Power Grotesk cannot be shipped: the README says the name.

Dark on the hue, not the hue on black. The C1's reader tries a code inverted, but readers that
do not read nothing of a light code on black: zxing-cpp without `try_invert` and OpenCV's both
miss it, and read the dark one (the tests decode every sample both ways with zxing-cpp, the
library the C1 uses, from the dev group). The finders are rounded only a little for the same
reason: OpenCV's detectors start missing finders rounded further, where zxing-cpp still reads them.

## What it does, in order

| Stage | Where |
|---|---|
| Power Grotesk, downloads, APK, boot screens | Mac |
| Stock `boot_a`, `boot_b` and `frp` read by mtkclient (rabbit's unlock permission checked) | preloader |
| Into the bootloader: FASTBOOT to the preloader | preloader |
| `flashing unlock` (the wheel says yes, if asked) | LK |
| Live slot from fastbootd | fastbootd |
| `set_active`, a blank vbmeta with verity and verification off, both slots | LK |
| GSI into `system_<slot>`, erase `userdata` and `md_udc` | fastbootd |
| First boot: wizard and launcher off | Android |
| Magisk: the live slot's stock boot image, `boot_patch.sh` on the R1 plus the shell's grant (`device/grant/`), checked, flashed, then `/data/adb/magisk` and root for adb | Android, fastbootd |
| device_admin module, key layouts, boot animation, tailscaled, privacy indicators, density 280, the 24-hour clock and no window transitions | Android (root) |
| Off-mode hook and charge-only screen in the boot ramdisk (`device/offmode/install.sh`), LK's off-mode-charge to 0 | Android (root) |
| Boot logo and charging screens, from the R1's own partition | LK |
| APK, speed compile, the update source, root for the app, Device Owner, reboot | Android |
| The sound pack, as `sound-pack` in the app's device-protected `files/` | Android (root) |
| Power Grotesk into the app's device-protected `files/fonts/`, as the app's data | Android (root) |
| The app restarted once, on its compiled code, unless the Device Owner reboot just did it | Android (root) |

## Where updates come from

The C1 updates itself from the releases of one GitHub repository, and only to an APK signed
with its own key (android/README.md, "Releases"). The install asks which repository,
`owner/repo` or a github.com link to it, offering the last answer, else
`croissant.updateRepo` from `android/gradle.properties` (a fork sets its own there). Keep it
unless you publish your own builds. It then looks at that repository's latest release and
says what it found. None yet is fine: the C1 looks again daily.

The answer reaches the app as `update-source` in its device-protected `files/`, moved in as
the font is. The app takes it into its settings at its next start and deletes it, so the
install restarts the app after the compile. Change it later in Settings > update on the C1.
Running `./croissant install` again hands over its answer again.

## The stock boot image

Magisk patches rabbit's own boot image, and neither fastbootd (no `fetch`) nor LineageOS
(`adb root` refused unless "Rooted debugging" is on) hands it over. The preloader does: on
the R1 it is unprotected, so [mtkclient](https://github.com/bkerler/mtkclient) reads
partitions from a powered-off R1, bootloader still locked, writing nothing. On a stock R1
this comes first: turn it off and unplug it, plug it in when told, then keep it plugged in
and hold the side button about 15 s until it restarts (after a read it stays in download
mode until then): the installer is already listening for its preloader and sends it to the
bootloader. Unplugged, it would boot rabbitOS instead. One read takes `boot_a`, `boot_b` and `frp` (its last byte is rabbit's unlock
permission, and the installer stops there if it is not set). The boot images are kept in
`cache/stock-boot/<serial>/`, both slots, each with its kernel's release and
build (`boot_<slot>.kernel`), and Magisk takes the live one when its kernel is the one the
R1 runs (`/proc/version`). Otherwise it is read again. The trimmed `stock-boot.img` an
earlier installer kept in `cache/devices/<serial>/` is taken too, once checked. An R1 already on
LineageOS without a copy here gets the same read: the installer turns it off, and after
the read the side button hold, plugged in, restarts it. With Magisk already on it and no
copy here, the installer stops before turning anything off: the live slot is Magisk's
image, and rabbit's has to come from elsewhere.

## Root for the adb shell

LineageOS refuses `adb root` ("Rooted debugging" is off and the shell cannot turn it on),
so the installer's root is Magisk's `su`, and su from the shell would ask Grant or Deny on
the R1 with nobody there to tap it. The patched boot image carries a Magisk `overlay.d`
boot script (`device/grant/`): once Android has booted it writes Magisk's policy row
granting uid 2000 root for good, quietly, only when it is missing. After flashing Magisk
the installer waits for it (`init.svc.croissant-grant` stopped, then `su -c id`) before
its first su. The patch is checked before it is flashed: kernel and dtb identical, and the
ramdisk is the stock one with Magisk's files and exactly these two added.

mtkclient is GPL-3 and not part of this repo: the installer installs it at a pinned commit
into its own venv in the cache (`cache/mtkclient`, with uv), with libusb from
PyPI's libusb-package, so nothing comes from Homebrew. On Linux it needs access to the
preloader (USB vendor 0e8d): the udev rule the installer offers at its start (above), or
mtkclient's own (`Setup/Linux/52-mtk.rules` in its repo) with ModemManager stopped for the
read. The installer prints the commands when it is refused.

The device-side work is the repo's own scripts (`device/*/install.sh`,
`device/service.d/`, `device/offmode/`, `device/grant/`, `scripts/enter-fastboot.py`, `device/logo/`, `device/bootanim/`).
The installer pushes and runs them.

## Proven on two R1s

The installer was built against one R1 and checked on the Mac and the emulator: the
blank vbmeta, the boot image trim, the logo partition and the headless Magisk patch each
match the result of the same step done by hand on the first R1. A second R1 went from
stock to Croissant with it end to end on 2026-10-10.
