#!/bin/sh
# Check that the server and the installer carry the version in VERSION, and, given a tag
# (the release workflow passes it), that the tag is v<VERSION>:
#   scripts/check-version.sh [vX.Y.Z]
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
V=$(cat "$ROOT/VERSION")
fail=0

echo "$V" | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$' || {
    echo "VERSION: '$V' is not X.Y.Z with minor and patch below 100"; fail=1; }

check() { # file what found
    [ "$3" = "$V" ] || { echo "$1: $2 is '$3', VERSION is '$V'"; fail=1; }
}
first_version() { awk -F'"' '/^version = "/ { print $2; exit }' "$1"; }
lock_version() { awk -F'"' -v n="name = \"$2\"" 'prev == n && /^version = "/ { print $2; exit } { prev = $0 }' "$1"; }

check server/pyproject.toml version "$(first_version "$ROOT/server/pyproject.toml")"
check server/uv.lock croissant-server "$(lock_version "$ROOT/server/uv.lock" croissant-server)"
check installer/pyproject.toml version "$(first_version "$ROOT/installer/pyproject.toml")"
check installer/uv.lock croissant "$(lock_version "$ROOT/installer/uv.lock" croissant)"

if [ -n "$1" ] && [ "$1" != "v$V" ]; then
    echo "tag $1 is not v$V: run scripts/bump-version.sh, commit, and tag that commit"; fail=1
fi
[ $fail = 0 ] && echo "version $V"
exit $fail
