"""
The folder of a C1's backups, and the upload that fills it.

A backup is a zip the C1 makes and applies itself; this server only keeps it. Each is named
by the server's own clock, `2026-11-01-1402.zip` (`-2`, `-3` for a second one in the same
minute), and carries a `manifest.json` that the list reads. The server adds to the folder
and never changes or deletes a backup.

`GET /backups` and `GET /backups/<name>` are app.py's; the upload is a WebSocket on
`/backup`, JSON text frames and binary chunks:

    <- {"type":"begin","size":N,"sha256":"<hex>"}   C1 first
    -> {"type":"ready","name":"2026-11-01-1402.zip"}
    <- a binary chunk (at most Config.backup_chunk_bytes)
    -> {"type":"got","bytes":<total so far>}          then the next chunk, and so on
    -> {"type":"saved","name":"...","folder":"~/.croissant/backups"}   when bytes == size
    -> {"type":"error","error":"..."}                 instead, when it is turned away

The bytes go to `<name>.part` (mode 600) and become the backup only once the size and the
hash match. A connection that closes before `saved` drops the part: that is how the C1
cancels.
"""

from __future__ import annotations

import asyncio
import errno
import hashlib
import json
import logging
import os
import re
import shutil
import zipfile
import zlib
from collections.abc import Callable
from datetime import datetime
from pathlib import Path
from typing import Any, BinaryIO

from websockets.asyncio.server import ServerConnection
from websockets.exceptions import ConnectionClosed

log = logging.getLogger(__name__)

# `^\d{4}-\d{2}-\d{2}-\d{4}(-\d+)?\.zip$`, with the stamp and the suffix as groups. The only
# way a name is accepted, so a path outside the folder cannot be asked for.
NAME = re.compile(r"(\d{4}-\d{2}-\d{2}-\d{4})(?:-(\d+))?\.zip", re.ASCII)
STAMP = "%Y-%m-%d-%H%M"
# What a listing takes from manifest.json, and the JSON type each has.
MANIFEST_FIELDS = {"app": str, "version": str, "versionCode": int, "serial": str, "date": str}
SHA256 = re.compile(r"[0-9a-fA-F]{64}", re.ASCII)


class Refused(Exception):
    """An upload turned away. Its text is what the C1 shows."""


class Unreadable(Exception):
    """A backup whose manifest cannot be read. Its text is why, and is listed with it."""


def tilde(folder: Path) -> str:
    """The folder as it is told to the C1: the home folder collapsed to `~`."""
    try:
        return f"~/{folder.relative_to(Path.home()).as_posix()}"
    except ValueError:
        return str(folder)


def _echo(value: Any) -> str:
    """Something the C1 sent, shortened, for an error text."""
    return repr(value)[:80]


