"""An Atelier app's QR code, as Beam would draw it: its repository's link, for a C1 to scan
(Atelier, +, the scan key) or a phone to open.

The code is drawn black on the app's hue, the way Beam puts black on a hue (a key, the side
wheel's disc), on a black rounded card: the ground. Dark on light is what every reader takes;
the C1's reader takes it inverted too (tryInvert), but readers that do not try (zxing-cpp
without it, OpenCV) read nothing of a light code on black. Modules are round dots, the
finders and alignment marks softly rounded squares, and the middle is a disc of the ground
with the app's glyph on it in the hue, as the Atelier board draws an app. Error correction H,
so the middle can go; a quiet zone of four modules of hue all round. The hue is the one Beam
draws (lift): a dark pick comes out lighter, as on the C1, and black reads on it. No words:
the README carries the name (the font cannot be shipped).

    qr.svg   crisp in a README (GitHub shows it through <img>)
    qr.png   1024 px, to share
"""
from __future__ import annotations

import math
from pathlib import Path

import segno

from . import lucide

GROUND = '#000000'
QUIET = 4          # modules of hue round the code, as the standard asks
FRAME = 2          # modules of black round the hue: the card
DOT = 0.43         # a module's dot radius, in modules
HOLE = 0.135       # the middle's black disc, its radius as a part of the code's side
GLYPH = 1.3        # the glyph's 24-unit box, as a part of that radius
PNG = 1024


def link(slug: str) -> str:
    """What the code holds: the repository's link, which the C1's add page takes (RepoRef.parse)
    and a phone opens."""
    return f'https://github.com/{slug}'


