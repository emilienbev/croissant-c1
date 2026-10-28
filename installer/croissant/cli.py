"""croissant: put Croissant on a Rabbit R1.

  ./croissant install        asks what it needs, and every question also has a flag (--help)
  ./croissant doctor         every check, nothing changed
  ./croissant app install    an APK, or one built from a source checkout, onto an installed R1
  ./croissant boot-screens   render the boot logo and animation for a name
  ./croissant font           take the owner's Power Grotesk and put it on the R1
  ./croissant sounds         pick the interface sounds, hearing each, and put them on an installed R1
  ./croissant fetch          download and check the proven files (and mtkclient) ahead of time
  ./croissant atelier new    a small Atelier app to start from, in a new folder, with its QR code
  ./croissant atelier run    an Atelier app from a folder onto the C1, again on every save
  ./croissant atelier qr     an Atelier app's QR code again, for a C1 to scan
  croissant upgrade          the CLI installed from a release, to the latest one
"""
import argparse
import sys
from pathlib import Path

from . import __version__, apk, artifacts, atelier, brand, device, font, gsi, magisk, paths, plan, preloader, prereqs, sounds, stages, ui, upgrade
from .tools import Adb, Fastboot, binaries

FONT_HELP = 'the Power Grotesk zip from BeFonts, or a folder holding its TTFs'
SERIAL_HELP = 'which R1, when more than one is plugged in'


def _tools(serial):
    adb_exe, fb_exe = binaries()
    return Adb(adb_exe, serial), Fastboot(fb_exe, serial)


def cmd_install(args):
    p, given = plan.from_flags(args)
    adb, fb = _tools(args.serial)
    ctx = stages.Ctx(adb, fb, p)
    ui.hello('From rabbitOS to Croissant on a Rabbit R1, in about half an hour.',
             'Stopped or failed? Run it again and it carries on.')
    prereqs.ensure_usb()

    ui.step('The R1')
    with ui.spinner('looking for the R1'):
        where = device.locate(adb, fb, args.serial)
    described = {'android': 'on, running Android', 'lk': 'in the bootloader', 'fastbootd': 'in fastbootd',
                 'stock': 'on, running rabbitOS', 'none': 'not seen, which is fine if it is off'}
    ui.info(f'R1 {where.serial or ""} {described[where.mode]}'.replace('  ', ' '))

    on = where.mode == 'android' and device.on_gsi(adb)
    will_flash = not on or p.reflash
    if on and not p.reflash and ui.INTERACTIVE and 'gsi' not in given:
        fp = adb.getprop('ro.build.fingerprint').split('/')[1]
        will_flash = ui.select(f'It already runs {fp}.', [
            ('Keep it, and bring everything else up to date', False),
            ('Flash LineageOS again (erases the R1)', True)]) is True
        p.reflash = will_flash

    if will_flash and where.mode in ('stock', 'none') and ui.INTERACTIVE:
        # The preloader read checks this against frp before the unlock (stages.read_stock).
        allowed = ui.select('Has rabbit allowed this R1 to be unlocked?', [
            ('Yes: developer mode and device modification > unlock, in rabbithole', 'allowed'),
            ('Its bootloader is already unlocked', 'unlocked'),
            ('Not yet, show me how', False)])
        if not allowed:
            ui.banner('Before anything else', stages.HOW_TO_ALLOW)
            return
        ctx.said_unlocked = allowed == 'unlocked'

    font.ensure(args.font)                # before anything renders with it
    ui.step('The plan')
    remembered = ctx.state() if where.serial else {}
    if ui.INTERACTIVE:
        p = ctx.plan = plan.ask(p, given, will_flash, remembered)
        ui.console.print()
    else:
        plan.unasked(p, given, remembered)
    plan.show(p, will_flash, where.serial or 'plugged in when asked')
    if ui.INTERACTIVE and not ui.confirm('Go ahead?'):
        ui.fail('Nothing was changed.')
    if will_flash and not ui.INTERACTIVE and not p.yes:
        ui.fail('Nothing was changed: this would erase the R1, which needs --yes when nothing can be asked.')

    stages.host(ctx, will_flash)
    if will_flash:
        stages.flash(ctx, where)
        stages.first_boot(ctx)
    ctx.remember(name=p.name, offline_models=p.offline_models, update_repo=p.update_repo, sounds=p.sounds)
    stages.root(ctx)
    stages.device_files(ctx)
    stages.offmode(ctx)
    stages.logo(ctx)
    stages.reboot_if_needed(ctx)
    stages.app(ctx, ctx.apk)
    stages.sound_pack(ctx)
    stages.fonts(ctx)
    stages.restart_app(ctx)
    if p.offline_models:
        stages.offline_models(ctx)
    stages.verify(ctx)
    ui.done('Continue the setup on the R1.')


