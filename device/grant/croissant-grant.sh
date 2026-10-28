#!/system/bin/sh
#
# croissant-grant.sh <Magisk's tmpfs> : root for the adb shell (uid 2000), for good.
#
# LineageOS's "Rooted debugging" is off and cannot be turned on from the shell, so the
# installer's root is Magisk's su. Without a policy row, su from the shell asks Grant/Deny
# on the R1's screen, nobody is there to answer during an install, and a prompt that times
# out is remembered as a deny. This writes the row Magisk's own Grant writes (policy 2,
# until 0 = forever), with logging and notification off as for the app's row, so no toast
# on every su. Started once per boot by croissant-grant.rc, as root in
# Magisk's domain; writes only when the row is not already there; prints nothing.

M=${1:-/sbin}/magisk
[ -x "$M" ] || M=magisk
i=0
until "$M" -v >/dev/null 2>&1; do        # magiskd answering
    i=$((i + 1))
    [ $i -ge 60 ] && exit 1
    sleep 1
done
"$M" --sqlite "SELECT policy FROM policies WHERE uid=2000 AND until=0" 2>/dev/null \
    | grep -qx 'policy=2' && exit 0
"$M" --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES(2000,2,0,0,0)" \
    >/dev/null 2>&1
