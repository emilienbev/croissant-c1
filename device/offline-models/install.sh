#!/system/bin/sh
#
# Install (or remove) the offline Translator's models in an app's storage.
# Run as root ON THE DEVICE, after push.sh has staged
# everything in /data/local/tmp/offline-models:
#
#   adb shell su -c "sh /data/local/tmp/offline-models/install.sh [PACKAGE]"
#   adb shell su -c "sh /data/local/tmp/offline-models/install.sh remove [PACKAGE]"
#
# PACKAGE defaults to com.bleural.croissant. What it does:
#   1. makes the Parakeet encoder's saved pre-packed form with ortload, on this CPU and
#      with the onnxruntime the app ships: enc.saved.onnx and
#      its 1.19 GB enc.saved.onnx.data, 18.7 s to load instead of 25.4 s;
#   2. moves it, the decoder, joiner, tokens and the Bergamot pairs to
#      /data/data/PACKAGE/files/offline/{stt,mt} (a rename: same partition, no copy),
#      owned by the app and labelled as its data;
#   3. deletes the staging directory, the original encoder with it.
# The saved encoder is tied to the onnxruntime inside the sherpa-onnx AAR and to this
# CPU: when the AAR changes version, run push.sh again.

set -e
STAGE=/data/local/tmp/offline-models
if [ "$1" = "remove" ]; then REMOVE=1; shift; fi
PKG=${1:-com.bleural.croissant}
APPDIR=/data/data/$PKG
DEST=$APPDIR/files/offline

[ -d "$APPDIR" ] || { echo "$PKG is not installed"; exit 1; }

if [ -n "$REMOVE" ]; then
    rm -rf "$DEST" "$STAGE"
    echo "removed $DEST"
    exit 0
fi

for f in ortload libonnxruntime.so stt/encoder.int8.onnx stt/decoder.int8.onnx \
         stt/joiner.int8.onnx stt/tokens.txt; do
    [ -f "$STAGE/$f" ] || { echo "missing $STAGE/$f (run push.sh)"; exit 1; }
done
[ "$(ls -d $STAGE/mt/*/ 2>/dev/null | wc -l)" -eq 10 ] || { echo "expected ten pairs in $STAGE/mt"; exit 1; }

echo "--- saving the pre-packed encoder (about 30 s)"
cd "$STAGE/stt"
rm -f enc.saved.onnx enc.saved.onnx.data
chmod 0755 "$STAGE/ortload"
LD_LIBRARY_PATH=$STAGE taskset 0f "$STAGE/ortload" encoder.int8.onnx 4 save 99 0 "$STAGE/stt/enc.saved.onnx"
[ -s enc.saved.onnx ] && [ -s enc.saved.onnx.data ] || { echo "ortload wrote nothing"; exit 1; }
rm encoder.int8.onnx

echo "--- moving into $DEST"
UID_=$(stat -c %u "$APPDIR")
# The app's own label, categories included: restorecon gives files moved in from
# /data/local/tmp plain s0.
CTX=$(stat -c %C "$APPDIR")
rm -rf "$DEST"
mkdir -p "$APPDIR/files"
mkdir "$DEST"
mv "$STAGE/stt" "$STAGE/mt" "$DEST/"
chown -R "$UID_:$UID_" "$APPDIR/files"
chmod -R u=rwX,go= "$DEST"
chcon -R "$CTX" "$APPDIR/files"
rm -rf "$STAGE"

echo "--- installed:"
ls -lZ "$APPDIR/files" | grep offline
ls -l "$DEST/stt"
ls "$DEST/mt"
du -sh "$DEST"
