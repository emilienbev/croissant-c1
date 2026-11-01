"""croissant backup and restore: the app's answer to the receiver, the zip checked against its
manifest, the pick list, what is pushed and how, the outcome the app writes, and the offer
ahead of a reflash.

  uv run --project installer python -m unittest discover -s installer/tests

No R1 needed: a stand-in plays adb, and the folder of backups is a temporary one.
"""
import contextlib
import hashlib
import itertools
import json
import re
import stat
import tempfile
import unittest
import zipfile
from datetime import datetime
from pathlib import Path
from unittest import mock

import scratch  # noqa: F401  (the cache in a temporary directory, before croissant)
from croissant import archive, backup, backup_config as conf, cli, device, paths, restore, ui
from test_updates import flags

THIS, OTHER = 'R1TEST0001', 'R1TEST0002'
FILES = {'prefs.json': b'{}', 'keys.json': b'{"version": 1, "keys": {}}', 'files/reminders.json': b'[]'}
FILES_DIR = f'/data/user_de/0/{paths.PACKAGE}/files'


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def make_zip(path: Path, entries=FILES, **manifest) -> Path:
    """A backup as the app writes it: manifest.json listing every other entry with its sha256.
    [manifest] replaces fields of the manifest, to make it lie."""
    body = {'format': 1, 'app': paths.PACKAGE, 'version': '0.1.0', 'versionCode': 100, 'serial': THIS,
            'date': '2026-11-01T14:02:31+01:00', 'files': {n: sha(b) for n, b in entries.items()}} | manifest
    with zipfile.ZipFile(path, 'w') as zf:
        zf.writestr('manifest.json', json.dumps(body))
        for n, b in entries.items():
            zf.writestr(n, b)
    return path


def lying(path: Path) -> Path:
    """A zip whose manifest gives prefs.json another sha256 than its content has."""
    return make_zip(path, {'prefs.json': b'{}'}, files={'prefs.json': sha(b'other')})


def answered(stem: str) -> str:
    return ('Broadcasting: Intent { act=com.bleural.croissant.action.BACKUP cmp=... (has extras) }\n'
            f'Broadcast completed: result=-1, data="{FILES_DIR}/backup-out/{stem}.zip"')


def failed(why: str):
    return lambda stem: f'Broadcasting: Intent {{ ... }}\nBroadcast completed: result=1, data="{why}"'


NO_ANSWER = lambda stem: 'Broadcasting: Intent { cmp=... }\nBroadcast completed: result=0'


class FakeAdb:
    """Plays adb for backup and restore: every call in order, the app's answer to the receiver,
    the zip a pull brings, and the outcome the app writes."""

    def __init__(self, answer=answered, zip_bytes=None, root=True, outcomes=(), left='', sum_wrong=False):
        self.serial, self.answer, self.zip_bytes, self.has_root = THIS, answer, zip_bytes, root
        self.outcomes, self.left, self.sum_wrong = list(outcomes), left, sum_wrong
        self.calls, self.pushed, self.stem = [], {}, None

    def root(self):
        return self.has_root

    def script(self, text, name, timeout=300, **kw):
        self.calls.append(('script', name, text))
        if name == 'backup':
            self.stem = re.search(r'--es name (\S+)', text)[1]
            return self.answer(self.stem)
        if name == 'restore':
            return ('0' * 64 if self.sum_wrong else next(iter(self.pushed.values()))) + '  restored.zip'
        return ''

    def sh(self, cmd, **kw):
        self.calls.append(('sh', cmd))
        if cmd.endswith('restore-outcome.json"'):
            return self.outcomes.pop(0) if len(self.outcomes) > 1 else (self.outcomes[0] if self.outcomes else '')
        if cmd.startswith('su -c "ls '):
            return self.left
        return ''

    def push(self, local, remote, timeout=600):
        self.pushed[remote] = sha(Path(local).read_bytes())
        self.calls.append(('push', remote))

    def pull(self, remote, local, timeout=600):
        self.calls.append(('pull', remote))
        if self.zip_bytes is None:
            raise ui.Abort(f'adb pull {remote} failed: remote object does not exist')
        Path(local).write_bytes(self.zip_bytes)

    def named(self, kind, name=None):
        return [c for c in self.calls if c[0] == kind and (name is None or c[1] == name)]

    def removed(self):
        return [c[1] for c in self.calls if c[0] == 'sh' and 'rm -f ' in c[1]]


