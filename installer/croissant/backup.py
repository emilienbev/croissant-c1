"""croissant backup: the app's archive over USB into this computer's folder of backups.

The app builds the zip (its BackupReceiver, reached by root, as the receiver is not exported)
and the CLI carries it: root copies it to the shell's folder, adb pulls it, it is checked
against its manifest, and the copies on the C1 are deleted whatever happened.
"""
import re
from pathlib import Path

from . import archive, backup_config as conf, paths, ui
from .paths import STAGE
from .tools import Adb

# am broadcast ends with the ordered broadcast's result code, and its data when there is some.
REPLY = re.compile(r'^Broadcast completed: result=(-?\d+)(.*)\Z', re.M | re.S)
DATA = ', data="'
RESULT_OK = -1          # Activity.RESULT_OK: the data is the zip's path on the C1
RESULT_FAILED = 1       # the data is why
SAFE_PATH = r'/[A-Za-z0-9._/-]+/backup-out/{}\.zip'


class NoApp(ui.Abort):
    """Nothing on the C1 can make a backup. Its text says why."""


def run(adb: Adb):
    """The command: a backup of the C1 on USB, kept in the folder."""
    ui.step('Backup')
    path = make(adb, archive.next_name())
    ui.done(f'Kept {path.name} in {path.parent}.')


def before_erase(adb: Adb):
    """The offer ahead of a reflash, which erases what the app keeps. With no app answering, a
    backup cannot be made and the install goes on as it always has. An app that answers but
    fails, or a copy that does not check out, stops the install before anything is erased."""
    ui.step('A backup first')
    try:
        path = make(adb, archive.next_name(), accept=_accepted)
    except NoApp as e:
        ui.warn(f'No backup could be made, so it goes on without one: {e}')
        return
    except ui.Abort as e:
        raise ui.Abort(f'{e}\nNothing was erased. Run again with --no-backup to go on without one.') from e
    if path:
        ui.ok(f'kept {path}')
    else:
        ui.info('No backup made.')


def make(adb: Adb, name: str, accept=lambda: True) -> Path | None:
    """Ask the app for the zip [name], keep it here once it checks out, and delete the copies
    on the C1 whatever happens. [accept] is asked once the app has answered, to keep it or not."""
    if not adb.root():
        raise NoApp('root is not granted to the adb shell, so the app cannot be asked '
                    '(Magisk > Superuser, com.android.shell)')
    stem = name.removesuffix('.zip')
    made = [f'{paths.app_files()}/backup-out/{name}', f'{STAGE}/croissant-backup-{name}']
    try:
        with ui.spinner('asking the app for a backup'):
            remote = _ask(adb, stem)
        made.append(remote)
        if not accept():
            return None
        return _keep(adb, remote, name)
    finally:
        _discard(adb, made)


def _accepted() -> bool:
    if not ui.INTERACTIVE:
        return True            # the question's default; --no-backup is the way out when nothing can be asked
    ui.info('The app answers, so this C1 can be backed up: its settings, provider keys, photos and other files.')
    return ui.confirm('Keep a backup on this computer before erasing it?', default=True)


def _ask(adb: Adb, stem: str) -> str:
    """The zip's path on the C1, from the app's answer to the root broadcast."""
    out = adb.script(f'am broadcast -n {paths.backup_receiver()} -a {paths.NAMESPACE}.action.BACKUP '
                     f'--es name {stem} 2>&1\n', 'backup', timeout=conf.ARCHIVE_TIMEOUT_S)
    m = REPLY.search(out)
    if not m:
        raise ui.Abort(f'the answer to the backup request was not understood: {out!r}')
    result, rest = int(m[1]), m[2].strip()
    if rest and not (rest.startswith(DATA) and rest.endswith('"')):
        raise ui.Abort(f'the answer to the backup request was not understood: {out!r}')
    data = rest[len(DATA):-1] if rest else None
    if result == 0 and data is None:
        raise NoApp(f'{paths.PACKAGE} did not answer: either it is older than backups or it is not running '
                    '(am broadcast reported result 0, no data)')
    if result == RESULT_FAILED:
        raise ui.Abort(f'the app could not make the backup: {data or "no reason given"}')
    if result != RESULT_OK or not data or not re.fullmatch(SAFE_PATH.format(re.escape(stem)), data):
        raise ui.Abort(f'the app answered with result {result}, data {data!r}, '
                       f'not a zip called {stem}.zip in its backup-out folder')
    return data


def _keep(adb: Adb, remote: str, name: str) -> Path:
    """Pull the zip, check it against its manifest, and keep it under [name]."""
    folder = archive.folder()
    staged, part = f'{STAGE}/croissant-backup-{name}', folder / f'{name}.part'
    try:
        with ui.spinner('copying the backup to this computer'):
            adb.script(f'set -e\ncp {remote} {staged}\nchmod 0644 {staged}\n', 'backup-copy')
            adb.pull(staged, part, timeout=conf.TRANSFER_TIMEOUT_S)
            part.chmod(conf.FILE_MODE)
        with ui.spinner('checking it against its manifest'):
            manifest = archive.verify(part)
        ui.ok(f'whole: {len(manifest["files"])} files, every sha256 matches its manifest')
        archive.link(part, folder / name)
    except BaseException:
        part.unlink(missing_ok=True)
        raise
    return folder / name


def _discard(adb: Adb, remote: list[str]):
    """Delete the copies on the C1. A failure to is said, never hidden behind the error before it."""
    gone = ' '.join(dict.fromkeys(remote))
    try:
        adb.sh(f'su -c "rm -f {gone}"')
    except ui.Abort as e:
        ui.warn(f'The copies on the C1 are still there ({gone}): {e}')
