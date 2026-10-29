#!/usr/bin/env python3
"""
Draws docs/images/glyphs/, the README's row of the features round the scroll wheel: one
disc a place, in Beam's order, each in its hue with its glyph in black. Each place is its
own image, named for it, so the README can link each glyph to its page. Each carries half
the gap on both sides, so the images set side by side read as one row. The discs in
EXTRA follow, drawn the same way, for the README's text.

    uv run --with pillow python scripts/glyphs.py

Everything is read from the app itself, so the row never falls behind the device: the
places and their order from ui/Feature.kt and ui/beam/Places.kt, the hues from
ui/beam/Ramp.kt (`hueOf`), the glyphs from ui/Icons.kt, drawn as the side wheel draws
them (a glyph box the disc's radius, 2-unit round strokes on the 24-unit grid).

It writes an SVG into a temporary folder and shoots it with headless Chrome, which a Mac
with Chrome or Playwright already has; `CHROME=/path/to/it` names another. With any other
rasteriser at hand, convert that SVG instead. Pillow trims the shot to the row and cuts it
into the places.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UI = ROOT / "android/app/src/main/java/com/bleural/croissant/ui"
OUT = ROOT / "docs/images/glyphs"

# The row as the wheel has it, at twice the README's own size so it stays crisp.
SCALE = 2
DISC = 46 * SCALE
GAP = 7 * SCALE

# Discs beyond the row, for a feature inside a place: each its file name, the place whose
# hue it takes and its glyph in ui/Icons.kt.
EXTRA = [("esim", "Settings", "Sim")]


# ---- the app's own sources ------------------------------------------------------------


def places() -> list[str]:
    """Beam's places in wheel order (ui/beam/Places.kt: the room without Settings, then Agents, Atelier, Settings)."""
    body = (UI / "Feature.kt").read_text()
    entries = re.findall(r"^    (\w+)\(\"", body, re.M)
    room = [f for f in entries if f not in ("Atelier", "Agents", "Settings")]
    return room + ["Agents", "Atelier", "Settings"]


def hues() -> dict[str, str]:
    """Each feature's hue in Beam (`hueOf`), as #rrggbb."""
    body = (UI / "beam/Ramp.kt").read_text()
    found = re.findall(r"Feature\.(\w+) -> Color\(0xFF([0-9A-Fa-f]{6})\)", body)
    return {f: "#" + c.lower() for f, c in found}


def glyph_names() -> dict[str, str]:
    """The glyph each feature carries (ui/Feature.kt's `glyph`)."""
    body = (UI / "Feature.kt").read_text()
    return dict(re.findall(r"^            (\w+) -> Glyphs\.(\w+)$", body, re.M))


def discs() -> list[tuple[str, str, str]]:
    """Every disc to draw, each its file name, its hue and its glyph: the row's places, then EXTRA."""
    hue, named = hues(), glyph_names()
    row = [(f.lower(), hue[f], named[f]) for f in places()]
    return row + [(name, hue[place], glyph) for name, place, glyph in EXTRA]


# ---- ui/Icons.kt, as the Sheet draws it -------------------------------------------------


def shapes(name: str, icons: str) -> list[tuple[str, str, float, bool]]:
    """One glyph's paths, each (how, path data, stroke width, even odd): how is fill, stroke or both."""
    start = icons.index(f"val {name} by lazy {{")
    body = icons[start : start + balanced(icons[start:])]
    out = []
    for call in calls(body):
        how, args = call
        if how not in ("fill", "stroke", "both"):
            continue
        width, odd = 2.0, False
        data = value(args[0])
        for a in args[1:]:
            if a.startswith("width"):
                width = float(a.split("=")[1].strip().rstrip("f"))
            elif a.startswith("evenOdd"):
                odd = a.split("=")[1].strip() == "true"
        out.append((how, data, width, odd))
    if not out:
        raise SystemExit(f"{name}: no path in it, is it drawn another way?")
    return out


def balanced(s: str) -> int:
    """How far the brace opened in [s] runs."""
    depth = 0
    for i, c in enumerate(s):
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i + 1
    raise SystemExit("a glyph's braces do not close")


def calls(body: str) -> list[tuple[str, list[str]]]:
    """The Sheet calls in a glyph's body, each its name and its arguments as written."""
    out = []
    for m in re.finditer(r"\b(fill|stroke|both)\(", body):
        inside = body[m.end() : m.end() + run(body[m.end() :])]
        out.append((m.group(1), split(inside)))
    return out


def run(s: str) -> int:
    """How far the parenthesis already opened before [s] runs."""
    depth, quoted = 1, False
    for i, c in enumerate(s):
        if c == '"':
            quoted = not quoted
        elif quoted:
            continue
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
    raise SystemExit("a call's parentheses do not close")


def split(args: str) -> list[str]:
    """One call's arguments, cut at the commas of its own level."""
    out, depth, quoted, at = [], 0, False, 0
    for i, c in enumerate(args):
        if c == '"':
            quoted = not quoted
        elif quoted:
            continue
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == "," and depth == 0:
            out.append(args[at:i].strip())
            at = i + 1
    if args[at:].strip():
        out.append(args[at:].strip())
    return out


