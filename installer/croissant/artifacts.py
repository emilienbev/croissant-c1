"""The downloads: fetched once, hash-checked, cached. Each has a pinned default, the one
proven on an R1, and can be overridden (a newer build is a choice the person
makes, told it is untried). Tailscale is the exception: the latest stable, upgraded on the
device from its Tailscale page after that.
"""
import gzip
import hashlib
import json
import lzma
import platform
import re
import shutil
import tarfile
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path

from . import ui
from .paths import CACHE


@dataclass(frozen=True)
class Artifact:
    name: str
    url: str
    digest: str          # hex; empty when nothing was given to check against
    algo: str = 'sha256'

    @property
    def path(self) -> Path:
        return CACHE / self.name


MAGISK = Artifact(
    'Magisk-v30.7.apk',
    'https://github.com/topjohnwu/Magisk/releases/download/v30.7/Magisk-v30.7.apk',
    'e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48bfc6b5bee0afae9ebd5')

# Google's own checksums, from dl.google.com/android/repository/repository2-3.xml.
PLATFORM_TOOLS = {
    'Darwin': Artifact('platform-tools_r37.0.1-darwin.zip',
                       'https://dl.google.com/android/repository/platform-tools_r37.0.1-darwin.zip',
                       '6ae73f4de6452dc57e62ec02b68eed92a4c21661', 'sha1'),
    'Linux': Artifact('platform-tools_r37.0.1-linux.zip',
                      'https://dl.google.com/android/repository/platform-tools_r37.0.1-linux.zip',
                      '477254aa5f903c15cf51001717bdf347fb6b53e0', 'sha1'),
}


def _hash(path: Path, algo: str) -> str:
    h = hashlib.new(algo)
    with open(path, 'rb') as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def have(a: Artifact) -> bool:
    return a.path.exists() and (not a.digest or _hash(a.path, a.algo) == a.digest)


def seed(dirs: list[Path], artifacts: list[Artifact]):
    """Copy any artifact already on this machine (by name and hash) into the cache."""
    CACHE.mkdir(parents=True, exist_ok=True)
    for a in artifacts:
        if have(a):
            continue
        for d in dirs:
            p = d.expanduser() / a.name
            if p.exists() and (not a.digest or _hash(p, a.algo) == a.digest):
                shutil.copyfile(p, a.path)
                ui.ok(f'{a.name} from {d}')
                break


def fetch(a: Artifact) -> Path:
    if have(a):
        return a.path
    CACHE.mkdir(parents=True, exist_ok=True)
    part = a.path.with_suffix(a.path.suffix + '.part')
    req = urllib.request.Request(a.url, headers={'User-Agent': 'croissant'})
    with urllib.request.urlopen(req) as r, open(part, 'wb') as f, \
            ui.progress(a.name, int(r.headers.get('Content-Length') or 0)) as advance:
        while chunk := r.read(1 << 20):
            f.write(chunk)
            advance(len(chunk))
    if a.digest:
        got = _hash(part, a.algo)
        if got != a.digest:
            part.unlink()
            ui.fail(f'{a.name}: {a.algo} is {got}, expected {a.digest}. Nothing kept. Try again.')
    else:
        ui.warn(f'{a.name}: no checksum to check it against')
    part.rename(a.path)
    ui.ok(a.name)
    return a.path


def image(source: Path) -> Path:
    """A GSI as a raw ext4 image: unpacked once beside its archive (.gz or .xz), checked."""
    if source.suffix in ('.gz', '.xz'):
        img = CACHE / source.with_suffix('').name
        if not img.exists():
            opener = gzip.open if source.suffix == '.gz' else lzma.open
            tmp = img.with_suffix('.img.part')
            with ui.spinner(f'unpacking {source.name}'), opener(source, 'rb') as src, open(tmp, 'wb') as dst:
                shutil.copyfileobj(src, dst, 1 << 22)
            tmp.rename(img)
    else:
        img = source
    with open(img, 'rb') as f:
        f.seek(0x438)
        if f.read(2) != b'\x53\xef':
            ui.fail(f'{img} is not an ext4 system image (a sparse or erofs image is not supported yet)')
    return img


# --- overrides ------------------------------------------------------------------------

def magisk(spec: str | None) -> Artifact | Path:
    """The pinned Magisk, a local APK, or a release tag from GitHub ('latest' or 'v30.7')."""
    if not spec:
        return MAGISK
    if Path(spec).expanduser().is_file():
        return Path(spec).expanduser()
    api = f'https://api.github.com/repos/topjohnwu/Magisk/releases/' + \
        ('latest' if spec == 'latest' else f'tags/{spec}')
    rel = json.load(urllib.request.urlopen(urllib.request.Request(api, headers={'User-Agent': 'croissant'})))
    for asset in rel['assets']:
        if re.fullmatch(r'Magisk-v[\d.]+\.apk', asset['name']):
            digest = (asset.get('digest') or '').removeprefix('sha256:')
            return Artifact(asset['name'], asset['browser_download_url'], digest)
    ui.fail(f'no Magisk APK in release {spec}')


def magisk_version_code(apk: Path) -> str:
    """MAGISK_VER_CODE as the APK's own scripts carry it (what `magisk -V` will say)."""
    with zipfile.ZipFile(apk) as z:
        m = re.search(r'^MAGISK_VER_CODE=(\d+)', z.read('assets/util_functions.sh').decode(), re.M)
    return m[1]


def tailscale(version: str | None) -> Artifact:
    """The latest stable tailscale, or a version; its .sha256 is published beside it. Offline,
    the one asked for if it is cached, else the newest cached (checked when it came down)."""
    base = 'https://pkgs.tailscale.com/stable/'
    try:
        if not version or version == 'latest':
            version = json.load(urllib.request.urlopen(base + '?mode=json', timeout=15))['TarballsVersion']
        name = f'tailscale_{version}_arm64.tgz'
        digest = urllib.request.urlopen(base + name + '.sha256', timeout=15).read().decode().split()[0]
        return Artifact(name, base + name, digest)
    except OSError:
        cached = sorted(CACHE.glob('tailscale_*_arm64.tgz'),
                        key=lambda p: [int(n) for n in re.findall(r'\d+', p.name)])
        want = [p for p in cached if version and version != 'latest' and p.name == f'tailscale_{version}_arm64.tgz']
        if not (want or cached):
            ui.fail('pkgs.tailscale.com cannot be reached and no tailscale is cached')
        p = (want or cached)[-1]
        return Artifact(p.name, base + p.name, '')


def tailscale_binaries(a: Artifact) -> Path:
    """The static arm64 tailscaled and tailscale, unpacked; returns their directory."""
    tgz = fetch(a)
    out = CACHE / a.name.removesuffix('.tgz')
    if not (out / 'tailscaled').exists():
        with tarfile.open(tgz) as t:
            t.extractall(CACHE, filter='data')
    return out


def platform_tools() -> Path:
    """Google's adb and fastboot for this OS, unpacked; returns the directory."""
    a = PLATFORM_TOOLS.get(platform.system())
    if not a:
        ui.fail(f'no platform-tools pinned for {platform.system()} (macOS and Linux only)')
    out = CACHE / 'platform-tools'
    if not (out / 'fastboot').exists():
        with zipfile.ZipFile(fetch(a)) as z:
            z.extractall(CACHE)
        for tool in ('adb', 'fastboot'):
            (out / tool).chmod(0o755)
    return out
