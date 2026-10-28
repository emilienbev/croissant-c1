#!/usr/bin/env python3
"""Render LK's charging screens: the boot logo with a battery glyph under the wordmark.

  uv run --with pillow device/logo/charging.py [take]      # previews, in cache/previews
  uv run --with pillow device/logo/charging.py partition <template.bin> <out.bin> [logo.png]

They replace stock's rabbit in a wheel in the `logo` partition, same indexes, same roles:

  15..34  the loop LK plays while it charges (stock: the rabbit running, a bolt blinking)
  2, 37   a still with the bolt
  39..41  the red blink: 39 and 41 on, 40 off (stock: a red "!" on the battery)

The glyph sits where the boot animation's track is, the wordmark's ink bottom + 32 px,
centred on the wordmark's ink: an outline in muted ink, the R1 orange inside. 480x640, opaque.
`frames(logo, take)` works from any logo (the installer's named ones).
"""
import math, os, pathlib, sys
from PIL import Image, ImageDraw

here = pathlib.Path(__file__).resolve().parent
W, H = 480, 640
GAP = 32                                  # under the wordmark's ink, as the boot loader's track
BW, BH, R, STROKE = 60, 30, 8, 3          # the battery body
NUB_W, NUB_H = 4, 12
INSET = 3                                 # clear between the outline and what is inside it
MUTED, ORANGE, RED = (0x8E, 0x8A, 0x87), (0xFF, 0x4F, 0x00), (0xE5, 0x48, 0x4D)
SS = 4
LOOP = list(range(15, 35))                # 20 frames
STILL = (2, 37)
LOW = {39: True, 40: False, 41: True}
TAKES = ('fill', 'bolt', 'breathe')
TAKE = 'fill'                             # picked (2026-10-05); the others kept as takes


def _mix(c, a, bg=(0, 0, 0)):
    return tuple(round(bg[i] + (c[i] - bg[i]) * a) for i in range(3))


def _glyph(bg, inside=None, level=1.0, bolt=None):
    """The battery at SS scale on [bg]: [inside] the fill colour, [level] its share of the
    width, [bolt] the bolt's colour, or None."""
    w, h = (BW + NUB_W + 2) * SS, BH * SS
    im = Image.new('RGB', (w, h), bg)
    d = ImageDraw.Draw(im)
    s = STROKE * SS
    d.rounded_rectangle((0, 0, BW * SS - 1, h - 1), radius=R * SS, outline=MUTED, width=s)
    nx = BW * SS + SS
    d.rounded_rectangle((nx, (h - NUB_H * SS) // 2, nx + NUB_W * SS - 1, (h + NUB_H * SS) // 2 - 1),
                        radius=2 * SS, fill=MUTED)
    if inside and level > 0:
        i = s + INSET * SS
        right = i + (BW * SS - 2 * i) * level
        d.rounded_rectangle((i, i, max(i + 2 * SS, right) - 1, h - i - 1), radius=(R - STROKE - 1) * SS, fill=inside)
    if bolt:
        cx, cy, k = BW * SS / 2, h / 2, SS
        pts = [(1.5, -10), (-6, 1.5), (-0.5, 1.5), (-1.5, 10), (6, -1.5), (0.5, -1.5)]
        d.polygon([(cx + x * k, cy + y * k) for x, y in pts], fill=bolt)
    return im.resize((w // SS, h // SS), Image.LANCZOS)


def _place(logo, glyph):
    im = logo.copy()
    x0, _, x1, bottom = logo.getbbox()     # the wordmark's ink, dot included
    im.paste(glyph, ((x0 + x1 - BW) // 2, bottom + GAP))   # the body centred under it, the nub outside
    return im


def _ease(t):
    return 0.5 - 0.5 * math.cos(math.pi * t)


def loop_glyph(take: str, f: int, bg):
    """Frame [f] of 20 of the loop."""
    if take == 'fill':                     # the fill grows over 14 frames, holds full for 4, empty for 2
        level = _ease(min(f / 13, 1)) if f < 18 else 0
        return _glyph(bg, ORANGE, level if f < 18 else 0)
    if take == 'bolt':                     # stock's timing: on 6, fading 4, off 10
        a = 1 if f < 6 else (1 - (f - 5) / 5) if f < 10 else 0
        return _glyph(bg, bolt=_mix(ORANGE, a, bg) if a > 0 else None)
    if take == 'breathe':                  # full, from 35 % to full and back
        a = 0.35 + 0.65 * (0.5 - 0.5 * math.cos(2 * math.pi * f / 20))
        return _glyph(bg, _mix(ORANGE, a, bg), 1.0)
    raise ValueError(take)


def frames(logo: Image.Image, take: str = TAKE) -> dict:
    """{logo index: image} for every charging screen, over [logo]."""
    logo = logo.convert('RGB')
    bg = logo.getpixel((0, 0))
    out = {i: _place(logo, loop_glyph(take, n, bg)) for n, i in enumerate(LOOP)}
    for i in STILL:
        out[i] = _place(logo, _glyph(bg, bolt=ORANGE))
    for i, on in LOW.items():
        out[i] = _place(logo, _glyph(bg, RED if on else None, 0.18))
    return out


BOOT = (0, 38)                            # the boot logo, and its copy drawn at the kernel stage


def partition(template: bytes, logo: Image.Image) -> bytes:
    """[template] (an R1's own logo partition) with the boot logo at 0 and 38 and these
    charging screens at their indexes; everything else stays the R1's own."""
    import importlib.util, tempfile
    spec = importlib.util.spec_from_file_location('mtklogo', here / 'logo.py')
    mtk = importlib.util.module_from_spec(spec); spec.loader.exec_module(mtk)
    with tempfile.TemporaryDirectory() as t:
        src, out = pathlib.Path(t, 'logo.bin'), pathlib.Path(t, 'new.bin')
        src.write_bytes(template)
        header, blobs = mtk.load(src)
        logo = logo.convert('RGB')
        for i in BOOT:
            blobs[i] = mtk.image_to_blob(logo)
        for i, im in frames(logo).items():
            blobs[i] = mtk.image_to_blob(im)
        mtk.save(header, blobs, out)
        return out.read_bytes()


def main():
    if sys.argv[1:2] == ['partition']:     # charging.py partition <template.bin> <out.bin> [logo.png]
        logo = Image.open(sys.argv[4] if len(sys.argv) > 4 else here / 'croissant-boot-logo.png')
        out = partition(pathlib.Path(sys.argv[2]).read_bytes(), logo)
        pathlib.Path(sys.argv[3]).write_bytes(out)
        print('wrote', sys.argv[3], len(out), 'bytes')
        return
    takes = sys.argv[1:] or TAKES
    logo = Image.open(here / 'croissant-boot-logo.png')
    assets = pathlib.Path(os.environ.get('CROISSANT_CACHE') or here.parents[1] / 'cache') / 'previews'
    assets.mkdir(parents=True, exist_ok=True)
    for take in takes:
        fs = frames(logo, take)
        for name, i in (('a', 15), ('b', 20), ('c', 25), ('d', 31)):
            fs[i].save(assets / f'charging-{take}-{name}.png', optimize=True)
        print('wrote', take, 'to', assets)
    fs[2].save(assets / 'charging-still.png', optimize=True)
    fs[39].save(assets / 'charging-low.png', optimize=True)


if __name__ == '__main__':
    main()
