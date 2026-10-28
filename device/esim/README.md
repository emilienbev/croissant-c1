# eSIM tooling: lpac on the Mac, the card in the R1

Drives the removable eUICC in the R1 from the Mac with [lpac](https://github.com/estkme-group/lpac),
without any app code: the way to inspect or repair the card outside the app's eSIM page.

```
lpac (Mac) --stdio JSON--> lpac-r1.py --lines over adb shell--> EsimBridge (R1, root)
     |                                                              |
     +-- HTTPS to the SM-DP+ (curl, on the Mac)                     +-- ITelephony logical
                                                                        channel -> modem -> card
```

- `EsimBridge.java`: a dex run as root with `app_process`. One request per line:
  `open <aid>` -> `ok <channel>`, `tx <channel> <apdu hex>` -> `ok <response hex with
  SW>`, `close <channel>` -> `ok`; `err <message>` on failure; `ready` at start; end of
  input closes every channel. It opens AIDs in lowercase, which is what gets past the
  phone process's ISD-R check. The app's root helper will speak the same protocol.
- `lpac-r1.py`: builds the dex if the source changed (javac and the SDK's d8), pushes it
  to `/data/local/tmp/esim-bridge.dex` if it differs, starts the bridge, runs lpac with
  `LPAC_APDU=stdio LPAC_HTTP=curl` and answers lpac's APDU requests through the bridge.

## Setup (once)

lpac must be built from source: the v2.3.0 release's stdio backend inverts a success
check and fails every request (`if (json_request(...)) return -1`), fixed on main.
Pinned to main at `c7c8d4f817ddfeab21d104870b4647439f9cfab7` (2026-09-17). Needs the
Xcode command line tools and the SDK's CMake (`sdkmanager --install "cmake;3.31.6"`).

```bash
cd device/esim
git clone https://github.com/estkme-group/lpac build/lpac
git -C build/lpac checkout c7c8d4f817ddfeab21d104870b4647439f9cfab7
export PATH=~/Library/Android/sdk/cmake/3.31.6/bin:$PATH
cmake -S build/lpac -B build/lpac/build -G Ninja -DSTANDALONE_MODE=ON -DBUILD_TESTING=OFF -DLPAC_WITH_APDU_PCSC=OFF -DCMAKE_BUILD_TYPE=Release
cmake --build build/lpac/build
DESTDIR=output cmake --install build/lpac/build
rm -rf bin && cp -R build/lpac/output/executables bin
```

`bin/` and `build/` are ignored by git.

## Handing the R1 a code

```bash
device/esim/send-code.sh 'LPA:1$rsp.truphone.com$QRF-SPEEDTEST'
```

Opens Settings > eSIM > Add profile on the device with the code filled in, for adding a
profile from the app without typing it on the panel. Use the script rather than `adb
shell am start` by hand: the dollars have to survive this shell and the device's, and
`adb shell ... -d 'LPA:1$a$b'` arrives as `LPA:1.b` with the `$a` eaten (2026-09-17).
`am` prints "dat=LPA:" for every URI and warns the activity was already running; both
are normal.

## Showing the R1 a QR code

```bash
uv run device/esim/qr.py 'LPA:1$rsp.truphone.com$QRF-SPEEDTEST'
```

Draws the code as a QR code and opens it, to point the camera at from Settings > eSIM >
Add profile. An eSIM QR code holds nothing but the activation code, so this is the same
thing an operator sends. The R1's lens is fixed focus, so it wants the
picture big on the screen and the device about a hand's width away; `--scale` makes the
modules larger.

## Use

```bash
uv run device/esim/lpac-r1.py chip info
uv run device/esim/lpac-r1.py profile list
uv run device/esim/lpac-r1.py --trace /tmp/apdu.log profile download -a 'LPA:1$smdp.example.com$EXAMPLE-MATCHING-ID'
uv run device/esim/lpac-r1.py profile enable <iccid>
uv run device/esim/lpac-r1.py --sim-reset
uv run device/esim/lpac-r1.py notification process -a -r
```

`--serial` (or `R1_SERIAL`) picks the device, the only one on adb by default; everything after the options goes to
lpac (docs/USAGE.md in its repo). `--trace` logs every APDU with its round trip.

The card switches profiles without telling the modem (no REFRESH), so follow every
`profile enable` and `profile disable` with `--sim-reset`: the bridge powers the SIM down
and up and the script waits for `gsm.sim.state` to be `LOADED` again (up to 50 s on a
real profile).

Every command after `chip info` and `profile list` writes to the card.

Measured 2026-09-17: 40 to 60 ms per APDU. lpac sets the channel in CLA (`81...`), the
card then answers `61xx` and lpac fetches the rest with GET RESPONSE, so most ES10
commands cost two round trips.
