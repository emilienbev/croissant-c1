"""Where the C1 gets its updates: the answer read as the app reads it, the question and its
defaults, the live look at GitHub, the file handed to the app, and the app's restart after
the compile.

  uv run --project installer python -m unittest discover -s installer/tests

No R1 and no network needed: stand-ins play adb and GitHub.
"""
import io
import json
import subprocess
import sys
import tempfile
import types
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

import scratch  # noqa: F401  (the cache in a temporary directory, before croissant)
from croissant import apk, cli, font, paths, plan, stages, ui, updates

APPDIR = f'/data/user_de/0/{paths.PACKAGE}'
UPDATER = paths.REPO / 'android/app/src/main/java/com/bleural/croissant/update/Updater.kt'


def flags(**kw):
    """The install flags as argparse leaves them when none is given, with [kw] on top."""
    return types.SimpleNamespace(**{'name': None, 'gsi': None, 'gsi_checksum': None, 'apk': None,
                                    'offline_models': False, 'magisk': None, 'tailscale': None,
                                    'reflash': False, 'yes': False, 'update_repo': None, 'sounds': None} | kw)


class Parse(unittest.TestCase):
    def test_takes(self):
        for given, repo in {
            'owner/repo': 'owner/repo',
            '  owner/repo\n': 'owner/repo',
            'Owner-1/re.po_x-y': 'Owner-1/re.po_x-y',
            'owner/repo.git': 'owner/repo',
            'github.com/owner/repo': 'owner/repo',
            'www.github.com/owner/repo': 'owner/repo',
            'https://github.com/owner/repo': 'owner/repo',
            'http://www.github.com/owner/repo/': 'owner/repo',
            'https://GitHub.com/owner/repo.git': 'owner/repo',
            'https://github.com/owner/repo/releases/latest': 'owner/repo',
            'a' * 39 + '/' + 'r' * 100: 'a' * 39 + '/' + 'r' * 100,
        }.items():
            self.assertEqual(updates.parse(given), repo, given)

    def test_refuses(self):
        for given in ('', 'owner', 'owner/', '/repo', 'owner/repo/more', '-owner/repo', 'owner-/repo',
                      'ow--ner/repo', 'own_er/repo', 'a' * 40 + '/repo', 'owner/' + 'r' * 101,
                      'owner/.', 'owner/..', 'owner/.git', 'owner/re po', 'owner/repo?tab=x',
                      'owner/repo;reboot', 'github.com/owner', 'https://github.com/',
                      'gitlab.com/owner/repo', 'https://gitlab.com/owner/repo',
                      'https://github.com.example.com/owner/repo', 'ftp://github.com/owner/repo'):
            self.assertIsNone(updates.parse(given), given)

    def test_check_says_what_it_wants(self):
        self.assertEqual(updates.check('https://github.com/me/mine.git'), 'me/mine')
        with self.assertRaises(ui.Abort) as e:
            updates.check('gitlab.com/me/mine')
        self.assertIn(updates.WRONG, str(e.exception))


class GradleDefault(unittest.TestCase):
    def props(self, text: str) -> Path:
        root = Path(tempfile.mkdtemp())
        (root / 'android').mkdir()
        (root / 'android/gradle.properties').write_text(text)
        return root

    def test_the_checkout_names_a_repository(self):
        self.assertIsNotNone(updates.parse(paths.update_repo() or ''))

    def test_read_as_the_application_id_is(self):
        root = self.props('# a fork\ncroissant.applicationId=org.fork.croissant\n'
                          'croissant.updateRepo = someone/fork \n')
        with mock.patch.object(paths, 'REPO', root):
            self.assertEqual(paths.update_repo(), 'someone/fork')
            self.assertEqual(paths._application_id(), 'org.fork.croissant')

    def test_absent(self):
        with mock.patch.object(paths, 'REPO', self.props('croissant.applicationId=org.fork.croissant\n')):
            self.assertIsNone(paths.update_repo())