class Folder(unittest.TestCase):
    """A temporary folder of backups, and a quiet terminal that remembers what it was told."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.folder = Path(self.tmp.name) / 'backups'
        self.said = []
        patches = [mock.patch.object(conf, 'BACKUPS', self.folder),
                   mock.patch.object(ui, 'spinner', side_effect=lambda m: contextlib.nullcontext())]
        patches += [mock.patch.object(ui, kind, side_effect=lambda m, kind=kind: self.said.append((kind, m)))
                    for kind in ('step', 'ok', 'info', 'warn', 'done')]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)

    def keep(self, name, **kw) -> Path:
        self.folder.mkdir(mode=0o700, exist_ok=True)
        return make_zip(self.folder / name, **kw)

    def told(self, kind):
        return [m for k, m in self.said if k == kind]


class Names(Folder):
    NOW = datetime(2026, 11, 1, 14, 2, 31)

    def test_the_computers_clock_names_it(self):
        self.assertEqual(archive.next_name(self.NOW), '2026-11-01-1402.zip')

    def test_a_second_in_the_same_minute_is_2_then_3(self):
        self.keep('2026-11-01-1402.zip')
        self.assertEqual(archive.next_name(self.NOW), '2026-11-01-1402-2.zip')
        self.keep('2026-11-01-1402-2.zip')
        self.assertEqual(archive.next_name(self.NOW), '2026-11-01-1402-3.zip')

    def test_a_name_being_copied_by_the_server_is_taken(self):
        self.keep('2026-11-01-1402.zip.part')
        self.assertEqual(archive.next_name(self.NOW), '2026-11-01-1402-2.zip')

    def test_the_name_is_the_servers_pattern(self):
        for ok in ('2026-11-01-1402.zip', '2026-11-01-1402-2.zip', '2026-11-01-1402-10.zip'):
            self.assertTrue(archive.NAME.fullmatch(ok), ok)
        for bad in ('2026-11-01-1402.zip.part', '2026-11-01-14.zip', '../2026-11-01-1402.zip', 'backup.zip'):
            self.assertFalse(archive.NAME.fullmatch(bad), bad)


class Whole(Folder):
    """A zip is kept or pushed only when its manifest lists every entry and every sha256 matches."""

    def check(self, entries=FILES, **manifest):
        return archive.verify(make_zip(self.folder_made() / 'x.zip', entries, **manifest))

    def folder_made(self):
        self.folder.mkdir(exist_ok=True)
        return self.folder

    def test_a_whole_zip(self):
        self.assertEqual(sorted(self.check()['files']), sorted(FILES))

    def test_a_wrong_hash_names_the_entry_and_both_hashes(self):
        with self.assertRaises(archive.Damaged) as e:
            self.check(files={n: sha(b) for n, b in FILES.items()} | {'prefs.json': sha(b'other')})
        self.assertIn(f'prefs.json is {sha(FILES["prefs.json"])}, the manifest says {sha(b"other")}', str(e.exception))

    def test_an_entry_the_manifest_does_not_list(self):
        with self.assertRaises(archive.Damaged) as e:
            self.check(files={n: sha(b) for n, b in FILES.items() if n != 'keys.json'})
        self.assertIn('in the zip but not in the manifest: keys.json', str(e.exception))

    def test_an_entry_the_zip_lacks(self):
        with self.assertRaises(archive.Damaged) as e:
            self.check(files={n: sha(b) for n, b in FILES.items()} | {'files/photo.jpg': sha(b'x')})
        self.assertIn('listed in the manifest but not in the zip: files/photo.jpg', str(e.exception))

    def test_an_entry_twice(self):
        path = self.folder_made() / 'x.zip'
        make_zip(path)
        with zipfile.ZipFile(path, 'a') as zf, self.assertWarns(UserWarning):
            zf.writestr('prefs.json', b'{}')
        with self.assertRaises(archive.Damaged) as e:
            archive.verify(path)
        self.assertIn('more than once: prefs.json', str(e.exception))

    def test_the_manifest_is_needed_and_must_be_one(self):
        path = self.folder_made() / 'x.zip'
        with zipfile.ZipFile(path, 'w') as zf:
            zf.writestr('prefs.json', b'{}')
        with self.assertRaises(archive.Damaged) as e:
            archive.verify(path)
        self.assertIn('has no manifest.json', str(e.exception))
        for kw, why in (({'format': 2}, 'format 2'), ({'serial': 7}, 'serial 7'), ({'version': None}, 'version None'),
                        ({'files': []}, 'files []'), ({'files': {'prefs.json': 'zz'}}, "sha256 'zz'")):
            with self.assertRaises(archive.Damaged) as e:
                self.check(**kw)
            self.assertIn(why, str(e.exception))

    def test_not_a_zip(self):
        path = self.folder_made() / 'x.zip'
        path.write_bytes(b'not a zip')
        with self.assertRaises(archive.Damaged) as e:
            archive.verify(path)
        self.assertIn('x.zip is not a readable zip', str(e.exception))

    def test_a_byte_flipped_inside_an_entry_is_found(self):
        path = self.folder_made() / 'x.zip'
        make_zip(path, {'prefs.json': b'{"volume": 40, "pad": "' + b'a' * 400 + b'"}'})
        raw = bytearray(path.read_bytes())
        i = raw.index(b'"volume"') + 3                    # a stored byte flipped inside the entry
        raw[i] ^= 0xFF
        path.write_bytes(raw)
        with self.assertRaises(archive.Damaged):
            archive.verify(path)


class Receiver(Folder):
    """The three things the app's receiver answers, and what is asked of it."""
    NAME = '2026-11-01-1402.zip'

    def make(self, answer, **kw):
        adb = FakeAdb(answer=answer, zip_bytes=make_zip(Path(self.tmp.name) / 'src.zip').read_bytes(), **kw)
        return adb, lambda: backup.make(adb, self.NAME)

    def test_it_is_asked_as_root_for_the_name_picked(self):
        adb, go = self.make(answered)
        go()
        text = adb.named('script', 'backup')[0][2]
        self.assertEqual(text, f'am broadcast -n {paths.PACKAGE}/{paths.NAMESPACE}.backup.BackupReceiver '
                               f'-a {paths.NAMESPACE}.action.BACKUP --es name 2026-11-01-1402 2>&1\n')

    def test_result_ok_with_the_path_is_a_backup_kept(self):
        adb, go = self.make(answered)
        path = go()
        self.assertEqual(path, self.folder / self.NAME)
        self.assertEqual(adb.named('pull')[0][1], f'/data/local/tmp/croissant-backup-{self.NAME}')
        self.assertIn(f'cp {FILES_DIR}/backup-out/{self.NAME} /data/local/tmp/croissant-backup-{self.NAME}',
                      adb.named('script', 'backup-copy')[0][2])

    def test_result_1_is_the_apps_reason(self):
        adb, go = self.make(failed('the disk on this C1 is full'))
        with self.assertRaises(ui.Abort) as e:
            go()
        self.assertNotIsInstance(e.exception, backup.NoApp)
        self.assertEqual(str(e.exception), 'the app could not make the backup: the disk on this C1 is full')
        self.assertEqual(adb.named('pull'), [])

    def test_result_0_with_no_data_is_no_app_and_says_so(self):
        adb, go = self.make(NO_ANSWER)
        with self.assertRaises(backup.NoApp) as e:
            go()
        self.assertIn(f'{paths.PACKAGE} did not answer: either it is older than backups or it is not running',
                      str(e.exception))
        self.assertIn('result 0, no data', str(e.exception))
        self.assertEqual(adb.named('pull'), [])

    def test_no_root_is_said_before_anything_is_asked(self):
        adb, go = self.make(answered, root=False)
        with self.assertRaises(backup.NoApp) as e:
            go()
        self.assertIn('root is not granted', str(e.exception))
        self.assertEqual(adb.calls, [])

    def test_an_answer_it_cannot_read_is_shown_whole(self):
        adb, go = self.make(lambda stem: 'Error: Activity manager is not running')
        with self.assertRaises(ui.Abort) as e:
            go()
        self.assertIn("'Error: Activity manager is not running'", str(e.exception))

    def test_a_path_that_is_not_the_backup_asked_for_is_not_followed(self):
        for data in ('/system/bin/sh', f'{FILES_DIR}/backup-out/2026-11-01-1403.zip', f'/data/x;reboot/backup-out/{self.NAME}'):
            adb, go = self.make(lambda stem, data=data: f'Broadcast completed: result=-1, data="{data}"')
            with self.assertRaises(ui.Abort) as e:
                go()
            self.assertIn(repr(data), str(e.exception))
            self.assertEqual(adb.named('pull'), [])
            self.assertNotIn(data, ' '.join(adb.removed()))


