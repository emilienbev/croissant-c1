"""The preloader read's pure logic: mtkclient's output, the files it writes, the checks.

  uv run --project installer python -m unittest discover -s installer/tests

No R1 and no mtkclient needed: a stand-in script plays mtkclient. With a real read at hand
(never in the repo: it is rabbit's firmware), point CROISSANT_TEST_READ at its directory
(boot_a.bin, boot_b.bin) to check it too, CROISSANT_TEST_TRIMMED at a boot image trimmed
by hand from its header's sizes to compare against, and CROISSANT_TEST_PATCHED at a
Magisk-patched one.
"""
import gzip
import os
import struct
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

import scratch  # noqa: F401,E402  (the cache in a temporary directory, before croissant)
from croissant import bootimg, preloader, ui  # noqa: E402

PAGE = 2048
MIB = 1 << 20


def cpio(files: dict[str, bytes]) -> bytes:
    out = bytearray()
    for i, (name, data) in enumerate(list(files.items()) + [('TRAILER!!!', b'')]):
        n = name.encode() + b'\0'
        fields = [i + 1, 0o100644, 0, 0, 1, 0, len(data), 0, 0, 0, 0, len(n), 0]
        out += b'070701' + b''.join(b'%08X' % f for f in fields) + n
        out += b'\0' * (-len(out) % 4) + data
        out += b'\0' * (-len(out) % 4)
    return bytes(out)


def boot(files: dict[str, bytes], size: int = 32 * MIB, kernel: bytes = b'K' * 5000) -> bytes:
    """A boot header v2 image like the R1's, with its partition tail (an AVB0 blob)."""
    ramdisk, dtb = gzip.compress(cpio(files)), b'D' * 300
    h = bytearray(PAGE)
    h[:8] = bootimg.MAGIC
    for off, v in ((8, len(kernel)), (16, len(ramdisk)), (24, 0), (36, PAGE), (40, 2), (1632, 0), (1648, len(dtb))):
        struct.pack_into('<I', h, off, v)
    img = bytes(h)
    for part in (kernel, ramdisk, dtb):
        img += part + b'\0' * (-len(part) % PAGE)
    return (img + b'AVB0').ljust(size, b'\0')


STOCK = {'init': b'\x7fELF', 'first_stage_ramdisk/fstab.mt6765': b'fstab'}
PATCHED = STOCK | {'.backup/init': b'\x7fELF', '.backup/.magisk': b'KEEPVERITY=true\n',
                   'overlay.d/sbin/magisk.xz': b'xz'}


def guide_trim(d: bytes) -> int:
    """The image's length from its header, written out apart from bootimg.trim to check it."""
    ksz, = struct.unpack_from('<I', d, 8)
    rsz, = struct.unpack_from('<I', d, 16)
    ssz, = struct.unpack_from('<I', d, 24)
    page, = struct.unpack_from('<I', d, 36)
    rec, = struct.unpack_from('<I', d, 1632)
    dtb, = struct.unpack_from('<I', d, 1648)
    pg = lambda n: (n + page - 1) // page  # noqa: E731
    return page * (1 + pg(ksz) + pg(rsz) + pg(ssz) + pg(rec) + pg(dtb))


UNPROTECTED = """\
Preloader - Status: Waiting for PreLoader VCOM, please reconnect mobile/iot device to brom mode
...........
Port - Device detected :)
Preloader - 	CPU:			MT6765/MT8768t(Helio P35/G35)
Preloader - 	ME_ID:			0123456789ABCDEF0123456789ABCDEF
Preloader - 	SOC_ID:			FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210
DaHandler - Device is unprotected.
DAXFlash - DA SLA is disabled
DAXFlash - EMMC CID:        150100474436414d420aabcdef0123456789
Progress: |##########| 100.0% Read (Sector 0x10000 of 0x10000, ) 64.00 MB/s
DaHandler - All partitions were dumped
"""
PROTECTED = """\
Preloader - Status: Waiting for PreLoader VCOM, please reconnect mobile/iot device to brom mode
Port - Device detected :)
DaHandler - Device is protected.
"""


