"""Adb's root handling: Magisk's su or the shell, never `adb root`, whose refusal on
LineageOS restarts adbd and drops the R1 off USB."""
import unittest

import scratch  # noqa: F401  (the cache in a temporary directory, before croissant)
from croissant.tools import Adb


class FakeAdb(Adb):
    """Records every adb command; `su` answers as configured."""

    def __init__(self, su: bool):
        super().__init__('adb', 'TEST')
        self.su, self.calls = su, []

    def run(self, *args, **kw):
        self.calls.append(args)
        return ''

    def push(self, local, remote):
        self.calls.append(('push', remote))

    def exec_out(self, cmd, timeout=300):
        self.calls.append(('exec-out', cmd))
        return b''

    def sh(self, cmd, **kw):
        self.calls.append(('shell', cmd))
        return '0' if cmd.startswith('su -c id') and self.su else ''


class AdbRootTest(unittest.TestCase):
    def assert_no_adb_root(self, adb):
        self.assertFalse([c for c in adb.calls if c and c[0] == 'root'], adb.calls)

    def test_script_without_su_runs_as_the_shell_when_root_is_optional(self):
        adb = FakeAdb(su=False)
        adb.script('true\n', 'x', need_root=False)
        self.assertEqual(adb.calls[-1][1], 'sh /data/local/tmp/croissant-x.sh')
        self.assert_no_adb_root(adb)

    def test_script_with_su_runs_through_su(self):
        adb = FakeAdb(su=True)
        adb.script('true\n', 'x')
        self.assertEqual(adb.calls[-1][1], 'su -c "sh /data/local/tmp/croissant-x.sh"')
        self.assert_no_adb_root(adb)

    def test_script_needing_root_without_su_fails_without_adb_root(self):
        adb = FakeAdb(su=False)
        with self.assertRaises(BaseException):
            adb.script('true\n', 'x')
        self.assert_no_adb_root(adb)

    def test_root_is_magisk_su_only(self):
        adb = FakeAdb(su=False)
        self.assertFalse(adb.root())
        self.assert_no_adb_root(adb)


class LogoReadTest(unittest.TestCase):
    def test_the_logo_is_read_through_su(self):
        from croissant import stages
        adb = FakeAdb(su=True)
        stages.read_logo(adb)
        self.assertEqual(adb.calls, [('exec-out', "su -c 'dd if=/dev/block/by-name/logo bs=1048576 2>/dev/null'")])


class PrivacyIndicatorsTest(unittest.TestCase):
    def test_simple_device_config_is_disabled_before_the_flags_are_set(self):
        from croissant import stages
        adb = FakeAdb(su=True)
        stages.privacy_indicators(adb)
        cmds = [c[1] for c in adb.calls if c[0] == 'shell']
        self.assertEqual(cmds[0], 'pm disable-user --user 0 org.protonaosp.deviceconfig')
        for flag in stages.INDICATORS:
            self.assertIn(f'device_config put privacy {flag} false', cmds)


class DisplayTest(unittest.TestCase):
    def test_density_clock_and_transitions_are_set(self):
        from croissant import stages
        adb = FakeAdb(su=True)
        stages.display(adb)
        cmds = [c[1] for c in adb.calls if c[0] == 'shell']
        self.assertIn(f'wm density {stages.DENSITY}', cmds)
        self.assertIn('settings put system time_12_24 24', cmds)
        self.assertIn('settings put global transition_animation_scale 0', cmds)

    def test_edge_swipes_show_no_bars(self):
        from croissant import stages
        adb = FakeAdb(su=True)
        stages.no_swipe_bars(adb)
        self.assertIn('settings put secure user_setup_complete 0', [c[1] for c in adb.calls if c[0] == 'shell'])


if __name__ == '__main__':
    unittest.main()