class Backup(Folder):
    NAME = '2026-11-01-1402.zip'

    def adb(self, **kw):
        return FakeAdb(zip_bytes=make_zip(Path(self.tmp.name) / 'src.zip').read_bytes(), **kw)

    def test_kept_mode_600_in_a_folder_of_700_and_both_are_said(self):
        with mock.patch.object(archive, 'next_name', return_value=self.NAME):
            backup.run(self.adb())
        kept = self.folder / self.NAME
        self.assertEqual(stat.S_IMODE(kept.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.folder.stat().st_mode), 0o700)
        self.assertEqual(self.told('done'), [f'Kept {self.NAME} in {self.folder}.'])
        self.assertEqual(sorted(p.name for p in self.folder.iterdir()), [self.NAME])      # no .part left

    def test_an_open_folder_is_closed_to_others(self):
        self.folder.mkdir(mode=0o755)
        backup.make(self.adb(), self.NAME)
        self.assertEqual(stat.S_IMODE(self.folder.stat().st_mode), 0o700)

    def test_the_copies_on_the_c1_are_deleted_after_a_backup(self):
        adb = self.adb()
        backup.make(adb, self.NAME)
        self.assertEqual(adb.removed(), [f'su -c "rm -f {FILES_DIR}/backup-out/{self.NAME} '
                                        f'/data/local/tmp/croissant-backup-{self.NAME}"'])

    def test_a_damaged_zip_is_refused_not_kept_and_the_copies_still_go(self):
        adb = FakeAdb(zip_bytes=lying(Path(self.tmp.name) / 'src.zip').read_bytes())
        with self.assertRaises(archive.Damaged) as e:
            backup.make(adb, self.NAME)
        self.assertIn('prefs.json', str(e.exception))
        self.assertEqual(list(self.folder.iterdir()), [])
        self.assertEqual(len(adb.removed()), 1)

    def test_a_failed_pull_is_said_and_the_copies_still_go(self):
        adb = FakeAdb(zip_bytes=None)
        with self.assertRaises(ui.Abort) as e:
            backup.make(adb, self.NAME)
        self.assertIn('remote object does not exist', str(e.exception))
        self.assertEqual(len(adb.removed()), 1)

    def test_the_copies_go_when_the_app_could_not_and_when_nothing_answered(self):
        for answer, error in ((failed('no space'), ui.Abort), (NO_ANSWER, backup.NoApp)):
            adb = FakeAdb(answer=answer)
            with self.assertRaises(error):
                backup.make(adb, self.NAME)
            self.assertEqual(len(adb.removed()), 1)

    def test_a_copy_that_will_not_delete_is_said_with_the_paths(self):
        adb = self.adb()
        real = adb.sh
        adb.sh = lambda cmd, **kw: ui.fail('rm: Permission denied') if 'rm -f ' in cmd else real(cmd, **kw)
        backup.make(adb, self.NAME)
        self.assertIn(f'{FILES_DIR}/backup-out/{self.NAME}', self.told('warn')[0])
        self.assertIn('Permission denied', self.told('warn')[0])

    def test_a_backup_is_never_replaced(self):
        adb = self.adb()
        real = adb.pull

        def pull(remote, local, timeout=600):          # croissant-server saves the same name meanwhile
            real(remote, local)
            make_zip(self.folder / self.NAME, version='9.9.9')
        adb.pull = pull
        with self.assertRaises(ui.Abort) as e:
            backup.make(adb, self.NAME)
        self.assertIn('appeared', str(e.exception))
        self.assertEqual(archive.read_manifest(self.folder / self.NAME)['version'], '9.9.9')
        self.assertEqual([p.name for p in self.folder.iterdir()], [self.NAME])


