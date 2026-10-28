#!/system/bin/sh
#
# croissant-chgres.sh : our charge-only screen instead of Lineage's.
#
# The charger (/system/bin/charger) reads /product/etc/res/values/charger/animation.txt and
# the images beside it; /product is read-only, and in charger mode there is no /data, so no
# Magisk module. This builds the tree in /dev from the files magiskinit copied to its tmpfs
# with this script (overlay.d/sbin/croissant-res-*, flat), labels it as system files and
# bind-mounts it over /product/etc/res. Run from croissant-offmode.rc at `on init`, before the
# `charger` trigger starts the charger; in a normal boot too, where nothing reads it, so the
# mount can be checked over adb. Rendered by charger.py.

say() { echo "croissant-chgres: $*"; echo "croissant-chgres: $*" > /dev/kmsg 2>/dev/null; }

SRC=${0%/*}
D=/dev/croissant-res
T=$(readlink -f /product/etc/res)
[ -d "$T" ] || { say "no /product/etc/res"; exit 1; }
grep -q " $T " /proc/mounts && { say "already mounted"; exit 0; }

for f in animation.txt battery_scale.png battery_fail.png percent_font.png; do
    [ -f "$SRC/croissant-res-$f" ] || { say "missing $SRC/croissant-res-$f, Lineage's screen stays"; exit 1; }
done
mkdir -p $D/values/charger $D/images/charger
cp "$SRC/croissant-res-animation.txt" $D/values/charger/animation.txt
for f in battery_scale battery_fail percent_font; do
    cp "$SRC/croissant-res-$f.png" $D/images/charger/$f.png
done
chmod 755 $D $D/values $D/values/charger $D/images $D/images/charger
chmod 644 $D/values/charger/animation.txt $D/images/charger/*.png
chcon -R u:object_r:system_file:s0 $D
mount -o bind $D "$T" && say "mounted over $T" || say "mount failed"
