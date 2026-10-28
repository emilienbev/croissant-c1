#!/usr/bin/env python3
"""Render the off-mode charger's resources: the boot logo with the battery glyph, filling.

  uv run --with pillow device/offmode/charger.py [out_dir] [logo.png]

AOSP's `/system/bin/charger` (charge-only mode, after LK's charging still) reads
`/product/etc/res/values/charger/animation.txt` and the images it names under
`/product/etc/res/images/`; a tree from here is bind-mounted over `/product/etc/res`.
Written to `device/offmode/res` by default:

  values/charger/animation.txt     Lineage's layout and timings, our ranges and percent line
  images/charger/battery_scale.png 22 full-screen frames, row-interleaved (minui's
                                   multi-surface: PNG row y is frame y % 22), tEXt Frames=22
  images/charger/battery_fail.png  the logo with LK's low-battery glyph (unknown level)
  images/charger/percent_font.png  Power Grotesk Regular in minui's grid, 96 x 2 cells

Every frame is 480x640 and opaque, so the charger's centring puts it at 0,0 and it is
LK's charging still (`device/logo/charging.py`, imported, not copied) with frame k's
fill at k/21. The charger starts on the first frame whose range holds the level and
steps only through frames whose range holds it (healthd_mode_charger.cpp); every range
starts at 0, so from the level's fill it plays on to full (one frame at 100), 7 cycles,
then the screen blanks. It draws "<level>%" over each frame, 20 px under the glyph, in
ink F2F2F3, centred on the screen; the glyphs sit right of their cells' centres by the
battery's offset from it (`shift`), so the text centres on the battery
(2026-10-05: wider digit spacing over moving the boot logo).

With the defaults it also writes previews to `cache/previews/`
(charger-a/b/c at 23, 62, 100 %, charger-fail), the percent composited from the font
PNG as minui does, and checks the tree the way the charger will read it.
`tree(logo)` works from any logo (the installer's named ones).
"""
import importlib.util, io, math, os, pathlib, re, struct, sys
from PIL import Image, ImageDraw, ImageFont, PngImagePlugin

here = pathlib.Path(__file__).resolve().parent
repo = here.parents[1]
_spec = importlib.util.spec_from_file_location('charging', repo / 'device/logo/charging.py')
lk = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(lk)

# Frame k (fill k/21) holds the levels up to its own fill: the charger starts at the level's
# frame and every later one holds it too, so the fill grows to full, holds, and starts again,
# as LK's `fill` take does. (Lineage's ranges, 5k..5k+9, hold two frames only: the edge would jitter.)
FRAMES = [(500, 0, round(100 * k / 21)) for k in range(21)] + [(1000, 0, 100)]
INK = (0xF2, 0xF2, 0xF3)
TEXT_GAP = 20                             # clear rows between the glyph and the digits' top
# The owner's own Power Grotesk, where `./croissant font` keeps it (the checkout's cache/fonts);
# CROISSANT_FONTS points elsewhere.
CACHE = pathlib.Path(os.environ.get('CROISSANT_CACHE') or repo / 'cache')
FONT = pathlib.Path(os.environ.get('CROISSANT_FONTS') or CACHE / 'fonts') / 'PowerGrotesk-Regular.ttf'
SIZE = 41.5                               # digits 29 px tall
CELL_H, BASELINE = 42, 34                 # ascender + descender at SIZE; digits' top lands on row 5
SS = 4


def glyph_bottom(logo: Image.Image) -> int:
    """The first row under the glyph, where charging.py's _place puts it."""
    return logo.convert('RGB').getbbox()[3] + lk.GAP + lk.BH


def shift(logo: Image.Image) -> float:
    """How far right of the screen's centre the battery's centre is (charging.py centres it on
    the wordmark's ink, which sits right of centre in the committed logo). The charger centres
    the text on the screen, so the glyphs sit this far right in their cells instead."""
    x0, _, x1, _ = logo.convert('RGB').getbbox()
    return (x0 + x1) / 2 - lk.W / 2


