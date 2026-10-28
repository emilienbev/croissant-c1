#!/bin/sh
# Hands the R1 an eSIM activation code: opens Settings > eSIM > Add profile with the
# code filled in.
#
#   device/esim/send-code.sh 'LPA:1$rsp.truphone.com$QRF-SPEEDTEST'
#
# The dollars in a code have to survive two shells, this one and the device's, which is
# the whole point of this script: `adb shell ... -d 'LPA:1$a$b'` loses them on the
# device side and the app then sees a code it cannot read. `am` always logs the URI as
# "dat=LPA:" (it scrubs them) and says "Activity not started ... delivered to currently
# running top-most instance" because the app is already up. Both are normal.
set -eu
if [ $# -ne 1 ]; then
    echo "usage: $0 'LPA:1\$<SM-DP+ address>\$<matching id>'" >&2
    exit 2
fi
serial=${R1_SERIAL:-$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')}
case $serial in
    ''|*[[:space:]]*) echo "no single device on adb: set R1_SERIAL" >&2; exit 1 ;;
esac
adb -s "$serial" shell am start -a android.intent.action.VIEW \
    -d "$(printf %s "$1" | sed 's/\$/\\$/g')"
