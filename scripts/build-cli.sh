#!/bin/sh
# The CLI as a release publishes it (release.yml), into dist/ or the folder given:
#   croissant-cli-X.Y.Z.tar.gz   what the CLI needs of this checkout (its tracked files, as they
#                                are here), with install.json: where it came from and its version
#   its .sha256
#   install.sh                   scripts/install.sh with the repository, the version and uv's written in
#
#   scripts/build-cli.sh [OUT]   CROISSANT_REPO=owner/repo for another repository than
#                                android/gradle.properties' croissant.updateRepo
# Try it without GitHub: CROISSANT_DIST=dist CROISSANT_HOME=/tmp/c sh dist/install.sh
set -eu
export COPYFILE_DISABLE=1          # macOS tar: no ._ files for its extended attributes
ROOT=$(cd "$(dirname "$0")/.." && pwd)
V=$(cat "$ROOT/VERSION")
OUT=${1:-$ROOT/dist}
REPO=${CROISSANT_REPO:-$(sed -n 's/^croissant\.updateRepo=//p' "$ROOT/android/gradle.properties")}
UV=$(sed -n 's/^UV_VERSION=//p' "$ROOT/croissant")
name=croissant-cli-$V

echo "$REPO" | grep -Eq '^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$' || { echo "not owner/repo: '$REPO'"; exit 1; }
[ -n "$UV" ] || { echo "no UV_VERSION in ./croissant"; exit 1; }

stage=$(mktemp -d "${TMPDIR:-/tmp}/croissant-cli.XXXXXX")
trap 'rm -rf "$stage"' EXIT INT TERM
mkdir -p "$stage/$name" "$OUT"

# What it reads at run time: the launcher, the installer, the device's files and scripts it
# pushes or loads, the application id and update repository, and Lucide's glyphs.
(cd "$ROOT" && git ls-files -z -- croissant VERSION LICENSE NOTICE installer ':!installer/tests' \
    device scripts android/gradle.properties android/app/src/main/assets/lucide/icons.json) |
    (cd "$ROOT" && tar --null -T - -cf -) | tar -xf - -C "$stage/$name"
printf '{"repo": "%s", "version": "%s"}\n' "$REPO" "$V" >"$stage/$name/install.json"

(cd "$stage" && find "$name" -type f | LC_ALL=C sort >files &&
    tar -cf - -T files | gzip -n -9 >"$OUT/$name.tar.gz")
cd "$OUT"
if command -v sha256sum >/dev/null; then sha256sum "$name.tar.gz"; else shasum -a 256 "$name.tar.gz"; fi >"$name.tar.gz.sha256"
sed -e "s|@REPO@|$REPO|g" -e "s|@VERSION@|$V|g" -e "s|@UV_VERSION@|$UV|g" "$ROOT/scripts/install.sh" >install.sh
chmod 755 install.sh
cat "$name.tar.gz.sha256"
echo "install.sh for $REPO $V"