class Listing(Folder):
    def test_newest_first_by_the_stamp_then_the_number(self):
        for name in ('2026-10-31-0900.zip', '2026-11-01-1402.zip', '2026-11-01-1402-2.zip',
                     '2026-11-01-1402-10.zip', '2026-11-01-0830.zip'):
            self.keep(name)
        self.keep('notes.txt').write_text('not a backup')
        self.keep('2026-11-01-1500.zip.part')
        self.assertEqual([b.path.name for b in archive.listing()],
                         ['2026-11-01-1402-10.zip', '2026-11-01-1402-2.zip', '2026-11-01-1402.zip',
                          '2026-11-01-0830.zip', '2026-10-31-0900.zip'])

    def test_no_folder_no_backups(self):
        self.assertEqual(archive.listing(), [])

    def test_the_list_shows_the_date_from_the_name_the_version_and_whose_it_is(self):
        self.keep('2026-11-01-1402.zip', version='0.1.0', serial=THIS)
        self.keep('2026-10-30-0815.zip', version='0.0.9', serial=OTHER)
        self.keep('2026-11-01-1402-2.zip', version='0.1.1', serial=THIS)
        rows = restore.choices(archive.listing(), THIS)
        self.assertEqual([r[0] for r in rows], ['2026-11-01 14:02 (2)  0.1.1       this C1',
                                                '2026-11-01 14:02      0.1.0       this C1',
                                                '2026-10-30 08:15      0.0.9       another C1'])
        self.assertEqual([r[2] for r in rows], [None, None, None])

    def test_a_zip_with_no_readable_manifest_is_listed_with_why_and_cannot_be_picked(self):
        self.keep('2026-11-01-1402.zip')
        (self.folder / '2026-11-01-1500.zip').write_bytes(b'broken')
        rows = restore.choices(archive.listing(), THIS)
        self.assertEqual(rows[0][0], '2026-11-01 15:00  cannot be restored')
        self.assertIn('2026-11-01-1500.zip is not a readable zip', rows[0][2])
        self.assertIsNone(rows[1][2])


