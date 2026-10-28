"""Where the C1 gets its updates: a GitHub repository whose releases the app installs.

Asked in the install plan (or `--update-repo`), handed to the app as a file it takes into
its settings at its next start (stages.update_source), and changed later in Settings >
update on the C1. Read as the app reads it (Source.parse in update/Releases.kt), so the
installer takes exactly what the app would.
"""
import http.client
import json
import re
import urllib.error
import urllib.request
from pathlib import Path

from . import artifacts, ui

OWNER = re.compile(r'[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}')
REPO = re.compile(r'[A-Za-z0-9._-]{1,100}')
LINK = re.compile(r'(?:https?://)?(?:www\.)?github\.com/', re.I)
VERSION = re.compile(r'v?(\d{1,4})\.(\d{1,2})\.(\d{1,2})')    # a release's tag, vX.Y.Z

WRONG = 'owner/repo on GitHub, or a github.com link to it'


def parse(given: str) -> str | None:
    """`owner/repo`, from itself or from a github.com link to it (with or without the scheme,
    `www.`, `.git`, a trailing slash or further path). None for anything else, another host
    included: it goes into a URL path, on the Mac and on the C1."""
    s = given.strip()
    link = LINK.match(s)
    parts = [p for p in s[link.end() if link else 0:].split('/') if p]
    if len(parts) < 2 or (not link and len(parts) > 2):
        return None
    owner, repo = parts[0], parts[1].removesuffix('.git')
    if not OWNER.fullmatch(owner) or not REPO.fullmatch(repo) or repo in ('.', '..'):
        return None
    return f'{owner}/{repo}'


def check(given: str) -> str:
    repo = parse(given)
    if not repo:
        ui.fail(f'update source {given!r}: {WRONG}')
    return repo


def _latest(repo: str, timeout=10) -> dict:
    req = urllib.request.Request(f'https://api.github.com/repos/{repo}/releases/latest',
                                 headers={'User-Agent': 'croissant'})
    return json.load(urllib.request.urlopen(req, timeout=timeout))


def _apk(rel: dict) -> tuple[str | None, dict | None]:
    """A release's version and its APK asset, named as release.yml names it (the only one the
    app takes); None for either when it has none."""
    m = VERSION.fullmatch(str(rel.get('tag_name', '')))
    version = '.'.join(str(int(n)) for n in m.groups()) if m else None
    assets = {a.get('name'): a for a in rel.get('assets') or []}
    return version, assets.get(f'croissant-{version}.apk') if version else None


def look(repo: str, timeout=10):
    """What the C1 would find there now: its latest release, none yet, or that GitHub could not
    be asked. Said, never fatal: the C1 looks for itself."""
    try:
        with ui.spinner(f'looking at {repo} on GitHub'):
            rel = _latest(repo, timeout)
    except urllib.error.HTTPError as e:
        if e.code == 404:                 # GitHub's answer for no release and for no such repository
            ui.info(f'{repo} has no release yet, or is not a public repository. The C1 will look again daily.')
        else:                             # 403 and 429 are its rate limit
            ui.info(f'could not look at {repo} on GitHub (HTTP {e.code}). The C1 will look for itself.')
        return
    except (OSError, ValueError, http.client.HTTPException):
        ui.info(f'could not look at {repo} on GitHub. The C1 will look for itself.')
        return
    version, asset = _apk(rel)
    if asset:
        ui.ok(f'{repo}: Croissant {version}')
    else:
        ui.warn(f'{repo}: its latest release, {rel.get("tag_name")}, has no Croissant APK the C1 can '
                'install. It will look again daily.')


def download(repo: str, timeout=30) -> Path:
    """The latest release's APK from [repo], checked against the .sha256 published beside it
    (else GitHub's own digest), kept in the cache."""
    try:
        with ui.spinner(f'looking at {repo} on GitHub'):
            rel = _latest(repo, timeout)
    except urllib.error.HTTPError as e:
        ui.fail(f'{repo}: no release to download (HTTP {e.code})' if e.code == 404 else
                f'could not ask GitHub for {repo}\'s latest release (HTTP {e.code})')
    except (OSError, ValueError, http.client.HTTPException) as e:
        ui.fail(f'could not ask GitHub for {repo}\'s latest release: {e}')
    version, asset = _apk(rel)
    if not asset:
        ui.fail(f'{repo}: its latest release, {rel.get("tag_name")}, has no croissant-X.Y.Z.apk')
    name = asset['name']
    sums = {a.get('name'): a for a in rel.get('assets') or []}
    digest = ''
    if listed := sums.get(name + '.sha256'):
        req = urllib.request.Request(listed['browser_download_url'], headers={'User-Agent': 'croissant'})
        digest = urllib.request.urlopen(req, timeout=timeout).read().decode().split()[0]
    elif (asset.get('digest') or '').startswith('sha256:'):
        digest = asset['digest'].removeprefix('sha256:')
    if not digest:
        ui.fail(f'{repo} {rel.get("tag_name")}: no checksum for {name}, so it is not used')
    return artifacts.fetch(artifacts.Artifact(name, asset['browser_download_url'], digest.lower()))
