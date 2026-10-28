#!/system/bin/sh
#
# croissant-offmode.sh 0|1 : set LK's `off-mode-charge` flag in `para`.
#
# 1 = a plugged-in power-up goes to charge-only mode, 0 = it boots. Kept at 0, except across a
# Power off with the charger in: the app writes 1 just before it powers off, so LK charges
# instead of booting, and croissant-offmode.rc writes 0 again as soon as charge-only mode starts,
# so the next plug into an off R1 boots. It also writes 0 on every normal boot, for the paths
# that skip charge-only mode (the side button pressed while LK is still up).
#
# Runs from the boot ramdisk (Magisk overlay.d, copied to Magisk's tmpfs), as root, in charger
# mode as well as a normal boot: toybox or BusyBox, no /data. Writes only the value byte and
# the checksum, and only when the block checks out.
#
# The block: para+0x20000, 16 KiB: "ENV_v1\0\0", NUL-separated key=value to +0x3ff4,
# "ENV_v1\0\0", then a u32 LE checksum, the byte sum of the data.

V=$1
case "$V" in 0|1) ;; *) echo "usage: $0 0|1" >&2; exit 2 ;; esac

say() { echo "croissant-offmode: $*"; echo "croissant-offmode: $*" > /dev/kmsg 2>/dev/null; }

P=
for d in /dev/block/by-name/para /dev/block/platform/bootdevice/by-name/para; do
    [ -b "$d" ] && { P=$d; break; }
done
if [ -z "$P" ]; then
    for u in /sys/class/block/*/uevent; do
        grep -qx 'PARTNAME=para' "$u" 2>/dev/null || continue
        d=${u%/uevent}; P=/dev/block/${d##*/}; break
    done
fi
[ -b "$P" ] || { say "no para partition"; exit 1; }

BASE=131072                  # 0x20000
DATA=$((BASE + 8))
DLEN=16364                   # 0x3fec, up to the tail signature
TAIL=$((BASE + 16372))       # 0x3ff4
CSUM=$((BASE + 16380))       # 0x3ffc

rd() { dd if="$P" bs=1 skip="$1" count="$2" 2>/dev/null; }
bytes() { rd "$1" "$2" | od -An -v -tu1; }

[ "$(rd $BASE 6)" = ENV_v1 ] && [ "$(rd $TAIL 6)" = ENV_v1 ] || { say "no LK env block"; exit 1; }

set -- $(bytes $CSUM 4)
cs=$(( $1 + $2 * 256 + $3 * 65536 + $4 * 16777216 ))
sum=0
for b in $(bytes $DATA $DLEN); do sum=$((sum + b)); done
[ "$sum" = "$cs" ] || { say "checksum $cs does not match the data ($sum), not writing"; exit 1; }

# The value's offset: walk the NUL-separated entries, an empty one ends the list. A pipe, not
# a heredoc: a heredoc needs a writable temp dir, and charger mode has no /data.
at=$(rd $DATA 1024 | tr '\0' '\n' | {
    off=0
    while IFS= read -r e; do
        [ -z "$e" ] && break
        case "$e" in off-mode-charge=*) echo $((off + 16)); break ;; esac
        off=$((off + ${#e} + 1))
    done
})
[ -n "$at" ] || { say "off-mode-charge not in the env"; exit 1; }
at=$((DATA + at))

cur=$(rd $at 1)
case "$cur" in 0|1) ;; *) say "off-mode-charge is '$cur', not 0 or 1"; exit 1 ;; esac
[ "$cur" = "$V" ] && { say "off-mode-charge already $V"; exit 0; }

n=$((cs + V - cur))
oct() { printf '\\%03o' "$1"; }
printf "$(oct $((n & 255)))$(oct $((n >> 8 & 255)))$(oct $((n >> 16 & 255)))$(oct $((n >> 24 & 255)))" \
    | dd of="$P" bs=1 seek=$CSUM count=4 conv=notrunc 2>/dev/null
printf "$V" | dd of="$P" bs=1 seek=$at count=1 conv=notrunc 2>/dev/null
sync

set -- $(bytes $CSUM 4)
[ "$(rd $at 1)" = "$V" ] && [ $(( $1 + $2 * 256 + $3 * 65536 + $4 * 16777216 )) = "$n" ] \
    || { say "read-back failed"; exit 1; }
say "off-mode-charge $cur -> $V"
