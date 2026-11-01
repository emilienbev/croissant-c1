"""A backup zip on this computer: its name, its manifest, and whether it is whole.

The app makes and applies the zip. Here it is only named, listed and checked against its own
manifest.json (every entry listed, every sha256 matching) before it is kept or pushed.
"""
import hashlib
import json
import os
import re
import zipfile
import zlib
from collections import Counter
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from . import backup_config as conf, ui

# 2026-11-01-1402.zip, and -2, -3 for a second one in the same minute: croissant-server's names.
NAME = re.compile(r'(\d{4}-\d{2}-\d{2}-\d{4})(?:-(\d+))?\.zip', re.ASCII)
STAMP = '%Y-%m-%d-%H%M'
MANIFEST = 'manifest.json'
SHA256 = re.compile(r'[0-9a-f]{64}', re.ASCII)
SHOWN = 5                    # how many failing entries a message lists
CHUNK = 1024 * 1024


class Damaged(ui.Abort):
    """A zip that is not whole. Its text says what is wrong with it."""


@dataclass(frozen=True)
class Backup:
    path: Path
    stamp: str                      # 2026-11-01-1402, from the name
    number: int                     # 1, or the suffix of a second one in the same minute
    manifest: dict | None           # None when it could not be read
    problem: str | None

    @property
    def when(self) -> str:
        s = self.stamp
        return f'{s[:10]} {s[11:13]}:{s[13:]}' + (f' ({self.number})' if self.number > 1 else '')


def folder() -> Path:
    """The folder of backups, made if it is not there, readable by this user only."""
    conf.BACKUPS.mkdir(parents=True, exist_ok=True)
    conf.BACKUPS.chmod(conf.FOLDER_MODE)
    return conf.BACKUPS


def next_name(now: datetime | None = None) -> str:
    """A name for a backup made now, by this computer's clock: not one in the folder, nor one
    being copied there (croissant-server's .part)."""
    stamp = (now or datetime.now()).strftime(STAMP)
    taken = {p.name.removesuffix('.part') for p in conf.BACKUPS.glob(f'{stamp}*')} if conf.BACKUPS.is_dir() else set()
    name, n = f'{stamp}.zip', 1
    while name in taken:
        n += 1
        name = f'{stamp}-{n}.zip'
    return name


def listing() -> list[Backup]:
    """Every backup in the folder, newest first. One whose manifest cannot be read says why."""
    if not conf.BACKUPS.is_dir():
        return []
    found = []
    for path in conf.BACKUPS.iterdir():
        m = NAME.fullmatch(path.name)
        if not (m and path.is_file()):
            continue
        try:
            manifest, problem = read_manifest(path), None
        except Damaged as e:
            manifest, problem = None, str(e)
        found.append(Backup(path, m[1], int(m[2] or 1), manifest, problem))
    return sorted(found, key=lambda b: (b.stamp, b.number), reverse=True)


def read_manifest(path: Path) -> dict:
    """The zip's manifest.json, or Damaged saying why it cannot be used."""
    try:
        with zipfile.ZipFile(path) as zf:
            return _manifest(zf, path.name)
    except (zipfile.BadZipFile, OSError) as e:
        raise Damaged(f'{path.name} is not a readable zip: {e}') from e


def verify(path: Path) -> dict:
    """The manifest of a zip that is whole: it lists every entry, nothing else is in it, and
    every sha256 matches. Damaged, with what failed, otherwise."""
    try:
        with zipfile.ZipFile(path) as zf:
            manifest = _manifest(zf, path.name)
            _check_entries(zf, manifest['files'], path.name)
            return manifest
    except (zipfile.BadZipFile, zlib.error, NotImplementedError, OSError) as e:
        raise Damaged(f'{path.name} is not a readable zip: {e}') from e


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        while chunk := f.read(CHUNK):
            h.update(chunk)
    return h.hexdigest()


def link(part: Path, final: Path):
    """[part] becomes the backup [final], which is never replaced: croissant-server may have
    taken the name while the copy ran."""
    try:
        os.link(part, final)
    except FileExistsError:
        raise ui.Abort(f'{final.name} appeared in {final.parent} while the backup was being copied, '
                       'so it was not kept (nothing there was changed)') from None
    part.unlink()


def _manifest(zf: zipfile.ZipFile, name: str) -> dict:
    try:
        info = zf.getinfo(MANIFEST)
    except KeyError:
        raise Damaged(f'{name} has no {MANIFEST}') from None
    with zf.open(info) as f:
        raw = f.read(conf.MANIFEST_MAX_BYTES + 1)
    if len(raw) > conf.MANIFEST_MAX_BYTES:
        raise Damaged(f'{name}: {MANIFEST} is over {conf.MANIFEST_MAX_BYTES} bytes')
    try:
        m = json.loads(raw)
    except (json.JSONDecodeError, UnicodeDecodeError) as e:
        raise Damaged(f'{name}: {MANIFEST} is not JSON: {e}') from e
    if not isinstance(m, dict):
        raise Damaged(f'{name}: {MANIFEST} holds {type(m).__name__}, not an object')
    if m.get('format') != conf.MANIFEST_FORMAT:
        raise Damaged(f'{name}: {MANIFEST} has format {m.get("format")!r}, this CLI reads {conf.MANIFEST_FORMAT}')
    for key in ('version', 'serial'):
        if not isinstance(m.get(key), str):
            raise Damaged(f'{name}: {MANIFEST} has {key} {m.get(key)!r}, not text')
    files = m.get('files')
    if not isinstance(files, dict):
        raise Damaged(f'{name}: {MANIFEST} has files {files!r}, not an object')
    for entry, digest in files.items():
        if not (isinstance(digest, str) and SHA256.fullmatch(digest)):
            raise Damaged(f'{name}: {MANIFEST} gives {entry} the sha256 {digest!r}')
    return m


def _check_entries(zf: zipfile.ZipFile, files: dict, name: str):
    held = [i.filename for i in zf.infolist() if i.filename != MANIFEST]
    problems = []
    if twice := sorted(n for n, times in Counter(held).items() if times > 1):
        problems.append(f'in the zip more than once: {_few(twice)}')
    if absent := sorted(set(files) - set(held)):
        problems.append(f'listed in the manifest but not in the zip: {_few(absent)}')
    if unlisted := sorted(set(held) - set(files)):
        problems.append(f'in the zip but not in the manifest: {_few(unlisted)}')
    if not problems:
        wrong = [f'{n} is {got}, the manifest says {files[n]}' for n in sorted(files)
                 if (got := _sha256(zf, n)) != files[n]]
        if wrong:
            problems.append('sha256 differs: ' + '; '.join(wrong[:SHOWN]) + _more(wrong))
    if problems:
        raise Damaged(f'{name} is damaged: ' + '. '.join(problems))


def _sha256(zf: zipfile.ZipFile, entry: str) -> str:
    h = hashlib.sha256()
    with zf.open(entry) as f:
        while chunk := f.read(CHUNK):
            h.update(chunk)
    return h.hexdigest()


def _few(names: list) -> str:
    return ', '.join(names[:SHOWN]) + _more(names)


def _more(items: list) -> str:
    return f' and {len(items) - SHOWN} more' if len(items) > SHOWN else ''