class Output(unittest.TestCase):
    def test_unprotected(self):
        o = preloader.parse(UNPROTECTED)
        self.assertTrue(o.waited and o.unprotected and o.dumped)
        self.assertFalse(o.protected or o.stale or o.access or o.no_libusb)

    def test_protected(self):
        o = preloader.parse(PROTECTED)
        self.assertTrue(o.protected)
        self.assertFalse(o.unprotected or o.dumped)
        self.assertTrue(preloader.parse('Device was protected. Successfully bypassed security.').protected)

    def test_failures(self):
        self.assertTrue(preloader.parse('Please disconnect, start mtkclient and reconnect.').stale)
        self.assertTrue(preloader.parse('usb.core.USBError: [Errno 13] Access denied (insufficient permissions)').access)
        self.assertTrue(preloader.parse('usb.core.NoBackendError: No backend available').no_libusb)

    def test_unit_lines_kept_out_of_files(self):
        for line in UNPROTECTED.splitlines():
            r = preloader.redact(line)
            for value in ('0123456789ABCDEF', 'FEDCBA98', '150100474436'):
                self.assertNotIn(value, r)
        self.assertEqual(preloader.redact('DaHandler - Device is unprotected.'), 'DaHandler - Device is unprotected.')

    def test_shown(self):
        self.assertFalse(preloader.shown('...........'))
        self.assertFalse(preloader.shown('Progress: |#####     | 50.0% Read'))
        self.assertTrue(preloader.shown('DaHandler - Device is unprotected.'))
        self.assertEqual(preloader.clean('\x1b[32mok\x1b[0m  '), 'ok')


class Images(unittest.TestCase):
    def test_frp(self):
        self.assertTrue(preloader.frp_allows(b'\0' * (MIB - 1) + b'\1'))
        self.assertFalse(preloader.frp_allows(b'\0' * MIB))
        self.assertIsNone(preloader.frp_allows(b'\1'))

    def test_boot_problem(self):
        self.assertIsNone(preloader.boot_problem(boot(STOCK)))
        self.assertIn('bytes', preloader.boot_problem(boot(STOCK, 16 * MIB)))
        self.assertIn('ANDROID!', preloader.boot_problem(b'\0' * 32 * MIB))

    def test_stock_or_patched(self):
        self.assertEqual(preloader.patched(boot(STOCK)), [])
        self.assertIn('.backup/.magisk', preloader.patched(boot(PATCHED)))
        self.assertIn('overlay.d/sbin/magisk.xz', preloader.patched(boot(PATCHED)))

    def test_trim(self):
        d = boot(STOCK)
        t = bootimg.trim(d)
        self.assertEqual(len(t), guide_trim(d))
        self.assertEqual(d[len(t):len(t) + 4], b'AVB0')

    def test_check(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t)
            (d / 'boot_a.bin').write_bytes(boot(STOCK))
            (d / 'boot_b.bin').write_bytes(boot(PATCHED))
            (d / 'frp.bin').write_bytes(b'\0' * (MIB - 1) + b'\1')
            r = preloader.check(d)
            self.assertEqual(list(r.boots), ['a'])
            self.assertTrue(r.notes['b'].startswith('already patched'))
            self.assertTrue(r.frp)
            self.assertFalse((d / 'boot_b.bin').exists())
            self.assertFalse((d / 'frp.bin').exists())
        with tempfile.TemporaryDirectory() as t:
            r = preloader.check(Path(t))
            self.assertEqual(r.boots, {})
            self.assertEqual(r.notes, {'a': 'not read', 'b': 'not read'})
            self.assertIsNone(r.frp)

    def test_cache(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t)
            (d / 'boot_a.bin').write_bytes(boot(STOCK))
            (d / 'boot_b.bin').write_bytes(boot(STOCK))
            preloader.adopt({'a': d / 'boot_a.bin', 'b': d / 'boot_b.bin'}, 'R1TEST')
        self.assertEqual(preloader.cached('R1TEST', 'a'), preloader.STOCK / 'R1TEST/boot_a.bin')
        self.assertIsNone(preloader.cached('OTHER', 'a'))
        self.assertIsNone(preloader.cached(None, 'a'))
        (preloader.STOCK / 'R1TEST/boot_b.bin').write_bytes(boot(PATCHED))
        self.assertIsNone(preloader.cached('R1TEST', 'b'))


