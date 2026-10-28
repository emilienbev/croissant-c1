#!/system/bin/sh
# Install this module straight into Magisk's module dir (no zip). Run as root on
# the device after: adb push device/magisk/croissant-device-admin /data/local/tmp/
#   adb shell su -c "sh /data/local/tmp/croissant-device-admin/install.sh"
#   adb shell su -c "sh /data/local/tmp/croissant-device-admin/install.sh remove"
# Takes effect at the next boot. Magisk mounts system/ over the real /system.
set -e
ID=croissant-device-admin
DST=/data/adb/modules/$ID
if [ "$1" = "remove" ]; then rm -rf "$DST"; echo "removed $DST - reboot to apply"; exit 0; fi
rm -rf "$DST"
mkdir -p "$DST"
cp -r /data/local/tmp/$ID/module.prop /data/local/tmp/$ID/system "$DST/"
chown -R root:root "$DST"
find "$DST" -type d -exec chmod 0755 {} +
find "$DST" -type f -exec chmod 0644 {} +
echo "--- installed:"; find "$DST" -type f | xargs ls -lZ
echo "--- reboot to apply, then: pm list features | grep device_admin"
