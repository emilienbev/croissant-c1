"""croissant atelier: an Atelier app from a folder on this computer onto a plugged-in C1.

  ./croissant atelier new my-app        a small app to start from, in a new folder, asked for
  ./croissant atelier run ./my-app      push it, then push again on every save, with its log
  ./croissant atelier qr ./my-app       its QR code again (qr.svg, qr.png), for a C1 to scan

An app is a folder with croissant.json and croissant.js (docs/atelier.md). Its id is the
folder's name, made safe as the C1 makes a repo's name (app_id). It lands in the app's
device-protected files, atelier/apps/<id>/, as the font does, and the app reads it again:
the board shows it, and its page, if it is open, starts again on the new script. Its saved
state is kept across pushes (--fresh drops it). What the app logs (console.log) and every
error it runs into come back here as they happen.
"""
from __future__ import annotations

import json
import re
import shutil
import subprocess
import threading
import time
import zlib
from pathlib import Path

from . import device, lucide, paths, qr, starter, ui
from .tools import Adb

CROISSANT = 1
USES = {'network', 'model', 'words'}
MAX_SCRIPT = 256 * 1024
MAX_ID = 40
HUE = re.compile(r'^#[0-9a-fA-F]{6}$')
GLYPH = re.compile(r'^[a-z0-9][a-z0-9-]{0,63}$')
TOOL = re.compile(r'^[a-z][a-z0-9_]{0,22}$')
MANIFEST, SCRIPT = 'croissant.json', 'croissant.js'
FILES = (MANIFEST, SCRIPT)


def app_id(name: str) -> str:
    """[name] as an app's id, as the C1 makes one (AppId.of in atelier/AtelierStore.kt): A to Z
    lowercased, anything else outside a-z, 0-9 and the dash a dash, runs of dashes one and none
    at the ends, cut to 40; "app" if nothing is left."""
    s = ''.join(c.lower() if 'A' <= c <= 'Z' else c if 'a' <= c <= 'z' or '0' <= c <= '9' else '-' for c in name)
    return re.sub('-+', '-', s).strip('-')[:MAX_ID].rstrip('-') or 'app'


def check(folder: Path) -> dict:
    """The folder's croissant.json, checked as the C1 checks it (atelier/AppManifest.kt), or Abort with why."""
    if not folder.is_dir():
        ui.fail(f'{folder} is not a folder')
    for name in FILES:
        if not (folder / name).is_file():
            ui.fail(f'no {name} in {folder}: not a Croissant app ({paths.COMMAND} atelier new makes one)')
        # adb pushes a link as a link, which the device's root script would then follow.
        if (folder / name).is_symlink():
            ui.fail(f'{folder / name} is a link: push the file itself')
    try:
        m = json.loads((folder / MANIFEST).read_text())
    except (json.JSONDecodeError, UnicodeDecodeError) as e:
        ui.fail(f'{MANIFEST} is not JSON: {e}')
    if not isinstance(m, dict):
        ui.fail(f'{MANIFEST} is not an object')
    problems = []
    v = m.get('croissant')
    if 'croissant' not in m:
        problems.append('croissant: missing, the version this app is written for (1)')
    elif not isinstance(v, int) or isinstance(v, bool) or v < 1:
        problems.append('croissant: a number')
    elif v > CROISSANT:
        problems.append(f'croissant: {v} needs a newer Croissant (this one runs croissant {CROISSANT})')
    name = str(m.get('name', '')).strip()
    if not name or len(name) > 24:
        problems.append('name: 1 to 24 letters')
    if not HUE.match(str(m.get('hue', ''))):
        problems.append('hue: #RRGGBB')
    if 'glyph' in m and m['glyph'] and not GLYPH.match(str(m['glyph'])):
        problems.append('glyph: a Lucide icon\'s name (lucide.dev)')
    unknown = [u for u in m.get('uses', []) if u not in USES]
    if unknown:
        problems.append(f'uses: {", ".join(map(str, unknown))} (Croissant has {", ".join(sorted(USES))})')
    tools = m.get('tools', [])
    if not isinstance(tools, list):
        problems.append('tools: a list')
        tools = []
    names = []
    for i, t in enumerate(tools):
        if not isinstance(t, dict):
            problems.append(f'tools[{i}]: an object')
            continue
        name = str(t.get('name', ''))
        names.append(name)
        if not TOOL.match(name):
            problems.append(f'tools[{i}].name: lowercase letters, digits and _, up to 23')
        if not str(t.get('description', '')).strip():
            problems.append(f'tools[{i}].description: what it does, for the model')
        p = t.get('parameters')
        if p is not None and (not isinstance(p, dict) or p.get('type', 'object') != 'object'):
            problems.append(f'tools[{i}].parameters: a JSON Schema object')
        for j, ex in enumerate(t.get('examples', []) if isinstance(t.get('examples', []), list) else [None]):
            ok = (isinstance(ex, str) and ex.strip()) or (
                isinstance(ex, dict) and str(ex.get('say', '')).strip() and isinstance(ex.get('args', {}), dict))
            if not ok:
                problems.append(f'tools[{i}].examples[{j}]: a sentence, or {{"say": ..., "args": {{...}}}}')
    if len(set(names)) != len(names):
        problems.append('tools: two have the same name')
    if (folder / SCRIPT).stat().st_size > MAX_SCRIPT:
        problems.append(f'{SCRIPT} is over {MAX_SCRIPT // 1024} kB')
    if problems:
        ui.fail(f'{MANIFEST}:\n  ' + '\n  '.join(problems))
    return m


