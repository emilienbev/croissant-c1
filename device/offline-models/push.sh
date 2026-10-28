#!/bin/sh
# Put the offline Translator's models on the R1. Run on
# the Mac, from anywhere, with the R1 on USB and android/libs/fetch.sh already run:
#
#   device/offline-models/push.sh [SERIAL] [PACKAGE]   (SERIAL: the only adb device by default)
#
# Downloads into $OFFLINE_MODELS (default cache/offline-models in the checkout), each file
# hash-checked, kept for next time:
#   - Parakeet TDT 0.6B v3 int8, sherpa-onnx's asr-models release (487 MB);
#   - the ten Bergamot pairs to and from English, Mozilla's CDN through Foxlet 0.5.0's
#     registry.json (device/offline-models/fetch_bergamot.py, 381 MB);
#   - the onnxruntime-android 1.28.0 AAR from Maven Central, for its headers only.
# Builds device/offline-models/ortload.cc with the NDK (build-ortload.sh, which reads $NDK)
# against the libonnxruntime.so inside android/libs' sherpa-onnx AAR (the runtime the app
# reads the saved encoder with),
# pushes all of it to /data/local/tmp/offline-models and runs install.sh there as root,
# which makes the saved encoder (~30 s) and moves everything into the app's storage.
# About 1.4 GB on the device. Run it again whenever the sherpa-onnx AAR changes.
set -e
SERIAL=${1:-$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')}
case $SERIAL in
    ''|*[[:space:]]*) echo "no single device on adb: pass its serial as the first argument"; exit 1 ;;
esac
PKG=${2:-com.bleural.croissant}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
CACHE=${OFFLINE_MODELS:-${CROISSANT_CACHE:-$ROOT/cache}/offline-models}
AAR=$ROOT/android/libs/sherpa-onnx-1.13.8.aar
STAGE=/data/local/tmp/offline-models

PARAKEET=sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8
PARAKEET_SHA=5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf
REGISTRY_SHA=1a3b02c2a91be818350e2b3e1968cc5413feeb1ea3bdf4726f75d95354e6aa89

get() { # file url sha256
    if [ -f "$1" ] && [ "$(shasum -a 256 "$1" | cut -d' ' -f1)" = "$3" ]; then return; fi
    echo "fetching $(basename "$1")"
    curl -fL --retry 3 -o "$1.part" "$2"
    got=$(shasum -a 256 "$1.part" | cut -d' ' -f1)
    [ "$got" = "$3" ] || { rm -f "$1.part"; echo "$1: sha256 $got, expected $3"; exit 1; }
    mv "$1.part" "$1"
}

[ -f "$AAR" ] || { echo "no $AAR: run android/libs/fetch.sh"; exit 1; }
adb -s "$SERIAL" get-state >/dev/null
mkdir -p "$CACHE"
cd "$CACHE"

# Parakeet: the tarball goes once it is unpacked; the directory is what is kept.
if [ ! -f "$PARAKEET/tokens.txt" ]; then
    get "$PARAKEET.tar.bz2" \
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$PARAKEET.tar.bz2" $PARAKEET_SHA
    tar xjf "$PARAKEET.tar.bz2"
    rm "$PARAKEET.tar.bz2"
fi

# Bergamot: fetch_bergamot.py checks every file against the registry's hashes.
get registry.json https://raw.githubusercontent.com/yinvoke/foxlet-translate/v0.5.0/registry.json $REGISTRY_SHA
(cd "$ROOT/server" && uv run python "$ROOT/device/offline-models/fetch_bergamot.py" "$CACHE/registry.json" "$CACHE/bergamot")

# ortload, against the AAR's own onnxruntime.
"$HERE/build-ortload.sh" "$CACHE/ort"

echo "--- pushing to $STAGE"
adb -s "$SERIAL" shell "rm -rf $STAGE; mkdir -p $STAGE/stt $STAGE/mt"
adb -s "$SERIAL" push ort/ortload ort/libonnxruntime.so "$HERE/install.sh" $STAGE/
for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do
    adb -s "$SERIAL" push "$PARAKEET/$f" $STAGE/stt/
done
for d in bergamot/*/; do adb -s "$SERIAL" push "${d%/}" $STAGE/mt/; done
# Foxlet finds a pair's files by name; the smoke CLI's config.yml is not one of them.
adb -s "$SERIAL" shell "rm $STAGE/mt/*/config.yml"

adb -s "$SERIAL" shell "su -c 'sh $STAGE/install.sh $PKG'"
