# Croissant: rules for changing the code

For anyone, human or agent, changing this repo. What it is: README.md. How each part
builds and runs: android/README.md, server/README.md, installer/README.md. Check claims
against the device or the code, not the docs.

## The device

- The application id is `com.bleural.croissant`, set once as `croissant.applicationId` in
  `android/gradle.properties` (the installer reads it there too; a fork sets its own). It
  must never change once installed: a new id is a new app, and the installed one stops
  being Device Owner. The Kotlin namespace and packages stay `com.bleural.croissant`.
- Canvas: 480x640 px at density 280, so 274x366 dp (1 sp = 1.75 px). Glanceable, few
  elements per screen, no text below 15 sp.
- Side button: hold to talk from any screen (jumps to Ask), short press sleeps, any
  press wakes. It arrives as F12. The scroll wheel arrives as F11 (up) and F10 (down).
  Both come from the key layouts in `device/keylayout/`, installed by its `install.sh`
  and a reboot.
- No system bars, ever, and no top bar. Pages run full screen. The control centre is
  pulled down from the top edge. Back is an edge drag from the left or the header
  chevron; there is no system back.
- Every page steps back through the shared elastic pull, so wheel up at the top and a
  finger pulled down both show the same stretch. Use `PullState` (`ui/Pull.kt`:
  `rememberPull`, `Modifier.elasticPull`) or Beam's helpers in `ui/beam/Kit.kt`
  (`rememberWordsScroll`, `rememberRows`, `PulledCanvas`). Never call `onBack` straight
  from a `WheelTarget`.
- Power off, the Tailscale screen and the eSIM page need a persistent Magisk root policy
  row for the app (android/README.md).

## The UI seam

- The UI is swappable. Nothing under `com.bleural.croissant` outside `ui/` imports
  `com.bleural.croissant.ui`; `MainActivity` imports only `ui/Designs.kt`.
- `Core.kt` is the device. `UiShell.kt` is the seam.
- Designs live side by side, one package each under `ui/` (`ui/beam/` is the one that
  ships), registered with their variants in `ui/Designs.kt`, picked live in Settings >
  Device > Design once there is more than one look. A design package never imports another.
- A feature goes in `Core` and then into each design's screens, in separate commits.
- Every text style starts from `Grotesk` (`Grotesk.copy(...)`, `ui/Theme.kt`), never a
  bare `TextStyle`.
- Every text field is `OwnTextField` (`ui/Keyboard.kt`), typed on the design's own keys or
  the plain ones (`ui/PlainKeys.kt`). The system keyboard is blocked app-wide
  (`NoSystemKeyboard`, round every design in `Designs.build`); `KeyboardGuardTest` fails on
  any other text field.

## Build and install

Always the release build, always followed by the compile and a restart:

```bash
cd android && ./gradlew -q :app:assembleRelease
adb -s <serial> install -r app/build/outputs/apk/release/app-release.apk
adb -s <serial> shell cmd package compile -m speed -f com.bleural.croissant
adb -s <serial> shell su -c 'killall com.bleural.croissant'
```

- Release is signed with the debug key unless the release key is given (android/README.md,
  "Releases"), so it replaces an install built on the same machine in place.
- Without the compile line a fresh install runs interpreted until the nightly dexopt.
  The restart makes the app use the compiled code: `install -r` relaunches it (it is
  HOME) before the compile. A debug build can never be compiled ahead of time on this
  GSI and runs three to five times slower (PERFORMANCE.md). Use debug only for a quick
  compile check.
- The Gradle daemon JVM is pinned by `android/gradle/gradle-daemon-jvm.properties`;
  `JAVA_HOME` changes nothing.
- The app is HOME and a device admin: it cannot be force-stopped, and killing it
  relaunches it.
- Server: `cd server && uv run croissant-server`. Use uv for all Python, never pip or venv.

## Probing over adb

- Screenshot: `adb -s <serial> exec-out screencap -p > shot.png`.
- Wheel ticks: `input keyevent 140` (down), `141` (up). For anything timed, write the
  wheel's events with `sendevent` on `/dev/input/event2` instead: `input` starts a JVM
  per tick.
- A side-button hold: as root, `sendevent /dev/input/event0 1 116 1`, a sleep, then
  `sendevent /dev/input/event0 1 116 0`. Never `input keyevent --longpress` for F12:
  its instant key-up reads as a short press and sleeps the device.
- Do not tap blindly on the Wi-Fi list: a stray tap can join a network.
- Quoting through `adb shell su -c` breaks on parentheses. Push a script to
  `/data/local/tmp` and run it with `su -c 'sh <script>'`.

## Commits

- Subject: `type(scope): summary`, in the imperative, lower case, no period, under 72
  characters. Types: `feat`, `fix`, `refactor`, `perf`, `docs`, `test`, `build`, `chore`.
  The scope is the area: `beam`, `ui`, `core`, `server`, `installer`, `device`, `docs`.
- Body, when behaviour changes: a `Before:` line and an `After:` line, short and concrete.
  Otherwise a line or two on what changed and why.

```
feat(beam): add a delete key to the reminder page

Before: a reminder could only be checked off or renamed.
After: an X in the danger red, left of the check, deletes it through the confirm sheet.
```
