#!/bin/sh
# The two AARs behind the offline Translator, from their
# GitHub releases, hash-checked. Not in git (53 MB); run once before building:
#   android/libs/fetch.sh
# Neither is on Maven Central. The saved Parakeet encoder on the R1 is tied to the
# onnxruntime inside the sherpa-onnx AAR (1.28.2 in 1.13.8): change SHERPA and the
# models have to be installed again (device/offline-models/push.sh).
set -e
cd "$(dirname "$0")"

SHERPA=1.13.8
SHERPA_SHA=633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96
FOXLET=0.5.0
FOXLET_SHA=5dbb384907d69c2aba129fd15dba30dda94ede0fa556dc6a167d041ee479ec0e

get() { # file url sha256
    if [ -f "$1" ] && [ "$(shasum -a 256 "$1" | cut -d' ' -f1)" = "$3" ]; then echo "have $1"; return; fi
    curl -fL --retry 3 -o "$1.part" "$2"
    got=$(shasum -a 256 "$1.part" | cut -d' ' -f1)
    [ "$got" = "$3" ] || { rm -f "$1.part"; echo "$1: sha256 $got, expected $3"; exit 1; }
    mv "$1.part" "$1"
    echo "got $1"
}

get sherpa-onnx-$SHERPA.aar \
    https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA/sherpa-onnx-$SHERPA.aar $SHERPA_SHA
get foxlet-v$FOXLET.aar \
    https://github.com/yinvoke/foxlet-translate/releases/download/v$FOXLET/foxlet-v$FOXLET.aar $FOXLET_SHA
