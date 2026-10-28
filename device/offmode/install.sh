#!/system/bin/sh
#
# Add (or remove) the off-mode hook in the live boot image's ramdisk.
# Run as root ON THE DEVICE, with this directory pushed:
#
#   adb push device/offmode /data/local/tmp/croissant-offmode
#   adb shell su -c "sh /data/local/tmp/croissant-offmode/install.sh"          # or: install.sh remove
#
# Dumps boot of the live slot (kept as boot<slot>-before.img next to this script; pull it),
# adds overlay.d/croissant-offmode.rc, and to overlay.d/sbin croissant-offmode.sh, croissant-chgres.sh and the
# charge-only screen's files from res/ (flat, as croissant-res-<name>) with Magisk's magiskboot,
# and a copy of the rules there too (init reads overlay.d/*.rc into init.rc and keeps no file
# of it; the copy in Magisk's tmpfs is what the installer compares to know they changed),
# repacks and writes it back. Takes effect at the next boot. A Magisk update that repatches
# boot drops them: run this again after one.

set -e
SRC=/data/local/tmp/croissant-offmode
MB=/data/adb/magisk/magiskboot
SLOT=$(getprop ro.boot.slot_suffix)
BOOT=/dev/block/by-name/boot$SLOT
W=$SRC/work

[ -x $MB ] || { echo "no $MB"; exit 1; }
[ -b $BOOT ] || { echo "no $BOOT"; exit 1; }
RES="values/charger/animation.txt images/charger/battery_scale.png images/charger/battery_fail.png images/charger/percent_font.png"
for F in croissant-offmode.rc croissant-offmode.sh croissant-chgres.sh $(for r in $RES; do echo res/$r; done); do
    [ -f "$SRC/$F" ] || { echo "missing $SRC/$F (adb push the directory first)"; exit 1; }
done
sh -n "$SRC/croissant-offmode.sh"
sh -n "$SRC/croissant-chgres.sh"

rm -rf $W; mkdir -p $W; cd $W
dd if=$BOOT of=boot.img bs=1048576 2>/dev/null
cp boot.img "$SRC/boot$SLOT-before.img"
$MB unpack boot.img >/dev/null 2>&1
[ -f ramdisk.cpio ] || { echo "magiskboot found no ramdisk"; exit 1; }

OLD="overlay.d/croissant-offmode.rc overlay.d/sbin/croissant-offmode.rc overlay.d/sbin/croissant-offmode.sh overlay.d/sbin/croissant-chgres.sh"
for r in $RES; do OLD="$OLD overlay.d/sbin/croissant-res-${r##*/}"; done
for e in $OLD; do $MB cpio ramdisk.cpio "rm $e" >/dev/null 2>&1 || true; done
if [ "$1" != "remove" ]; then
    $MB cpio ramdisk.cpio \
        "add 0644 overlay.d/croissant-offmode.rc $SRC/croissant-offmode.rc" \
        "add 0644 overlay.d/sbin/croissant-offmode.rc $SRC/croissant-offmode.rc" \
        "add 0755 overlay.d/sbin/croissant-offmode.sh $SRC/croissant-offmode.sh" \
        "add 0755 overlay.d/sbin/croissant-chgres.sh $SRC/croissant-chgres.sh" >/dev/null
    for r in $RES; do
        $MB cpio ramdisk.cpio "add 0644 overlay.d/sbin/croissant-res-${r##*/} $SRC/res/$r" >/dev/null
    done
fi
echo "--- overlay.d now:"
$MB cpio ramdisk.cpio "ls -r overlay.d" 2>/dev/null | grep -v '^Loading'

$MB repack boot.img new.img >/dev/null 2>&1
[ -s new.img ] || { echo "repack failed"; exit 1; }

# Write the image's real length only (boot header v2: the sections, page aligned), so the
# partition's tail stays as it is (the stale stock AVB footer; vbmeta has verification off).
u32() { dd if=new.img bs=1 skip=$1 count=4 2>/dev/null | od -An -tu4 | tr -d ' '; }
[ "$(dd if=new.img bs=1 count=8 2>/dev/null)" = ANDROID! ] && [ "$(u32 40)" = 2 ] \
    || { echo "not a v2 boot image"; exit 1; }
PG=$(u32 36)
pages() { echo $(( ($1 + PG - 1) / PG )); }
N=$(( PG * (1 + $(pages $(u32 8)) + $(pages $(u32 16)) + $(pages $(u32 24)) + $(pages $(u32 1632)) + $(pages $(u32 1648))) ))
head -c $N new.img > img.img
dd if=img.img of=$BOOT bs=$PG conv=fsync 2>/dev/null
sync
A=$(sha256sum img.img | cut -d' ' -f1)
B=$(head -c "$N" $BOOT | sha256sum | cut -d' ' -f1)
[ "$A" = "$B" ] || { echo "read-back differs: write $SRC/boot$SLOT-before.img back before rebooting"; exit 1; }
echo "--- boot$SLOT written ($N bytes, $A); reboot to apply"
