"""Root and the hand-over around the preloader read: the shell's grant in the patched ramdisk,
the check before flashing, the FASTBOOT listener, which stock image Magisk patches, the wait
for the grant, the Magisk app's version, and the read's clean-up.

  uv run --project installer python -m unittest discover -s installer/tests

No R1 needed: stand-ins play adb. CROISSANT_TEST_READ (a real read's directory, outside
the repo) adds the same checks on rabbit's own ramdisk and kernels.
"""
import gzip
import os
import subprocess
import sys
import tempfile
import textwrap
import time
import types
import unittest
from pathlib import Path

import scratch  # noqa: F401  (the cache in a temporary directory, before croissant)
from test_preloader import MIB, PATCHED, STOCK, boot

from croissant import bootimg, magisk, paths, preloader, stages, ui  # noqa: E402

BANNER = ('Linux version 4.19.191-gcd94d4ffc137 (builder@c0941d9aa82e) (Android (6443078 based on '
          'r383902) clang version 11.0.1 (https://android.googlesource.com/toolchain/llvm-project '
          'b397f81), LLD 11.0.1 (/buildbot/tmp b397f81)) #6 SMP PREEMPT Thu Sep 10 19:28:43 UTC 2026\n')
OLDER = BANNER.replace('#6 SMP PREEMPT Thu Sep 10 19:28:43', '#4 SMP PREEMPT Fri Jul 10 01:43:56')


def kernel(banner: str) -> bytes:
    """A gzip'd kernel carrying [banner], as the R1's boot image stores it."""
    return gzip.compress(b'\0' * 4096 + banner.encode() + b'\0' * 4096)


def patched_files(stock: dict[str, bytes]) -> dict[str, bytes]:
    """What boot_patch.sh and then magisk.overlay_script() leave in a stock ramdisk."""
    return stock | {'init': b'magiskinit', '.backup': b'', '.backup/init': stock.get('init', b''),
                    '.backup/.magisk': b'KEEPVERITY=true\n', 'overlay.d': b'', 'overlay.d/sbin': b'',
                    'overlay.d/sbin/magisk.xz': b'xz', 'overlay.d/sbin/stub.xz': b'xz',
                    'overlay.d/sbin/init-ld.xz': b'xz'} | magisk.overlay_files()


class Overlay(unittest.TestCase):
    def test_files(self):
        f = magisk.overlay_files()
        self.assertEqual(sorted(f), ['overlay.d/croissant-grant.rc', 'overlay.d/sbin/croissant-grant.sh'])

    def test_rc(self):
        rc = magisk.overlay_files()['overlay.d/croissant-grant.rc'].decode()
        self.assertIn(f'service {magisk.GRANT_SERVICE} /system/bin/sh ${{MAGISKTMP}}/croissant-grant.sh '
                      '${MAGISKTMP}\n', rc)
        for line in ('user root', 'group root', 'seclabel u:r:magisk:s0', 'oneshot', 'disabled'):
            self.assertIn(f'\n    {line}\n', rc)
        self.assertIn(f'on property:sys.boot_completed=1\n    start {magisk.GRANT_SERVICE}\n', rc)

    def test_script(self):
        sh = magisk.overlay_files()['overlay.d/sbin/croissant-grant.sh'].decode()
        # Magisk 30.7's table: policies (uid INT, policy INT, until INT, logging INT,
        # notification INT, PRIMARY KEY(uid)) in native/src/core/sqlite.cpp; ALLOW is 2.
        self.assertIn('"REPLACE INTO policies (uid,policy,until,logging,notification) '
                      'VALUES(2000,2,0,0,0)"', sh)
        self.assertIn('until "$M" -v', sh)                     # waits for magiskd, bounded
        self.assertIn('[ $i -ge 60 ] && exit 1', sh)
        self.assertNotIn('echo', sh)                           # quiet
        with tempfile.NamedTemporaryFile('w', suffix='.sh') as f:
            f.write(sh)
            f.flush()
            self.assertEqual(subprocess.run(['sh', '-n', f.name]).returncode, 0)

    def test_script_runs(self):
        """Against a stand-in magisk: waits, writes the row once, then leaves it."""
        with tempfile.TemporaryDirectory() as t:
            d = Path(t)
            (d / 'magisk').write_text(textwrap.dedent(f'''\
                #!/bin/sh
                case "$1" in
                  -v) [ -f {d}/up ] || {{ touch {d}/up; exit 1; }}; echo 30.7:MAGISK:R ;;
                  --sqlite) case "$2" in
                      SELECT*) [ -f {d}/row ] && echo policy=2 ;;
                      *) echo "$2" >> {d}/sql; touch {d}/row ;;
                    esac ;;
                esac
            '''))
            (d / 'magisk').chmod(0o755)
            sh = d / 'grant.sh'
            sh.write_bytes(magisk.overlay_files()['overlay.d/sbin/croissant-grant.sh'])
            for _ in range(2):
                p = subprocess.run(['sh', str(sh), str(d)], capture_output=True, text=True, timeout=30)
                self.assertEqual((p.returncode, p.stdout, p.stderr), (0, '', ''))
            self.assertEqual((d / 'sql').read_text().count('REPLACE INTO policies'), 1)

    def test_overlay_script(self):
        s = magisk.overlay_script()
        self.assertIn('rm -f kernel\n', s)                     # the kernel kept byte for byte
        self.assertIn('"add 0644 overlay.d/croissant-grant.rc ../croissant-grant.rc"', s)
        self.assertIn('"add 0755 overlay.d/sbin/croissant-grant.sh ../croissant-grant.sh"', s)
        self.assertIn('../magiskboot repack ../new-boot.img ../croissant-boot.img', s)