class Backups:
    def __init__(self, folder: Path, max_bytes: int, chunk_bytes: int, manifest_bytes: int,
                 now: Callable[[], datetime] = datetime.now):
        self.folder = folder
        self.max_bytes = max_bytes
        self.chunk_bytes = chunk_bytes
        self.manifest_bytes = manifest_bytes
        # The server's local clock names a backup; a test sets it.
        self.now = now

    @property
    def shown(self) -> str:
        return tilde(self.folder)

    # What the folder holds

    def listing(self) -> list[dict[str, Any]]:
        """Every backup, newest first. One whose manifest cannot be read has an `error` instead."""
        if not self.folder.is_dir():
            return []
        named = []
        for path in self.folder.iterdir():
            m = NAME.fullmatch(path.name)
            if m and path.is_file():
                # By stamp, then by suffix as a number: `-10` is after `-2`, no suffix is the first.
                named.append(((m.group(1), int(m.group(2) or 1)), path))
        return [self._entry(p) for _, p in sorted(named, reverse=True)]

    def path(self, name: str) -> Path | None:
        """The backup called [name], None when the name is not one of ours or it is not here."""
        if NAME.fullmatch(name) is None:
            return None
        path = self.folder / name
        return path if path.is_file() else None

    def read(self, name: str) -> bytes | None:
        path = self.path(name)
        if path is None:
            return None
        try:
            return path.read_bytes()
        except FileNotFoundError:   # taken away between the check and the read
            return None

    def _entry(self, path: Path) -> dict[str, Any]:
        entry: dict[str, Any] = {"name": path.name, "size": path.stat().st_size}
        try:
            return {**entry, **self._manifest(path)}
        except Unreadable as e:
            return {**entry, "error": str(e)}

    def _manifest(self, path: Path) -> dict[str, Any]:
        """The listed fields of the zip's manifest.json; [Unreadable] says why it cannot be read."""
        try:
            with zipfile.ZipFile(path) as z:
                try:
                    info = z.getinfo("manifest.json")
                except KeyError:
                    raise Unreadable("the zip has no manifest.json") from None
                if info.file_size > self.manifest_bytes:
                    raise Unreadable(f"manifest.json is {info.file_size} bytes, over the {self.manifest_bytes} read")
                raw = z.read(info)
        except (zipfile.BadZipFile, zlib.error, EOFError, NotImplementedError, RuntimeError, OSError) as e:
            raise Unreadable(f"the zip cannot be read: {type(e).__name__}: {e}") from e
        try:
            manifest = json.loads(raw)
        except ValueError as e:   # not JSON, or not UTF-8
            raise Unreadable(f"manifest.json is not JSON: {e}") from e
        if not isinstance(manifest, dict):
            raise Unreadable(f"manifest.json is {type(manifest).__name__}, not an object")
        found = {}
        for key, kind in MANIFEST_FIELDS.items():
            value = manifest.get(key)
            if type(value) is not kind:
                raise Unreadable(f"manifest.json has {key} {_echo(value)}, expected {kind.__name__}")
            found[key] = value
        return found

    # An upload

    async def receive(self, ws: ServerConnection) -> None:
        """One upload on `/backup` (the frames are in the module's doc)."""
        try:
            name = await self._store(ws)
        except Refused as e:
            await self._turn_away(ws, str(e))
        except OSError as e:
            await self._turn_away(ws, self._os_text(e))
        except ConnectionClosed as e:
            log.info("backup upload cancelled: %s", e)
        else:
            log.info("saved %s in %s", name, self.folder)
            try:
                await ws.send(json.dumps({"type": "saved", "name": name, "folder": self.shown}))
                await ws.close()
            except ConnectionClosed:
                # The backup is whole and stays; the C1 only did not hear it.
                log.warning("saved %s, but the C1 left before it was told", name)

    async def _turn_away(self, ws: ServerConnection, why: str) -> None:
        log.warning("backup refused: %s", why)
        try:
            await ws.send(json.dumps({"type": "error", "error": why}))
            await ws.close()
        except ConnectionClosed:
            pass

    async def _store(self, ws: ServerConnection) -> str:
        """The upload's bytes into a backup of their own; its name. Any way out but this drops the part."""
        size, expected = self._begin(await ws.recv())
        name, out = self._claim(size)
        part = self.folder / f"{name}.part"
        kept = False
        try:
            with out:
                await ws.send(json.dumps({"type": "ready", "name": name}))
                await self._take(ws, out, size, expected)
                out.flush()
                await asyncio.to_thread(os.fsync, out.fileno())
            final = self.folder / name
            if final.exists():   # only if one was put there by hand while this arrived
                raise Refused(f"{name} appeared in {self.folder} while the backup was arriving")
            part.rename(final)
            kept = True
        finally:
            if not kept:
                part.unlink(missing_ok=True)
                log.info("dropped %s", part.name)
        return name

    def _begin(self, frame: str | bytes) -> tuple[int, str]:
        """The size and the sha256 (lower case) a `begin` frame announces."""
        if isinstance(frame, bytes):
            raise Refused(f"the first frame has to be a begin message in text, got {len(frame)} binary bytes")
        try:
            msg = json.loads(frame)
        except ValueError as e:
            raise Refused(f"the first frame is not JSON: {e}") from e
        if not isinstance(msg, dict) or msg.get("type") != "begin":
            raise Refused(f"the first frame has to be a begin message, got {_echo(frame)}")
        size, sha = msg.get("size"), msg.get("sha256")
        if type(size) is not int or size < 1:
            raise Refused(f"begin needs a size in bytes of at least 1, got {_echo(size)}")
        if size > self.max_bytes:
            raise Refused(f"the backup is {size} bytes, over the {self.max_bytes} bytes this server takes")
        if not isinstance(sha, str) or SHA256.fullmatch(sha) is None:
            raise Refused(f"begin needs a sha256 of 64 hex characters, got {_echo(sha)}")
        return size, sha.lower()

    def _claim(self, size: int) -> tuple[str, BinaryIO]:
        """
        The first free name for a backup arriving now, with its `.part` open (mode 600). A part
        left by a crash holds its name, so the next backup takes the following suffix.
        """
        self.folder.mkdir(mode=0o700, parents=True, exist_ok=True)
        free = shutil.disk_usage(self.folder).free
        if free < size:
            raise Refused(f"the disk on this machine is full: {free} bytes free, the backup is {size} bytes")
        stamp = self.now().strftime(STAMP)
        n = 1
        while True:
            name = f"{stamp}.zip" if n == 1 else f"{stamp}-{n}.zip"
            if not (self.folder / name).exists():
                try:
                    # O_EXCL: two uploads in the same minute cannot take the same name.
                    fd = os.open(self.folder / f"{name}.part", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                    return name, os.fdopen(fd, "wb")
                except FileExistsError:
                    pass
            n += 1

    async def _take(self, ws: ServerConnection, out: BinaryIO, size: int, expected: str) -> None:
        """The chunks into [out], each answered, until [size] bytes have come; then the hash."""
        digest = hashlib.sha256()
        received = 0
        while received < size:
            chunk = await ws.recv()
            if isinstance(chunk, str):
                raise Refused(f"expected a binary chunk after {received} of {size} bytes, got text {_echo(chunk)}")
            if not chunk:
                raise Refused(f"an empty chunk after {received} of {size} bytes")
            if len(chunk) > self.chunk_bytes:
                raise Refused(f"a chunk of {len(chunk)} bytes is over the {self.chunk_bytes} bytes a chunk may hold")
            digest.update(chunk)
            if received + len(chunk) > size:
                raise Refused(self._damaged(received + len(chunk), digest.hexdigest(), size, expected))
            try:
                out.write(chunk)
            except OSError as e:
                raise Refused(self._os_text(e, f" after {received} of {size} bytes")) from e
            received += len(chunk)
            await ws.send(json.dumps({"type": "got", "bytes": received}))
        if digest.hexdigest() != expected:
            raise Refused(self._damaged(received, digest.hexdigest(), size, expected))

    @staticmethod
    def _damaged(got: int, got_sha: str, size: int, expected: str) -> str:
        return (f"the backup arrived damaged: {got} bytes, sha256 {got_sha}, "
                f"expected {size} bytes, sha256 {expected}")

    def _os_text(self, e: OSError, after: str = "") -> str:
        """[after] says how far the bytes had come, when they had."""
        if e.errno == errno.ENOSPC:
            return f"the disk on this machine is full{after}: {e}"
        return f"could not write the backup in {self.folder}{after}: {e}"
