"""The Croissant APK: a file, built from a source checkout, or the latest release of a repository.

Whatever signs it is the key every later update must be signed with (an uninstall
unbinds Device Owner). A build here uses this machine's debug key, ~/.android/debug.keystore,
as the release build is configured to (android/README.md); a release, its project's key.
"""
import subprocess
from pathlib import Path

from . import paths, prereqs, ui, updates
from .paths import REPO

ANDROID = REPO / 'android'
BUILT = 'app/build/outputs/apk/release/app-release.apk'
LATEST = 'latest'                 # --apk latest: the latest release of the update repository


def resolve(given: str | None, repo: str | None = None) -> Path:
    """[given]: 'latest' downloads [repo]'s latest release, else a file or a checkout (local);
    None builds this checkout, or, installed (no source here), takes the latest release."""
    if given is None and paths.INSTALLED:
        given = LATEST
    if given == LATEST:
        if not (repo := repo or paths.update_repo()):
            ui.fail('--apk latest needs --update-repo OWNER/REPO (android/gradle.properties sets none)')
        return updates.download(repo)
    return local(given) if given else build(ANDROID)


def local(given: str) -> Path:
    """An .apk file as it is, or a source checkout (its root or its android/) built."""
    p = Path(given).expanduser()
    if p.is_file():
        return p
    if not p.is_dir():
        ui.fail(f'{given}: no such file or folder')
    for d in (p / 'android', p):
        if (d / 'gradlew').is_file() and (d / 'app').is_dir():
            return build(d.resolve())
    ui.fail(f'{given}: neither an APK nor a checkout of Croissant\'s source (no android/gradlew)')


def build(android: Path) -> Path:
    """The release APK built in [android], a checkout's android/."""
    if not prereqs.ensure_build():
        ui.fail('This machine cannot build the app: '
                f'{"; ".join(prereqs.missing_for_build())}. Pass --apk latest for the latest '
                'release, or --apk FILE.')
    if not list((android / 'libs').glob('*.aar')):
        with ui.spinner('fetching the offline Translator\'s AARs (android/libs/fetch.sh)'):
            r = subprocess.run([str(android / 'libs/fetch.sh')], capture_output=True, text=True)
        if r.returncode != 0:
            ui.console.print(r.stdout + r.stderr, markup=False, highlight=False)
            ui.fail('fetching the AARs failed (its output is above)')
    # Its output is kept until it fails: shown as it came, it would run through the spinner.
    with ui.spinner(f'building the release APK in {android} (a few minutes the first time)'):
        r = subprocess.run(['./gradlew', '-q', ':app:assembleRelease', f'-Pcroissant.applicationId={paths.PACKAGE}'],
                           cwd=android, env=prereqs.build_env(), capture_output=True, text=True)
    built = android / BUILT
    if r.returncode != 0 or not built.exists():
        ui.console.print(r.stdout + r.stderr, markup=False, highlight=False)
        ui.fail('the build failed (its output is above)')
    ui.ok(f'built {built.name}')
    return built
