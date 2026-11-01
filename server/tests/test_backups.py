"""The backups folder, its routes and the upload (backups.py). Run: uv run python -m unittest discover tests"""

import asyncio
import errno
import hashlib
import inspect
import io
import json
import os
import stat
import tempfile
import unittest
import zipfile
from datetime import datetime
from pathlib import Path
from unittest import mock

from websockets.asyncio.client import connect
from websockets.asyncio.server import serve
from websockets.datastructures import Headers
from websockets.exceptions import ConnectionClosed
from websockets.http11 import Request

from croissant_server.app import http_routes
from croissant_server.backups import Backups, tilde

MANIFEST = {"format": 1, "app": "com.bleural.croissant", "version": "0.1.0", "versionCode": 100,
            "serial": "TEST0001", "date": "2026-11-01T14:02:31+01:00", "files": {}}
CHUNK = 1024


def make_zip(manifest=MANIFEST, extra: bytes = b"") -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as z:
        if manifest is not None:
            z.writestr("manifest.json", json.dumps(manifest))
        z.writestr("files/photo.jpg", extra)
    return buf.getvalue()


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def backups_in(folder: Path, max_bytes: int = 1 << 20, now=lambda: datetime(2026, 11, 1, 14, 2, 31)) -> Backups:
    return Backups(folder, max_bytes, CHUNK, 4096, now=now)


class FakeCosts:
    def public(self):
        return {"chat": {"default": None, "entries": []}}


class FolderTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.folder = Path(tmp.name) / "backups"
        self.backups = backups_in(self.folder)

    def put(self, name: str, data: bytes) -> None:
        self.folder.mkdir(exist_ok=True)
        (self.folder / name).write_bytes(data)

    def test_no_folder_yet_is_an_empty_list(self):
        self.assertEqual(self.backups.listing(), [])
        self.assertFalse(self.folder.exists())

    def test_newest_first_by_stamp_then_by_the_number_of_the_suffix(self):
        for name in ("2026-10-31-0900.zip", "2026-11-01-1402.zip", "2026-11-01-1402-2.zip",
                     "2026-11-01-1402-10.zip", "2026-11-01-1402-9.zip"):
            self.put(name, make_zip())
        self.assertEqual([b["name"] for b in self.backups.listing()],
                         ["2026-11-01-1402-10.zip", "2026-11-01-1402-9.zip", "2026-11-01-1402-2.zip",
                          "2026-11-01-1402.zip", "2026-10-31-0900.zip"])

    def test_an_entry_is_what_the_manifest_says(self):
        data = make_zip()
        self.put("2026-11-01-1402.zip", data)
        self.assertEqual(self.backups.listing(), [{
            "name": "2026-11-01-1402.zip", "size": len(data), "app": "com.bleural.croissant",
            "version": "0.1.0", "versionCode": 100, "serial": "TEST0001", "date": "2026-11-01T14:02:31+01:00"}])

    def test_parts_and_other_files_are_not_listed(self):
        self.put("2026-11-01-1402.zip", make_zip())
        self.put("2026-11-01-1403.zip.part", b"half")
        self.put("notes.txt", b"mine")
        self.put("2026-11-01-1404.zip.bak", make_zip())
        (self.folder / "2026-11-01-1405.zip").mkdir()
        self.assertEqual([b["name"] for b in self.backups.listing()], ["2026-11-01-1402.zip"])

    def test_a_damaged_zip_is_listed_with_why_and_no_version(self):
        self.put("2026-11-01-1402.zip", make_zip())
        self.put("2026-11-01-1403.zip", b"not a zip")
        self.put("2026-11-01-1404.zip", make_zip(manifest=None))
        self.put("2026-11-01-1405.zip", make_zip(manifest={**MANIFEST, "versionCode": "100"}))
        listed = {b["name"]: b for b in self.backups.listing()}
        for name, why in (("2026-11-01-1403.zip", "the zip cannot be read: BadZipFile"),
                          ("2026-11-01-1404.zip", "the zip has no manifest.json"),
                          ("2026-11-01-1405.zip", "manifest.json has versionCode '100', expected int")):
            self.assertTrue(listed[name]["error"].startswith(why), listed[name])
            self.assertNotIn("version", listed[name])
        self.assertEqual(listed["2026-11-01-1403.zip"]["size"], len(b"not a zip"))
        self.assertNotIn("error", listed["2026-11-01-1402.zip"])

    def test_a_manifest_over_the_read_limit_is_listed_with_its_size(self):
        self.put("2026-11-01-1402.zip", make_zip(manifest={**MANIFEST, "files": {"x": "y" * 5000}}))
        error = self.backups.listing()[0]["error"]
        self.assertTrue(error.startswith("manifest.json is ") and error.endswith(" bytes, over the 4096 read"), error)

    def test_only_a_name_of_the_pattern_is_found(self):
        data = make_zip()
        self.put("2026-11-01-1402.zip", data)
        (self.folder.parent / "x.zip").write_bytes(b"outside")
        self.assertEqual(self.backups.read("2026-11-01-1402.zip"), data)
        self.assertEqual(self.backups.read("2026-11-01-1402-2.zip"), None)
        for name in ("../x.zip", "..%2Fx.zip", "/etc/passwd", "x.zip", "", "2026-11-01-1402.zip/",
                     "2026-11-01-1402.zip\n", "./2026-11-01-1402.zip", "backups/2026-11-01-1402.zip",
                     "2026-11-01-1402.zip.part", "2026-11-01-1402-.zip", "2026-11-01-1402-a.zip",
                     "2026-11-01-14.zip", "٢026-11-01-1402.zip", "../backups/2026-11-01-1402.zip"):
            self.assertIsNone(self.backups.path(name), repr(name))
            self.assertIsNone(self.backups.read(name), repr(name))

    def test_the_folder_is_told_with_the_home_collapsed(self):
        self.assertEqual(tilde(Path.home() / ".croissant" / "backups"), "~/.croissant/backups")
        self.assertEqual(tilde(self.folder), str(self.folder))


class RoutesTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.folder = Path(tmp.name) / "backups"
        self.backups = backups_in(self.folder)
        self.process = http_routes(None, FakeCosts(), backups=self.backups)

    async def get(self, path, process=None):
        r = (process or self.process)(None, Request(path, Headers()))
        return await r if inspect.isawaitable(r) else r

    async def test_the_list_names_the_folder_and_has_each_backup(self):
        r = await self.get("/backups")
        self.assertEqual((r.status_code, json.loads(r.body)), (200, {"folder": str(self.folder), "backups": []}))
        self.folder.mkdir()
        data = make_zip()
        (self.folder / "2026-11-01-1402.zip").write_bytes(data)
        for path in ("/backups", "/backups/", "/backups?x=1"):
            body = json.loads((await self.get(path)).body)
            self.assertEqual([(b["name"], b["size"], b["version"]) for b in body["backups"]],
                             [("2026-11-01-1402.zip", len(data), "0.1.0")], path)

    async def test_a_backup_is_its_zip_bytes(self):
        self.folder.mkdir()
        data = make_zip()
        (self.folder / "2026-11-01-1402.zip").write_bytes(data)
        r = await self.get("/backups/2026-11-01-1402.zip")
        self.assertEqual((r.status_code, r.headers["Content-Type"], r.body), (200, "application/zip", data))

    async def test_a_name_that_is_not_one_of_ours_is_a_404(self):
        self.folder.mkdir()
        (self.folder.parent / "x.zip").write_bytes(b"outside")
        (self.folder / "2026-11-01-1402.zip").write_bytes(make_zip())
        for path in ("/backups/..%2Fx.zip", "/backups/../x.zip", "/backups/x.zip", "/backups/2026-11-01-1500.zip",
                     "/backups/2026-11-01-1402.zip/extra", "/backups//2026-11-01-1402.zip"):
            r = await self.get(path)
            self.assertEqual(r.status_code, 404, path)
            self.assertIn("no such backup", json.loads(r.body)["error"])

    async def test_a_server_with_no_backups_object_says_so(self):
        for path in ("/backups", "/backups/2026-11-01-1402.zip"):
            r = await self.get(path, http_routes(None, FakeCosts()))
            self.assertEqual(r.status_code, 404, path)


class UploadTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.folder = Path(tmp.name) / "backups"
        self.set_backups(backups_in(self.folder, max_bytes=100_000))
        await self.start()

    def set_backups(self, backups: Backups):
        self.backups = backups
        self.left = asyncio.Queue()   # one entry per upload the server has finished with

    async def start(self):
        async def handler(ws):
            try:
                await self.backups.receive(ws)
            finally:
                self.left.put_nowait(None)

        server = await serve(handler, "127.0.0.1", 0, max_size=4 * 1024 * 1024,
                             process_request=http_routes(None, FakeCosts(), backups=self.backups))
        self.addAsyncCleanup(server.wait_closed)
        self.addCleanup(server.close)
        self.port = server.sockets[0].getsockname()[1]

    async def recv(self, ws):
        return json.loads(await asyncio.wait_for(ws.recv(), 5))

    async def upload(self, data: bytes, sha: str | None = None, size: int | None = None, chunk: int = CHUNK):
        """The C1's side: begin, then a chunk per answer. Every frame the server sent, in order."""
        frames = []
        async with connect(f"ws://127.0.0.1:{self.port}/backup") as ws:
            await ws.send(json.dumps({"type": "begin", "size": size or len(data), "sha256": sha or sha256(data)}))
            frames.append(await self.recv(ws))
            sent = 0
            while frames[-1]["type"] in ("ready", "got") and sent < len(data):
                await ws.send(data[sent:sent + chunk])
                sent += chunk
                frames.append(await self.recv(ws))
            if frames[-1]["type"] == "got":
                frames.append(await self.recv(ws))
        await asyncio.wait_for(self.left.get(), 5)
        return frames

    def files(self) -> list[str]:
        return sorted(os.listdir(self.folder)) if self.folder.exists() else []

    async def test_a_full_upload_is_saved_under_the_clock_s_name(self):
        data = make_zip(extra=bytes(range(256)) * 12)
        self.assertGreater(len(data), 3 * CHUNK)
        frames = await self.upload(data)
        self.assertEqual(frames[0], {"type": "ready", "name": "2026-11-01-1402.zip"})
        totals = [f["bytes"] for f in frames[1:-1]]
        self.assertEqual(totals, [min(len(data), (i + 1) * CHUNK) for i in range(len(totals))])
        self.assertEqual(totals[-1], len(data))
        self.assertEqual(frames[-1], {"type": "saved", "name": "2026-11-01-1402.zip", "folder": self.backups.shown})
        self.assertEqual(self.files(), ["2026-11-01-1402.zip"])
        saved = self.folder / "2026-11-01-1402.zip"
        self.assertEqual(saved.read_bytes(), data)
        self.assertEqual(stat.S_IMODE(saved.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.folder.stat().st_mode), 0o700)
        self.assertEqual(self.backups.listing()[0]["version"], "0.1.0")

    async def test_a_second_and_third_in_the_same_minute_take_a_suffix(self):
        names = []
        for _ in range(3):
            names.append((await self.upload(make_zip()))[-1]["name"])
        self.assertEqual(names, ["2026-11-01-1402.zip", "2026-11-01-1402-2.zip", "2026-11-01-1402-3.zip"])
        self.assertEqual(self.files(), sorted(names))

    async def test_the_next_minute_has_its_own_name(self):
        clock = [datetime(2026, 11, 1, 14, 2, 59)]
        self.set_backups(backups_in(self.folder, now=lambda: clock[0]))
        first = (await self.upload(make_zip()))[-1]["name"]
        clock[0] = datetime(2026, 11, 1, 14, 3, 0)
        second = (await self.upload(make_zip()))[-1]["name"]
        self.assertEqual((first, second), ("2026-11-01-1402.zip", "2026-11-01-1403.zip"))

    async def test_a_part_left_by_a_crash_holds_its_name(self):
        self.folder.mkdir()
        (self.folder / "2026-11-01-1402.zip.part").write_bytes(b"left over")
        frames = await self.upload(make_zip())
        self.assertEqual(frames[-1]["name"], "2026-11-01-1402-2.zip")
        self.assertEqual((self.folder / "2026-11-01-1402.zip.part").read_bytes(), b"left over")

    async def test_a_wrong_hash_drops_the_part(self):
        data = make_zip(extra=bytes(range(256)) * 12)
        wrong = sha256(b"something else")
        with self.assertLogs("croissant_server.backups", "WARNING"):
            frames = await self.upload(data, sha=wrong)
        self.assertEqual(frames[-2]["type"], "got")
        self.assertEqual(frames[-1], {"type": "error", "error": (
            f"the backup arrived damaged: {len(data)} bytes, sha256 {sha256(data)}, "
            f"expected {len(data)} bytes, sha256 {wrong}")})
        self.assertEqual(self.files(), [])
        self.assertEqual(self.backups.listing(), [])

    async def test_more_bytes_than_announced_is_damaged_and_drops_the_part(self):
        data = make_zip(extra=b"x" * 200)
        self.assertLess(len(data), CHUNK)
        with self.assertLogs("croissant_server.backups", "WARNING"):
            frames = await self.upload(data, size=len(data) - 100)
        self.assertEqual([f["type"] for f in frames], ["ready", "error"])
        self.assertIn(f"the backup arrived damaged: {len(data)} bytes, sha256 {sha256(data)}", frames[1]["error"])
        self.assertIn(f"expected {len(data) - 100} bytes", frames[1]["error"])
        self.assertEqual(self.files(), [])

    async def test_a_close_mid_way_drops_the_part(self):
        data = make_zip(extra=bytes(range(256)) * 12)
        async with connect(f"ws://127.0.0.1:{self.port}/backup") as ws:
            await ws.send(json.dumps({"type": "begin", "size": len(data), "sha256": sha256(data)}))
            name = (await self.recv(ws))["name"]
            await ws.send(data[:CHUNK])
            self.assertEqual(await self.recv(ws), {"type": "got", "bytes": CHUNK})
            self.assertEqual(self.files(), [f"{name}.part"])
        await asyncio.wait_for(self.left.get(), 5)
        self.assertEqual(self.files(), [])

    async def test_an_upload_over_the_max_is_refused_at_begin(self):
        self.set_backups(backups_in(self.folder, max_bytes=1000))
        with self.assertLogs("croissant_server.backups", "WARNING"):
            frames = await self.upload(b"x" * 1001, size=1001)
        self.assertEqual(frames, [{"type": "error",
                                   "error": "the backup is 1001 bytes, over the 1000 bytes this server takes"}])
        self.assertEqual(self.files(), [])
        async with connect(f"ws://127.0.0.1:{self.port}/backup") as ws:   # exactly the max is taken
            await ws.send(json.dumps({"type": "begin", "size": 1000, "sha256": sha256(b"x" * 1000)}))
            self.assertEqual((await self.recv(ws))["type"], "ready")
        await asyncio.wait_for(self.left.get(), 5)

    async def test_the_server_closes_after_an_error(self):
        with self.assertLogs("croissant_server.backups", "WARNING"):
            async with connect(f"ws://127.0.0.1:{self.port}/backup") as ws:
                await ws.send(json.dumps({"type": "begin", "size": 10**9, "sha256": sha256(b"")}))
                self.assertEqual((await self.recv(ws))["type"], "error")
                with self.assertRaises(ConnectionClosed):
                    await asyncio.wait_for(ws.recv(), 5)
            await asyncio.wait_for(self.left.get(), 5)

    async def test_a_begin_that_is_not_one_is_refused_with_what_it_sent(self):
        good = sha256(b"x")
        cases = [(b"\x00\x01", "the first frame has to be a begin message in text, got 2 binary bytes"),
                 ("not json", "the first frame is not JSON"),
                 (json.dumps({"type": "start"}), "the first frame has to be a begin message"),
                 (json.dumps({"type": "begin", "size": 0, "sha256": good}), "a size in bytes of at least 1, got 0"),
                 (json.dumps({"type": "begin", "size": "9", "sha256": good}), "a size in bytes of at least 1, got '9'"),
                 (json.dumps({"type": "begin", "size": True, "sha256": good}), "a size in bytes of at least 1, got True"),
                 (json.dumps({"type": "begin", "size": 9, "sha256": "abc"}), "a sha256 of 64 hex characters, got 'abc'")]
        for frame, why in cases:
            with self.assertLogs("croissant_server.backups", "WARNING"):
                async with connect(f"ws://127.0.0.1:{self.port}/backup") as ws:
                    await ws.send(frame)
                    answer = await self.recv(ws)
                await asyncio.wait_for(self.left.get(), 5)
            self.assertEqual(answer["type"], "error", frame)
            self.assertIn(why, answer["error"], frame)
        self.assertEqual(self.files(), [])

    async def test_a_chunk_over_the_limit_or_a_text_frame_drops_the_part(self):
        for frame, why in ((b"x" * (CHUNK + 1), f"a chunk of {CHUNK + 1} bytes is over the {CHUNK} bytes"),
                           ("hello", "expected a binary chunk after 0 of 2000 bytes, got text 'hello'"),
                           (b"", "an empty chunk after 0 of 2000 bytes")):
            with self.assertLogs("croissant_server.backups", "WARNING"):
                async with connect(f"ws://127.0.0.1:{self.port}/backup") as ws:
                    await ws.send(json.dumps({"type": "begin", "size": 2000, "sha256": sha256(b"x")}))
                    await self.recv(ws)
                    await ws.send(frame)
                    answer = await self.recv(ws)
                await asyncio.wait_for(self.left.get(), 5)
            self.assertIn(why, answer["error"])
            self.assertEqual(self.files(), [])

    async def test_a_full_disk_is_said_to_be_that(self):
        data = make_zip()
        free = 10
        with mock.patch("croissant_server.backups.shutil.disk_usage",
                        return_value=mock.Mock(free=free)), self.assertLogs("croissant_server.backups", "WARNING"):
            frames = await self.upload(data)
        self.assertEqual(frames, [{"type": "error", "error": (
            f"the disk on this machine is full: {free} bytes free, the backup is {len(data)} bytes")}])
        self.assertEqual(self.files(), [])

    async def test_a_disk_that_fills_while_writing_is_said_to_be_that(self):
        class FullDisk:
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def write(self, chunk):
                raise OSError(errno.ENOSPC, os.strerror(errno.ENOSPC))

        data = make_zip(extra=bytes(range(256)) * 12)
        with mock.patch.object(self.backups, "_claim", return_value=("2026-11-01-1402.zip", FullDisk())), \
                self.assertLogs("croissant_server.backups", "WARNING"):
            frames = await self.upload(data)
        self.assertEqual([f["type"] for f in frames], ["ready", "error"])
        self.assertTrue(frames[1]["error"].startswith("the disk on this machine is full after 0 of "), frames[1])
        self.assertIn(f"of {len(data)} bytes", frames[1]["error"])
        self.assertEqual(self.files(), [])

    async def test_the_zip_comes_back_over_http_on_the_same_port(self):
        data = make_zip(extra=b"y" * 5000)
        name = (await self.upload(data))[-1]["name"]
        reader, writer = await asyncio.open_connection("127.0.0.1", self.port)
        writer.write(f"GET /backups/{name} HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".encode())
        raw = await asyncio.wait_for(reader.read(), 5)
        writer.close()
        head, _, body = raw.partition(b"\r\n\r\n")
        self.assertTrue(head.startswith(b"HTTP/1.1 200"), head)
        self.assertIn(b"application/zip", head)
        self.assertEqual(body, data)


if __name__ == "__main__":
    unittest.main()
