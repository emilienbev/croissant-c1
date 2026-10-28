"""`croissant upgrade`: the CLI install.sh put in ~/.croissant, to its repository's latest release
(or --to X.Y.Z).

The latest release's tag comes from where github.com/OWNER/REPO/releases/latest redirects (no
API, no rate limit). Then that release's own install.sh does the rest, as for a first install:
it downloads and checks the CLI, puts it beside this one and switches to it
(scripts/install.sh). A checkout of the source is brought up to date with git pull instead.
"""
import http.client
import os
import subprocess
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

from . import __version__, paths, ui, updates

TAG = '/releases/tag/'


def version(s: str) -> tuple[int, ...] | None:
    m = updates.VERSION.fullmatch(s.strip())
    return tuple(int(n) for n in m.groups()) if m else None


def latest(repo: str, timeout=15) -> str:
    """[repo]'s latest release, X.Y.Z."""
    req = urllib.request.Request(f'https://github.com/{repo}/releases/latest', method='HEAD',
                                 headers={'User-Agent': 'croissant'})
    try:
        with ui.spinner(f'looking at {repo} on GitHub'):
            url = urllib.request.urlopen(req, timeout=timeout).geturl()
    except urllib.error.HTTPError as e:
        ui.fail(f'{repo}: no release to upgrade to, or not a public repository (HTTP {e.code})')
    except (OSError, http.client.HTTPException) as e:
        ui.fail(f'could not ask GitHub for {repo}\'s latest release: {e}')
    tag = url.split(TAG, 1)[1].split('/')[0] if TAG in url else ''
    if not (v := version(tag)):
        ui.fail(f'{repo} has no release yet' if not tag else f'{repo}: its latest release, {tag}, is not vX.Y.Z')
    return '.'.join(map(str, v))


def _install_sh(repo: str, to: str, into: Path, timeout=30) -> Path:
    """That release's install.sh; from CROISSANT_DIST when set, as install.sh takes it (a test)."""
    out = into / 'install.sh'
    if dist := os.environ.get('CROISSANT_DIST'):
        out.write_bytes((Path(dist) / 'install.sh').read_bytes())
        return out
    url = f'https://github.com/{repo}/releases/download/v{to}/install.sh'
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'croissant'})
        out.write_bytes(urllib.request.urlopen(req, timeout=timeout).read())
    except urllib.error.HTTPError as e:
        ui.fail(f'{repo} v{to} has no install.sh (HTTP {e.code}): a release from before the CLI was published')
    except (OSError, http.client.HTTPException) as e:
        ui.fail(f'could not download {url}: {e}')
    return out


def run(args):
    if not paths.INSTALLED:
        ui.fail('This is a checkout of the source: git pull brings it up to date. croissant upgrade '
                'is for the CLI installed from a release (installer/README.md).')
    repo, now = paths.install()['repo'], __version__
    if args.to:
        if not (v := version(args.to)):
            ui.fail(f'--to {args.to}: not X.Y.Z')
        to = '.'.join(map(str, v))
    else:
        to = latest(repo)
        if version(to) <= (version(now) or ()):
            ui.ok(f'Croissant\'s CLI {now} is the latest ({repo})' if to == now else
                  f'Croissant\'s CLI {now} is newer than {repo}\'s latest release, {to}')
            return
    with tempfile.TemporaryDirectory() as d:
        script = _install_sh(repo, to, Path(d))
        # Its PATH line is there already, or was taken out on purpose: CROISSANT_UPGRADE leaves it.
        env = os.environ | {'CROISSANT_HOME': str(paths.HOME), 'CROISSANT_VERSION': to, 'CROISSANT_UPGRADE': '1'}
        if subprocess.run(['sh', str(script)], env=env).returncode != 0:
            ui.fail(f'the upgrade to {to} failed (above); {now} is still the one in use')
    ui.ok(f'Croissant\'s CLI {to}' + ('' if to == now else f', in place of {now}'))
