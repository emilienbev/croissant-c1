"""Power Grotesk, the owner's own copy: the app's typeface and the boot screens'.

The font is commercial (the free one is for personal use only), so the repo and the APK
never carry it. The owner downloads the zip from BeFonts in a browser (the page has a bot
check, so nothing here can fetch it); this takes the three files the app uses out of it,
checks them byte for byte, and keeps them in one place, `FONTS` (cache/fonts in the
checkout). The boot screen, charger and server tools read them from there, and
`stages.fonts` puts them on the R1.
"""
import hashlib
import os
import zipfile
from pathlib import Path

from . import paths, ui
from .paths import CACHE

PAGE = 'https://befonts.com/power-grotesk-font.html'
FONTS = Path(os.environ.get('CROISSANT_FONTS') or CACHE / 'fonts')

# The three files the app loads, as BeFonts ships them (the other weights are not used).
FILES = {
    'PowerGrotesk-Light.ttf': '983ab57c8e85618e798fb323a9ff8aebb5a230f53f27bf21754927a8adac9274',
    'PowerGrotesk-Regular.ttf': '3525d319c7d3712a3f3a27edc57f0a198caf09fb5728d0701cf21d0429ebbfcf',
    'PowerGrotesk-Medium.ttf': '085b335ad45ecbf741f27618b3ba12e0d02ac900efccc4060a32f79f4708a006',
}
MAX_SIZE = 4 << 20       # each is about 70 KB; anything far bigger is not the font


def _sum(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def have() -> bool:
    """All three in the cache, each the expected bytes."""
    return all((FONTS / n).is_file() and _sum((FONTS / n).read_bytes()) == h for n, h in FILES.items())


def _from_zip(path: Path) -> dict[str, bytes]:
    """The three members, found by file name and read into memory; nothing is extracted to
    a path the zip names, and nothing in it is run."""
    found = {}
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            name = info.filename.replace('\\', '/').rsplit('/', 1)[-1]
            if name in FILES and not info.is_dir() and info.file_size <= MAX_SIZE and name not in found:
                found[name] = z.read(info)
    return found


def _from_dir(path: Path) -> dict[str, bytes]:
    found = {}
    for name in FILES:
        f = next((f for f in sorted(path.rglob(name)) if f.is_file() and f.stat().st_size <= MAX_SIZE), None)
        if f:
            found[name] = f.read_bytes()
    return found


def take(src: Path) -> None:
    """Check the three files in [src] (the BeFonts zip, or a directory holding them) and
    keep them in `FONTS`. Fails, changing nothing, on a missing or different file."""
    src = src.expanduser()
    if src.is_dir():
        found = _from_dir(src)
    elif zipfile.is_zipfile(src):
        try:
            found = _from_zip(src)
        except zipfile.BadZipFile as e:
            ui.fail(f'{src}: a damaged zip ({e}). Download it again.')
    elif not src.exists():
        ui.fail(f'{src}: no such file')
    else:
        ui.fail(f'{src}: not a zip or a folder')
    missing = [n for n in FILES if n not in found]
    if missing:
        ui.fail(f'{src} has no {", ".join(missing)}. It should be the zip from {PAGE}.')
    wrong = [n for n, h in FILES.items() if _sum(found[n]) != h]
    if wrong:
        ui.fail(f'{src}: {", ".join(wrong)} not the expected file (sha256 differs). '
                f'Use the zip from {PAGE}, unchanged.')
    FONTS.mkdir(parents=True, exist_ok=True)
    for n, data in found.items():
        tmp = FONTS / (n + '.part')
        tmp.write_bytes(data)
        tmp.replace(FONTS / n)


def downloads() -> list[Path]:
    """BeFonts zips in ~/Downloads, newest first."""
    d = Path.home() / 'Downloads'
    return sorted(d.glob('power-grotesk-font*.zip'), key=lambda p: p.stat().st_mtime, reverse=True)


def ensure(given: str | None = None) -> Path:
    """The font step: the three files in `FONTS`, from [given] (a zip or a directory), else
    as kept before, else asked for, the newest BeFonts zip in ~/Downloads offered first."""
    ui.step('The font')
    if given:
        try:
            take(Path(given))
            ui.ok(f'Power Grotesk from {given}')
            return FONTS
        except ui.Abort as e:
            if not ui.INTERACTIVE:
                raise
            ui.warn(str(e))
    elif have():
        ui.ok('Power Grotesk, kept from before')
        return FONTS
    if not ui.INTERACTIVE:
        ui.fail(f'Croissant needs Power Grotesk. Download the zip, then pass it with --font PATH:\n{PAGE}')
    ui.info('Croissant is set in Power Grotesk. Download the zip (free for personal use):')
    ui.link(PAGE)
    newest = downloads()
    answer = given or (str(newest[0]) if newest else '')
    while True:
        answer = ui.path('The zip, or a folder holding its TTFs:', answer)
        if not answer:
            continue
        try:
            take(Path(answer))
        except ui.Abort as e:
            ui.warn(str(e))
            continue
        ui.ok(f'Power Grotesk from {answer}')
        return FONTS


def require() -> Path:
    """`FONTS`, for whatever renders with it; a clear stop when it is not there yet."""
    if not have():
        ui.fail(f'Power Grotesk is not kept yet. Run {paths.COMMAND} font first.')
    return FONTS


def device_sums(sums: str) -> dict[str, str]:
    """{file name: sha256} from `sha256sum` output on the R1."""
    out = {}
    for line in sums.splitlines():
        parts = line.split()
        if len(parts) == 2:
            out[parts[1].rsplit('/', 1)[-1]] = parts[0]
    return out


def on_device(sums: str) -> bool:
    got = device_sums(sums)
    return all(got.get(n) == h for n, h in FILES.items())
