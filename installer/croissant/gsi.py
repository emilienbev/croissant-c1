"""Which LineageOS goes on the R1: the proven build, what SourceForge has now, or any other.

Only the proven one has been run on an R1. The others are offered because the R1 is a
Treble device (a GSI replaces `system` alone and keeps rabbit's kernel and vendor), so a
newer TrebleDroid build has a fair chance; the installer says which is which.

Andy Yan's builds (sourceforge.net/projects/andyyan-gsi) name their variant in the file:
`arm64_bvN` is A/B, vanilla, **no superuser** (Magisk is the only root),
and not `vndklite` (the R1's vendor has a proper VNDK 31).
"""
import re
import urllib.request
from dataclasses import dataclass

from .artifacts import Artifact

SF = 'https://sourceforge.net/projects/andyyan-gsi'
# Folders whose builds carry TrebleDroid's device fixes, newest Android first.
FOLDERS = ('lineage-21-pre-qpr2-td', 'lineage-20-td')


@dataclass(frozen=True)
class Gsi:
    label: str                 # what the person reads
    artifact: Artifact | None  # None: a local file, see `path`
    proven: bool = False
    path: str | None = None    # a local .img, .img.gz or .img.xz

    @property
    def file_name(self) -> str:
        return self.artifact.name if self.artifact else self.path.rsplit('/', 1)[-1]


PROVEN = Gsi(
    'LineageOS 20 (Android 13), 2025-10-21',
    Artifact('lineage-20.0-20251021-UNOFFICIAL-arm64_bvN.img.gz',
             f'{SF}/files/lineage-20-td/lineage-20.0-20251021-UNOFFICIAL-arm64_bvN.img.gz/download',
             '7b2b92393efe83e21c35c6063c33006257947e791455a44681edc22852599408'),
    proven=True)


def _label(name: str) -> str:
    m = re.match(r'lineage-(\d+)\.\d+-(\d{4})(\d\d)(\d\d)-', name)
    if not m:
        return name
    android = {20: 13, 21: 14, 22: 15, 23: 16}.get(int(m[1]))
    return f'LineageOS {m[1]}' + (f' (Android {android})' if android else '') + f', {m[2]}-{m[3]}-{m[4]}'


def available(timeout=10) -> list[Gsi]:
    """The arm64_bvN builds SourceForge lists right now, newest folder first; md5 from its feed."""
    found = []
    for folder in FOLDERS:
        try:
            req = urllib.request.Request(f'{SF}/rss?path=/{folder}', headers={'User-Agent': 'croissant'})
            feed = urllib.request.urlopen(req, timeout=timeout).read().decode()
        except OSError:
            continue
        for item in re.findall(r'<item>(.*?)</item>', feed, re.S):
            title = re.search(r'<title><!\[CDATA\[(.*?)\]\]>', item)
            md5 = re.search(r'<media:hash algo="md5">(\w+)', item)
            if not title or not md5:
                continue
            path = title[1]
            name = path.rsplit('/', 1)[-1]
            if '/History/' in path or not name.endswith('-arm64_bvN.img.gz'):
                continue
            if name == PROVEN.artifact.name:
                continue
            found.append(Gsi(_label(name), Artifact(name, f'{SF}/files{path}/download', md5[1], 'md5')))
    return found


def custom(where: str, digest: str | None = None) -> Gsi:
    """Any other GSI: a URL (with a sha256 or md5 to check it by, when given) or a file."""
    if re.match(r'https?://', where):
        name = where.rstrip('/').removesuffix('/download').rsplit('/', 1)[-1].split('?')[0]
        algo = 'md5' if digest and len(digest) == 32 else 'sha256'
        return Gsi(name, Artifact(name, where, digest or '', algo))
    return Gsi(where.rsplit('/', 1)[-1], None, path=where)


def warnings(g: Gsi) -> list[str]:
    name = g.file_name
    out = []
    if not g.proven:
        out.append('not yet run on an R1: check the side button, wheel, camera, mic and Wi-Fi after')
    if 'vndklite' in name:
        out.append('a vndklite build is for vendors without a proper VNDK, and the R1 has one')
    if re.search(r'_b[vg]S', name):
        out.append('a bvS/bgS build carries its own superuser, which fights Magisk')
    if 'arm64' not in name:
        out.append('the file name does not say arm64')
    if g.artifact and not g.artifact.digest:
        out.append('no checksum given: the download cannot be checked')
    return out