class Restore(Folder):
    NAME = '2026-11-01-1402.zip'
    OK = json.dumps({'name': NAME, 'ok': True})

    def setUp(self):
        super().setUp()
        for p in (mock.patch.object(restore.time, 'sleep'),
                  mock.patch.object(conf, 'OUTCOME_WAIT_S', 180)):
            p.start()
            self.addCleanup(p.stop)

    def go(self, adb, given=None, serial=THIS):
        return restore.run(adb, serial, str(given) if given else None)

    def test_the_zip_goes_in_as_the_apps_own_file_with_its_label(self):
        zip_path = self.keep(self.NAME)
        adb = FakeAdb(outcomes=[self.OK])
        self.go(adb, zip_path)
        remote = f'/data/local/tmp/croissant-restore-{self.NAME}'
        self.assertEqual(adb.pushed, {remote: sha(zip_path.read_bytes())})
        script = adb.named('script', 'restore')[0][2]
        for line in (f'APPDIR=/data/user_de/0/{paths.PACKAGE}',
                     'U=$(stat -c %u $APPDIR)', 'C=$(stat -c %C $APPDIR)',
                     f'rm -f {FILES_DIR}/restore/*.zip',                           # exactly one zip there
                     f'rm -f {FILES_DIR}/restore-outcome.json',                    # an old outcome is not this one
                     f'mv -f {remote} {FILES_DIR}/restore/{self.NAME}',
                     f'chown $U:$U $APPDIR/files {FILES_DIR}/restore {FILES_DIR}/restore/{self.NAME}',
                     f'chmod 0600 {FILES_DIR}/restore/{self.NAME}',
                     f'chcon $C $APPDIR/files {FILES_DIR}/restore {FILES_DIR}/restore/{self.NAME}'):
            self.assertIn(line + '\n', script)
        self.assertTrue(script.startswith('set -eu\n'))

    def test_pushed_then_restarted_then_the_outcome_is_read(self):
        adb = FakeAdb(outcomes=['', self.OK])
        self.go(adb, self.keep(self.NAME))
        kinds = [(c[0], c[1]) for c in adb.calls if c[0] != 'sh' or 'cat' in c[1]]
        self.assertEqual(kinds[:4], [('push', f'/data/local/tmp/croissant-restore-{self.NAME}'),
                                     ('script', 'restore'), ('script', 'restart-app'),
                                     ('sh', f'su -c "cat {FILES_DIR}/restore-outcome.json"')])
        self.assertEqual(adb.named('script', 'restart-app')[0][2], f'killall {paths.PACKAGE}')
        self.assertEqual(self.told('done'), [f'Restored {self.NAME}.'])

    def test_the_apps_refusal_is_printed_and_fails(self):
        refusal = {'name': self.NAME, 'ok': False, 'error': 'this app is 0.1.0 (100), the backup is 0.2.0 (200)'}
        with self.assertRaises(ui.Abort) as e:
            self.go(FakeAdb(outcomes=[json.dumps(refusal)]), self.keep(self.NAME, version='0.2.0'))
        self.assertEqual(str(e.exception), f'the app did not restore {self.NAME}: '
                                           'this app is 0.1.0 (100), the backup is 0.2.0 (200)')

    def test_an_outcome_still_being_written_is_waited_for(self):
        adb = FakeAdb(outcomes=['{"name": "2026', '', self.OK])
        self.go(adb, self.keep(self.NAME))
        self.assertEqual(len(adb.named('sh', f'su -c "cat {FILES_DIR}/restore-outcome.json"')), 3)

    def test_no_outcome_in_time_fails_with_what_was_seen(self):
        adb = FakeAdb(outcomes=[''], left=self.NAME)
        with mock.patch.object(restore.time, 'monotonic', side_effect=itertools.count(0, 100)), \
                self.assertRaises(ui.Abort) as e:
            self.go(adb, self.keep(self.NAME))
        msg = str(e.exception)
        self.assertIn('the app wrote no restore outcome in 180 s', msg)
        self.assertIn("restore-outcome.json read: ''", msg)
        self.assertIn(f'restore/ on the C1 holds: {self.NAME}', msg)
        self.assertIn('so the app did not take it', msg)
        self.assertEqual(len(adb.named('sh', f'su -c "cat {FILES_DIR}/restore-outcome.json"')), 2)

    def test_no_outcome_with_the_zip_gone_says_the_app_took_it(self):
        with mock.patch.object(restore.time, 'monotonic', side_effect=itertools.count(0, 100)), \
                self.assertRaises(ui.Abort) as e:
            self.go(FakeAdb(), self.keep(self.NAME))
        self.assertIn('restore/ on the C1 holds: nothing', str(e.exception))
        self.assertIn('the app took it but did not finish', str(e.exception))

    def test_a_damaged_zip_is_refused_before_anything_is_pushed(self):
        adb = FakeAdb()
        self.folder.mkdir()
        bad = lying(self.folder / self.NAME)
        with self.assertRaises(archive.Damaged):
            self.go(adb, bad)
        self.assertEqual(adb.calls, [])

    def test_without_root_nothing_is_pushed(self):
        adb = FakeAdb(root=False)
        with self.assertRaises(ui.Abort) as e:
            self.go(adb, self.keep(self.NAME))
        self.assertIn('needs root', str(e.exception))
        self.assertEqual(adb.calls, [])

    def test_the_staged_file_does_not_stay_when_the_script_fails(self):
        adb = FakeAdb()
        real = adb.script
        adb.script = lambda text, name, **kw: ui.fail('stat: No such file') if name == 'restore' else real(text, name, **kw)
        with self.assertRaises(ui.Abort):
            self.go(adb, self.keep(self.NAME))
        self.assertEqual(adb.removed(), [f'rm -f /data/local/tmp/croissant-restore-{self.NAME}'])
        self.assertEqual(adb.named('script', 'restart-app'), [])

    def test_a_zip_that_arrives_changed_is_not_restarted_into(self):
        adb = FakeAdb(sum_wrong=True)
        with self.assertRaises(ui.Abort) as e:
            self.go(adb, self.keep(self.NAME))
        self.assertIn('arrived', str(e.exception))
        self.assertEqual(adb.named('script', 'restart-app'), [])

    def test_a_path_must_exist_and_be_named_as_the_app_takes_it(self):
        with self.assertRaises(ui.Abort) as e:
            self.go(FakeAdb(), self.folder / 'missing.zip')
        self.assertIn('no such file', str(e.exception))
        odd = self.keep('my backup.zip')
        with self.assertRaises(ui.Abort) as e:
            self.go(FakeAdb(), odd)
        self.assertIn('named like 2026-11-01-1402.zip', str(e.exception))

    def test_the_pick_comes_from_the_list_and_defaults_to_the_newest(self):
        self.keep('2026-10-30-0815.zip', serial=OTHER)
        newest = self.keep(self.NAME)
        asked = {}

        def pick(message, rows, default=None):
            asked.update(rows=rows, default=default)
            return rows[1][1]                                  # the older one
        adb = FakeAdb(outcomes=[json.dumps({'name': '2026-10-30-0815.zip', 'ok': True})])
        with mock.patch.object(ui, 'INTERACTIVE', True), mock.patch.object(ui, 'select', side_effect=pick):
            self.go(adb)
        self.assertEqual(asked['default'], newest)
        self.assertTrue(asked['rows'][0][0].endswith('this C1'))
        self.assertTrue(asked['rows'][1][0].endswith('another C1'))
        self.assertEqual(list(adb.pushed), ['/data/local/tmp/croissant-restore-2026-10-30-0815.zip'])

    def test_nothing_to_pick_from_is_said(self):
        with mock.patch.object(ui, 'INTERACTIVE', True), self.assertRaises(ui.Abort) as e:
            self.go(FakeAdb())
        self.assertIn(f'no backups in {self.folder}', str(e.exception))
        self.assertIn(f'{paths.COMMAND} backup', str(e.exception))

    def test_with_nothing_to_ask_the_backup_must_be_named(self):
        self.keep(self.NAME)
        with mock.patch.object(ui, 'INTERACTIVE', False), self.assertRaises(ui.Abort) as e:
            self.go(FakeAdb())
        self.assertIn('name the backup to restore', str(e.exception))

    def test_only_unreadable_backups_is_said_with_why(self):
        (self.keep(self.NAME).parent / '2026-11-01-1500.zip').write_bytes(b'broken')
        (self.folder / self.NAME).write_bytes(b'broken too')
        with mock.patch.object(ui, 'INTERACTIVE', True), self.assertRaises(ui.Abort) as e:
            self.go(FakeAdb())
        self.assertIn('can be restored', str(e.exception))
        self.assertIn('2026-11-01-1500.zip is not a readable zip', str(e.exception))