def cmd_app_install(args):
    """A chosen app onto an installed R1. Updates otherwise come over the air (Settings > update)."""
    adb, fb = _tools(args.serial)
    ctx = stages.Ctx(adb, fb)
    prereqs.ensure_usb()
    where = device.locate(adb, fb, args.serial)
    if where.mode != 'android' or not device.on_gsi(adb):
        ui.fail(f'app install needs an installed R1, booted, on USB. For a new one: {paths.COMMAND} install')
    path = apk.local(args.path)
    adb.root()
    stages.app(ctx, path)
    stages.restart_app(ctx)


def cmd_doctor(args):
    """Every check, nothing changed."""
    adb, fb = _tools(args.serial)
    ui.step('This machine')
    for a in (gsi.PROVEN.artifact, artifacts.MAGISK):
        (ui.ok if artifacts.have(a) else ui.info)(f'{a.name} {"cached" if artifacts.have(a) else "not downloaded yet"}')
    has = font.have()
    (ui.ok if has else ui.warn)('Power Grotesk' if has else f'Power Grotesk not here ({paths.COMMAND} font)')
    prereqs.report()
    if preloader.installed():
        ui.ok(f'mtkclient {preloader.COMMIT[:7]}, libusb {preloader.libusb()}')
    else:
        ui.info(f'mtkclient {preloader.COMMIT[:7]} not installed yet')
    where = device.locate(adb, fb, args.serial)
    ui.step(f'R1 {where.serial or ""}: {where.mode}')
    if where.serial:
        # On Android the live slot's copy must be of the firmware it runs; the other slot's
        # may be an older one.
        live = adb.getprop('ro.boot.slot_suffix').removeprefix('_') if where.mode == 'android' else ''
        running = adb.sh('cat /proc/version', check=False) if live else None
        kept = [f'boot_{s}' for s in preloader.SLOTS
                if preloader.cached(where.serial, s, running if s == live else None)]
        (ui.ok if kept else ui.info)(f'stock {" and ".join(kept)} cached' if kept else
                                     'no stock boot image cached (read over the preloader when Magisk needs it)')
    if where.mode in ('lk', 'fastbootd'):
        ui.info(f'unlocked: {fb.getvar("unlocked")}, current-slot: {fb.getvar("current-slot")}')
        return
    if where.mode != 'android':
        ui.info('boot it into Android (or run install) for the rest')
        return
    if not device.on_gsi(adb):
        ui.warn(f'not a GSI: {adb.getprop("ro.build.fingerprint")}')
        return
    ui.ok(adb.getprop('ro.build.fingerprint').split('/')[1])
    device.check_vendor(adb)
    root = adb.root()
    (ui.ok if root else ui.warn)('root through Magisk su' if root else 'no root (Magisk su not granted to the adb shell)')
    v = magisk.version(adb)
    (ui.ok if v else ui.warn)(f'Magisk {v or "absent"}')
    if not root:
        return
    ui.ok('Magisk environment') if v and magisk.env_ok(adb, v) else ui.warn('Magisk environment missing')
    (ui.ok if magisk.granted(adb, 2000) else ui.warn)('root for adb')
    features = adb.sh('pm list features', check=False)
    (ui.ok if 'device_admin' in features else ui.warn)('device_admin feature')
    layouts = adb.script('ls /data/system/devices/keylayout', 'ls-keylayout', check=False)
    for k in stages.KEYLAYOUTS:
        (ui.ok if k in layouts else ui.warn)(f'key layout {k}')
    ts = adb.script('/data/adb/tailscale/ts status --json 2>/dev/null | grep -m1 BackendState',
                    'ts-status', check=False).strip().rstrip(',')
    if ts:
        ui.ok(f'tailscaled {ts}')
    else:                                 # its boot script waits for Wi-Fi before starting it
        why = adb.script('tail -n1 /data/adb/tailscale/boot.log', 'ts-boot', check=False).strip()
        ui.warn(f'tailscaled not answering{f" ({why})" if why else ""}')
    hook = adb.script('ls "$(magisk --path)/croissant-offmode.sh"', 'ls-offmode',
                      check=False).strip().endswith('croissant-offmode.sh')
    (ui.ok if hook else ui.warn)('boot ramdisk hook croissant-offmode' if hook else 'boot ramdisk hook croissant-offmode missing')
    lk = adb.script("grep -m1 'LK_ENV.off-mode-charge' /proc/pl_lk", 'pl-lk', check=False).strip()
    (ui.ok if lk.endswith('=0') else ui.warn)(f'LK read {lk.split("]")[-1] or "no off-mode-charge"}')
    installed = adb.sh(f'pm path {paths.PACKAGE}', check=False)
    (ui.ok if installed else ui.warn)('Croissant installed' if installed else 'Croissant not installed')
    if installed:
        on = stages.font_on_device(adb)
        (ui.ok if on else ui.warn)('Power Grotesk on the R1' if on else f'Power Grotesk not on the R1 ({paths.COMMAND} font)')
    off = stages.indicators_off(adb)
    (ui.ok if off else ui.warn)('privacy indicators off' if off else 'a privacy indicator is on')
    still = stages.bars_ok(adb)
    (ui.ok if still else ui.warn)('no system bars on an edge swipe' if still else
                                  'an edge swipe can show the system bars (user_setup_complete is not 0)')
    shown = stages.display_ok(adb)
    (ui.ok if shown else ui.warn)(f'density {stages.DENSITY}, 24-hour clock, no window transitions' if shown else
                                  f'density, clock or window transitions not set (wants {stages.DENSITY}, 24-hour, '
                                  'transition_animation_scale 0)')
    (ui.ok if stages._is_owner(adb) else ui.warn)('Device Owner')
    locked = 'LOCKED' in adb.sh('dumpsys activity activities | grep mLockTaskModeState', check=False)
    (ui.ok if locked else ui.warn)('lock task')