class PatchCheck(unittest.TestCase):
    ours = magisk.overlay_files()

    def check(self, stock: dict, patched: dict) -> list[str]:
        return bootimg.patch_problems(boot(stock), boot(patched), self.ours)

    def test_accepts_magisk_and_ours(self):
        self.assertEqual(self.check(STOCK, patched_files(STOCK)), [])

    def test_ours_required_exactly(self):
        p = patched_files(STOCK)
        self.assertIn('overlay.d/sbin/croissant-grant.sh missing',
                      self.check(STOCK, {k: v for k, v in p.items() if 'grant.sh' not in k}))
        self.assertIn('overlay.d/croissant-grant.rc is not the file this installer adds',
                      self.check(STOCK, p | {'overlay.d/croissant-grant.rc': b'on boot\n'}))

    def test_nothing_else(self):
        p = patched_files(STOCK)
        self.assertEqual(self.check(STOCK, p | {'overlay.d/other.rc': b'x'}), ['overlay.d/other.rc added'])
        self.assertEqual(self.check(STOCK, p | {'overlay.d/sbin/x.sh': b'x'}), ['overlay.d/sbin/x.sh added'])
        self.assertEqual(self.check(STOCK, p | {'first_stage_ramdisk/fstab.mt6765': b'changed'}),
                         ['first_stage_ramdisk/fstab.mt6765 changed'])
        gone = {k: v for k, v in p.items() if not k.startswith('first_stage')}
        self.assertEqual(self.check(STOCK, gone), ['first_stage_ramdisk/fstab.mt6765 removed'])

    def test_still_patched(self):
        """An image with the grant is Magisk-patched to the stock check, never cached as stock."""
        raw = boot(patched_files(STOCK))
        self.assertIn('overlay.d/croissant-grant.rc', preloader.patched(raw))
        self.assertIn('already patched', preloader.stock_problem(raw, None))

    @unittest.skipUnless(os.environ.get('CROISSANT_TEST_READ'), 'no real read given')
    def test_real_ramdisk(self):
        raw = (Path(os.environ['CROISSANT_TEST_READ']) / 'boot_a.bin').read_bytes()
        stock = bootimg.ramdisk_files(bootimg.trim(raw))
        self.assertIn('init', stock)
        self.assertFalse([n for n in stock if n.startswith(('overlay.d', '.backup'))])
        self.assertEqual(self.check(stock, patched_files(stock)), [])
        self.assertTrue(self.check(stock, patched_files(stock) | {'overlay.d/x.rc': b''}))


