#!/usr/bin/env python3
"""Render a name as the boot logo: the name in Power Grotesk Light, an orange dot after it.

  uv run --with pillow device/logo/wordmark.py <name> <out.png>

480x640, black, the mark centred. Measured off the first boot logo (2026-09-29):
Light at 50 px, the dot a 7 px circle in FF3200 on the baseline, 2 px clear of the last
letter, the baseline at y 333. The original sits 4 px right of centre; this centres it.
A name wider than 400 px is set smaller, around the same centre line. The installer (`installer/croissant/brand.py`) makes the logo and the boot
animation from this, so a device can carry its own name (2026-09-29).
"""
import os, pathlib, sys
from PIL import Image, ImageDraw, ImageFont

W, H = 480, 640
# The owner's own Power Grotesk, where `./croissant font` keeps it (the checkout's cache/fonts);
# CROISSANT_FONTS points elsewhere.
REPO = pathlib.Path(__file__).resolve().parents[2]
FONTS = pathlib.Path(os.environ.get('CROISSANT_FONTS') or
                     pathlib.Path(os.environ.get('CROISSANT_CACHE') or REPO / 'cache') / 'fonts')
FONT = FONTS / 'PowerGrotesk-Light.ttf'
SIZE, BASELINE, MAX_W = 50, 333, 400
WHITE, ORANGE = (255, 255, 255), (255, 0x32, 0)
SS = 4  # supersampling, for edges close to the browser-rendered original


def render(name: str) -> Image.Image:
    if not FONT.is_file():
        raise FileNotFoundError(f'{FONT}: no Power Grotesk Light. ./croissant font puts it there, or set CROISSANT_FONTS')
    size = SIZE
    while True:
        f = ImageFont.truetype(str(FONT), size * SS)
        left, _, right, _ = f.getbbox(name, anchor='ls')
        dot, gap = round(size * SS * 0.14), round(size * SS * 0.03)
        width = right - left + gap + dot
        if width <= MAX_W * SS or size <= 20:
            break
        size -= 1
    base = (H // 2 + round((BASELINE - H // 2) * size / SIZE)) * SS
    x0 = (W * SS - width) // 2 - left
    im = Image.new('RGB', (W * SS, H * SS), (0, 0, 0))
    d = ImageDraw.Draw(im)
    d.text((x0, base), name, font=f, fill=WHITE, anchor='ls')
    dx = x0 + right + gap
    d.ellipse((dx, base - dot, dx + dot - 1, base - 1), fill=ORANGE)
    return im.resize((W, H), Image.LANCZOS)


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    render(sys.argv[1]).save(sys.argv[2])
    print('wrote', sys.argv[2])
