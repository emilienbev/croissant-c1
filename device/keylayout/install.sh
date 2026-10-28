#!/system/bin/sh
#
# Install (or remove) the side-button and scroll-wheel key layouts. Run as root ON
# THE DEVICE:
#
#   adb push device/keylayout/mtk-kpd.kl device/keylayout/och1970_holl_key.kl \
#            device/keylayout/install.sh /data/local/tmp/
#   adb shell su -c "sh /data/local/tmp/install.sh"
#   adb shell su -c "sh /data/local/tmp/install.sh remove"
#
# Uses Android's user key-layout repository, $ANDROID_DATA/system/devices/keylayout/,
# which libinput searches after /odm, /vendor and /system. No Magisk module, no
# magic mount: one file on /data, undone by deleting it. Takes effect at the next
# boot - EventHub reads .kl files only when it adds the input device.

set -e
DIR=/data/system/devices/keylayout
KLS="mtk-kpd.kl och1970_holl_key.kl"
# First attempt targeted the wrong device (see mtk-kpd.kl's header); clean it up.
STALE=mtk-pmic-keys.kl

if [ "$1" = "remove" ]; then
    for KL in $KLS; do rm -f "$DIR/$KL"; done
    rm -f "$DIR/$STALE"
    echo "removed $KLS from $DIR - reboot to apply"
    exit 0
fi

for KL in $KLS; do
    [ -f "/data/local/tmp/$KL" ] || { echo "missing /data/local/tmp/$KL (adb push it first)"; exit 1; }
done

mkdir -p "$DIR"
rm -f "$DIR/$STALE"
for KL in $KLS; do
    cp "/data/local/tmp/$KL" "$DIR/$KL"
    chmod 0644 "$DIR/$KL"
done
chown -R system:system /data/system/devices
chmod 0755 /data/system/devices "$DIR"
restorecon -R /data/system/devices

echo "--- installed:"
ls -lZ "$DIR"
for KL in $KLS; do echo "$KL:"; grep -v '^#' "$DIR/$KL" | grep .; done
echo "--- reboot to apply, then: dumpsys input | grep -B4 KeyLayoutFile"