class Firmware(unittest.TestCase):
    def test_kernel_id(self):
        self.assertEqual(bootimg.kernel_id(BANNER), '4.19.191-gcd94d4ffc137 #6 SMP PREEMPT Thu Sep 10 19:28:43 UTC 2026')
        self.assertEqual(bootimg.kernel_version(boot(STOCK, kernel=kernel(BANNER))), bootimg.kernel_id(BANNER))
        self.assertIsNone(bootimg.kernel_version(boot(STOCK)))

    def test_mismatch(self):
        raw = boot(STOCK, kernel=kernel(OLDER))
        self.assertIsNone(preloader.firmware_problem(raw, OLDER))
        self.assertIsNone(preloader.firmware_problem(raw, None))
        self.assertIn('the R1 runs', preloader.firmware_problem(raw, BANNER))

    def test_cached_only_for_its_firmware(self):
        with tempfile.TemporaryDirectory() as t:
            f = Path(t) / 'boot_a.bin'
            f.write_bytes(boot(STOCK, kernel=kernel(OLDER)))
            kept = preloader.adopt({'a': f}, 'R1FW')
        self.assertEqual((kept['a'].parent / 'boot_a.kernel').read_text().strip(), bootimg.kernel_id(OLDER))
        self.assertEqual(preloader.cached('R1FW', 'a', OLDER), kept['a'])
        self.assertEqual(preloader.cached('R1FW', 'a'), kept['a'])
        self.assertIsNone(preloader.cached('R1FW', 'a', BANNER))

    @unittest.skipUnless(os.environ.get('CROISSANT_TEST_READ'), 'no real read given')
    def test_real_kernels(self):
        d = Path(os.environ['CROISSANT_TEST_READ'])
        ids = {s: bootimg.kernel_version((d / f'boot_{s}.bin').read_bytes()) for s in preloader.SLOTS}
        for s, k in ids.items():
            self.assertRegex(k, r'^4\.19\.\d+-g[0-9a-f]+ #\d+ ')


class Listener(unittest.TestCase):
    def setUp(self):
        self.ef = paths.load('scripts/enter-fastboot.py', 'enter_fastboot')

    def feed(self, w, *polls):
        return [w.feed(p) for p in polls]

    def test_appears(self):
        w = self.ef.Watch({'/dev/cu.usbmodem1'})
        self.assertEqual(self.feed(w, {'/dev/cu.usbmodem1'}, {'/dev/cu.usbmodem1', '/dev/cu.usbmodem2'}),
                         [None, '/dev/cu.usbmodem2'])

    def test_same_name_comes_back(self):
        """Download mode's port goes, the preloader's comes back under the same name."""
        w = self.ef.Watch({'/dev/cu.usbmodem1'}, must_go={'/dev/cu.usbmodem1'})
        self.assertEqual(self.feed(w, {'/dev/cu.usbmodem1'}, set(), set(), {'/dev/cu.usbmodem1'}),
                         [None, None, None, '/dev/cu.usbmodem1'])

    def test_nothing_before_it_goes(self):
        w = self.ef.Watch({'/dev/cu.usbmodem1'}, must_go={'/dev/cu.usbmodem1'})
        self.assertEqual(self.feed(w, {'/dev/cu.usbmodem1', '/dev/cu.usbmodem9'}, {'/dev/cu.usbmodem9'},
                                   {'/dev/cu.usbmodem9', '/dev/cu.usbmodem3'}),
                         [None, None, '/dev/cu.usbmodem3'])

    def test_gone_and_new_in_one_poll(self):
        w = self.ef.Watch({'/dev/ttyACM0'}, must_go={'/dev/ttyACM0'})
        self.assertEqual(w.feed({'/dev/ttyACM1'}), '/dev/ttyACM1')

    def test_unrelated_ports_ignored(self):
        """No port of the R1's in download mode: the first port to appear is the preloader."""
        w = self.ef.Watch({'/dev/cu.usbmodemOTHER'}, must_go=())
        self.assertEqual(self.feed(w, {'/dev/cu.usbmodemOTHER'}, {'/dev/cu.usbmodemOTHER', '/dev/cu.usbmodem5'}),
                         [None, '/dev/cu.usbmodem5'])

    def test_send_times_out(self):
        real = self.ef.candidates
        self.ef.candidates = lambda: {'/dev/cu.usbmodem1'}
        try:
            with self.assertRaises(self.ef.NoPreloader):
                self.ef.send_fastboot({'/dev/cu.usbmodem1'}, timeout_s=0.2, must_go={'/dev/cu.usbmodem1'})
        finally:
            self.ef.candidates = real


