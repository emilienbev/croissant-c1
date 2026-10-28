"""What this machine needs: Java and the Android SDK to build the app, the latest release as
the other way to it, and on Linux the udev rule for the R1 over USB.

  uv run --project installer python -m unittest discover -s installer/tests

No network, no sudo and no real Java or SDK needed: stand-ins play them.
"""
import io
import json
import os
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

import scratch  # noqa: F401  (the cache in a temporary directory, before croissant)
from croissant import apk, artifacts, paths, plan, prereqs, ui, updates


def quiet(test):
    """ui's output and questions patched out for one test case."""
    for name in ('ok', 'info', 'warn'):
        p = mock.patch.object(ui, name)
        p.start()
        test.addCleanup(p.stop)


class Java(unittest.TestCase):
    def test_major(self):
        for given, major in {'21.0.4': 21, '17': 17, '1.8.0_402': 8, '26-ea': 26, '': 0}.items():
            self.assertEqual(prereqs._major(given), major, given)

    def probe(self, found: dict):
        """java() with [found] mapping each java binary to what it says, and nothing else here."""
        return mock.patch.object(prereqs, '_probe', side_effect=lambda exe: found.get(str(exe), (None, 0)))

    def test_order_and_the_first_new_enough(self):
        with tempfile.TemporaryDirectory() as d:
            old, new = Path(d, 'old/bin/java'), Path(d, 'new/bin/java')
            for exe in (old, new):
                exe.parent.mkdir(parents=True)
                exe.touch()
            found = {str(old): (old.parent.parent, 11), str(new): (new.parent.parent, 21)}
            with mock.patch.dict(os.environ, {'JAVA_HOME': str(old.parent.parent)}), \
                    mock.patch.object(prereqs.shutil, 'which', return_value=str(new)), self.probe(found):
                self.assertEqual(prereqs.java(), (new.parent.parent, 21))
                self.assertEqual(prereqs.build_env()['JAVA_HOME'], str(new.parent.parent))

    def test_too_old_is_said_with_its_version(self):
        with tempfile.TemporaryDirectory() as d:
            old = Path(d, 'bin/java')
            old.parent.mkdir()
            old.touch()
            with mock.patch.dict(os.environ, {'JAVA_HOME': d}), \
                    mock.patch.object(prereqs.shutil, 'which', return_value=None), \
                    mock.patch.object(prereqs, 'SDKMAN_JAVA', Path(d, 'none')), \
                    self.probe({str(old): (Path(d), 11)}), mock.patch.object(prereqs, 'sdk', return_value=None):
                self.assertEqual(prereqs.java(), (Path(d), 11))
                self.assertIn('Java 17 or newer (found 11)', prereqs.missing_for_build())

    def test_a_real_probe_of_nothing(self):
        self.assertEqual(prereqs._probe('/nonexistent/java'), (None, 0))


