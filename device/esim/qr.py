# /// script
# requires-python = ">=3.12"
# dependencies = ["segno"]
# ///
"""Draw an activation code as a QR code on the Mac, to point the R1's camera at.

    uv run device/esim/qr.py 'LPA:1$rsp.truphone.com$QRF-SPEEDTEST'
    uv run device/esim/qr.py --out /tmp/code.png 'LPA:1$...'

An eSIM QR code holds nothing but the activation code, so this is the same thing an
operator sends. Settings > eSIM > Add profile opens the camera; hold the R1 about a
hand's width from the screen, since the lens is fixed focus. See
device/esim/README.md.
"""

import argparse
import subprocess
import sys
from pathlib import Path

import segno


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("code", help="the activation code, LPA:1$<SM-DP+>$<matching ID>")
    ap.add_argument("--out", type=Path, default=Path("/tmp/esim-qr.png"), help="where to write it (default /tmp/esim-qr.png)")
    ap.add_argument("--scale", type=int, default=16, help="pixels per module (default 16)")
    ap.add_argument("--quiet", action="store_true", help="do not open the picture")
    args = ap.parse_args()

    code = args.code.strip()
    if not code.upper().startswith("LPA:"):
        print(f"warning: {code!r} does not start with LPA:", file=sys.stderr)

    # Error correction M and a wide quiet zone: the R1's lens is soft at close range,
    # and both help it read.
    segno.make(code, error="m").save(args.out, scale=args.scale, border=4)
    print(f"{args.out} ({code})")
    if not args.quiet:
        subprocess.run(["open", str(args.out)], check=False)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