def cmd_boot_screens(args):
    brand.check_name(args.name)
    if args.name != brand.DEFAULT_NAME:
        font.ensure(args.font)
    ui.step('Boot screens')
    logo, anim = brand.preview(args.name, Path(args.out))
    ui.ok(f'logo {logo}')
    ui.ok(f'animation {anim}')


def cmd_font(args):
    """The font on its own: kept here, then onto an installed R1 if one is plugged in."""
    font.ensure(args.font)
    adb, fb = _tools(args.serial)
    where = device.locate(adb, fb, args.serial)
    if where.mode != 'android' or not device.on_gsi(adb) or not adb.sh(f'pm path {paths.PACKAGE}', check=False):
        ui.info(f'No installed R1 on USB. Plug one in and run {paths.COMMAND} font again.')
        return
    ctx = stages.Ctx(adb, fb)
    stages.fonts(ctx)
    stages.restart_app(ctx)


def cmd_sounds(args):
    """The interface sounds on their own, onto an installed R1: picked by ear unless named."""
    adb, fb = _tools(args.serial)
    where = device.locate(adb, fb, args.serial)
    if where.mode != 'android' or not device.on_gsi(adb) or not adb.sh(f'pm path {paths.PACKAGE}', check=False):
        ui.fail(f'sounds needs an installed R1, booted, on USB. For a new one: {paths.COMMAND} install')
    ctx = stages.Ctx(adb, fb)
    if args.pack:
        ctx.plan.sounds = sounds.check(args.pack)
    elif ui.INTERACTIVE:
        ctx.plan.sounds = sounds.pick(ctx.state().get('sounds', sounds.DEFAULT))
    else:
        ui.fail(f'Nothing was changed: name the pack when nothing can be asked ({", ".join(sounds.PACKS)}).')
    stages.sound_pack(ctx)
    stages.restart_app(ctx)
    ctx.remember(sounds=ctx.plan.sounds)


