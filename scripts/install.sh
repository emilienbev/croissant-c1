#!/bin/sh
# Croissant's CLI for this user, from a release (installer/README.md, "Installed from a release"):
#
#   curl -fsSL https://github.com/@REPO@/releases/latest/download/install.sh | sh
#
# This is the template: scripts/build-cli.sh writes the repository, the version and uv's into
# the copy each release publishes. It puts the CLI in ~/.croissant (CROISSANT_HOME):
# versions/X.Y.Z, current (the one in use), bin/croissant (on the PATH), cache/ (downloads and
# each R1's files, kept across upgrades), and adds bin/ to the PATH in the shell's startup file.
# `croissant upgrade` runs a newer release's copy of this.
#
#   CROISSANT_VERSION=X.Y.Z      another release than this one's
#   CROISSANT_NO_MODIFY_PATH=1   leave the shell's startup files alone
#   CROISSANT_DIST=DIR           the files from a folder (build-cli.sh's dist/), not GitHub
#   CROISSANT_UPGRADE=1          croissant upgrade's run: its own PATH as it is, nothing said of it
set -eu

REPO='@REPO@'
VERSION=${CROISSANT_VERSION:-'@VERSION@'}
VERSION=${VERSION#v}
UV_VERSION='@UV_VERSION@'
HOME_DIR=${CROISSANT_HOME:-$HOME/.croissant}

say() { printf '%s\n' "$*"; }
fail() { printf 'croissant: %s\n' "$*" >&2; exit 1; }

case $REPO in *@*) fail "this is the template: scripts/build-cli.sh writes the copy to run" ;; esac
case $VERSION in
    [0-9]*.[0-9]*.[0-9]*) ;;
    *) fail "version '$VERSION' is not X.Y.Z" ;;
esac

# --- this machine ---------------------------------------------------------------------------
case $(uname -s) in
    Darwin) os=macOS ;;
    Linux) os=Linux ;;
    *) fail "Croissant's CLI runs on macOS and Linux, not $(uname -s)" ;;
esac
case $(uname -m) in
    x86_64 | amd64 | arm64 | aarch64) ;;
    *) fail "Croissant's CLI runs on x86_64 and arm64, not $(uname -m)" ;;
esac
if command -v curl >/dev/null 2>&1; then
    get() { curl -fsSL --retry 3 -o "$2" "$1"; }
elif command -v wget >/dev/null 2>&1; then
    get() { wget -q -O "$2" "$1"; }
else
    fail "it needs curl or wget to download"
fi
if command -v sha256sum >/dev/null 2>&1; then
    sha256() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then
    sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }
else
    fail "it needs sha256sum or shasum to check the download"
fi
command -v tar >/dev/null 2>&1 || fail "it needs tar"

tmp=$(mktemp -d "${TMPDIR:-/tmp}/croissant.XXXXXX")
trap 'rm -rf "$tmp"' EXIT INT TERM

# --- the release ----------------------------------------------------------------------------
name=croissant-cli-$VERSION
say "Croissant's CLI $VERSION, for $os, into $HOME_DIR"
if [ -n "${CROISSANT_DIST:-}" ]; then
    cp "$CROISSANT_DIST/$name.tar.gz" "$CROISSANT_DIST/$name.tar.gz.sha256" "$tmp/" \
        || fail "no $name.tar.gz and its .sha256 in $CROISSANT_DIST"
else
    base=https://github.com/$REPO/releases/download/v$VERSION
    get "$base/$name.tar.gz" "$tmp/$name.tar.gz" || fail "could not download $base/$name.tar.gz"
    get "$base/$name.tar.gz.sha256" "$tmp/$name.tar.gz.sha256" || fail "could not download its .sha256"
fi
want=$(cut -d' ' -f1 <"$tmp/$name.tar.gz.sha256")
[ "$(sha256 "$tmp/$name.tar.gz")" = "$want" ] || fail "$name.tar.gz does not match its .sha256: nothing was changed"
tar -xzf "$tmp/$name.tar.gz" -C "$tmp"
[ -f "$tmp/$name/install.json" ] || fail "$name.tar.gz is not the CLI as build-cli.sh makes it"

