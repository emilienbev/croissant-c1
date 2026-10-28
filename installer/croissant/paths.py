"""Where things are: the checkout the CLI runs from (or the release installed by install.sh),
and its cache.

Everything the CLI keeps (downloads, mtkclient, the stock boot images, each R1's work files,
the boot screens, the font) stays in the cache: the checkout's gitignored `cache/`, or, for
an install, `~/.croissant/cache`, kept across upgrades. CROISSANT_CACHE points it elsewhere.
"""
import importlib.util
import json
import os
import pathlib

REPO = pathlib.Path(__file__).resolve().parents[2]
DEVICE = REPO / 'device'

# A release's CLI, as install.sh puts it in ~/.croissant/versions/X.Y.Z, has this file at its
# root (scripts/build-cli.sh): the repository it came from and its version. A checkout has not.
INSTALL = REPO / 'install.json'
INSTALLED = INSTALL.is_file()
HOME = REPO.parents[1] if INSTALLED else None       # ~/.croissant
COMMAND = 'croissant' if INSTALLED else './croissant'
CACHE = pathlib.Path(os.environ.get('CROISSANT_CACHE') or (HOME if INSTALLED else REPO) / 'cache')


def install() -> dict:
    """install.json: {"repo": "owner/repo", "version": "X.Y.Z"}; empty in a checkout."""
    return json.loads(INSTALL.read_text()) if INSTALLED else {}

# The app's classes, which keep this namespace whatever the application id.
NAMESPACE = 'com.bleural.croissant'


def _gradle_property(name: str) -> str | None:
    for line in (REPO / 'android/gradle.properties').read_text().splitlines():
        key, _, value = line.partition('=')
        if key.strip() == name:
            return value.strip()
    return None


def _application_id() -> str:
    """The app's application id, where the build sets it: android/gradle.properties."""
    if not (app_id := _gradle_property('croissant.applicationId')):
        raise SystemExit('android/gradle.properties sets no croissant.applicationId')
    return app_id


def update_repo() -> str | None:
    """The GitHub repository the app looks to for releases when nothing else is set, where
    the build sets it (BuildConfig.UPDATE_REPO): android/gradle.properties."""
    return _gradle_property('croissant.updateRepo')


# The installed app's id; --package replaces it (cli.main), so read it as paths.PACKAGE.
PACKAGE = _application_id()


def admin() -> str:
    return f'{PACKAGE}/{NAMESPACE}.AdminReceiver'
STAGE = '/data/local/tmp'   # where files land on the device before a root script moves them


def load(relpath: str, name: str):
    """Import one of the repo's scripts by path (they live beside the files they serve)."""
    spec = importlib.util.spec_from_file_location(name, REPO / relpath)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod
