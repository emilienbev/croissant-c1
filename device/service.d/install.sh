#!/system/bin/sh
#
# Install (or remove) tailscaled as a Magisk boot daemon.
# Run as root ON THE DEVICE, with the four files pushed to /data/local/tmp/croissant-tailscale/:
#
#   adb push tailscaled tailscale device/service.d/ts device/service.d/tailscaled.sh \
#            device/service.d/install.sh /data/local/tmp/croissant-tailscale/
#   adb shell su -c "sh /data/local/tmp/croissant-tailscale/install.sh"
#   adb shell su -c "sh /data/local/tmp/croissant-tailscale/install.sh remove"
#
# The binaries and the `ts` wrapper go to /data/adb/tailscale (the node's state stays
# there too, so a reinstall keeps the login), the boot script to /data/adb/service.d.
# Takes effect at the next boot. `remove` leaves tailscaled.state alone.

set -e
SRC=/data/local/tmp/croissant-tailscale
TSDIR=/data/adb/tailscale
HOOK=/data/adb/service.d/tailscaled.sh

if [ "$1" = "remove" ]; then
    rm -f "$HOOK" "$TSDIR/tailscaled" "$TSDIR/tailscale" "$TSDIR/ts"
    echo "removed $HOOK and the binaries (state kept in $TSDIR) - reboot to apply"
    exit 0
fi

for F in tailscaled tailscale ts tailscaled.sh; do
    [ -f "$SRC/$F" ] || { echo "missing $SRC/$F (adb push it first)"; exit 1; }
done

mkdir -p "$TSDIR" /data/adb/service.d
for F in tailscaled tailscale ts; do
    cp -f "$SRC/$F" "$TSDIR/$F"
done
chown root:root "$TSDIR/tailscaled" "$TSDIR/tailscale" "$TSDIR/ts"
chmod 700 "$TSDIR/tailscaled" "$TSDIR/tailscale"
chmod 755 "$TSDIR/ts"
chcon u:object_r:system_file:s0 "$TSDIR/tailscaled" "$TSDIR/tailscale"

cp -f "$SRC/tailscaled.sh" "$HOOK"
chown root:root "$HOOK"
chmod 755 "$HOOK"
sh -n "$HOOK"

echo "--- installed:"
ls -lZ "$TSDIR" "$HOOK"
"$TSDIR/tailscaled" --version | head -1
echo "--- reboot to apply, then: su -c '$TSDIR/ts status'"
