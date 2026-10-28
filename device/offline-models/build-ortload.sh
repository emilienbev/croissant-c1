#!/bin/sh
# Build ortload (ortload.cc beside this file) for the R1, with the NDK, against the
# libonnxruntime.so inside android/libs' sherpa-onnx AAR (run android/libs/fetch.sh first):
#
#   device/offline-models/build-ortload.sh DIR
#
# Leaves DIR/ortload and DIR/libonnxruntime.so, which ortload loads and has to sit beside
# it on the device. The headers come from the onnxruntime-android 1.28.0 AAR on Maven
# Central, fetched into DIR, hash-checked and kept. push.sh runs this; so does the release
# workflow. NDK: $NDK, else $ANDROID_HOME/ndk/28.2.13676358, else the Mac's default SDK.
set -e
OUT=${1:?usage: $0 DIR}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
AAR=$ROOT/android/libs/sherpa-onnx-1.13.8.aar
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
NDK=${NDK:-$SDK/ndk/28.2.13676358}
ORT_SHA=f351a0638696f54b35184290dbc001d66daae17281ad0b548d2c70347d53b8a9
case $(uname -s) in
    Darwin) HOST=darwin-x86_64 ;;
    Linux) HOST=linux-x86_64 ;;
    *) echo "no NDK host for $(uname -s)"; exit 1 ;;
esac
CXX=$NDK/toolchains/llvm/prebuilt/$HOST/bin/aarch64-linux-android33-clang++
[ -f "$AAR" ] || { echo "no $AAR: run android/libs/fetch.sh"; exit 1; }
[ -x "$CXX" ] || { echo "no $CXX: install NDK 28.2.13676358 or set NDK"; exit 1; }

mkdir -p "$OUT"
cd "$OUT"
if [ ! -f ort-1.28.0.aar ] || [ "$(shasum -a 256 ort-1.28.0.aar | cut -d' ' -f1)" != $ORT_SHA ]; then
    echo "fetching ort-1.28.0.aar"
    curl -fL --retry 3 -o ort-1.28.0.aar.part \
        https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.28.0/onnxruntime-android-1.28.0.aar
    got=$(shasum -a 256 ort-1.28.0.aar.part | cut -d' ' -f1)
    [ "$got" = $ORT_SHA ] || { rm -f ort-1.28.0.aar.part; echo "ort-1.28.0.aar: sha256 $got, expected $ORT_SHA"; exit 1; }
    mv ort-1.28.0.aar.part ort-1.28.0.aar
fi
rm -rf include
unzip -q -j -o ort-1.28.0.aar 'headers/*' -d include
unzip -q -j -o "$AAR" jni/arm64-v8a/libonnxruntime.so -d .
"$CXX" -O2 -march=armv8-a -std=c++17 -Iinclude "$HERE/ortload.cc" -L. -lonnxruntime -static-libstdc++ -o ortload
echo "built $OUT/ortload"