class Read(unittest.TestCase):
    """read() against a stand-in for mtkclient, run as a real process."""

    def fake(self, output: str, files: bool, sleep: float = 0) -> list[str]:
        script = Path(preloader.CACHE) / 'fake_mtk.py'
        script.write_text(textwrap.dedent(f'''
            import sys, time
            from pathlib import Path
            sys.stdout.write({output!r})
            sys.stdout.flush()
            time.sleep({sleep})
            if {files}:
                Path('boot_a.bin').write_bytes(Path(sys.argv[1]).read_bytes())
                Path('boot_b.bin').write_bytes(Path(sys.argv[1]).read_bytes())
                Path('frp.bin').write_bytes(bytes((1 << 20) - 1) + bytes([1]))
                Path('hwparam.json').write_text('{{"meid": "0123456789ABCDEF"}}')
        '''))
        img = Path(preloader.CACHE) / 'boot.bin'
        img.write_bytes(boot(STOCK))
        return [sys.executable, str(script), str(img)]

    def run_read(self, argv, **kw):
        real = preloader.ensure
        preloader.ensure = lambda: argv   # mtkclient's own arguments come after these
        try:
            return preloader.read(again='Run again.', **kw)
        finally:
            preloader.ensure = real

    def test_good_read(self):
        d = self.run_read(self.fake(UNPROTECTED, True))
        r = preloader.check(d)
        self.assertEqual(sorted(r.boots), ['a', 'b'])
        self.assertTrue(r.frp)
        self.assertFalse((d / 'hwparam.json').exists())
        log = preloader.LOG.read_text()
        self.assertIn('Device is unprotected.', log)
        self.assertNotIn('0123456789ABCDEF', log)
        self.assertNotIn('150100474436', log)

    def test_protected(self):
        with self.assertRaisesRegex(ui.Abort, 'protected'):
            self.run_read(self.fake(PROTECTED, False))

    def test_no_preloader(self):
        waiting = UNPROTECTED.splitlines()[0] + '\n'
        with self.assertRaisesRegex(ui.Abort, 'No preloader seen'):
            self.run_read(self.fake(waiting, False, sleep=30), wait_s=2)

    def test_stale(self):
        with self.assertRaisesRegex(ui.Abort, 'download mode'):
            self.run_read(self.fake('Please disconnect, start mtkclient and reconnect.\n', False))


@unittest.skipUnless(os.environ.get('CROISSANT_TEST_READ'), 'no real read given')
class RealRead(unittest.TestCase):
    """A real read of an R1, outside the repo."""

    def test_both_slots_stock(self):
        d = Path(os.environ['CROISSANT_TEST_READ'])
        for s in preloader.SLOTS:
            raw = (d / f'boot_{s}.bin').read_bytes()
            self.assertIsNone(preloader.boot_problem(raw))
            self.assertEqual(preloader.patched(raw), [])
            self.assertEqual(len(bootimg.trim(raw)), guide_trim(raw))

    @unittest.skipUnless(os.environ.get('CROISSANT_TEST_TRIMMED'), 'no trimmed image given')
    def test_trim_as_before(self):
        raw = (Path(os.environ['CROISSANT_TEST_READ']) / 'boot_a.bin').read_bytes()
        self.assertEqual(bootimg.trim(raw), Path(os.environ['CROISSANT_TEST_TRIMMED']).read_bytes())

    @unittest.skipUnless(os.environ.get('CROISSANT_TEST_PATCHED'), 'no patched image given')
    def test_patched_refused(self):
        raw = Path(os.environ['CROISSANT_TEST_PATCHED']).read_bytes().ljust(32 * MIB, b'\0')
        self.assertIn('.backup/.magisk', preloader.patched(raw))


if __name__ == '__main__':
    unittest.main()