def font(dx: float = 0) -> tuple:
    """(minui font PNG, cell width, first row of the digits' ink in a cell). ASCII 0x20..0x7F,
    each glyph's ink centred in its cell on a common baseline; Regular in both rows (the
    charger never asks for bold). Cells fit the digits, '%' is set smaller to fit; other
    wider glyphs are clipped."""
    if not FONT.is_file():
        raise FileNotFoundError(f'{FONT}: no Power Grotesk Regular. ./croissant font puts it there, or set CROISSANT_FONTS')

    def ink(c, size):
        canvas = Image.new('L', (int(SIZE * SS * 3), CELL_H * SS * 2))
        ImageDraw.Draw(canvas).text((SIZE * SS, BASELINE * SS), c, font=ImageFont.truetype(str(FONT), size * SS),
                                    fill=255, anchor='ls')
        box = canvas.getbbox()
        return (canvas.crop((box[0], 0, box[2], canvas.height)), box[2] - box[0]) if box else None
    inks = {c: i for c in map(chr, range(0x20, 0x80)) if c.isprintable() and (i := ink(c, SIZE))}
    # A pixel clear each side, and room for each glyph to sit [dx] right of its cell's centre.
    cw = math.ceil(max(inks[c][1] for c in '0123456789') / SS + 2 * abs(dx)) + 2
    # The cells are the digits' width, so "23%" reads as one word: '%' is set smaller, on the
    # same baseline, to fit its cell.
    pct = SIZE
    while inks['%'][1] > (cw - 2) * SS:
        pct -= 0.5
        inks['%'] = ink('%', pct)
    row = Image.new('L', (96 * cw * SS, CELL_H * SS))
    for c, (ink, w) in inks.items():
        cut = max(0, (w - cw * SS) // 2)
        ink = ink.crop((cut, 0, min(w, cut + cw * SS), CELL_H * SS))
        row.paste(ink, ((ord(c) - 0x20) * cw * SS + (cw * SS - ink.width) // 2 + round(dx * SS), 0))
    row = row.resize((96 * cw, CELL_H), Image.BOX)                     # value = coverage
    out = Image.new('L', (96 * cw, 2 * CELL_H))
    out.paste(row, (0, 0)); out.paste(row, (0, CELL_H))
    digits = row.crop((16 * cw, 0, 26 * cw, CELL_H))
    return out, cw, digits.point(lambda v: 255 if v >= 128 else 0).getbbox()[1]


def frames(logo: Image.Image) -> list:
    logo = logo.convert('RGB')
    bg = logo.getpixel((0, 0))
    n = len(FRAMES)
    return [lk._place(logo, lk._glyph(bg, lk.ORANGE, k / (n - 1))) for k in range(n)]


def fail(logo: Image.Image) -> Image.Image:
    logo = logo.convert('RGB')
    return lk._place(logo, lk._glyph(logo.getpixel((0, 0)), lk.RED, 0.18))


def interleave(fs: list) -> Image.Image:
    """minui's multi-surface layout: PNG row y is row y // n of frame y % n."""
    n, (w, h) = len(fs), fs[0].size
    rows, stride = [f.tobytes() for f in fs], w * 3
    data = b''.join(rows[y % n][(y // n) * stride:(y // n + 1) * stride] for y in range(h * n))
    return Image.frombytes('RGB', (w, h * n), data)


def _png(im: Image.Image, **text) -> bytes:
    info = PngImagePlugin.PngInfo()
    for k, v in text.items():
        info.add_text(k, v)
    b = io.BytesIO(); im.save(b, 'PNG', optimize=True, pnginfo=info)
    return b.getvalue()


def percent_y(logo: Image.Image, top: int) -> int:
    """percent_display's y is the cell's top (healthd_draw.cpp determine_xy, gr_text)."""
    return glyph_bottom(logo) + TEXT_GAP - top


def animation_txt(y: int) -> str:
    lines = ['# animation: num_cycles, first_frame_repeats, animation_file',
             'animation: 7 1 charger/battery_scale', '',
             '# percent_display: text_percent_pos_x, text_percent_pos_y, color_r, color_g, color_b, color_a, font_file',
             f'percent_display: c {y} {INK[0]} {INK[1]} {INK[2]} 255 charger/percent_font', '',
             'fail: charger/battery_fail', '',
             '# frame: disp_time min_level max_level']
    return '\n'.join(lines + [f'frame: {t} {lo} {hi}' for t, lo, hi in FRAMES]) + '\n'


def tree(logo: Image.Image) -> dict:
    """{path under /product/etc/res: contents} for [logo]."""
    fnt, _, top = font(shift(logo))
    fs = frames(logo)
    return {
        'values/charger/animation.txt': animation_txt(percent_y(logo, top)).encode(),
        'images/charger/battery_scale.png': _png(interleave(fs), Frames=str(len(fs))),
        'images/charger/battery_fail.png': _png(fail(logo)),
        'images/charger/percent_font.png': _png(fnt),
    }


# What the charger does with the tree, for the previews and the check.

def _chunks(png: bytes) -> list:
    out, i = [], 8
    while i < len(png):
        n, = struct.unpack('>I', png[i:i + 4])
        out.append((png[i + 4:i + 8].decode(), png[i + 8:i + 8 + n])); i += 12 + n
    return out


def decode_scale(png: bytes) -> list:
    """battery_scale as res_create_multi_display_surface reads it: Frames from a tEXt chunk
    before the first IDAT (png_read_info stops there), rows de-interleaved."""
    chunks = _chunks(png)
    head = chunks[:[c for c, _ in chunks].index('IDAT')]
    n = next(int(d.split(b'\0')[1]) for c, d in head if c == 'tEXt' and d.split(b'\0')[0] == b'Frames')
    im = Image.open(io.BytesIO(png))
    assert im.mode == 'RGB' and im.height % n == 0, (im.mode, im.height, n)
    w, h, stride, rows = im.width, im.height // n, im.width * 3, im.tobytes()
    return [Image.frombytes('RGB', (w, h), b''.join(rows[(r * n + k) * stride:(r * n + k + 1) * stride]
                                                    for r in range(h))) for k in range(n)]


def parse(txt: str) -> tuple:
    """(frame lines, percent x, percent y) as AnimationParser.cpp reads them."""
    fr = [tuple(map(int, m)) for m in re.findall(r'^\s*frame: (-?\d+) (-?\d+) (-?\d+)', txt, re.M)]
    m = re.search(r'^\s*percent_display: (c|-?\d+) (c|-?\d+) ', txt, re.M)
    return fr, m.group(1), m.group(2)


def draw_percent(screen: Image.Image, level: int, fnt: Image.Image, px: str, py: str) -> Image.Image:
    """healthd_draw.cpp draw_percent: "%d%%", determine_xy, gr_text's regular row, blended as
    pixel_blend does."""
    W, H = screen.size
    cw, chh = fnt.width // 96, fnt.height // 2
    s = f'{level}%'
    x = (W - len(s) * cw) // 2 if px == 'c' else int(px) if int(px) >= 0 else W + int(px) - len(s) * cw
    y = (H - chh) // 2 if py == 'c' else int(py) if int(py) >= 0 else H + int(py) - chh
    assert x >= 0 and y >= 0 and x + len(s) * cw <= W and y + chh <= H, 'gr_text would stop'
    im, px_ = screen.copy(), screen.load()
    out, a_ = im.load(), fnt.load()
    for i, c in enumerate(s):
        for j in range(chh):
            for k in range(cw):
                a = a_[(ord(c) - 0x20) * cw + k, j]
                if a:
                    p = px_[x + i * cw + k, y + j]
                    out[x + i * cw + k, y + j] = INK if a == 255 else \
                        tuple((p[n] * (255 - a) + INK[n] * a) // 255 for n in range(3))
    return im


def first_frame(level: int) -> int:
    return next(i for i, (_, lo, hi) in enumerate(FRAMES) if lo <= level <= hi)


def check(files: dict, logo: Image.Image):
    fr, px, py = parse(files['values/charger/animation.txt'].decode())
    scale = decode_scale(files['images/charger/battery_scale.png'])
    fs = frames(logo)
    assert len(scale) == len(fr) == len(FRAMES), (len(scale), len(fr))
    assert all(a.tobytes() == b.tobytes() for a, b in zip(scale, fs)), 'frames differ'
    bg = logo.convert('RGB').getpixel((0, 0))
    assert scale[0].tobytes() == lk._place(logo.convert('RGB'), lk._glyph(bg)).tobytes(), 'frame 0 not empty'
    assert scale[-1].tobytes() == lk._place(logo.convert('RGB'), lk._glyph(bg, lk.ORANGE, 1.0)).tobytes(), 'frame 21 not full'
    assert all(f.size == (lk.W, lk.H) for f in scale)
    fnt = Image.open(io.BytesIO(files['images/charger/percent_font.png']))
    assert fnt.mode == 'L' and fnt.width % 96 == 0 and fnt.height % 2 == 0, (fnt.mode, fnt.size)
    fl = Image.open(io.BytesIO(files['images/charger/battery_fail.png']))
    assert fl.mode == 'RGB' and fl.size == (lk.W, lk.H)
    draw_percent(scale[-1], 100, fnt, px, py)   # the widest string fits
    print(f'check ok: {len(scale)} frames == {len(fr)} frame lines, font {fnt.size} '
          f'cell {fnt.width // 96}x{fnt.height // 2}, percent at {px} {py}')


def main():
    out = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else here / 'res'
    logo_path = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else repo / 'device/logo/croissant-boot-logo.png'
    logo = Image.open(logo_path).convert('RGB')
    files = tree(logo)
    for path, data in files.items():
        p = out / path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
        print('wrote', p, len(data), 'bytes')
    check(files, logo)
    if len(sys.argv) > 1:
        return
    _, px, py = parse(files['values/charger/animation.txt'].decode())
    fnt = Image.open(io.BytesIO(files['images/charger/percent_font.png']))
    fs = decode_scale(files['images/charger/battery_scale.png'])
    assets = CACHE / 'previews'
    assets.mkdir(parents=True, exist_ok=True)
    for name, level in (('a', 23), ('b', 62), ('c', 100)):
        draw_percent(fs[first_frame(level)], level, fnt, px, py).save(assets / f'charger-{name}.png', optimize=True)
    Image.open(io.BytesIO(files['images/charger/battery_fail.png'])).save(assets / 'charger-fail.png', optimize=True)
    print('wrote previews to', assets)


if __name__ == '__main__':
    main()