# --- uv, which runs it ----------------------------------------------------------------------
mkdir -p "$HOME_DIR/versions" "$HOME_DIR/bin" "$HOME_DIR/cache"
uv=$(command -v uv 2>/dev/null || true)
[ -z "$uv" ] && [ -x "$HOME_DIR/cache/uv/uv" ] && uv=$HOME_DIR/cache/uv/uv
if [ -z "$uv" ]; then
    say "uv $UV_VERSION (https://docs.astral.sh/uv/), which runs the CLI, into $HOME_DIR/cache/uv"
    get "https://github.com/astral-sh/uv/releases/download/$UV_VERSION/uv-installer.sh" "$tmp/uv-installer.sh" \
        || fail "could not download uv's installer"
    env UV_INSTALL_DIR="$HOME_DIR/cache/uv" UV_NO_MODIFY_PATH=1 sh "$tmp/uv-installer.sh" >/dev/null \
        || fail "uv's installer failed"
    uv=$HOME_DIR/cache/uv/uv
fi

# --- in place -------------------------------------------------------------------------------
dest=$HOME_DIR/versions/$VERSION
rm -rf "$dest"                       # the same version again: made again
mv "$tmp/$name" "$dest"
say "its Python and packages (uv sync, a minute the first time)"
# Made where it stays: uv's environment keeps absolute paths. Until current points here, the
# version in use is untouched.
"$uv" sync --quiet --frozen --no-dev --project "$dest/installer" || { rm -rf "$dest"; fail "uv sync failed"; }
"$dest/croissant" --version >/dev/null || { rm -rf "$dest"; fail "the CLI $VERSION does not start"; }

before=$(readlink "$HOME_DIR/current" 2>/dev/null || true)
ln -sfn "versions/$VERSION" "$HOME_DIR/current"
cat >"$HOME_DIR/bin/croissant" <<EOF
#!/bin/sh
# Croissant's CLI, the version in $HOME_DIR/current (install.sh; croissant upgrade).
exec "$HOME_DIR/current/croissant" "\$@"
EOF
chmod 755 "$HOME_DIR/bin/croissant"
# The one before stays, for the command still running from it (croissant upgrade); older go.
for d in "$HOME_DIR"/versions/*; do
    [ -d "$d" ] || continue
    case ${d##*/} in "$VERSION" | "${before##*/}") ;; *) rm -rf "$d" ;; esac
done

# --- the PATH -------------------------------------------------------------------------------
bin=$HOME_DIR/bin
case $bin in "$HOME"/*) shown='$HOME'${bin#"$HOME"} ;; *) shown=$bin ;; esac
rc= line=
if [ -z "${CROISSANT_NO_MODIFY_PATH:-}${CROISSANT_UPGRADE:-}" ]; then
    case ${SHELL:-sh} in
        */zsh) rc=${ZDOTDIR:-$HOME}/.zshrc ;;
        */bash)
            if [ "$os" = Linux ]; then
                rc=$HOME/.bashrc
            else                     # macOS starts bash as a login shell, which reads the first of these
                for f in .bash_profile .bash_login .profile; do
                    [ -f "$HOME/$f" ] && { rc=$HOME/$f; break; }
                done
                [ -n "$rc" ] || rc=$HOME/.bash_profile
            fi ;;
        */fish) rc=${XDG_CONFIG_HOME:-$HOME/.config}/fish/conf.d/croissant.fish ;;
        *) rc=$HOME/.profile ;;
    esac
    case ${SHELL:-sh} in
        */fish) line="contains -- \"$shown\" \$PATH; or set -gx PATH \"$shown\" \$PATH" ;;
        *) line="export PATH=\"$shown:\$PATH\"" ;;
    esac
    if grep -qsF "$line" "$rc"; then
        rc=                          # there already
    else
        mkdir -p "$(dirname "$rc")"
        printf '\n# Croissant'"'"'s CLI, from github.com/%s (remove these two lines to undo)\n%s\n' \
            "$REPO" "$line" >>"$rc"
    fi
fi

[ -n "${CROISSANT_UPGRADE:-}" ] && exit 0
say ""
say "Croissant's CLI $VERSION is installed: $bin/croissant"
case ":$PATH:" in
    *":$bin:"*) say "Run: croissant --help" ;;
    *)
        if [ -n "$rc" ]; then
            say "$bin is now on the PATH in $rc. Open a new terminal, or run:"
            case ${SHELL:-sh} in
                */fish) say "  set -gx PATH \"$bin\" \$PATH" ;;
                *) say "  export PATH=\"$bin:\$PATH\"" ;;
            esac
        else
            say "Add $bin to the PATH, or run it as $bin/croissant."
        fi
        say "Then: croissant --help" ;;
esac