class Question(unittest.TestCase):
    """ask() with only this question left open, and unasked() for --no-input."""
    others = {'name', 'apk', 'offline_models', 'sounds'}

    def setUp(self):
        self.looked = []
        for p in (mock.patch.object(updates, 'look', side_effect=self.looked.append),
                  mock.patch.object(ui, 'info')):
            p.start()
            self.addCleanup(p.stop)

    def ask(self, answer=None, given=(), remembered=None, p=None):
        """The plan after ask(); [answer] None takes the offered default. Returns it and the text mock."""
        with mock.patch.object(ui, 'text', side_effect=lambda m, d='', v=None: d if answer is None else answer) as t:
            p = plan.ask(p or plan.Plan(), self.others | set(given), False, remembered or {})
        return p, t

    def test_asks_with_the_build_default(self):
        p, t = self.ask()
        message, default, valid = t.call_args.args
        self.assertEqual((message, default), (plan.UPDATE_QUESTION, paths.update_repo()))
        self.assertEqual(p.update_repo, paths.update_repo())
        self.assertEqual(self.looked, [paths.update_repo()])
        self.assertIs(valid('https://github.com/me/mine'), True)
        self.assertEqual(valid('gitlab.com/me/mine'), updates.WRONG)
        ui.info.assert_any_call(plan.UPDATE_WHY)

    def test_a_link_is_kept_as_owner_and_repo(self):
        p, _ = self.ask('https://www.github.com/me/mine.git/')
        self.assertEqual(p.update_repo, 'me/mine')

    def test_the_remembered_answer_is_the_default(self):
        p, t = self.ask(remembered={'update_repo': 'me/mine'})
        self.assertEqual(t.call_args.args[1], 'me/mine')
        self.assertEqual(p.update_repo, 'me/mine')

    def test_remembered_in_the_state_file(self):
        ctx = stages.Ctx(types.SimpleNamespace(serial='R1UPDATES'), None)
        ctx.remember(name='croissant', update_repo='me/mine')
        self.assertEqual(plan.update_default(ctx.state()), 'me/mine')
        self.assertEqual(plan.update_default({}), paths.update_repo())

    def test_the_flag_skips_the_question(self):
        p, given = plan.from_flags(flags(update_repo='github.com/me/mine'))
        self.assertEqual(p.update_repo, 'me/mine')
        self.assertIn('update_repo', given)
        p, t = self.ask(given=given, p=p)
        t.assert_not_called()
        self.assertEqual((p.update_repo, self.looked), ('me/mine', ['me/mine']))

    def test_a_bad_flag_stops_before_anything(self):
        with self.assertRaises(ui.Abort):
            plan.from_flags(flags(update_repo='me'))

    def test_no_input_takes_the_default(self):
        p = plan.unasked(plan.Plan(), set(), {'update_repo': 'me/mine'})
        self.assertEqual(p.update_repo, 'me/mine')
        p = plan.unasked(plan.Plan(), set(), {})
        self.assertEqual(p.update_repo, paths.update_repo())
        self.assertEqual(self.looked, ['me/mine', paths.update_repo()])

    def test_no_input_and_no_default_is_an_error(self):
        with mock.patch.object(paths, 'update_repo', return_value=None), self.assertRaises(ui.Abort) as e:
            plan.unasked(plan.Plan(), set(), {})
        self.assertIn('--update-repo OWNER/REPO', str(e.exception))

    def test_no_input_keeps_the_flag(self):
        p, given = plan.from_flags(flags(update_repo='me/mine'))
        self.assertEqual(plan.unasked(p, given, {'update_repo': 'other/one'}).update_repo, 'me/mine')

    def test_the_cli_takes_the_flag_on_install(self):
        with mock.patch.object(cli, 'cmd_install') as run, \
                mock.patch.object(sys, 'argv', ['croissant', 'install', '--update-repo', 'me/mine']):
            cli.main()
        self.assertEqual(run.call_args.args[0].update_repo, 'me/mine')


class Look(unittest.TestCase):
    """The live look at GitHub: what it says, and that it never stops the install."""

    def setUp(self):
        self.said = []
        for kind in ('ok', 'info', 'warn'):
            p = mock.patch.object(ui, kind, side_effect=lambda m, kind=kind: self.said.append((kind, m)))
            p.start()
            self.addCleanup(p.stop)

    def look(self, answer):
        with mock.patch.object(updates.urllib.request, 'urlopen') as get:
            if isinstance(answer, BaseException):
                get.side_effect = answer
            else:
                get.return_value = io.BytesIO(answer if isinstance(answer, bytes) else json.dumps(answer).encode())
            updates.look('me/mine')
        self.assertEqual(len(self.said), 1, self.said)
        return get.call_args.args[0], self.said[0]

    def http(self, code):
        return urllib.error.HTTPError('https://api.github.com/repos/me/mine/releases/latest', code, 'x', {}, None)

    def test_a_release(self):
        req, said = self.look({'tag_name': 'v1.2.3', 'assets': [{'name': 'croissant-1.2.3.apk'},
                                                                {'name': 'croissant-1.2.3.apk.sha256'}]})
        self.assertEqual(req.full_url, 'https://api.github.com/repos/me/mine/releases/latest')
        self.assertEqual(req.get_header('User-agent'), 'croissant')
        self.assertEqual(said, ('ok', 'me/mine: Croissant 1.2.3'))

    def test_none_yet(self):
        _, (kind, msg) = self.look(self.http(404))
        self.assertEqual(kind, 'info')
        self.assertIn('no release yet', msg)
        self.assertIn('daily', msg)

    def test_could_not_look(self):
        for answer in (urllib.error.URLError('no route'), TimeoutError(), self.http(403), self.http(429),
                       self.http(500), b'<html>not json'):
            self.said.clear()
            _, (kind, msg) = self.look(answer)
            self.assertEqual(kind, 'info')
            self.assertIn('could not look at me/mine', msg)

    def test_a_release_the_c1_cannot_take(self):
        for rel in ({'tag_name': 'v1.2.3', 'assets': [{'name': 'app-release.apk'}]},
                    {'tag_name': 'nightly', 'assets': [{'name': 'croissant-nightly.apk'}]}):
            self.said.clear()
            _, (kind, msg) = self.look(rel)
            self.assertEqual(kind, 'warn')
            self.assertIn('no Croissant APK', msg)