def push(adb: Adb, folder: Path, app: str, fresh: bool = False):
    """The two files into atelier/apps/<id>/ as the app's own data, then the reload the app watches for."""
    stage = f'{paths.STAGE}/croissant-atelier'
    adb.sh(f'rm -rf {stage}; mkdir -p {stage}', check=False)
    for name in FILES:
        adb.push(folder / name, f'{stage}/{name}')
    now = int(time.time() * 1000)
    adb.script(f"""set -e
APPDIR=/data/user_de/0/{paths.PACKAGE}
U=$(stat -c %u $APPDIR)
C=$(stat -c %C $APPDIR)
A=$APPDIR/files/atelier
D=$A/apps/{app}
mkdir -p $D
mv -f {stage}/{MANIFEST} $D/{MANIFEST}
mv -f {stage}/{SCRIPT} $D/{SCRIPT}
rmdir {stage}
grep -q '"local"' $D/source.json 2>/dev/null || printf '{{"kind": "local", "installed": {now}}}\\n' > $D/source.json
{'rm -f $D/state.json' if fresh else ':'}
printf '%s\\n' {app} > $A/.reload
chown -h $U:$U $APPDIR/files $A $A/apps $D $D/* $A/.reload
chmod 0700 $A $A/apps $D
for F in $D/* $A/.reload; do [ -L $F ] || chmod 0600 $F; done
chcon $C $APPDIR/files
chcon -hR $C $A
mv -f $A/.reload $A/reload
""", 'atelier')


