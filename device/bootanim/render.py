#!/usr/bin/env python3
"""Render anim.html frame by frame into a bootanimation.zip.

  uv run --with playwright device/bootanim/render.py [outdir]

Uses Playwright's chromium (`uv run --with playwright playwright install chromium` once). Output: outdir/part0, part1 PNGs,
desc.txt, and bootanimation.zip (stored, not deflated, as Android requires).
"""
import pathlib, subprocess, sys
from playwright.sync_api import sync_playwright

here = pathlib.Path(__file__).resolve().parent
out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else here / 'build').resolve()
url = (here / 'anim.html').as_uri()

with sync_playwright() as p:
    b = p.chromium.launch()
    page = b.new_page(viewport={'width': 480, 'height': 640}, device_scale_factor=1)
    page.goto(url + '?frame=0')
    page.wait_for_function('document.title === "ready"')
    total, intro, loop = page.evaluate('[TOTAL, INTRO, LOOP]')
    parts = [(n, a, z) for n, a, z in [('part0', 0, intro), ('part1', intro, intro + loop)] if z > a]
    for name, a, z in parts:
        d = out / name; d.mkdir(parents=True, exist_ok=True)
        for f in range(a, z):
            page.evaluate(f'render({f})')
            (d / f'{f - a:05d}.png').write_bytes(page.locator('#stage').screenshot())
        print(name, z - a, 'frames')
    b.close()

desc = '480 640 30\n' + ''.join(('c 1 0 ' if n == 'part0' else 'p 0 0 ') + n + '\n' for n, _, _ in parts)
(out / 'desc.txt').write_text(desc)
zf = out / 'bootanimation.zip'
zf.unlink(missing_ok=True)
subprocess.run(['zip', '-0', '-q', '-r', str(zf), 'desc.txt', *[n for n, _, _ in parts]], cwd=out, check=True)
print('wrote', zf, zf.stat().st_size, 'bytes')
