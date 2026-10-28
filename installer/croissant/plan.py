"""The choices for one install: from the flags, and asked for whatever the flags left open.

Asked once, then kept in the device's state file, so running again after a stop does not
ask again (the answers become the defaults; flags still win).
"""
from dataclasses import dataclass
from pathlib import Path

from . import apk, brand, paths, prereqs, sounds, ui, updates
from . import gsi as builds

UPDATE_QUESTION = 'Where should this C1 get its updates?'
UPDATE_WHY = 'A GitHub repository whose releases this C1 installs. Keep the default unless you publish your own builds.'
APP_QUESTION = 'The Croissant app:'
OFFLINE_NEEDS_CHECKOUT = ('The offline Translator\'s models are built from a checkout of the source: '
                          'run ./croissant install --offline-models from one (installer/README.md).')
APP_WHY = (f'Building from source needs Java {prereqs.JAVA_MIN}+ and the Android SDK with NDK {prereqs.NDK} '
           '(offered next if missing). Later updates must be signed the same way: built on this '
           'machine, or a release.')


@dataclass
class Plan:
    name: str = brand.DEFAULT_NAME
    gsi: builds.Gsi = builds.PROVEN
    apk: str | None = None            # a file, a checkout, or apk.LATEST; None builds this checkout
    magisk: str | None = None         # None: pinned; a file, 'latest' or a release tag
    tailscale: str | None = None      # None or 'latest': the latest stable; or a version
    offline_models: bool = False
    sounds: str = sounds.DEFAULT      # the app's interface sound pack (sounds.PACKS)
    update_repo: str | None = None    # owner/repo; None leaves the app's own (update without --update-repo)
    reflash: bool = False             # flash even though the R1 already runs a GSI
    yes: bool = False


def from_flags(args) -> tuple[Plan, set]:
    """The plan the flags describe, and which of its fields they set."""
    p, given = Plan(), set()
    if args.name is not None:
        p.name, _ = brand.check_name(args.name), given.add('name')
    if args.gsi is not None:
        p.gsi = builds.PROVEN if args.gsi == 'proven' else builds.custom(args.gsi, args.gsi_checksum)
        given.add('gsi')
    if args.apk is not None:
        p.apk, _ = args.apk, given.add('apk')
    elif paths.INSTALLED:                 # no source here to build
        p.apk = apk.LATEST
    if args.offline_models and paths.INSTALLED:
        ui.fail(OFFLINE_NEEDS_CHECKOUT)
    if args.offline_models:
        p.offline_models, _ = True, given.add('offline_models')
    if args.update_repo is not None:
        p.update_repo, _ = updates.check(args.update_repo), given.add('update_repo')
    if args.sounds is not None:
        p.sounds, _ = sounds.check(args.sounds), given.add('sounds')
    p.magisk, p.tailscale, p.reflash, p.yes = args.magisk, args.tailscale, args.reflash, args.yes
    return p, given


