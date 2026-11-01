"""croissant restore: a backup zip from this computer's folder back into the app, over USB.

The app applies it, when it starts: it takes the one zip in its restore/ folder, refuses it
unless it fits (it judges the versions), and writes what happened to restore-outcome.json.
The CLI checks the zip against its manifest, pushes it as the installer pushes the fonts,
restarts the app and prints that outcome.
"""
import json
import time
from pathlib import Path

from . import archive, backup_config as conf, paths, ui
from .paths import STAGE
from .tools import Adb

OUTCOME = 'restore-outcome.json'


def run(adb: Adb, serial: str, given: str | None):
    """The command: [given] (a path), else one picked from the folder of backups."""
    ui.step('Restore')
    if not adb.root():
        ui.fail('restore needs root for the adb shell: Magisk > Superuser, com.android.shell')
    zip_path = _given(given) if given else _pick(serial)
    with ui.spinner(f'checking {zip_path.name} against its manifest'):
        manifest = archive.verify(zip_path)
    ui.ok(f'{zip_path.name} is whole: {len(manifest["files"])} files, version {manifest["version"]}, '
          f'{_whose(manifest, serial)}')
    with ui.spinner('putting it in the app\'s restore folder'):
        _push(adb, zip_path)
    with ui.spinner('starting the app again to apply it'):
        adb.script(f'killall {paths.PACKAGE}', 'restart-app', check=False)
    with ui.spinner('waiting for the app to say how it went'):
        outcome = _outcome(adb, zip_path.name)
    if not outcome['ok']:
        ui.fail(f'the app did not restore {zip_path.name}: {outcome.get("error") or "no reason given"}')
    ui.done(f'Restored {zip_path.name}.')


def choices(backups: list[archive.Backup], serial: str) -> list[tuple]:
    """The pick list, in the order given: (title, path, why it cannot be picked)."""
    width = max((len(b.when) for b in backups), default=0)
    rows = []
    for b in backups:
        if b.problem:
            rows.append((f'{b.when:<{width}}  cannot be restored', b.path, b.problem))
        else:
            rows.append((f'{b.when:<{width}}  {b.manifest["version"]:<10}  {_whose(b.manifest, serial)}', b.path, None))
    return rows


def _whose(manifest: dict, serial: str) -> str:
    return 'this C1' if manifest['serial'] == serial else 'another C1'


def _given(given: str) -> Path:
    path = Path(given).expanduser()
    if not path.is_file():
        ui.fail(f'{path}: no such file')
    if not archive.NAME.fullmatch(path.name):
        ui.fail(f'{path.name}: the app takes a backup named like 2026-11-01-1402.zip. Rename it to that.')
    return path


def _pick(serial: str) -> Path:
    backups = archive.listing()
    if not backups:
        ui.fail(f'no backups in {conf.BACKUPS}. Make one with {paths.COMMAND} backup.')
    rows = choices(backups, serial)
    if all(why for _, _, why in rows):
        ui.fail(f'no backup in {conf.BACKUPS} can be restored:\n' + '\n'.join(why for _, _, why in rows))
    if not ui.INTERACTIVE:
        ui.fail(f'Nothing was changed: name the backup to restore ({conf.BACKUPS}/NAME.zip) when nothing can be asked.')
    first = next(path for _, path, why in rows if not why)
    return ui.select('Restore which backup?', rows, default=first)


def _push(adb: Adb, zip_path: Path):
    """Into restore/, the only zip there, as the app's own file with its label, as the fonts go in."""
    name = zip_path.name
    staged = f'{STAGE}/croissant-restore-{name}'
    adb.push(zip_path, staged, timeout=conf.TRANSFER_TIMEOUT_S)
    try:
        got = _place(adb, name, staged)
    finally:
        adb.sh(f'rm -f {staged}', check=False)        # still there only if the script failed
    want = archive.sha256_file(zip_path)
    if got.split()[:1] != [want]:
        ui.fail(f'{name} arrived in {paths.app_files()}/restore changed: the C1 reads {got!r}, sent sha256 {want}')


def _place(adb: Adb, name: str, staged: str) -> str:
    """The root script that moves the staged zip in, as the app's own file. Prints its sha256."""
    restore_dir = f'{paths.app_files()}/restore'
    return adb.script(f"""set -eu
APPDIR=/data/user_de/0/{paths.PACKAGE}
U=$(stat -c %u $APPDIR)
C=$(stat -c %C $APPDIR)
mkdir -p {restore_dir}
rm -f {restore_dir}/*.zip
rm -f {paths.app_files()}/{OUTCOME}
mv -f {staged} {restore_dir}/{name}
chown $U:$U $APPDIR/files {restore_dir} {restore_dir}/{name}
chmod 0700 {restore_dir}
chmod 0600 {restore_dir}/{name}
chcon $C $APPDIR/files {restore_dir} {restore_dir}/{name}
sha256sum {restore_dir}/{name}
""", 'restore', timeout=conf.TRANSFER_TIMEOUT_S)


def _outcome(adb: Adb, name: str) -> dict:
    """What the app wrote about the restore, waited for a bounded time. Failing, with what was
    seen, if it never appears."""
    path = f'{paths.app_files()}/{OUTCOME}'
    deadline = time.monotonic() + conf.OUTCOME_WAIT_S
    seen = ''
    while True:
        seen = adb.sh(f'su -c "cat {path}"', check=False)
        try:
            outcome = json.loads(seen) if seen else None
        except json.JSONDecodeError:       # caught while it is being written
            outcome = None
        # The app writes its outcome for the zip it took; one for another name is a restore before this.
        if isinstance(outcome, dict) and isinstance(outcome.get('ok'), bool) and outcome.get('name') == name:
            return outcome
        if time.monotonic() >= deadline:
            break
        time.sleep(conf.OUTCOME_POLL_S)
    left = adb.sh(f'su -c "ls {paths.app_files()}/restore"', check=False)
    ui.fail(f'the app wrote no restore outcome in {conf.OUTCOME_WAIT_S} s. {OUTCOME} read: {seen!r}. '
            f'restore/ on the C1 holds: {left or "nothing"}. '
            + (f'{name} is still there, so the app did not take it: an app older than restores, or it has not restarted.'
               if name in left.split() else 'The zip is gone, so the app took it but did not finish.'))
