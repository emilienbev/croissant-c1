#!/usr/bin/env python3
"""Render the boot animation: the boot logo with a loader under the wordmark.

  uv run --with pillow device/bootanim/loader.py

Every frame is `device/logo/croissant-boot-logo.png` (the LK logo, so the first frame
changes nothing but the track appearing) with a 6 px track the width of the wordmark
under it, and a 40 px orange segment crossing it left to right in 1.2 s, the app's
Meter sweep. One part, looped until the boot completes (`p 0 0`), so the animation
ends within 1.2 s of the exit signal. Written straight into the Magisk module,
`device/magisk/croissant-bootanim/system/product/media/bootanimation.zip` (stored,
not deflated, as bootanimation requires), plus `build/loader-sheet.png` to look at.

`bootanimation(logo)` does the same for any logo (the installer's named ones,
`device/logo/wordmark.py`): the track spans the logo's own ink, measured, not fixed.
"""
import io, pathlib, zipfile
from PIL import Image, ImageDraw

here = pathlib.Path(__file__).resolve().parent
FPS, LOOP_S = 30, 1.2
FRAMES = round(FPS * LOOP_S)

GAP, THICK, SEG = 32, 6, 40         # px under the wordmark; track height; the segment
ORANGE, TRACK = (0xFF, 0x4F, 0x00), (0x1E, 0x1E, 0x21)
SS = 4                              # supersampling for the rounded ends


def frames(logo: Image.Image) -> list:
    """Every frame of the loop over [logo]: the track under its ink, the segment crossing it."""
    logo = logo.convert('RGB')
    x0, _, x1, bottom = logo.getbbox()   # the wordmark's ink, dot included
    y0 = bottom + GAP
    w, h = x1 - x0, THICK
    bg = logo.getpixel((0, 0))

    def frame(f: int) -> Image.Image:
        im = logo.copy()
        strip = Image.new('RGB', (w * SS, h * SS), bg)
        d = ImageDraw.Draw(strip)
        d.rounded_rectangle((0, 0, w * SS - 1, h * SS - 1), radius=h * SS // 2, fill=TRACK)
        sx = f / FRAMES * (w - SEG)
        d.rounded_rectangle((round(sx * SS), 0, round((sx + SEG) * SS) - 1, h * SS - 1), radius=h * SS // 2, fill=ORANGE)
        im.paste(strip.resize((w, h), Image.LANCZOS), (x0, y0))
        return im

    return [frame(f) for f in range(FRAMES)]


def bootanimation(logo: Image.Image, frames_=None) -> bytes:
    """The bootanimation.zip for [logo], stored (bootanimation reads it uncompressed)."""
    fs = frames_ or frames(logo)
    W, H = fs[0].size
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_STORED) as z:
        z.writestr('desc.txt', f'{W} {H} {FPS}\np 0 0 part0\n')
        for i, im in enumerate(fs):
            b = io.BytesIO(); im.save(b, 'PNG', optimize=True)
            z.writestr(f'part0/{i:05d}.png', b.getvalue())
    return out.getvalue()


def main():
    logo = Image.open(here / '../logo/croissant-boot-logo.png')
    fs = frames(logo)
    W, H = fs[0].size
    out = here.parents[0] / 'magisk/croissant-bootanim/system/product/media/bootanimation.zip'
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(bootanimation(logo, fs))
    print('wrote', out, out.stat().st_size, 'bytes,', FRAMES, 'frames')

    build = here / 'build'; build.mkdir(exist_ok=True)
    picks = [0, 6, 12, 18, 24, 30]
    sheet = Image.new('RGB', (len(picks) * (W // 2 + 8) + 8, H // 2 + 16), (0x19, 0x19, 0x1B))
    for n, f in enumerate(picks):
        sheet.paste(fs[f].resize((W // 2, H // 2), Image.LANCZOS), (8 + n * (W // 2 + 8), 8))
    sheet.save(build / 'loader-sheet.png')
    fs[12].crop((100, 280, 380, 400)).resize((560, 240), Image.NEAREST).save(build / 'loader-detail.png')
    print('wrote', build / 'loader-sheet.png', 'and loader-detail.png')


if __name__ == '__main__':
    main()