class FakeAdb:
    """Plays adb for the app stage: every command in order, and what each pushed file held."""

    def __init__(self, owner=True):
        self.serial, self.owner, self.calls, self.pushed = 'R1UPDATES', owner, [], {}

    def sh(self, cmd, timeout=60, check=True):
        self.calls.append(('shell', cmd))
        if cmd.startswith('pm path'):
            return f'package:/data/app/{paths.PACKAGE}/base.apk'
        if cmd.startswith('dumpsys package'):
            return '    userId=10123 gids=[]'
        if cmd == 'dumpsys device_policy':
            return f'Device Owner (User 0):\n  admin=ComponentInfo{{{paths.admin()}}}' if self.owner else ''
        if cmd == 'pm list features':
            return 'feature:android.software.device_admin'
        return ''

    def run(self, *args, timeout=120, check=True, show=False):
        self.calls.append(args)
        return ''

    def push(self, local, remote):
        self.pushed[remote] = Path(local).read_text()
        self.calls.append(('push', remote))

    def script(self, text, name, **kw):
        self.calls.append(('script', name, text))
        return 'policy=2' if name.startswith('magisk-granted') else ''

    def reboot(self, target=''):
        self.calls.append(('reboot', target))
        self.owner = True

    def wait_boot(self, timeout=600):
        pass

    def index(self, kind, name):
        return next(i for i, c in enumerate(self.calls) if c[0] == kind and name in c[1])

    def scripts(self, name):
        return [c[2] for c in self.calls if c[0] == 'script' and c[1] == name]