class FakeAdb:
    def __init__(self, running=BANNER, magisk_v='', serial='R1ROOT'):
        self.serial, self.running, self.magisk_v = serial, running, magisk_v
        self.calls, self.props, self.su, self.installed = [], {}, [], '40'

    def sh(self, cmd, timeout=60, check=True):
        self.calls.append(cmd)
        if cmd == 'cat /proc/version':
            return self.running.strip()
        if cmd == 'magisk -V':
            return self.magisk_v
        if cmd.startswith('dumpsys package com.topjohnwu.magisk'):
            return f'    versionCode={self.installed} minSdk=23 targetSdk=36' if self.installed else ''
        return ''

    def getprop(self, name):
        v = self.props.get(name, '')
        return v.pop(0) if isinstance(v, list) and len(v) > 1 else (v[0] if isinstance(v, list) else v)

    def has_su(self):
        self.calls.append('su')
        return self.su.pop(0) if self.su else False

    def run(self, *args, timeout=120, check=True):
        self.calls.append(args)
        if args[0] == 'install':
            self.installed = '30700'
        return ''

    def script(self, text, name, **kw):
        return 'ok' if 'envcheck' in name else 'policy=2'

    def reboot(self, target=''):
        self.calls.append(('reboot', target))


class StockBoot(unittest.TestCase):
    def ctx(self, adb):
        return stages.Ctx(adb, types.SimpleNamespace(serial=None))

    def setUp(self):
        self.read_called = []
        self.real_read = stages._read_from_android
        stages._read_from_android = lambda ctx, slot: self.read_called.append(slot) or boot(STOCK, kernel=kernel(BANNER))

    def tearDown(self):
        stages._read_from_android = self.real_read

    def test_legacy_trimmed_image(self):
        adb = FakeAdb(serial='R1LEGACY')
        ctx = self.ctx(adb)
        img = bootimg.trim(boot(STOCK, kernel=kernel(BANNER)))
        (ctx.work / 'stock-boot.img').write_bytes(img)
        self.assertEqual(stages._stock_boot(ctx, 'a'), img)
        self.assertEqual(self.read_called, [])

    def test_legacy_patched_or_other_firmware_not_used(self):
        for img in (bootimg.trim(boot(PATCHED, kernel=kernel(BANNER))), bootimg.trim(boot(STOCK, kernel=kernel(OLDER)))):
            adb = FakeAdb(serial='R1LEGACY2')
            ctx = self.ctx(adb)
            (ctx.work / 'stock-boot.img').write_bytes(img)
            self.read_called.clear()
            stages._stock_boot(ctx, 'a')
            self.assertEqual(self.read_called, ['a'])

    def test_magisk_without_a_copy_stops_first(self):
        adb = FakeAdb(magisk_v='30600', serial='R1MAGISK')
        with self.assertRaisesRegex(ui.Abort, 'Magisk 30600 is installed'):
            stages._stock_boot(self.ctx(adb), 'a')
        self.assertEqual(self.read_called, [])
        self.assertFalse([c for c in adb.calls if isinstance(c, tuple) and c[0] == 'reboot'])

    def test_cache_of_other_firmware_read_again(self):
        with tempfile.TemporaryDirectory() as t:
            f = Path(t) / 'boot_b.bin'
            f.write_bytes(boot(STOCK, kernel=kernel(OLDER)))
            preloader.adopt({'b': f}, 'R1OLD')
        stages._stock_boot(self.ctx(FakeAdb(serial='R1OLD')), 'b')
        self.assertEqual(self.read_called, ['b'])

    def test_read_of_other_firmware_refused(self):
        stages._read_from_android = lambda ctx, slot: boot(STOCK, kernel=kernel(OLDER))
        with self.assertRaisesRegex(ui.Abort, 'not the firmware this R1 runs'):
            stages._stock_boot(self.ctx(FakeAdb(serial='R1NEW')), 'a')


