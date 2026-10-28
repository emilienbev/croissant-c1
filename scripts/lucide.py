#!/usr/bin/env python3
"""
Writes the Lucide icons an Atelier app may name (croissant.json's "glyph") as one JSON asset:
android/app/src/main/assets/lucide/icons.json, {"version": ..., "icons": {name: [path, ...]}},
and Lucide's LICENSE beside it. Their tags, {"version": ..., "tags": {name: [word, ...]}}, go
to installer/croissant/lucide-tags.json, where `croissant atelier new` searches them.

Lucide (lucide.dev, ISC) draws on the same 24-unit grid as Croissant's own glyphs, 2-unit
round strokes and no fill, so each element is turned into SVG path data and the app draws
it with the same stroke as ui/Icons.kt. A path starting "f:" is filled instead.

    uv run python scripts/lucide.py            # the pinned version, checked against its hash

Standard library only. The npm tarball is read in memory, never unpacked.
"""

from __future__ import annotations

import base64
import hashlib
import io
import json
import re
import sys
import tarfile
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

VERSION = "1.57.0"
TARBALL = f"https://registry.npmjs.org/lucide-static/-/lucide-static-{VERSION}.tgz"
INTEGRITY = "sha512-nuf4YoaKt3Pq9scXVQ71SLr0Q1/UZiTcfBBivFsCVL6biAzPplTikVWXLetBmK3oFEJS6f2L1rzgEsoJN/Ce0Q=="
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "android/app/src/main/assets/lucide/icons.json"
TAGS = ROOT / "installer/croissant/lucide-tags.json"
SVG = "{http://www.w3.org/2000/svg}"
NAME = re.compile(r"^package/icons/([a-z0-9-]+)\.svg$")


def num(e: ET.Element, k: str, default: float = 0.0) -> float:
    v = e.get(k)
    return float(v) if v not in (None, "") else default


def fmt(x: float) -> str:
    return f"{x:.3f}".rstrip("0").rstrip(".") or "0"


def points(e: ET.Element) -> list[str]:
    vals = re.split(r"[\s,]+", (e.get("points") or "").strip())
    return [f"{fmt(float(vals[i]))} {fmt(float(vals[i + 1]))}" for i in range(0, len(vals) - 1, 2)]


def ellipse(cx: float, cy: float, rx: float, ry: float) -> str:
    return (f"M{fmt(cx - rx)} {fmt(cy)}a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(2 * rx)} 0"
            f"a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(-2 * rx)} 0z")


def rect(e: ET.Element) -> str:
    x, y, w, h = num(e, "x"), num(e, "y"), num(e, "width"), num(e, "height")
    rx = num(e, "rx", num(e, "ry"))
    ry = num(e, "ry", rx)
    rx, ry = min(rx, w / 2), min(ry, h / 2)
    if rx == 0 and ry == 0:
        return f"M{fmt(x)} {fmt(y)}h{fmt(w)}v{fmt(h)}h{fmt(-w)}z"
    sw, sh = w - 2 * rx, h - 2 * ry
    a = lambda dx, dy: f"a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(dx)} {fmt(dy)}"
    return (f"M{fmt(x + rx)} {fmt(y)}h{fmt(sw)}{a(rx, ry)}v{fmt(sh)}{a(-rx, ry)}"
            f"h{fmt(-sw)}{a(-rx, -ry)}v{fmt(-sh)}{a(rx, -ry)}z")


NUMBER = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")
COMMANDS = "MmZzLlHhVvCcSsQqTtAa"


def normalize(d: str) -> str:
    """Path data with every number and flag apart: an arc's two flags may be written run
    together with what follows ("a10 10 0 0010-10"), which a parser reading numbers misreads."""
    out: list[str] = []
    i, cmd, n = 0, "", 0
    while i < len(d):
        c = d[i]
        if c in " ,\t\n\r":
            i += 1
        elif c in COMMANDS:
            cmd, n = c, 0
            out.append(c)
            i += 1
        elif cmd in "Aa" and n % 7 in (3, 4):
            if c not in "01":
                raise ValueError(f"an arc flag that is not 0 or 1 in {d!r}")
            out.append(c)
            n += 1
            i += 1
        else:
            m = NUMBER.match(d, i)
            if m is None:
                raise ValueError(f"unreadable path data at {i} in {d!r}")
            out.append(fmt(float(m.group())))
            n += 1
            i = m.end()
    return " ".join(out)


def element(e: ET.Element) -> str | None:
    tag = e.tag.removeprefix(SVG)
    if tag == "path":
        d = normalize(e.get("d") or "")
    elif tag == "circle":
        d = ellipse(num(e, "cx"), num(e, "cy"), num(e, "r"), num(e, "r"))
    elif tag == "ellipse":
        d = ellipse(num(e, "cx"), num(e, "cy"), num(e, "rx"), num(e, "ry"))
    elif tag == "rect":
        d = rect(e)
    elif tag == "line":
        d = f"M{fmt(num(e, 'x1'))} {fmt(num(e, 'y1'))}L{fmt(num(e, 'x2'))} {fmt(num(e, 'y2'))}"
    elif tag in ("polyline", "polygon"):
        p = points(e)
        d = "M" + "L".join(p) + ("z" if tag == "polygon" else "")
    else:
        raise ValueError(f"an element Croissant does not draw: <{tag}>")
    fill = e.get("fill")
    return ("f:" if fill not in (None, "none") else "") + d


def main() -> None:
    print(f"lucide-static {VERSION}: fetching", file=sys.stderr)
    data = urllib.request.urlopen(TARBALL, timeout=120).read()
    algo, want = INTEGRITY.split("-", 1)
    if base64.b64encode(hashlib.new(algo, data).digest()).decode() != want:
        sys.exit("the tarball does not match its pinned hash")
    icons: dict[str, list[str]] = {}
    licence = None
    tags: dict[str, list[str]] = {}
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
        for m in tar.getmembers():
            if m.name == "package/LICENSE":
                licence = tar.extractfile(m).read().decode()
            if m.name == "package/tags.json":
                tags = json.loads(tar.extractfile(m).read())
            n = NAME.match(m.name)
            if not n or not m.isfile():
                continue
            root = ET.fromstring(tar.extractfile(m).read())
            icons[n.group(1)] = [d for d in (element(e) for e in root) if d]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    if licence is None:
        sys.exit("no LICENSE in the tarball")
    OUT.write_text(json.dumps({"version": VERSION, "icons": dict(sorted(icons.items()))}, separators=(",", ":")))
    (OUT.parent / "LICENSE").write_text(licence)
    kept = {n: [str(t) for t in tags[n]] for n in sorted(icons) if tags.get(n)}
    TAGS.write_text(json.dumps({"version": VERSION, "tags": kept}, separators=(",", ":")) + "\n")
    print(f"wrote {len(icons)} icons, {OUT.stat().st_size // 1024} kB, to {OUT}", file=sys.stderr)


if __name__ == "__main__":
    main()