def _follow(adb: Adb, app: str, stop: threading.Event):
    """The app's lines in the C1's log, from now on, until [stop]."""
    proc = subprocess.Popen(adb._argv('logcat', '-v', 'brief', '-T', '1', '-s', 'Atelier:V'),
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    try:
        for line in proc.stdout:
            if stop.is_set():
                break
            # "E/Atelier( 1234): water: render: ... (croissant.js line 3)"
            level, _, rest = line.partition('/')
            msg = rest.split('): ', 1)[-1].rstrip()
            if not msg.startswith(f'{app}: '):
                continue
            msg = ui.escape(msg[len(app) + 2:])
            ui.console.print(f'[red]{msg}[/]' if level == 'E' else f'[yellow]{msg}[/]' if level == 'W' else msg)
    finally:
        proc.terminate()


# --- new: an app to start from ----------------------------------------------------------

# Beam's neons (hueOf, ui/beam/Ramp.kt): each carries black words and reads on the black.
HUES = [('blue', '#1FA2FF'), ('orange', '#FF4F00'), ('red', '#FF2B2B'), ('pink', '#FF2E8F'),
        ('coral', '#FF8F80'), ('amber', '#FFA41B'), ('yellow', '#FFE01A'), ('green', '#62F23A'),
        ('mint', '#4CF2B8'), ('cyan', '#2CE5E8'), ('sky', '#7DD3FC'), ('violet', '#8F8AFF'),
        ('lilac', '#D08CFF')]
FEATURES = [
    ('network', 'the network: fetch a page or an API'),
    ('model', 'the chat model: ask it a question'),
    ('words', 'your words: a hold of the side button on its page brings what you said'),
    ('tool', 'a tool for Ask: "add one to it", said anywhere, reaches the app'),
]
OWNER = re.compile(r'^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$')
REPO = re.compile(r'^[A-Za-z0-9._-]{1,100}$')
FALLBACK_GLYPH = 'sparkles'


def parse_repo(text: str) -> tuple[str, str] | None:
    """`owner/repo` or a github.com link to it (a remote's git@github.com: too), as the C1 reads
    what it is given (Source.parse in update/Releases.kt); None for anything else."""
    s = re.sub(r'^(?:ssh://)?git@github\.com[:/]', 'github.com/', text.strip(), flags=re.I)
    link = re.compile(r'^(?:https?://)?(?:www\.)?github\.com/', re.I)
    linked = bool(link.match(s))
    parts = [p for p in link.sub('', s).split('/') if p]
    if len(parts) < 2 or (not linked and len(parts) > 2):
        return None
    owner, repo = parts[0], parts[1].removesuffix('.git')
    if not OWNER.match(owner) or not REPO.match(repo) or repo in ('.', '..'):
        return None
    return owner, repo


def _run(argv: list[str], timeout: float = 8) -> str | None:
    try:
        r = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return r.stdout.strip() if r.returncode == 0 else None


def github_owner(use_gh: bool = True) -> str | None:
    """Who is likely to publish: gh's login when gh is there and signed in, else git's
    github.user; None when neither says."""
    for argv in ([['gh', 'api', 'user', '-q', '.login']] if use_gh and shutil.which('gh') else []) + \
                [['git', 'config', '--get', 'github.user']]:
        login = _run(argv)
        if login and OWNER.match(login):
            return login
    return None


def remote_repo(folder: Path) -> tuple[str, str] | None:
    """The GitHub repository [folder]'s git remote points at, if any."""
    url = _run(['git', '-C', str(folder), 'remote', 'get-url', 'origin'], timeout=5)
    return parse_repo(url) if url else None


def default_hue(repo: str) -> str:
    """One of Beam's neons, the same for the same name."""
    return HUES[zlib.crc32(repo.encode()) % len(HUES)][1]


def default_glyph(name: str) -> str:
    return next(iter(lucide.search(name, 1)), FALLBACK_GLYPH)


def display_name(repo: str) -> str:
    """The header word from a repo's name: words apart, lowercase, up to 24."""
    return re.sub(r'[-_.\s]+', ' ', repo).strip().lower()[:24].strip() or 'my app'


def _hue_ok(s: str) -> bool:
    return bool(HUE.match(s.strip()))


def _swatch(hex_: str, label: str):
    return [(f'fg:{hex_}', '████'), ('', f'  {label}')]


def preview(names: list[str], hue: str, px: int = 16):
    """[names] drawn in the terminal, in [hue] on black as the Atelier board draws them, two
    pixels a character, numbered under each when there are several."""
    from PIL import Image
    from rich.text import Text
    lit = qr.lift(hue)
    cell = px + 3
    per = max(1, min(len(names), (ui.console.width - 2) // cell))
    for at in range(0, len(names), per):
        group = names[at:at + per]
        imgs = []
        for n in group:
            big = Image.new('RGB', (px * 8, px * 8), 'black')
            lucide.draw(big, n, px, px, px * 6, lit)
            imgs.append(big.resize((px, px), Image.LANCZOS))
        for y in range(0, px, 2):
            line = Text('  ')
            for img in imgs:
                for x in range(px):
                    top, low = img.getpixel((x, y)), img.getpixel((x, y + 1))
                    line.append('▀', style=f'#{top[0]:02x}{top[1]:02x}{top[2]:02x} on #{low[0]:02x}{low[1]:02x}{low[2]:02x}')
                line.append(' ' * (cell - px))
            ui.console.print(line)
        if len(names) > 1:
            ui.console.print('  ' + ''.join(str(at + i + 1).center(px).ljust(cell) for i in range(len(group))),
                             style=ui.GREY)


def _ask_glyph(current: str, hue: str) -> str:
    while True:
        preview([current], hue)
        q = ui.text(f'Glyph: a word to search Lucide\'s icons (enter keeps {current}):').strip()
        if not q:
            return current
        found = lucide.search(q, 8)
        if not found:
            ui.warn(f'no icon for "{q}"; another word, or lucide.dev/icons')
            continue
        preview(found, hue)
        pick = ui.select('Which one?', [(f'{i + 1}  {n}', n) for i, n in enumerate(found)] + [('search again', '')],
                         default=found[0])
        current = pick or current


def _tool_name(s: str) -> str:
    return re.sub(r'[^a-z0-9_]+', '_', s.strip().lower()).strip('_')


def _ask(args) -> tuple[Path, starter.Answers]:
    """Every answer, from the flags where given and asked for the rest (or the defaults when
    nothing can be asked)."""
    asking = ui.INTERACTIVE
    given = Path(args.name).expanduser() if args.name else None
    parent = given.parent if given else Path('.')

    # The name, which is the folder's and the repository's.
    typed = given.name if given else ''
    while True:
        if asking and not typed:
            typed = ui.text('App name (its folder and GitHub repository):',
                            validate=lambda s: bool(s.strip()) or 'a name').strip()
        if not typed:
            ui.fail(f'a name for the app: {paths.COMMAND} atelier new NAME')
        repo = app_id(typed)
        folder = parent / repo
        if folder.exists() and (not folder.is_dir() or any(folder.iterdir())):
            if not asking:
                ui.fail(f'{folder} is already there and not empty')
            ui.warn(f'{folder} is already there and not empty: another name')
            typed = ''
            continue
        break
    ui.info(f'folder {folder}, repository {repo}, id on the C1 {repo}')

    owner = args.owner
    if owner is None:
        if asking:
            with ui.spinner('who you are on GitHub'):
                found = github_owner()
            owner = ui.text('GitHub owner, a user or an organisation (empty: none yet, no QR code):', found or '',
                            validate=lambda s: not s.strip() or bool(OWNER.match(s.strip())) or 'a GitHub user or organisation name').strip()
        else:
            owner = github_owner(use_gh=False) or ''
    if owner and not OWNER.match(owner):
        ui.fail(f'{owner} is not a GitHub user or organisation name')
    if owner:
        ui.info(f'its code will hold {qr.link(f"{owner}/{repo}")}')

    name = args.title or display_name(repo)
    if asking and not args.title:
        name = ui.text('Its name on the C1, the header word (up to 24):', name,
                       validate=lambda s: 0 < len(s.strip()) <= 24 or '1 to 24 letters').strip()
    if not 0 < len(name.strip()) <= 24:
        ui.fail('--title: 1 to 24 letters')
    description = args.description
    if description is None:
        description = ui.text('One line about it, for the README and the repository (optional):').strip() if asking else ''

    hue = args.hue or default_hue(repo)
    if not _hue_ok(hue):
        ui.fail(f'--hue {hue}: #RRGGBB')
    if asking and not args.hue:
        choices = [(_swatch(h, f'{n}  {h}'), h) for n, h in HUES] + [('another: #RRGGBB', '')]
        hue = ui.select('Its hue:', choices, default=hue)
        if not hue:
            hue = ui.text('#RRGGBB:', '#', validate=lambda s: _hue_ok(s) or '#RRGGBB, as #1FA2FF').strip()
    hue = hue.upper()
    if qr.lift(hue) != hue:
        ui.info(f'{hue} is dark: the C1 draws it as {qr.lift(hue)}, so black reads on it')

    glyph = args.glyph
    if glyph and not lucide.has(glyph):
        near = ', '.join(lucide.search(glyph, 5))
        ui.fail(f'--glyph {glyph}: not a Lucide icon the C1 has{f" (close: {near})" if near else ""}')
    if not glyph:
        glyph = default_glyph(f'{name} {description}')
        if asking:
            glyph = _ask_glyph(glyph, hue)

    unknown = [u for u in args.uses or [] if u not in starter.USES]
    if unknown:
        ui.fail(f'--uses {",".join(unknown)}: Croissant has {", ".join(starter.USES)}')
    uses = [u for u in starter.USES if u in (args.uses or [])]
    tool = None
    if args.tool or args.tool_description or args.tool_example:
        d = starter.default_tool(name)
        tool = starter.Tool(_tool_name(args.tool or d.name), args.tool_description or d.description,
                            args.tool_example or d.example)
    if asking and args.uses is None and not tool:
        picked = ui.checkbox('What it needs beyond its page (all optional):',
                             [(t, k, False) for k, t in FEATURES])
        uses = [u for u in starter.USES if u in picked]
        if 'tool' in picked:
            d = starter.default_tool(name)
            tname = ui.text('The tool\'s name (lowercase letters, digits and _):', d.name,
                            validate=lambda s: bool(TOOL.match(_tool_name(s))) or 'lowercase letters, digits and _, up to 23')
            tdesc = ui.text('What it does, for the model:', d.description,
                            validate=lambda s: bool(s.strip()) or 'a line')
            tex = ui.text('A sentence that should call it:', d.example, validate=lambda s: bool(s.strip()) or 'a sentence')
            tool = starter.Tool(_tool_name(tname), tdesc.strip(), tex.strip())
    if tool and not TOOL.match(tool.name):
        ui.fail(f'--tool {tool.name}: lowercase letters, digits and _, up to 23, starting with a letter')
    return folder, starter.Answers(repo=repo, name=name.strip(), hue=hue, glyph=glyph, owner=owner or None,
                                   description=description.strip(), uses=uses, tool=tool)


def _git(folder: Path, a: starter.Answers) -> bool:
    """A repository on main with everything in a first commit, as the person's own git makes it."""
    if not shutil.which('git'):
        ui.info('no git here: the folder is not a repository yet')
        return False
    if _run(['git', '-C', str(folder), 'init', '-q', '-b', 'main']) is None:
        ui.warn('git init failed: the folder is not a repository yet')
        return False
    _run(['git', '-C', str(folder), 'add', '--', *sorted(p.name for p in folder.iterdir() if p.name != '.git')])
    if _run(['git', '-C', str(folder), 'commit', '-q', '-m', f'{a.name}: a new Atelier app']) is None:
        ui.warn('git did not commit (no user.name or user.email yet?): the files are staged, commit them yourself')
        return False
    return True


def _publish(folder: Path, a: starter.Answers):
    """gh repo create, only when asked and answered yes."""
    ui.todo(f'This makes github.com/{a.slug}, public: anyone can see and add it.')
    if not ui.confirm(f'Publish it now with gh, as github.com/{a.slug}?', default=False):
        ui.info(f'Later: gh repo create {a.slug} --public --source {folder} --push')
        return
    argv = ['gh', 'repo', 'create', a.slug, '--public', '--source', str(folder), '--push',
            '--description', a.description or f'{a.name}, an Atelier app for the C1']
    if subprocess.run(argv).returncode != 0:
        ui.warn('gh did not publish it; the folder is unchanged')
        return
    ui.ok(f'github.com/{a.slug} is public. On a C1: Atelier, +, scan the code in its README, or type {a.slug}.')


def new(args):
    """A new folder holding a small working app to start from: its croissant.json, croissant.js,
    README and QR code, from the answers (asked, or from the flags)."""
    ui.step('A new Atelier app')
    folder, a = _ask(args)
    ui.step('Making it')
    ui.summary([('folder', str(folder)), ('repository', a.slug or f'{a.repo} (no owner yet)'),
                ('name', a.name), ('hue', a.hue), ('glyph', a.glyph or ''),
                ('uses', ', '.join(a.uses) or 'nothing but its page'),
                ('tool', f'{a.tool.name}: "{a.tool.example}"' if a.tool else 'none')])
    if ui.INTERACTIVE and not ui.confirm('Make it?', default=True):
        ui.fail('Nothing was made.')
    written = starter.write(folder, a)
    check(folder)
    for p in written:
        ui.ok(str(p))
    committed = not args.no_git and _git(folder, a)
    if committed:
        ui.ok('a git repository on main, everything in its first commit')
    if not a.slug:
        ui.info(f'No QR code: it needs an owner. Once it has one: {paths.COMMAND} atelier qr {folder} --owner OWNER')
    ui.info(f'Try it on a C1 on USB:  {paths.COMMAND} atelier run {folder}')
    if a.slug and committed and ui.INTERACTIVE and shutil.which('gh'):
        _publish(folder, a)
    elif a.slug:
        ui.info(f'Publish it:  gh repo create {a.slug} --public --source {folder} --push')
    ui.done(f'{a.name} is in {folder}.')


def make_qr(args):
    """qr.svg and qr.png for an app: its folder (hue and glyph from croissant.json, the repo from
    its git remote) or owner/repo with --hue. A folder's README gets the code (under its title when
    it has no place for it yet), or with --readme is made again from croissant.json, its first
    paragraph or --description kept as the app's line."""
    target = args.target or '.'
    folder = Path(target).expanduser()
    readme = None
    if (folder / MANIFEST).is_file():
        m = check(folder)
        hue, glyph = args.hue or m['hue'], args.glyph or m.get('glyph')
        remote = remote_repo(folder)
        repo = remote[1] if remote else (folder.resolve().name if REPO.match(folder.resolve().name) else app_id(folder.resolve().name))
        owner = args.owner or (remote[0] if remote else None)
        if not owner:
            if ui.INTERACTIVE:
                with ui.spinner('who you are on GitHub'):
                    found = github_owner()
                owner = ui.text('GitHub owner of the repository:', found or '',
                                validate=lambda s: bool(OWNER.match(s.strip())) or 'a GitHub user or organisation name').strip()
            else:
                owner = github_owner(use_gh=False)
        if not owner:
            ui.fail('whose repository? --owner OWNER')
        out = Path(args.out).expanduser() if args.out else folder
        readme = folder / 'README.md'
    else:
        parsed = parse_repo(target)
        if not parsed:
            ui.fail(f'{target}: not an app folder (no {MANIFEST}) nor owner/repo')
        owner, repo = args.owner or parsed[0], parsed[1]
        hue, glyph = args.hue, args.glyph
        if not hue:
            ui.fail(f'the app\'s hue: --hue #RRGGBB (and --glyph NAME), or the app\'s folder in place of {target}')
        if getattr(args, 'readme', False):
            ui.fail('--readme is for an app\'s folder, which has its croissant.json')
        out = Path(args.out).expanduser() if args.out else Path('.')
    if not HUE.match(hue):
        ui.fail(f'hue {hue}: #RRGGBB')
    if not OWNER.match(owner):
        ui.fail(f'{owner} is not a GitHub user or organisation name')
    if glyph and not lucide.has(glyph):
        ui.warn(f'{glyph} is not a Lucide icon the C1 has: the code goes without one')
        glyph = None
    slug = f'{owner}/{repo}'
    out.mkdir(parents=True, exist_ok=True)
    for p in qr.write(out, slug, hue, glyph):
        ui.ok(str(p))
    ui.info(f'it holds {qr.link(slug)}')
    if readme and out.resolve() == folder.resolve():
        before = readme.read_text() if readme.is_file() else ''
        if getattr(args, 'readme', False):
            about = getattr(args, 'description', None) or starter.about(before)
            text = starter.readme(starter.from_manifest(m, repo, owner, about))
        elif before:
            text = starter.set_qr(before, slug)
        else:
            text = before
        if text != before:
            readme.write_text(text)
            ui.ok(f'{readme}: made again' if getattr(args, 'readme', False) else f'{readme}: its code')


def _stamp(folder: Path) -> tuple:
    return tuple((folder / n).stat().st_mtime_ns for n in FILES if (folder / n).exists())


def run(adb: Adb, fb, args):
    folder = Path(args.folder).expanduser().resolve()
    m = check(folder)
    app = app_id(folder.name)
    where = device.locate(adb, fb, args.serial)
    if where.mode != 'android' or not adb.sh(f'pm path {paths.PACKAGE}', check=False):
        ui.fail('no C1 with Croissant on USB. Plug one in and run it again.')
    push(adb, folder, app, fresh=args.fresh)
    ui.ok(f'{m["name"]} on the C1 ({app}): open Atelier and tap it')
    if args.once:
        return
    ui.info('pushed again on every save; its log below. Ctrl-C to stop.')
    stop = threading.Event()
    threading.Thread(target=_follow, args=(adb, app, stop), daemon=True).start()
    seen = _stamp(folder)
    try:
        while True:
            time.sleep(0.5)
            now = _stamp(folder)
            if now == seen:
                continue
            seen = now
            time.sleep(0.2)   # an editor writing in two steps
            try:
                check(folder)
            except ui.Abort as e:
                ui.warn(str(e))
                continue
            push(adb, folder, app)
            ui.ok(time.strftime('%H:%M:%S') + ' pushed')
    finally:
        stop.set()
