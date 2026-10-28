#!/bin/sh
# Fail on what should never be in this public repo, in every tracked text file:
#   - a home directory path (a Mac's or Linux's), which names a person;
#   - a Tailscale address (100.64.0.0/10) other than the documented examples 100.100.100.x,
#     the range itself written as 100.64.0.0/10, and its edges (100.64.0.0-1 and
#     100.127.255.254-255), which the tests of what is on the tailnet need.
# CI runs it (.github/workflows/ci.yml); run it before pushing:  scripts/check-leaks.sh
# The patterns are written so this file does not match itself.
cd "$(dirname "$0")/.." || exit 1
fail=0

homes=$(git grep -nIE '/Use[r]s/|/hom[e]/[a-z]')
if [ -n "$homes" ]; then
    echo "home directory paths (use ~ or a placeholder):"
    echo "$homes"
    fail=1
fi

# -o prints each match as file:line:match; a leading non-digit is part of the match.
tailnet=$(git grep -noIE '(^|[^0-9.])10[0]\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.[0-9]{1,3}\.[0-9]{1,3}(/[0-9]{1,2})?' \
    | grep -vE ':[^:]*10[0]\.10[0]\.10[0]\.[0-9]{1,3}$' \
    | grep -vE ':[^:]*10[0]\.6[4]\.0\.0/10$' \
    | grep -vE ':[^:]*10[0]\.(6[4]\.0\.[01]|12[7]\.255\.25[45])$')
if [ -n "$tailnet" ]; then
    echo "tailnet addresses (use 100.100.100.x in examples):"
    echo "$tailnet"
    fail=1
fi

[ $fail = 0 ] && echo "no leaks"
exit $fail