def cmd_atelier_new(args):
    atelier.new(args)


def cmd_atelier_qr(args):
    atelier.make_qr(args)


def cmd_atelier_run(args):
    adb, fb = _tools(args.serial)
    atelier.run(adb, fb, args)


def cmd_fetch(args):
    wanted = [gsi.PROVEN.artifact, artifacts.MAGISK, artifacts.tailscale(None)]
    if args.seed:
        artifacts.seed([Path(d) for d in args.seed], wanted)
    for a in wanted:
        artifacts.fetch(a)
    artifacts.image(gsi.PROVEN.artifact.path)
    preloader.ensure()
    ui.ok(f'everything in {artifacts.CACHE}')


def main():
    p = argparse.ArgumentParser(prog='croissant', description='Put Croissant on a Rabbit R1.')
    p.add_argument('--version', action='version', version=f'croissant {__version__}')
    p.add_argument('--no-input', action='store_true',
                   help='ask nothing: take every answer from the flags (also when not in a terminal)')
    sub = p.add_subparsers(dest='cmd', required=True)

    i = sub.add_parser('install', help='from rabbitOS (or wherever it got to) to Croissant',
                       description='Asks for anything the flags leave open, unless --no-input.')
    i.add_argument('--name', help='the name on the boot screens (default croissant)')
    i.add_argument('--gsi', metavar='proven|URL|FILE',
                   help='the LineageOS GSI: the proven build (default), a URL, or a local '
                        '.img/.img.gz/.img.xz')
    i.add_argument('--gsi-checksum', metavar='HEX', help='sha256 or md5 for a --gsi URL')
    i.add_argument('--apk', metavar='FILE|DIR|latest',
                   help='a Croissant APK, a checkout of the source to build, or latest: the update '
                        'repository\'s latest release (default: built from this checkout; installed, latest)')
    i.add_argument('--font', metavar='PATH', help=FONT_HELP + ' (needed with --no-input unless kept before)')
    i.add_argument('--magisk', metavar='FILE|latest|TAG', help='another Magisk than the proven v30.7')
    i.add_argument('--tailscale', metavar='latest|VERSION', help='a tailscale version (default: the latest stable)')
    i.add_argument('--update-repo', metavar='OWNER/REPO',
                   help='the GitHub repository whose releases the C1 installs (default: the last '
                        f'answer, else {paths.update_repo()}, from android/gradle.properties)')
    i.add_argument('--sounds', metavar='PACK', help=f'the interface sounds, one of {", ".join(sounds.PACKS)} '
                                                    f'(default: picked by ear; with --no-input, the last answer, '
                                                    f'else {sounds.DEFAULT})')
    i.add_argument('--offline-models', action='store_true',
                   help='also push the offline Translator models (1.4 GB, needs the NDK)')
    i.add_argument('--reflash', action='store_true', help='flash LineageOS even if the R1 already runs it (erases it)')
    i.add_argument('--serial', help=SERIAL_HELP)
    i.add_argument('--yes', action='store_true', help='do not ask before erasing the R1')
    i.set_defaults(fn=cmd_install)

    d = sub.add_parser('doctor', help='check every stage, change nothing')
    d.add_argument('--serial', help=SERIAL_HELP)
    d.set_defaults(fn=cmd_doctor)

    ap = sub.add_parser('app', help='the Croissant app on an installed R1')
    apsub = ap.add_subparsers(dest='app_cmd', required=True)
    u = apsub.add_parser('install', help='an APK, or one built from a source checkout, in place of the one there',
                         description='Updates come over the air (Settings > update on the C1); this puts a '
                                     'chosen app on it. It must be signed with the key the installed one was, '
                                     'or it is refused: an uninstall would drop Device Owner.')
    u.add_argument('path', metavar='APK|DIR', help='an .apk file, or a checkout of the source (its root or '
                                                   'android/), built here first')
    u.add_argument('--serial', help=SERIAL_HELP)
    u.set_defaults(fn=cmd_app_install)

    b = sub.add_parser('boot-screens', help='render the boot logo and animation for a name')
    b.add_argument('name', help='the name to draw')
    b.add_argument('--out', metavar='DIR', default=str(paths.CACHE / 'boot-screens'),
                   help='where logo.png and animation.png go (default: cache/boot-screens)')
    b.add_argument('--font', metavar='PATH', help=FONT_HELP + ' (a name other than croissant needs it)')
    b.set_defaults(fn=cmd_boot_screens)

    t = sub.add_parser('font', help='take the owner\'s Power Grotesk and put it on the R1')
    t.add_argument('--font', metavar='PATH', help=FONT_HELP)
    t.add_argument('--serial', help=SERIAL_HELP)
    t.set_defaults(fn=cmd_font)

    sn = sub.add_parser('sounds', help='pick the interface sounds, hearing each, and put them on an installed R1',
                        description='Moving onto a pack plays its demo on this machine (afplay, or ffplay): a '
                                    'user scrolling, opening Settings, flipping a switch, the Wi-Fi dropping and '
                                    'coming back, a photo kept and one deleted. The app starts again with them.')
    sn.add_argument('pack', nargs='?', metavar='PACK', help=f'one of {", ".join(sounds.PACKS)}, to skip the picker')
    sn.add_argument('--serial', help=SERIAL_HELP)
    sn.set_defaults(fn=cmd_sounds)

    f = sub.add_parser('fetch', help='download and check the proven files (and mtkclient) ahead of time')
    f.add_argument('--seed', nargs='*', help='directories that may already hold the files')
    f.set_defaults(fn=cmd_fetch)

    a = sub.add_parser('atelier', help='Atelier apps from this computer (docs/atelier.md)')
    asub = a.add_subparsers(dest='atelier_cmd', required=True)
    an = asub.add_parser('new', help='a small working app to start from, in a new folder, with its QR code',
                         description='Asks for the app\'s name (its folder and GitHub repository), its GitHub '
                                     'owner, its name on the C1, a line about it, its hue, a Lucide glyph '
                                     '(searched) and what it needs (the network, the chat model, your words, a '
                                     'tool for Ask). Then it makes the folder: croissant.json, croissant.js (a '
                                     'count the wheel and the keys move, and each feature asked for in use), a '
                                     'README with the QR code a C1 scans, qr.svg and qr.png, as a git repository '
                                     'with a first commit; and offers to publish it with gh. Each question has a '
                                     'flag; with --no-input the rest take their defaults.')
    an.add_argument('name', nargs='?', help='the app\'s name: the folder (made beside it, its name lowercase '
                                            'letters, digits and dashes) and the GitHub repository')
    an.add_argument('--owner', metavar='OWNER', help='its GitHub user or organisation (default: gh\'s login, '
                                                     'else git\'s github.user); "" for none yet, and no QR code')
    an.add_argument('--title', metavar='NAME', help='its name on the C1, up to 24 (default: from the app\'s name)')
    an.add_argument('--description', metavar='TEXT', help='a line about it, for the README and the repository')
    an.add_argument('--hue', metavar='#RRGGBB', help='its colour (default: one of Beam\'s, from the name)')
    an.add_argument('--glyph', metavar='NAME', help='a Lucide icon\'s name (default: the best match for its name)')
    an.add_argument('--uses', metavar='LIST', type=lambda s: [u for u in s.split(',') if u],
                    help='what it uses, comma separated, from network, model and words ("" for nothing)')
    an.add_argument('--tool', metavar='NAME', help='a tool for Ask, by this name (lowercase, digits and _)')
    an.add_argument('--tool-description', metavar='TEXT', help='what the tool does, for the model')
    an.add_argument('--tool-example', metavar='TEXT', help='a sentence that should call the tool')
    an.add_argument('--no-git', action='store_true', help='no git repository, no first commit')
    an.set_defaults(fn=cmd_atelier_new)
    aq = asub.add_parser('qr', help='an app\'s QR code again: qr.svg and qr.png, of its GitHub link',
                         description='For an app\'s folder: its hue and glyph from croissant.json, its repository '
                                     'from the folder\'s git remote (else its name and --owner), written into the '
                                     'folder, and the code put in its README (under the title if it has none; --readme makes the '
                                     'whole README again). For owner/repo: '
                                     '--hue is needed, written into the current folder.')
    aq.add_argument('target', nargs='?', metavar='FOLDER|OWNER/REPO', help='the app\'s folder (default: here), '
                                                                       'or its repository')
    aq.add_argument('--owner', metavar='OWNER', help='the repository\'s GitHub user or organisation')
    aq.add_argument('--hue', metavar='#RRGGBB', help='in place of croissant.json\'s')
    aq.add_argument('--glyph', metavar='NAME', help='in place of croissant.json\'s')
    aq.add_argument('--out', metavar='DIR', help='where qr.svg and qr.png go')
    aq.add_argument('--readme', action='store_true', help='the folder\'s README made again from croissant.json, '
                                                         'the code centred under its title')
    aq.add_argument('--description', metavar='LINE', help='with --readme: the app\'s line under its title '
                                                          '(default: the README\'s first paragraph)')
    aq.set_defaults(fn=cmd_atelier_qr)
    ar = asub.add_parser('run', help='push an app folder onto the C1, again on every save, and show its log',
                         description='The folder holds croissant.json and croissant.js; its name, made '
                                     'lowercase letters, digits and dashes, is the app\'s id on the C1. Its '
                                     'state there is kept across pushes unless --fresh.')
    ar.add_argument('folder', help='the app\'s folder')
    ar.add_argument('--once', action='store_true', help='push it once and stop')
    ar.add_argument('--fresh', action='store_true', help='drop its saved state on the C1 first')
    ar.add_argument('--serial', help=SERIAL_HELP)
    ar.set_defaults(fn=cmd_atelier_run)

    up = sub.add_parser('upgrade', help='this CLI, installed from a release, to the latest one',
                        description='For the CLI install.sh put in ~/.croissant: the latest release of the '
                                    'repository it came from, beside this one, then switched to. A checkout of '
                                    'the source is brought up to date with git pull.')
    up.add_argument('--to', metavar='X.Y.Z', help='that release instead, older ones too')
    up.set_defaults(fn=upgrade.run)

    for c in (i, d, u, t, sn, ar):
        c.add_argument('--package', metavar='ID',
                       help=f'the app\'s application id (default {paths.PACKAGE}, from android/gradle.properties)')

    args = p.parse_args()
    if getattr(args, 'package', None):
        paths.PACKAGE = args.package
    if args.no_input:
        ui.INTERACTIVE = False
    try:
        args.fn(args)
    except ui.Abort as e:
        ui.console.print(f'\n[red]{ui.escape(str(e))}[/]')
        sys.exit(1)
    except KeyboardInterrupt:
        ui.console.print('\nStopped. Run the same command again to carry on.')
        sys.exit(130)


if __name__ == '__main__':
    main()