class ShellRoot(unittest.TestCase):
    def setUp(self):
        self.real_time = stages.time
        stages.time = types.SimpleNamespace(time=time.time, sleep=lambda s: None)

    def tearDown(self):
        stages.time = self.real_time

    def test_waits_for_the_script_before_su(self):
        adb = FakeAdb()
        adb.props['init.svc.croissant-grant'] = ['', 'running', 'running', 'stopped']
        adb.su = [False, True]
        stages._wait_shell_root(stages.Ctx(adb, None))
        self.assertEqual(adb.calls, ['su', 'su'])
        self.assertEqual(adb.props['init.svc.croissant-grant'], ['stopped'])

    def test_never_granted(self):
        adb = FakeAdb()
        adb.props['init.svc.croissant-grant'] = 'stopped'
        with self.assertRaisesRegex(ui.Abort, 'did not grant it .init.svc.croissant-grant: stopped'):
            stages._wait_shell_root(stages.Ctx(adb, None), su_s=0.05)

    def test_never_started(self):
        adb = FakeAdb()
        with self.assertRaisesRegex(ui.Abort, 'never started'):
            stages._wait_shell_root(stages.Ctx(adb, None), script_s=0.05, su_s=0.05)


class MagiskApp(unittest.TestCase):
    def rest(self, installed):
        adb = FakeAdb()
        adb.installed = installed
        ctx = stages.Ctx(adb, None, magisk_apk=Path('/x/Magisk-v30.7.apk'), magisk_code='30700')
        stages._magisk_rest(ctx)
        return [c for c in adb.calls if isinstance(c, tuple) and c[0] == 'install']

    def test_stub_replaced(self):
        self.assertEqual(self.rest('40'), [('install', '-r', '-d', '/x/Magisk-v30.7.apk')])

    def test_absent_installed(self):
        self.assertEqual(len(self.rest('')), 1)

    def test_full_app_kept(self):
        self.assertEqual(self.rest('30700'), [])


class ReadCleanup(unittest.TestCase):
    """read() leaves nothing naming the unit, however it ends."""

    def fake(self, output: str, sleep: float) -> list[str]:
        script = Path(preloader.CACHE) / 'fake_mtk_unit.py'
        script.write_text(textwrap.dedent(f'''
            import sys, time
            from pathlib import Path
            Path('hwparam.json').write_text('{{"meid": "0123456789ABCDEF"}}')
            Path('.state').write_text('state')
            Path('frp.bin').write_bytes(bytes(1 << 20))
            sys.stdout.write({output!r})
            sys.stdout.flush()
            time.sleep({sleep})
        '''))
        return [sys.executable, str(script)]

    def run_read(self, argv, follow=None):
        real, real_follow = preloader.ensure, preloader._follow
        preloader.ensure = lambda: argv
        if follow:
            preloader._follow = follow
        try:
            return preloader.read(again='Run again.')
        finally:
            preloader.ensure, preloader._follow = real, real_follow

    def assertClean(self):
        for n in ('hwparam.json', '.state', 'frp.bin'):
            self.assertFalse((preloader.PENDING / n).exists(), n)

    def test_failure(self):
        with self.assertRaises(ui.Abort):
            self.run_read(self.fake('DaHandler - Device is protected.\n', 0))
        self.assertClean()

    def test_ctrl_c(self):
        procs = []

        def follow(p, wait_s, total_s):
            procs.append(p)
            deadline = time.time() + 10
            while not (preloader.PENDING / 'frp.bin').exists() and time.time() < deadline:
                time.sleep(0.05)
            raise KeyboardInterrupt
        with self.assertRaises(KeyboardInterrupt):
            self.run_read(self.fake('Preloader - Status: Waiting for PreLoader VCOM\n', 30), follow)
        self.assertIsNotNone(procs[0].poll())                 # killed, not left running
        self.assertClean()


if __name__ == '__main__':
    unittest.main()
