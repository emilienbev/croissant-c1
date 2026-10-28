#!/bin/sh
# Set the one version of the app, the server and the installer:
#   scripts/bump-version.sh X.Y.Z
# VERSION at the repo root is the source. This writes it there, into server/ and installer/
# pyproject.toml, and into their uv.lock entries, then checks they agree
# (scripts/check-version.sh). The app reads VERSION itself (android/app/build.gradle.kts):
# versionName is X.Y.Z, versionCode X*10000 + Y*100 + Z, so Y and Z stay below 100.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
V=$1
echo "$V" | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$' || {
    echo "usage: $0 X.Y.Z  (numbers only, minor and patch below 100)"; exit 1; }

printf '%s\n' "$V" > "$ROOT/VERSION"

set_project() { # dir package-name
    py=$ROOT/$1/pyproject.toml
    # The first `version = ` line is [project]'s: it comes before any other table.
    awk -v v="$V" '!done && /^version = "/ { $0 = "version = \"" v "\""; done = 1 } { print }' \
        "$py" > "$py.tmp" && mv "$py.tmp" "$py"
    # In uv.lock the project's own entry is the version line right under its name.
    lock=$ROOT/$1/uv.lock
    awk -v v="$V" -v n="name = \"$2\"" '
        prev == n && /^version = "/ { $0 = "version = \"" v "\"" } { print; prev = $0 }' \
        "$lock" > "$lock.tmp" && mv "$lock.tmp" "$lock"
}
set_project server croissant-server
set_project installer croissant

"$ROOT/scripts/check-version.sh"