class HandOff(unittest.TestCase):
    def setUp(self):
        self.apk = Path(tempfile.mkdtemp()) / 'app-release.apk'
        self.apk.write_bytes(b'a new build')
        for p in (mock.patch.object(stages.time, 'sleep'), mock.patch.object(ui, 'ok')):
            p.start()
            self.addCleanup(p.stop)

    def test_the_app_reads_this_file(self):
        kt = UPDATER.read_text()
        self.assertIn(f'const val SOURCE_FILE = "{stages.SOURCE_FILE}"', kt)
        self.assertIn('File(ctx.createDeviceProtectedStorageContext().filesDir, SOURCE_FILE)', kt)

    def test_the_script(self):
        adb = FakeAdb()
        stages.update_source(adb, 'me/mine')
        self.assertEqual(adb.pushed, {'/data/local/tmp/croissant-update-source': 'me/mine\n'})
        [sh] = adb.scripts('update-source')
        self.assertEqual(sh.splitlines(), [
            'set -e',
            f'APPDIR={APPDIR}',
            'U=$(stat -c %u $APPDIR)',
            'C=$(stat -c %C $APPDIR)',
            'mkdir -p $APPDIR/files',
            'mv -f /data/local/tmp/croissant-update-source $APPDIR/files/update-source',
            'chown $U:$U $APPDIR/files $APPDIR/files/update-source',
            'chmod 0600 $APPDIR/files/update-source',
            'chcon $C $APPDIR/files $APPDIR/files/update-source',
        ])
        self.assertIn(f'{APPDIR}/files/update-source', sh.replace('$APPDIR', APPDIR))
        with tempfile.NamedTemporaryFile('w', suffix='.sh') as f:
            f.write(sh)
            f.flush()
            self.assertEqual(subprocess.run(['sh', '-n', f.name]).returncode, 0)

    def test_owner_and_label_as_the_fonts_get(self):
        adb = FakeAdb()
        stages.update_source(adb, 'me/mine')
        with mock.patch.object(stages, 'font_on_device', side_effect=[False, True]), \
                mock.patch.object(font, 'require'), mock.patch.dict(font.FILES, clear=True):
            stages.fonts(stages.Ctx(adb, None))
        [source], [fonts] = adb.scripts('update-source'), adb.scripts('fonts')
        self.assertEqual(source.splitlines()[:4], fonts.splitlines()[:4])

    def test_restarted_after_the_compile_and_the_file(self):
        adb = FakeAdb(owner=True)
        ctx = stages.Ctx(adb, None, plan.Plan(update_repo='me/mine'))
        stages.app(ctx, self.apk)
        self.assertEqual(adb.scripts('restart-app'), [])          # not before the stages are done
        stages.restart_app(ctx)
        self.assertEqual(adb.scripts('restart-app'), [f'killall {paths.PACKAGE}'])
        compiled = adb.index('shell', f'cmd package compile -m speed -f {paths.PACKAGE}')
        self.assertLess(compiled, adb.index('push', 'croissant-update-source'))
        self.assertLess(adb.index('script', 'update-source'), adb.index('script', 'restart-app'))

    def test_once_with_the_fonts(self):
        adb = FakeAdb(owner=True)
        ctx = stages.Ctx(adb, None, plan.Plan(update_repo='me/mine'))
        stages.app(ctx, self.apk)
        with mock.patch.object(stages, 'font_on_device', side_effect=[False, True]), \
                mock.patch.object(font, 'require'), mock.patch.dict(font.FILES, clear=True):
            stages.fonts(ctx)
        stages.restart_app(ctx)
        stages.restart_app(ctx)
        self.assertEqual(len(adb.scripts('restart-app')), 1)

    def test_the_owner_reboot_is_the_restart(self):
        adb = FakeAdb(owner=False)
        ctx = stages.Ctx(adb, None, plan.Plan(update_repo='me/mine'))
        stages.app(ctx, self.apk)
        stages.restart_app(ctx)
        self.assertEqual(adb.scripts('restart-app'), [])
        self.assertLess(adb.index('script', 'update-source'), adb.index('shell', 'am start'))
        self.assertLess(adb.index('script', 'update-source'), adb.index('reboot', ''))

    def test_no_repo_hands_nothing(self):
        adb = FakeAdb(owner=True)
        ctx = stages.Ctx(adb, None)                               # ./croissant app install
        stages.app(ctx, self.apk)
        stages.restart_app(ctx)
        self.assertEqual((adb.pushed, adb.scripts('update-source')), ({}, []))
        self.assertEqual(len(adb.scripts('restart-app')), 1)

    def _app_install(self, adb, path, mode='android'):
        args = types.SimpleNamespace(serial=None, path=str(path))
        with mock.patch.object(cli, '_tools', return_value=(adb, None)), \
                mock.patch.object(cli.device, 'locate', return_value=types.SimpleNamespace(mode=mode)), \
                mock.patch.object(cli.device, 'on_gsi', return_value=True), \
                mock.patch.object(adb, 'root', create=True), mock.patch.object(updates, 'look') as look:
            cli.cmd_app_install(args)
        look.assert_not_called()

    def test_app_install_puts_the_apk_on_and_leaves_the_source(self):
        adb = FakeAdb(owner=True)
        self._app_install(adb, self.apk)
        self.assertEqual((adb.pushed, adb.scripts('update-source')), ({}, []))
        self.assertEqual(len(adb.scripts('restart-app')), 1)

    def test_app_install_needs_an_installed_r1_before_building(self):
        with mock.patch.object(cli.apk, 'build') as build, self.assertRaises(ui.Abort):
            self._app_install(FakeAdb(), self.apk.parent, mode='lk')
        build.assert_not_called()

    def test_app_install_builds_a_checkout_from_its_root_or_android(self):
        with tempfile.TemporaryDirectory() as d:
            android = Path(d) / 'android'
            (android / 'app').mkdir(parents=True)
            (android / 'gradlew').write_text('')
            for given in (d, android):
                with mock.patch.object(apk, 'build', return_value=self.apk) as build:
                    self.assertEqual(apk.local(str(given)), self.apk)
                build.assert_called_once_with(android.resolve())
            with self.assertRaises(ui.Abort):             # a folder that is neither
                apk.local(str(android / 'app'))

    def test_app_install_takes_no_release(self):
        with self.assertRaises(ui.Abort):
            apk.local('latest')


if __name__ == '__main__':
    unittest.main()