class BeforeErase(Folder):
    """The offer ahead of a reflash."""
    NAME = '2026-11-01-1402.zip'

    def setUp(self):
        super().setUp()
        p = mock.patch.object(archive, 'next_name', return_value=self.NAME)
        p.start()
        self.addCleanup(p.stop)
        self.zip = make_zip(Path(self.tmp.name) / 'src.zip').read_bytes()

    def offer(self, adb, answer=True, interactive=True):
        with mock.patch.object(ui, 'INTERACTIVE', interactive), \
                mock.patch.object(ui, 'confirm', return_value=answer) as confirm:
            backup.before_erase(adb)
        return confirm

    def test_accepted_runs_the_backup(self):
        adb = FakeAdb(zip_bytes=self.zip)
        confirm = self.offer(adb)
        self.assertEqual(confirm.call_args.kwargs, {'default': True})
        self.assertTrue((self.folder / self.NAME).is_file())
        self.assertEqual(len(adb.removed()), 1)
        self.assertEqual(self.told('ok')[-1], f'kept {self.folder / self.NAME}')

    def test_skipped_pulls_nothing_and_deletes_what_the_app_made(self):
        adb = FakeAdb(zip_bytes=self.zip)
        self.offer(adb, answer=False)
        self.assertEqual((adb.named('pull'), list(self.folder.glob('*'))), ([], []))
        self.assertEqual(len(adb.removed()), 1)
        self.assertEqual(self.told('info')[-1], 'No backup made.')

    def test_with_no_app_answering_it_says_none_could_be_made_and_goes_on(self):
        for adb in (FakeAdb(answer=NO_ANSWER), FakeAdb(root=False)):
            confirm = self.offer(adb)                             # does not raise
            confirm.assert_not_called()
        self.assertEqual(len(self.told('warn')), 2)
        self.assertIn('No backup could be made, so it goes on without one: ' + paths.PACKAGE + ' did not answer',
                      self.told('warn')[0])
        self.assertIn('so it goes on without one: root is not granted', self.told('warn')[1])

    def test_an_app_that_answers_but_fails_stops_before_the_erase(self):
        with self.assertRaises(ui.Abort) as e:
            self.offer(FakeAdb(answer=failed('no space')))
        self.assertEqual(str(e.exception), 'the app could not make the backup: no space\n'
                                           'Nothing was erased. Run again with --no-backup to go on without one.')

    def test_a_damaged_copy_stops_before_the_erase(self):
        with self.assertRaises(ui.Abort) as e:
            self.offer(FakeAdb(zip_bytes=lying(Path(self.tmp.name) / 'bad.zip').read_bytes()))
        self.assertIn('Nothing was erased', str(e.exception))

    def test_with_nothing_to_ask_the_default_is_yes(self):
        adb = FakeAdb(zip_bytes=self.zip)
        confirm = self.offer(adb, interactive=False)
        confirm.assert_not_called()
        self.assertTrue((self.folder / self.NAME).is_file())