def ask(p: Plan, given: set, will_flash: bool, remembered: dict) -> Plan:
    """Fill in what the flags did not, one question at a time."""
    if 'name' not in given:
        default = remembered.get('name', brand.DEFAULT_NAME)

        def valid(s):
            try:
                brand.check_name(s)
                return True
            except ui.Abort as e:
                return str(e)
        p.name = ui.text('The name on its boot screens:', default, valid)

    if will_flash and 'gsi' not in given:
        with ui.spinner('asking SourceForge for the current LineageOS builds'):
            live = builds.available()
        choices = [(f'{builds.PROVEN.label}  (proven on the R1, recommended)', builds.PROVEN)]
        choices += [(f'{g.label}  (newer, not yet tried on an R1)', g) for g in live]
        choices += [('Another build: a URL or a file on this machine', 'other')]
        pick = ui.select('Which LineageOS?', choices,
                         default=next((g for g in [builds.PROVEN] + live
                                       if g.file_name == remembered.get('gsi')), builds.PROVEN))
        if pick == 'other':
            where = ui.text('URL or file path of the GSI (.img, .img.gz or .img.xz):')
            digest = ui.text('Its sha256 or md5, to check the download by (enter to skip):') \
                if where.startswith('http') else None
            pick = builds.custom(where, digest or None)
        p.gsi = pick
    if will_flash:
        for w in builds.warnings(p.gsi):
            ui.warn(w)

    if 'update_repo' not in given:
        ui.info(UPDATE_WHY)
        p.update_repo = updates.parse(ui.text(UPDATE_QUESTION, update_default(remembered) or '',
                                              lambda s: updates.parse(s) is not None or updates.WRONG))
    updates.look(p.update_repo)

    if 'apk' not in given:
        p.apk = ask_app(p.update_repo)

    if 'sounds' not in given:
        p.sounds = sounds.pick(remembered.get('sounds', sounds.DEFAULT))

    if 'offline_models' not in given and not paths.INSTALLED:
        extras = ui.checkbox('Extras:', [
            ('Offline Translator: speech and ten language pairs on the R1 (1.4 GB, needs the NDK)',
             'offline_models', remembered.get('offline_models', False)),
        ])
        p.offline_models = 'offline_models' in extras
    return p


def ask_app(repo: str | None) -> str | None:
    """None to build this checkout, apk.LATEST for [repo]'s latest release, an APK's path, or
    (installed, with no source here) a checkout's. Building offers what it is missing;
    declined, the question comes back."""
    ui.info(APP_WHY)
    here = not paths.INSTALLED
    choices = [('Build from source', 'build')] if here else []
    if repo:
        choices.append((f'The latest release of {repo}', apk.LATEST))
    choices.append(('An APK file', 'file'))
    if not here:
        choices.append(('Build from a checkout of the source', 'source'))
    default = 'build' if here and not prereqs.missing_for_build() else apk.LATEST if repo else 'file'
    while True:
        pick = ui.select(APP_QUESTION, choices, default=default)
        if pick in ('build', 'source'):
            if prereqs.ensure_build():
                return None if pick == 'build' else _ask_path('The checkout\'s folder:', Path.is_dir)
            default = apk.LATEST if repo else 'file'
            continue
        if pick == apk.LATEST:
            return apk.LATEST
        return _ask_path('Path to the APK:', Path.is_file)


def _ask_path(question: str, exists) -> str:
    path = ui.path(question)
    while not exists(Path(path).expanduser()):
        path = ui.path(f'Not there. {question}')
    return path


def update_default(remembered: dict) -> str | None:
    """The answer given last time for this R1, else the repository the build names."""
    return remembered.get('update_repo') or paths.update_repo()


def unasked(p: Plan, given: set, remembered: dict) -> Plan:
    """With nothing to ask: the defaults ask() would offer, where the plan needs an answer."""
    if 'update_repo' not in given:
        default = update_default(remembered)
        if not default:
            ui.fail('Nothing was changed: where this C1 gets its updates needs --update-repo OWNER/REPO '
                    'when nothing can be asked (android/gradle.properties sets no croissant.updateRepo).')
        p.update_repo = updates.check(default)
    updates.look(p.update_repo)
    if 'sounds' not in given:
        p.sounds = remembered.get('sounds', sounds.DEFAULT)
    return p


def show(p: Plan, will_flash: bool, where: str):
    rows = [('R1', where), ('Name', p.name)]
    if will_flash:
        rows.append(('LineageOS', p.gsi.label + ('' if p.gsi.proven else '  (untried)')))
    app = f'the latest release of {p.update_repo}' if p.apk == apk.LATEST else p.apk
    rows += [('App', app or 'built from source'),
             ('Updates', p.update_repo or 'the app\'s own'),
             ('Sounds', sounds.PACKS[p.sounds].split(':')[0]),
             ('Magisk', p.magisk or 'v30.7 (proven)'),
             ('Tailscale', p.tailscale or 'the latest stable'),
             ('Extras', 'offline Translator' if p.offline_models else 'none')]
    ui.summary(rows)