class Sdk(unittest.TestCase):
    def setUp(self):
        quiet(self)
        self.root = Path(tempfile.mkdtemp())
        p = mock.patch.dict(os.environ, {'ANDROID_HOME': str(self.root)})
        p.start()
        self.addCleanup(p.stop)
        p = mock.patch.object(prereqs, 'java', return_value=(Path('/jdk'), 21))
        p.start()
        self.addCleanup(p.stop)
        p = mock.patch.object(prereqs, '_no_ndk_here', return_value=None)
        p.start()
        self.addCleanup(p.stop)

    def install(self, *packages):
        for pkg in packages:
            (self.root / pkg.replace(';', '/')).mkdir(parents=True)

    def test_what_is_missing(self):
        self.install('platforms;android-37.0')
        lacking = prereqs.missing_for_build()
        self.assertEqual(len(lacking), 1)
        self.assertIn('build-tools;36.0.0', lacking[0])
        self.assertIn(f'ndk;{prereqs.NDK}', lacking[0])
        self.assertNotIn('platforms', lacking[0])

    def test_complete(self):
        self.install(*prereqs.PACKAGES)
        self.assertEqual(prereqs.missing_for_build(), [])
        self.assertTrue(prereqs.ensure_build())
        self.assertEqual(prereqs.build_env()['ANDROID_HOME'], str(self.root))

    def test_defaults_where_android_studio_puts_it(self):
        with mock.patch.object(prereqs.platform, 'system', return_value='Darwin'):
            self.assertEqual(prereqs.default_sdk(), Path.home() / 'Library/Android/sdk')
        with mock.patch.object(prereqs.platform, 'system', return_value='Linux'):
            self.assertEqual(prereqs.default_sdk(), Path.home() / 'Android/Sdk')

    def test_nothing_asked_without_a_terminal(self):
        with mock.patch.object(ui, 'INTERACTIVE', False), mock.patch.object(ui, 'confirm') as asked, \
                mock.patch.object(prereqs.subprocess, 'run') as ran:
            self.assertFalse(prereqs.ensure_build())
        asked.assert_not_called()
        ran.assert_not_called()

    def test_declined_installs_nothing(self):
        with mock.patch.object(ui, 'INTERACTIVE', True), mock.patch.object(ui, 'confirm', return_value=False), \
                mock.patch.object(prereqs.subprocess, 'run') as ran:
            self.assertFalse(prereqs.ensure_build())
        ran.assert_not_called()

    def test_sdkmanager_from_the_pinned_tools(self):
        z = self.root / 'tools.zip'
        with zipfile.ZipFile(z, 'w') as f:
            f.writestr('cmdline-tools/bin/sdkmanager', '#!/bin/sh\n')
            f.writestr('cmdline-tools/source.properties', 'Pkg.Revision=22.0\n')
        with mock.patch.object(prereqs.artifacts, 'fetch', return_value=z) as fetch, \
                mock.patch.object(prereqs, 'CMDLINE_TOOLS_DIR', self.root / 'tools'), \
                mock.patch.object(prereqs.platform, 'system', return_value='Linux'):
            exe = prereqs._sdkmanager()
            self.assertEqual(prereqs._sdkmanager(), exe)          # kept: not fetched again
        self.assertEqual(exe, self.root / 'tools/bin/sdkmanager')
        self.assertTrue(os.access(exe, os.X_OK))
        self.assertEqual(fetch.call_args.args[0], prereqs.CMDLINE_TOOLS[('Linux', '')])
        self.assertEqual(fetch.call_count, 1)

    def test_installs_into_the_sdk_and_names_it_for_gradle(self):
        exe = Path('/cache/sdkmanager')
        props = self.root / 'local.properties'

        def sdkmanager(argv, env):
            self.assertEqual(env['JAVA_HOME'], '/jdk')
            self.install(*argv[2:])
            return mock.Mock(returncode=0)
        with mock.patch.object(prereqs.subprocess, 'run', side_effect=sdkmanager) as ran, \
                mock.patch.object(prereqs, '_sdkmanager', return_value=exe), \
                mock.patch.object(prereqs, 'LOCAL_PROPERTIES', props):
            self.assertTrue(prereqs._install_sdk(self.root))
        self.assertEqual(ran.call_args.args[0][:2], [str(exe), f'--sdk_root={self.root}'])
        self.assertEqual(props.read_text(), f'sdk.dir={self.root}\n')


class Usb(unittest.TestCase):
    def setUp(self):
        quiet(self)
        self.dir = Path(tempfile.mkdtemp())
        for p in (mock.patch.object(prereqs.platform, 'system', return_value='Linux'),
                  mock.patch.object(prereqs, 'UDEV_DIRS', (str(self.dir),)),
                  mock.patch.object(prereqs, 'UDEV_FILE', self.dir / 'absent' / '52-croissant.rules')):
            p.start()
            self.addCleanup(p.stop)

    def test_both_missing(self):
        self.assertEqual(prereqs.usb_vendors_missing(), ['0e8d', '18d1'])

    def test_mtkclient_and_android_rules_count(self):
        (self.dir / '52-mtk.rules').write_text('SUBSYSTEM=="usb", ATTR{idVendor}=="0E8D", MODE="0666"\n')
        self.assertEqual(prereqs.usb_vendors_missing(), ['18d1'])
        (self.dir / '51-android.rules').write_text('SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", TAG+="uaccess"\n')
        self.assertEqual(prereqs.usb_vendors_missing(), [])

    def test_not_linux(self):
        with mock.patch.object(prereqs.platform, 'system', return_value='Darwin'):
            self.assertEqual(prereqs.usb_vendors_missing(), [])

    def test_our_rule_names_both(self):
        for v in prereqs.USB_VENDORS:
            self.assertIn(f'ATTR{{idVendor}}=="{v}"', prereqs.UDEV_RULES)

    def test_offered_with_sudo(self):
        with mock.patch.object(ui, 'INTERACTIVE', True), mock.patch.object(ui, 'confirm', return_value=True), \
                mock.patch.object(prereqs.subprocess, 'run', return_value=mock.Mock(returncode=0)) as ran:
            prereqs.ensure_usb()
        argvs = [c.args[0] for c in ran.call_args_list]
        self.assertEqual(argvs[0], ['sudo', 'tee', str(prereqs.UDEV_FILE)])
        self.assertEqual(ran.call_args_list[0].kwargs['input'], prereqs.UDEV_RULES)
        self.assertEqual(argvs[1:], [['sudo', 'udevadm', 'control', '--reload-rules'],
                                     ['sudo', 'udevadm', 'trigger']])

    def test_never_without_a_yes(self):
        for interactive in (True, False):
            with mock.patch.object(ui, 'INTERACTIVE', interactive), \
                    mock.patch.object(ui, 'confirm', return_value=False), \
                    mock.patch.object(prereqs.subprocess, 'run') as ran:
                prereqs.ensure_usb()
            ran.assert_not_called()