class InstallOrder(unittest.TestCase):
    """cmd_install: the offer comes before the erase, only for a reflash of an R1 running a GSI."""

    def install(self, on=True, will_flash=True, **kw):
        calls = mock.Mock()
        args = flags(reflash=will_flash, yes=True, no_backup=kw.get('no_backup', False), serial=None, font=None)
        where = device.Where('android', THIS)
        with mock.patch.object(cli, 'stages') as stages, mock.patch.object(cli, 'backup') as bk, \
                mock.patch.object(cli, '_tools', return_value=(mock.Mock(), mock.Mock())), \
                mock.patch.object(cli.device, 'locate', return_value=where), \
                mock.patch.object(cli.device, 'on_gsi', return_value=on), \
                mock.patch.object(cli.plan, 'unasked', side_effect=lambda p, *a: p), \
                mock.patch.object(cli.plan, 'show'), mock.patch.object(cli.prereqs, 'ensure_usb'), \
                mock.patch.object(cli.font, 'ensure'), mock.patch.object(ui, 'INTERACTIVE', False), \
                mock.patch.object(ui, 'hello'), mock.patch.object(ui, 'step'), mock.patch.object(ui, 'info'), \
                mock.patch.object(ui, 'done'), \
                mock.patch.object(ui, 'spinner', side_effect=lambda m: contextlib.nullcontext()):
            calls.attach_mock(bk.before_erase, 'backup')
            calls.attach_mock(stages.flash, 'flash')
            cli.cmd_install(args)
        return [c[0] for c in calls.mock_calls]

    def test_the_offer_comes_first(self):
        self.assertEqual(self.install(), ['backup', 'flash'])

    def test_no_backup_flag_skips_it(self):
        self.assertEqual(self.install(no_backup=True), ['flash'])

    def test_a_first_install_has_nothing_to_back_up(self):
        self.assertEqual(self.install(on=False, will_flash=True), ['flash'])


if __name__ == '__main__':
    unittest.main()
