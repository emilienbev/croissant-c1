"""Render the static mascot and size study: uv run --with playwright device/mascot/preview.py."""
from pathlib import Path
from playwright.sync_api import sync_playwright

here = Path(__file__).resolve().parent
out = here / 'build'
out.mkdir(exist_ok=True)
with sync_playwright() as p:
    browser = p.chromium.launch()
    page = browser.new_page(viewport={'width': 900, 'height': 540}, device_scale_factor=1)
    page.goto((here / 'preview.html').as_uri())
    page.wait_for_function('Array.from(document.images).every(i => i.complete && i.naturalWidth > 0)')
    page.screenshot(path=str(out / 'contact-sheet.png'))
    page.set_viewport_size({'width': 640, 'height': 640})
    page.goto((here / 'mascot.svg').as_uri())
    page.screenshot(path=str(out / 'mascot.png'), omit_background=True)
    browser.close()
print(out / 'contact-sheet.png')