class AppQuestion(unittest.TestCase):
    def setUp(self):
        quiet(self)

    def ask(self, picks, repo='me/mine', builds=(True,), missing=()):
        with mock.patch.object(ui, 'select', side_effect=list(picks)) as select, \
                mock.patch.object(prereqs, 'missing_for_build', return_value=list(missing)), \
                mock.patch.object(prereqs, 'ensure_build', side_effect=list(builds)):
            return plan.ask_app(repo), select

    def test_build(self):
        got, select = self.ask(['build'])
        self.assertIsNone(got)
        message, choices = select.call_args.args
        self.assertEqual(message, plan.APP_QUESTION)
        self.assertEqual([c[1] for c in choices], ['build', apk.LATEST, 'file'])
        self.assertEqual(select.call_args.kwargs['default'], 'build')
        ui.info.assert_any_call(plan.APP_WHY)

    def test_the_release_is_the_default_when_a_build_cannot_happen(self):
        got, select = self.ask([apk.LATEST], missing=['the Android SDK'])
        self.assertEqual(got, apk.LATEST)
        self.assertEqual(select.call_args.kwargs['default'], apk.LATEST)

    def test_a_declined_build_asks_again(self):
        got, select = self.ask(['build', apk.LATEST], builds=(False,))
        self.assertEqual((got, select.call_count), (apk.LATEST, 2))
        self.assertEqual(select.call_args.kwargs['default'], apk.LATEST)

    def test_no_repository_no_release(self):
        _, select = self.ask(['build'], repo=None)
        self.assertEqual([c[1] for c in select.call_args.args[1]], ['build', 'file'])

    def test_a_file(self):
        with tempfile.NamedTemporaryFile(suffix='.apk') as f, \
                mock.patch.object(ui, 'path', side_effect=['/nonexistent.apk', f.name]):
            got, _ = self.ask(['file'])
        self.assertEqual(got, f.name)


class Release(unittest.TestCase):
    REL = {'tag_name': 'v1.2.3', 'assets': [
        {'name': 'croissant-1.2.3.apk', 'browser_download_url': 'https://x/croissant-1.2.3.apk'},
        {'name': 'croissant-1.2.3.apk.sha256', 'browser_download_url': 'https://x/croissant-1.2.3.apk.sha256'}]}

    def download(self, rel, sha=b'ABCDEF  croissant-1.2.3.apk\n'):
        answers = [io.BytesIO(json.dumps(rel).encode()), io.BytesIO(sha)]
        with mock.patch.object(updates.urllib.request, 'urlopen', side_effect=answers), \
                mock.patch.object(artifacts, 'fetch', side_effect=lambda a: a) as fetch:
            got = updates.download('me/mine')
        return got, fetch

    def test_checked_against_its_sha256(self):
        got, _ = self.download(self.REL)
        self.assertEqual(got, artifacts.Artifact('croissant-1.2.3.apk', 'https://x/croissant-1.2.3.apk', 'abcdef'))

    def test_github_digest_when_no_sha256_file(self):
        rel = {'tag_name': 'v1.2.3', 'assets': [dict(self.REL['assets'][0], digest='sha256:0123')]}
        got, _ = self.download(rel)
        self.assertEqual(got.digest, '0123')

    def test_unchecked_is_refused(self):
        with self.assertRaises(ui.Abort) as e:
            self.download({'tag_name': 'v1.2.3', 'assets': [self.REL['assets'][0]]})
        self.assertIn('no checksum', str(e.exception))

    def test_no_apk(self):
        with self.assertRaises(ui.Abort):
            self.download({'tag_name': 'v1.2.3', 'assets': [{'name': 'app-release.apk'}]})

    def test_resolve_latest(self):
        with mock.patch.object(updates, 'download', return_value=Path('/x.apk')) as got:
            self.assertEqual(apk.resolve(apk.LATEST, 'me/mine'), Path('/x.apk'))
            got.assert_called_once_with('me/mine')
            apk.resolve(apk.LATEST)
            got.assert_called_with(paths.update_repo())

    def test_a_build_that_cannot_happen_names_the_release(self):
        with mock.patch.object(prereqs, 'ensure_build', return_value=False), \
                mock.patch.object(prereqs, 'missing_for_build', return_value=['the Android SDK']), \
                self.assertRaises(ui.Abort) as e:
            apk.resolve(None)
        self.assertIn('--apk latest', str(e.exception))
        self.assertIn('the Android SDK', str(e.exception))


if __name__ == '__main__':
    unittest.main()
