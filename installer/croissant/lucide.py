"""Lucide's icons as the C1 has them, for an Atelier app's glyph: their names, found by a word,
and drawn.

The icons are the app's own asset (android/app/src/main/assets/lucide/icons.json), so a name
found here is one the C1 draws. Their tags (lucide-tags.json beside this file) come from the
same pinned release; scripts/lucide.py writes both. Nothing is fetched: a checkout is enough.
"""
from __future__ import annotations

import difflib
import functools
import json
import math
import re
from pathlib import Path

from . import paths

ICONS = paths.REPO / 'android/app/src/main/assets/lucide/icons.json'
TAGS = Path(__file__).with_name('lucide-tags.json')


@functools.cache
def icons() -> dict[str, list[str]]:
    """{name: [path data, ...]}, a path starting "f:" filled, the rest 2-unit round strokes on 24."""
    return json.loads(ICONS.read_text())['icons']


@functools.cache
def tags() -> dict[str, list[str]]:
    try:
        return json.loads(TAGS.read_text())['tags']
    except (OSError, ValueError, KeyError):
        return {}


def has(name: str) -> bool:
    return name in icons()


def search(query: str, limit: int = 12) -> list[str]:
    """The icons for [query], best first: by name, then by Lucide's tags, then names and tags
    close to it (a typo). Every word of the query counts."""
    words = [w for w in re.split(r'[^a-z0-9]+', query.lower()) if w]
    if not words:
        return []
    whole = '-'.join(words)
    names, tagged = icons(), tags()
    scores: dict[str, float] = {}
    for name in names:
        parts = name.split('-')
        tg = tagged.get(name, [])
        score = 0.0
        if name == whole:
            score += 100
        for w in words:
            if w in parts:
                score += 30 if parts[0] == w else 24
            elif any(p.startswith(w) for p in parts):
                score += 14
            elif w in name:
                score += 8
            if w in tg:
                score += 12
            elif any(t.startswith(w) for t in tg):
                score += 5
        if score:
            # Shorter names first among equals: "droplet" before "droplet-off".
            scores[name] = score - len(parts) * 0.5
    if len(scores) < limit:
        vocabulary = set(names) | {t for ts in tagged.values() for t in ts}
        for w in words:
            for near in difflib.get_close_matches(w, vocabulary, n=4, cutoff=0.75):
                r = difflib.SequenceMatcher(None, w, near).ratio()
                for name in names:
                    parts = name.split('-')
                    s = 6 * r if near in parts else 3 * r if near in tagged.get(name, []) else 0
                    if s and name not in scores or s > scores.get(name, 0):
                        scores[name] = s - len(parts) * 0.5
    return sorted(scores, key=lambda n: (-scores[n], n))[:limit]


# --- drawing ---------------------------------------------------------------------------

_TOKEN = re.compile(r'[MmZzLlHhVvCcSsQqTtAa]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?')
_ARGS = {'M': 2, 'L': 2, 'H': 1, 'V': 1, 'C': 6, 'S': 4, 'Q': 4, 'T': 2, 'A': 7, 'Z': 0}
_STEPS = 16   # segments a curve is drawn in: a glyph is a few hundred pixels at most


def _arc(x1, y1, rx, ry, phi, large, sweep, x2, y2):
    """Points along an SVG arc, after (x1, y1), as SVG's implementation notes convert it."""
    if rx == 0 or ry == 0 or (x1, y1) == (x2, y2):
        return [(x2, y2)]
    rx, ry = abs(rx), abs(ry)
    c, s = math.cos(math.radians(phi)), math.sin(math.radians(phi))
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    xp, yp = c * dx + s * dy, -s * dx + c * dy
    k = xp * xp / (rx * rx) + yp * yp / (ry * ry)
    if k > 1:
        rx, ry = rx * math.sqrt(k), ry * math.sqrt(k)
    num = rx * rx * ry * ry - rx * rx * yp * yp - ry * ry * xp * xp
    den = rx * rx * yp * yp + ry * ry * xp * xp
    f = math.sqrt(max(0.0, num / den)) * (-1 if large == sweep else 1)
    cxp, cyp = f * rx * yp / ry, -f * ry * xp / rx
    cx, cy = c * cxp - s * cyp + (x1 + x2) / 2, s * cxp + c * cyp + (y1 + y2) / 2

    def angle(ux, uy):
        return math.atan2(uy, ux)
    t1 = angle((xp - cxp) / rx, (yp - cyp) / ry)
    dt = angle((-xp - cxp) / rx, (-yp - cyp) / ry) - t1
    if sweep and dt < 0:
        dt += 2 * math.pi
    elif not sweep and dt > 0:
        dt -= 2 * math.pi
    n = max(4, int(abs(dt) / (math.pi / 16)))
    out = []
    for i in range(1, n + 1):
        t = t1 + dt * i / n
        ex, ey = rx * math.cos(t), ry * math.sin(t)
        out.append((c * ex - s * ey + cx, s * ex + c * ey + cy))
    return out