def value(arg: str) -> str:
    """An argument as path data: quoted pieces joined, or rect() or circle() as Icons.kt writes them."""
    if arg.startswith('"') or "+" in arg and '"' in arg:
        return "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', arg))
    m = re.fullmatch(r"(rect|circle)\(([^)]*)\)", arg.strip())
    if not m:
        raise SystemExit(f"a path neither written out nor rect/circle: {arg}")
    n = [float(x.strip().rstrip("f")) for x in m.group(2).split(",")]
    if m.group(1) == "circle":
        cx, cy, r = n
        return f"M{cx - r} {cy} a{r} {r} 0 1 0 {2 * r} 0 a{r} {r} 0 1 0 {-2 * r} 0 z"
    x, y, w, h, rx = n
    sw, sh = w - 2 * rx, h - 2 * rx
    return (
        f"M{x + rx} {y} h{sw} a{rx} {rx} 0 0 1 {rx} {rx} v{sh} a{rx} {rx} 0 0 1 {-rx} {rx} "
        f"h{-sw} a{rx} {rx} 0 0 1 {-rx} {-rx} v{-sh} a{rx} {rx} 0 0 1 {rx} {-rx} z"
    )


# ---- the row ----------------------------------------------------------------------------


def svg(row: list[tuple[str, str, str]]) -> str:
    icons = (UI / "Icons.kt").read_text()
    width = len(row) * DISC + (len(row) - 1) * GAP
    # The side wheel's own ratio (beam/SideWheel.kt): the glyph box is the disc's radius.
    box = DISC / 2
    k = box / 24
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{DISC}" viewBox="0 0 {width} {DISC}">']
    for i, (_, hue, glyph) in enumerate(row):
        x = i * (DISC + GAP)
        parts.append(f'<circle cx="{x + DISC / 2}" cy="{DISC / 2}" r="{DISC / 2}" fill="{hue}"/>')
        parts.append(f'<g transform="translate({x + (DISC - box) / 2} {(DISC - box) / 2}) scale({k})">')
        for how, d, w, odd in shapes(glyph, icons):
            if how == "fill":
                rule = ' fill-rule="evenodd"' if odd else ""
                parts.append(f'<path d="{d}" fill="#000"{rule}/>')
            else:
                fill = "#000" if how == "both" else "none"
                parts.append(
                    f'<path d="{d}" fill="{fill}" stroke="#000" stroke-width="{w}" '
                    'stroke-linecap="round" stroke-linejoin="round"/>'
                )
        parts.append("</g>")
    parts.append("</svg>")
    return "\n".join(parts)


def chrome() -> str:
    """Headless Chrome, wherever this machine keeps one."""
    if named := os.environ.get("CHROME"):
        return named
    here = [
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Chromium.app/Contents/MacOS/Chromium",
        shutil.which("chromium") or "",
        shutil.which("google-chrome") or "",
    ]
    cache = Path.home() / "Library/Caches/ms-playwright"
    here += sorted((str(p) for p in cache.glob("*/*/chrome-headless-shell")), reverse=True)
    for c in here:
        if c and Path(c).exists():
            return c
    raise SystemExit("no headless Chrome here: install one, or name it with CHROME=/path/to/it")


def shoot(source: str, row: list[tuple[str, str, str]], to: Path) -> None:
    """The SVG as a PNG, on nothing, trimmed to the row and cut into one image a disc."""
    from PIL import Image

    work = Path(tempfile.mkdtemp(prefix="croissant-glyphs-"))
    page_file = work / "glyphs.svg"
    page_file.write_text(source)
    shot = work / "shot.png"
    subprocess.run(
        [chrome(), "--headless", "--disable-gpu", "--default-background-color=00000000",
         "--force-device-scale-factor=1", "--window-size=1600,400", f"--screenshot={shot}", str(page_file)],
        check=True, capture_output=True,
    )
    image = Image.open(shot).convert("RGBA")
    strip = image.crop(image.getbbox())
    expected = len(row) * DISC + (len(row) - 1) * GAP
    if strip.size != (expected, DISC):
        raise SystemExit(f"the row came out {strip.size[0]}x{strip.size[1]}, not {expected}x{DISC}: {shot}")
    to.mkdir(parents=True, exist_ok=True)
    for i, (name, _, _) in enumerate(row):
        cell = Image.new("RGBA", (DISC + GAP, DISC), (0, 0, 0, 0))
        x = i * (DISC + GAP)
        cell.paste(strip.crop((x, 0, x + DISC, DISC)), (GAP // 2, 0))
        cell.save(to / f"{name}.png", optimize=True)
    print(f"{to.relative_to(ROOT)}/: {len(row)} images, {DISC + GAP}x{DISC} each")
    print(f"the svg it came from: {page_file}")


def main() -> int:
    row = discs()
    print("the discs: " + ", ".join(name for name, _, _ in row))
    shoot(svg(row), row, OUT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