def _luminance(r: float, g: float, b: float) -> float:
    def lin(c):
        v = c / 255
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def lift(hue: str) -> str:
    """[hue] (#RRGGBB) as Beam draws it (liftHue, ui/beam/AtelierRules.kt): as it is when black
    reads on it, else at full brightness, then mixed with white just as far as it needs."""
    rgb = int(hue[1:], 16)
    r0, g0, b0 = (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255
    if _luminance(r0, g0, b0) >= 0.175:
        return '#%06X' % rgb
    top = max(r0, g0, b0)
    k = 255 / top if top else 1
    r, g, b = r0 * k, g0 * k, b0 * k

    def mixed(t):
        return r + (255 - r) * t, g + (255 - g) * t, b + (255 - b) * t
    t = 0.0
    if _luminance(r, g, b) < 0.18:
        lo, hi = 0.0, 1.0
        for _ in range(24):
            mid = (lo + hi) / 2
            if _luminance(*mixed(mid)) >= 0.18:
                hi = mid
            else:
                lo = mid
        t = hi
    return '#%02X%02X%02X' % tuple(min(255, max(0, round(v))) for v in mixed(t))


def _alignments(version: int) -> list[int]:
    """The rows (and columns) of the alignment marks' centres, as the standard places them."""
    if version == 1:
        return []
    n = version // 7 + 2
    step = 26 if version == 32 else (version * 4 + n * 2 + 1) // (n * 2 - 2) * 2
    side = version * 4 + 17
    return [6] + sorted(side - 7 - i * step for i in range(n - 1))


class Design:
    """The code as shapes, in modules from the card's corner, which both renderers draw."""

    def __init__(self, text: str, hue: str, glyph: str | None):
        qr = segno.make_qr(text, error='h', boost_error=False)
        self.text, self.version = text, qr.version
        self.matrix = [[bool(v) for v in row] for row in qr.matrix]
        self.n = n = len(self.matrix)
        self.hue = lift(hue)
        self.glyph = glyph if glyph and lucide.has(glyph) else None
        self.o = o = FRAME + QUIET                     # the code's first module
        self.side = n + 2 * o
        self.rects: list[tuple] = []                   # (x, y, w, h, radius, colour)
        self.dots: list[tuple] = []                    # (cx, cy)
        self.discs: list[tuple] = []                   # (cx, cy, r, colour)
        s = self.side
        self.rects.append((0, 0, s, s, s * 0.085, GROUND))
        self.rects.append((FRAME, FRAME, s - 2 * FRAME, s - 2 * FRAME, s * 0.085 - FRAME * 0.6, self.hue))
        taken = set()
        for fx, fy in ((0, 0), (n - 7, 0), (0, n - 7)):
            self._mark(fx, fy, 7)
            taken |= {(fx + i, fy + j) for i in range(7) for j in range(7)}
        pos = _alignments(self.version)
        for cy in pos:
            for cx in pos:
                box = {(cx + i, cy + j) for i in range(-2, 3) for j in range(-2, 3)}
                if box & taken:
                    continue
                self._mark(cx - 2, cy - 2, 5)
                taken |= box
        c = n / 2
        self.hole = HOLE * n if self.glyph else 0
        for y, row in enumerate(self.matrix):
            for x, dark in enumerate(row):
                if not dark or (x, y) in taken:
                    continue
                if self.hole and math.hypot(x + 0.5 - c, y + 0.5 - c) < self.hole + 0.5:
                    continue
                self.dots.append((o + x + 0.5, o + y + 0.5))
        if self.glyph:
            # The ground showing through, and the glyph on it in the hue, as the Atelier board draws an app.
            self.discs.append((o + c, o + c, self.hole, GROUND))
            g = self.hole * GLYPH
            self.glyph_box = (o + c - g / 2, o + c - g / 2, g)

    def _mark(self, x: int, y: int, size: int):
        """A finder (7) or an alignment mark (5): a dark ring a module wide, a light one, a dark
        square. Only softly rounded: readers that look for the 1:1:3:1:1 runs (OpenCV's) start
        missing marks rounded any further, where zxing and phones still read them."""
        o, k = self.o, size / 7
        self.rects.append((o + x, o + y, size, size, 0.4 * k, GROUND))
        self.rects.append((o + x + 1, o + y + 1, size - 2, size - 2, 0.25 * k, self.hue))
        self.rects.append((o + x + 2, o + y + 2, size - 4, size - 4, min(0.8 * k, (size - 4) / 2), GROUND))

    # --- SVG ---------------------------------------------------------------------------

    def svg(self, px: int = 512) -> str:
        f = _fmt
        s = self.side
        out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {f(s)} {f(s)}" width="{px}" height="{px}">',
               f'<title>{_xml(self.text)}</title>']
        for x, y, w, h, r, colour in self.rects:
            out.append(f'<rect x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" rx="{f(r)}" fill="{colour}"/>')
        d = ''.join(f'M{f(cx - DOT)} {f(cy)}a{f(DOT)} {f(DOT)} 0 1 0 {f(2 * DOT)} 0a{f(DOT)} {f(DOT)} 0 1 0 {f(-2 * DOT)} 0z'
                    for cx, cy in self.dots)
        out.append(f'<path d="{d}" fill="{GROUND}"/>')
        for cx, cy, r, colour in self.discs:
            out.append(f'<circle cx="{f(cx)}" cy="{f(cy)}" r="{f(r)}" fill="{colour}"/>')
        if self.glyph:
            x, y, g = self.glyph_box
            out.append(f'<g transform="translate({f(x)} {f(y)}) scale({f(g / 24)})" fill="none" stroke="{self.hue}" '
                       'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">')
            for p in lucide.icons()[self.glyph]:
                if p.startswith('f:'):
                    out.append(f'<path d="{p[2:]}" fill="{self.hue}" stroke="none"/>')
                else:
                    out.append(f'<path d="{p}"/>')
            out.append('</g>')
        out.append('</svg>')
        return '\n'.join(out) + '\n'

    # --- PNG ---------------------------------------------------------------------------

    def png(self, px: int = PNG):
        """A Pillow image [px] on a side, drawn four times larger and brought down, for smooth edges."""
        from PIL import Image, ImageDraw
        big = px * 4
        k = big / self.side
        img = Image.new('RGBA', (big, big), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        for x, y, w, h, r, colour in self.rects:
            d.rounded_rectangle((x * k, y * k, (x + w) * k - 1, (y + h) * k - 1), radius=r * k, fill=colour)
        for cx, cy in self.dots:
            d.ellipse(((cx - DOT) * k, (cy - DOT) * k, (cx + DOT) * k, (cy + DOT) * k), fill=GROUND)
        for cx, cy, r, colour in self.discs:
            d.ellipse(((cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k), fill=colour)
        if self.glyph:
            x, y, g = self.glyph_box
            lucide.draw(img, self.glyph, x * k, y * k, g * k, self.hue)
        return img.resize((px, px), Image.LANCZOS)


def _fmt(v: float) -> str:
    return f'{v:.3f}'.rstrip('0').rstrip('.') or '0'


def _xml(s: str) -> str:
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')


def write(folder: Path, slug: str, hue: str, glyph: str | None) -> tuple[Path, Path]:
    """qr.svg and qr.png of [slug]'s link into [folder]."""
    design = Design(link(slug), hue, glyph)
    svg, png = folder / 'qr.svg', folder / 'qr.png'
    svg.write_text(design.svg())
    design.png().save(png, optimize=True)
    return svg, png
