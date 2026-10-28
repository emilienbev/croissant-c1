"""The boot screens for a device name: the LK logo and the boot animation.

Both come from the repo's own generators, `device/logo/wordmark.py` and
`device/bootanim/loader.py`; the logo partition is packed by `device/logo/logo.py`
with the R1's own partition as the template: the boot images (0 and 38) and the
charging screens (`device/logo/charging.py`) change, everything else stays its own.
"""
import re
import shutil
from pathlib import Path

from PIL import Image

from . import font, ui
from .paths import CACHE, DEVICE, load

DEFAULT_NAME = 'croissant'
MODULE = 'croissant-bootanim'


def check_name(name: str) -> str:
    if not 1 <= len(name) <= 24 or not re.fullmatch(r"[\w .'&+-]+", name):
        ui.fail(f'device name {name!r}: 1 to 24 letters, digits, spaces or . \' & + -')
    return name


def logo_png(name: str) -> Image.Image:
    # The default name keeps the committed logo, so an R1 already wearing it is left alone.
    if name == DEFAULT_NAME:
        return Image.open(DEVICE / 'logo/croissant-boot-logo.png').convert('RGB')
    font.require()                         # any other name is set in the owner's Power Grotesk
    return load('device/logo/wordmark.py', 'wordmark').render(name)


def logo_partition(template: bytes, png: Image.Image) -> bytes:
    """[template] (the R1's own logo partition) with the boot images replaced by [png] and
    the charging screens by ours over it (`device/logo/charging.py`)."""
    return load('device/logo/charging.py', 'charging').partition(template, png)


def charger_res(name: str, png: Image.Image) -> dict:
    """{path under /product/etc/res: file} for the charge-only screen (device/offmode/charger.py):
    the committed tree for the default name, rendered for any other."""
    res = DEVICE / 'offmode/res'
    rels = ('values/charger/animation.txt', 'images/charger/battery_scale.png',
            'images/charger/battery_fail.png', 'images/charger/percent_font.png')
    if name == DEFAULT_NAME:
        return {r: res / r for r in rels}
    font.require()
    out = CACHE / 'brand' / re.sub(r'\W+', '-', name.lower()).strip('-') / 'charger-res'
    for r, data in load('device/offmode/charger.py', 'charger').tree(png).items():
        (out / r).parent.mkdir(parents=True, exist_ok=True)
        (out / r).write_bytes(data)
    return {r: out / r for r in rels}


def bootanim_module(name: str, png: Image.Image) -> Path:
    """The Magisk module of device/magisk/croissant-bootanim/, with this name's animation."""
    loader = load('device/bootanim/loader.py', 'loader')
    out = CACHE / 'brand' / re.sub(r'\W+', '-', name.lower()).strip('-') / MODULE
    if out.exists():
        shutil.rmtree(out)
    shutil.copytree(DEVICE / 'magisk' / MODULE, out)
    if name != DEFAULT_NAME:              # the default's zip is the committed one
        zip_path = out / 'system/product/media/bootanimation.zip'
        zip_path.write_bytes(loader.bootanimation(png))
    return out


def preview(name: str, out: Path):
    """The logo and a strip of the animation, to look at before a device sees them."""
    loader = load('device/bootanim/loader.py', 'loader')
    png = logo_png(name)
    out.mkdir(parents=True, exist_ok=True)
    png.save(out / 'logo.png')
    fs = loader.frames(png)
    W, H = fs[0].size
    picks = [0, 9, 18, 27]
    sheet = Image.new('RGB', (len(picks) * (W // 2 + 8) + 8, H // 2 + 16), (0x19, 0x19, 0x1B))
    for n, f in enumerate(picks):
        sheet.paste(fs[f].resize((W // 2, H // 2), Image.LANCZOS), (8 + n * (W // 2 + 8), 8))
    sheet.save(out / 'animation.png')
    return out / 'logo.png', out / 'animation.png'