def outlines(d: str) -> list[tuple[list[tuple[float, float]], bool]]:
    """Path data as polylines: (points, closed) for each subpath, curves and arcs flattened."""
    toks = _TOKEN.findall(d)
    out: list[tuple[list, bool]] = []
    pts: list = []
    x = y = sx = sy = 0.0
    ctrl = None          # the last curve's second control point, for S and T
    cmd = ''
    i = 0

    def flush(closed=False):
        nonlocal pts
        if pts:
            out.append((pts, closed))
        pts = []

    while i < len(toks):
        if toks[i].isalpha():
            cmd = toks[i]
            i += 1
            if cmd in 'Zz':
                flush(closed=True)
                x, y = sx, sy
                ctrl = None
                continue
        up, rel = cmd.upper(), cmd.islower()
        n = _ARGS[up]
        a = [float(v) for v in toks[i:i + n]]
        i += n
        if len(a) < n:
            break
        ox, oy = (x, y) if rel else (0.0, 0.0)
        if up == 'M':
            flush()
            x, y = a[0] + ox, a[1] + oy
            sx, sy = x, y
            pts = [(x, y)]
            cmd = 'l' if rel else 'L'      # further pairs are lines
            ctrl = None
            continue
        if not pts:
            pts = [(x, y)]
        if up == 'L':
            x, y = a[0] + ox, a[1] + oy
            pts.append((x, y))
            ctrl = None
        elif up == 'H':
            x = a[0] + ox
            pts.append((x, y))
            ctrl = None
        elif up == 'V':
            y = a[0] + (y if rel else 0.0)
            pts.append((x, y))
            ctrl = None
        elif up in 'CS':
            if up == 'C':
                c1 = (a[0] + ox, a[1] + oy)
                c2, end = (a[2] + ox, a[3] + oy), (a[4] + ox, a[5] + oy)
            else:
                c1 = (2 * x - ctrl[0], 2 * y - ctrl[1]) if ctrl and ctrl[2] == 'C' else (x, y)
                c2, end = (a[0] + ox, a[1] + oy), (a[2] + ox, a[3] + oy)
            p0 = (x, y)
            for k in range(1, _STEPS + 1):
                t = k / _STEPS
                u = 1 - t
                pts.append((u ** 3 * p0[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t ** 3 * end[0],
                            u ** 3 * p0[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t ** 3 * end[1]))
            x, y = end
            ctrl = (c2[0], c2[1], 'C')
        elif up in 'QT':
            if up == 'Q':
                c1, end = (a[0] + ox, a[1] + oy), (a[2] + ox, a[3] + oy)
            else:
                c1 = (2 * x - ctrl[0], 2 * y - ctrl[1]) if ctrl and ctrl[2] == 'Q' else (x, y)
                end = (a[0] + ox, a[1] + oy)
            p0 = (x, y)
            for k in range(1, _STEPS + 1):
                t = k / _STEPS
                u = 1 - t
                pts.append((u * u * p0[0] + 2 * u * t * c1[0] + t * t * end[0],
                            u * u * p0[1] + 2 * u * t * c1[1] + t * t * end[1]))
            x, y = end
            ctrl = (c1[0], c1[1], 'Q')
        elif up == 'A':
            end = (a[5] + ox, a[6] + oy)
            pts.extend(_arc(x, y, a[0], a[1], a[2], int(a[3]), int(a[4]), *end))
            x, y = end
            ctrl = None
    flush()
    return out


def draw(img, name: str, x: float, y: float, side: float, ink, width: float = 2.0):
    """Icon [name] onto the Pillow image [img], its 24-unit box at (x, y) [side] pixels on a side,
    in [ink]: strokes [width] units wide with round caps and joins, as the C1 draws it."""
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)
    k = side / 24
    w = width * k
    r = w / 2
    for path in icons()[name]:
        filled = path.startswith('f:')
        for pts, closed in outlines(path.removeprefix('f:')):
            p = [(x + px * k, y + py * k) for px, py in pts]
            if filled:
                if len(p) > 2:
                    d.polygon(p, fill=ink)
                continue
            if closed:
                p.append(p[0])
            if len(p) > 1:
                d.line(p, fill=ink, width=max(1, round(w)))
            for px, py in p:      # round joins and caps
                d.ellipse((px - r, py - r, px + r, py + r), fill=ink)
