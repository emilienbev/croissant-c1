"""The install, as stages that each check before they act.

Run it again at any point and it carries on from where the R1 is: a stage whose check
passes is skipped. The only destructive part, the flash, runs only when the R1 is not
already on the GSI, and only after `erase` is typed.
"""
import json
import re
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path

from . import apk, artifacts, bootimg, brand, device, font, magisk, paths, preloader, sounds, ui, vbmeta
from .paths import CACHE, DEVICE, STAGE, load
from .plan import Plan
from .tools import Adb, Fastboot

KEYLAYOUTS = ('mtk-kpd.kl', 'och1970_holl_key.kl')
SOURCE_FILE = 'update-source'    # the app's name for it, Updater.SOURCE_FILE

HOW_TO_ALLOW = '''rabbit has to allow the unlock first, and only rabbit can:

  1. In rabbithole (the rabbit website), settings > r1: read the warranty warning, type
     the sentence it asks for, enter the R1's IMEI (on the R1: settings > about), and
     choose "void warranty and enable developer mode".
  2. With the R1 on and online, back in settings > r1 > developer > device modification,
     choose unlock. That grants the permission. The installer does the unlock itself.

Then run '''  + paths.COMMAND + ''' install again.'''


@dataclass
class Ctx:
    adb: Adb
    fb: Fastboot
    plan: Plan = field(default_factory=Plan)
    # Resolved on the Mac by host(), before the R1 is touched.
    apk: Path | None = None
    system: Path | None = None           # the GSI as a raw ext4 image
    magisk_apk: Path | None = None
    magisk_code: str = ''
    tailscale: Path | None = None        # the directory holding tailscaled and tailscale
    reboot_needed: bool = False
    said_unlocked: bool = False          # answered "its bootloader is already unlocked"
    frp_allows: bool | None = None       # rabbit's permission, as the preloader read found it
    read: preloader.Read | None = None   # the pre-unlock read, until the R1's serial is known
    restart_needed: bool = False         # the app, to start again once the stages are done (restart_app)

    @property
    def name(self) -> str:
        return self.plan.name

    @property
    def work(self) -> Path:
        d = CACHE / 'devices' / (self.adb.serial or 'unknown')
        d.mkdir(parents=True, exist_ok=True)
        return d

    def state(self) -> dict:
        p = self.work / 'state.json'
        return json.loads(p.read_text()) if p.exists() else {}

    def remember(self, **kv):
        s = self.state() | kv
        (self.work / 'state.json').write_text(json.dumps(s, indent=1))


# --- the Mac side, all before the R1 is touched ------------------------------------

def host(ctx: Ctx, will_flash: bool):
    ui.step('On this machine')
    p = ctx.plan
    if will_flash:
        g = p.gsi
        with ui.spinner(f'getting {g.label}'):
            src = artifacts.fetch(g.artifact) if g.artifact else Path(g.path).expanduser()
            if not src.exists():
                ui.fail(f'{src}: no such file')
            ctx.system = artifacts.image(src)
        ui.ok(f'{g.label} ready')
    with ui.spinner('getting Magisk and tailscale'):
        m = artifacts.magisk(p.magisk)
        ctx.magisk_apk = m if isinstance(m, Path) else artifacts.fetch(m)
        ctx.magisk_code = artifacts.magisk_version_code(ctx.magisk_apk)
        ctx.tailscale = artifacts.tailscale_binaries(artifacts.tailscale(p.tailscale))
    ui.ok(f'Magisk {ctx.magisk_code} and tailscale ready')
    with ui.spinner('getting the app'):
        ctx.apk = apk.resolve(p.apk, p.update_repo)
    ui.ok(f'app {ctx.apk}')
    with ui.spinner('drawing the boot screens'):
        brand.check_name(ctx.name)
    ui.ok(f'boot screens for "{ctx.name}"')


# --- getting into the bootloader -----------------------------------------------------

def to_lk(ctx: Ctx, where: device.Where, after_read: bool = False):
    fb, adb = ctx.fb, ctx.adb
    if where.mode == 'lk':
        return
    if where.mode == 'fastbootd':
        fb.run('reboot', 'bootloader', show=False)
    elif where.mode == 'android':
        adb.reboot('bootloader')
    else:
        # From rabbitOS or from off: the preloader handshake. There are no
        # key combos on the R1; this is what rabbit's own web flash tool does.
        ef = load('scripts/enter-fastboot.py', 'enter_fastboot')
        # The listener's first look is taken before the person is told anything.
        baseline = ef.candidates()
        if after_read:
            # Still in download mode from the read: a long hold restarts it, and plugged in,
            # its preloader shows on the way up. Unplugged, it would boot rabbitOS instead.
            must_go, wait_s = ef.mtk_ports(baseline), 180
            ui.todo('Keep the R1 plugged in and hold the side button about 15 s until it restarts.')
            ui.info('Waiting up to 3 minutes for its preloader, which shows for 1.5 s as it restarts.')
        else:
            must_go, wait_s = (), 120
            ui.todo('Turn the R1 completely off, unplug it, then plug it back in.')
            ui.info('Waiting up to 2 minutes for its preloader, which shows for 1.5 s once plugged in.')
        try:
            with ui.spinner('waiting for the preloader'):
                port = ef.send_fastboot(baseline, timeout_s=wait_s, must_go=must_go)
        except ef.NoPreloader as e:
            if after_read:
                ui.fail(f'{e}. Nothing was changed on the R1. Keep it plugged in, hold the side '
                        'button about 15 s, and run again. If it keeps happening, use a cable that '
                        'carries data, or another port.')
            ui.fail(f'{e}. Use a cable that carries data, another port, and make sure the R1 '
                    'is fully off, then run again.')
        ui.ok(f'sent FASTBOOT to the preloader ({port})')
        fb.serial = None                 # the serial is only known once it is in fastboot
    with ui.spinner('waiting for the bootloader'):
        fb.wait(userspace=False, timeout=120)
    if not fb.serial:
        serials = fb.devices()
        fb.serial = ctx.adb.serial = serials[0]
    ui.ok(f'R1 {fb.serial} in the bootloader')


# --- the stock boot images, over the preloader ---------------------------------------

def read_stock(ctx: Ctx):
    """rabbit's boot images (both slots) and its unlock permission, read over the preloader
    while the bootloader is still locked: mtkclient needs no unlock and writes nothing."""
    ui.step('Reading the stock boot image')
    preloader.ensure()
    ui.info('Magisk needs rabbit\'s own boot image, read over the preloader. Nothing is written.')
    ui.todo('Turn the R1 off and unplug it. On rabbitOS, power off from the side button\'s menu. '
            'If it will not turn off, hold the side button about 15 s.')
    ui.pause('Press Enter once the R1 is off and unplugged.')
    again = 'Unplug it, hold the side button about 15 s, and run again.'
    with ui.spinner('reading boot_a, boot_b and frp over the preloader'):
        r = preloader.check(preloader.read(again=again))
    bad = [f'boot_{s}: {why}' for s, why in r.notes.items()]
    if bad:
        ui.fail(f'mtkclient ran, but {". ".join(bad)}. Nothing was written to the R1. {again}')
    if r.frp is None:
        ui.fail(f'mtkclient did not read frp. Nothing was written to the R1. {again}')
    ui.ok('boot_a and boot_b read, both rabbit\'s')
    ctx.frp_allows = r.frp
    if r.frp:
        ui.ok('frp: rabbit allows the unlock')
    elif ctx.said_unlocked:
        ui.warn('frp says rabbit has not allowed the unlock. Carrying on, since the bootloader '
                'is already unlocked.')
    else:
        ui.fail('Nothing was changed: frp says rabbit has not allowed this R1 to be unlocked yet. '
                'Unplug it and hold the side button about 15 s, then turn it on.\n\n' + HOW_TO_ALLOW)
    ctx.read = r


def _stock_boot(ctx: Ctx, slot: str) -> bytes:
    """The live slot's stock boot image, of the firmware it runs: from the cache, or the
    trimmed copy an earlier installer kept, else read now."""
    adb = ctx.adb
    running = adb.sh('cat /proc/version', check=False)
    for serial in dict.fromkeys((adb.serial, ctx.fb.serial)):
        if p := preloader.cached(serial, slot, running):
            ui.ok(f'stock boot_{slot} from {p}')
            return p.read_bytes()
        if serial and (p := preloader.slot_dir(serial) / f'boot_{slot}.bin').exists():
            why = preloader.stock_problem(p.read_bytes(), running) or 'not a good read'
            ui.info(f'{p} not used: {why}')
    legacy = ctx.work / 'stock-boot.img'  # what installers before the preloader read kept
    if legacy.exists():
        raw = legacy.read_bytes()
        why = preloader.stock_problem(raw, running)
        if not why:
            ui.ok(f'stock boot_{slot} from {legacy}')
            return raw
        ui.info(f'{legacy} not used: {why}')
    if v := magisk.version(adb):
        ui.fail(f'Nothing was changed: Magisk {v} is installed, so boot_{slot} on this R1 is its patched '
                f'image, and this machine has no copy of rabbit\'s own. A read over the preloader '
                f'would only find the patched one. Magisk has to patch rabbit\'s image for this '
                f'firmware ({bootimg.kernel_id(running) or running.strip()}). Put a copy of it at '
                f'{legacy} (trimmed or a whole partition read), then run again. Without one, rabbit\'s '
                'flash tool (https://rabbit-hmi-oss.github.io/flashing/) puts rabbitOS back, and an '
                'install from there reads it.')
    raw = _read_from_android(ctx, slot)
    if why := preloader.firmware_problem(raw, running):
        ui.fail(f'boot_{slot} as read is not the firmware this R1 runs ({why}). Not patching it.')
    return raw


def _read_from_android(ctx: Ctx, slot: str) -> bytes:
    """An R1 already on the GSI, with rabbit's boot_<slot> and no copy of it here: off over
    adb, the preloader read, then back to Android."""
    adb = ctx.adb
    ui.step('Reading the stock boot image')
    preloader.ensure()
    ui.info(f'Magisk needs rabbit\'s boot_{slot}, read over the preloader. Nothing is written.')
    adb.reboot('-p')
    ui.todo('The R1 is turning off. Unplug it.')
    ui.pause('Press Enter once it is unplugged and its screen is dark.')
    again = 'Hold the side button about 15 s, turn the R1 on, and run again.'
    with ui.spinner(f'reading boot_{slot} over the preloader'):
        r = preloader.check(preloader.read(again=again))
    if slot not in r.boots:
        why = r.notes[slot]
        if why.startswith('already patched'):
            ui.fail(f'boot_{slot} on this R1 is {why}, and this machine has no stock copy of it. '
                    'Magisk has to patch rabbit\'s own image: put it back (rabbit\'s flash tool, or '
                    f'fastboot flash boot_{slot} with a stock image of the same firmware), then run '
                    f'again. {again}')
        ui.fail(f'mtkclient ran, but boot_{slot}: {why}. Nothing was written to the R1. {again}')
    kept = preloader.adopt(r.boots, adb.serial)
    ui.ok(f'stock boot_{slot} kept in {kept[slot].parent}')
    ui.todo('Keep it plugged in and hold the side button about 15 s to restart it out of download '
            'mode. If LineageOS does not start, hold the side button again to turn it on.')
    with ui.spinner('waiting for LineageOS'):
        adb.wait_boot(timeout=600)
    return kept[slot].read_bytes()


# --- the flash ----------------------------------------------------------------------

def unlock(ctx: Ctx):
    fb = ctx.fb
    with ui.spinner('asking the bootloader whether it is unlocked'):
        unlocked = fb.getvar('unlocked') == 'yes'
    if unlocked:
        ui.ok('bootloader already unlocked')
        return
    if ctx.frp_allows is False:
        ui.fail('Nothing was changed: the bootloader is locked, and frp says rabbit has not allowed '
                'the unlock.\n\n' + HOW_TO_ALLOW)
    ui.step('Unlocking the bootloader')
    ui.todo('If the R1 asks to confirm the unlock, scroll the wheel up (Volume Up = Yes).')
    with ui.spinner('unlocking'):
        out = fb.run('flashing', 'unlock', check=False, timeout=300)
        time.sleep(3)
        fb.wait(userspace=False, timeout=120)
        unlocked = fb.getvar('unlocked') == 'yes'
    if not unlocked:
        ui.fail('still locked. If fastboot refused the command, rabbit has not granted the '
                'unlock yet: rabbithole > settings > r1 > developer > device modification > '
                f'unlock, with the R1 online, then run again.\n{out.strip()}')
    ui.ok('unlocked')


def flash(ctx: Ctx, where: device.Where):
    """Everything destructive: unlock, AVB off, the GSI, the wipe."""
    fb = ctx.fb
    ui.confirm_erase(ctx.plan.yes)
    if where.mode in ('stock', 'none'):
        read_stock(ctx)
    to_lk(ctx, where, after_read=ctx.read is not None)
    if ctx.read:
        kept = preloader.adopt(ctx.read.boots, ctx.fb.serial)
        ui.ok(f'stock boot images kept in {next(iter(kept.values())).parent}')
        ctx.read = None
    unlock(ctx)

    # LK's current-slot lied on the first unit (said b, booted a). fastbootd, which runs
    # in the booted kernel, is the one to trust.
    ui.step('Finding the live slot')
    with ui.spinner('restarting into fastbootd to ask it'):
        fb.run('reboot', 'fastboot', show=False)
        fb.wait(userspace=True)
        slot = fb.getvar('current-slot').removeprefix('_')
    if slot not in ('a', 'b'):
        ui.fail(f'fastbootd reported slot {slot!r}')
    ctx.remember(slot=slot)
    ui.ok(f'slot {slot}')

    ui.step('Turning off verified boot (both slots)')
    with ui.spinner('writing a blank vbmeta to both slots'):
        fb.run('reboot', 'bootloader', show=False)
        fb.wait(userspace=False)
        fb.run('set_active', slot, show=False)
        blank = ctx.work / 'vbmeta-blank.img'
        blank.write_bytes(vbmeta.blank())
        for s in ('a', 'b'):
            out = fb.run('--disable-verity', '--disable-verification', 'flash', f'vbmeta_{s}', str(blank))
            if 'Rewriting vbmeta struct' not in out:
                ui.fail('fastboot did not rewrite the vbmeta flags, so the GSI would fail verity')
    ui.ok(f'verified boot off on both slots, slot {slot} active')

    ui.step('Flashing LineageOS (about 2 minutes)')
    with ui.spinner(f'flashing system_{slot}'):
        fb.run('reboot', 'fastboot', show=False)
        fb.wait(userspace=True)
        fb.run('snapshot-update', 'cancel', check=False, show=False)
        fb.run('flash', f'system_{slot}', str(ctx.system), timeout=900)
    ui.ok(f'LineageOS on system_{slot}')

    # `fastboot -w` would erase the partition named metadata, which is unused here; the
    # real /metadata is md_udc, and stock keys left on it keep /data from coming up.
    ui.step('Wiping user data')
    with ui.spinner('erasing userdata and md_udc'):
        fb.run('erase', 'userdata', show=False)
        fb.run('erase', 'md_udc', show=False)
        fb.run('reboot', show=False)
    ctx.remember(flashed=True, gsi=ctx.plan.gsi.file_name)
    ui.ok('flashed. The first boot is slow, and the camera turning is a good sign.')


def first_boot(ctx: Ctx):
    """The GSI's first boot: adb is on and needs no dialog on this build."""
    adb = ctx.adb
    ui.step('Waiting for LineageOS to boot')
    try:
        with ui.spinner('waiting for its first boot (slow, the camera turning is a good sign)'):
            adb.wait_boot(timeout=900)
    except ui.Abort:
        ui.fail('adb never came up. This GSI ships with adb on. If it booted to a setup '
                'screen instead, finish it, turn on USB debugging '
                '(Settings > About phone > Build number x7 > Developer options), and run again.')
    if not device.on_gsi(adb):
        ui.fail(f'booted into {adb.getprop("ro.build.fingerprint")!r}, not the GSI')
    ctx.remember(fingerprint=adb.getprop('ro.build.fingerprint'))
    device.check_vendor(adb)
    with ui.spinner('putting the setup wizard and launcher out of the way'):
        _quiet_first_boot(adb)
    ui.ok('setup wizard and launcher out of the way')


def _quiet_first_boot(adb: Adb):
    # The setup wizard is optional on this build, and Lineage's launcher crash-loops on
    # the R1 (no app widgets); the app becomes HOME later. Setup is marked
    # complete for the install; device_files puts it back to 0 ([no_swipe_bars]).
    adb.sh('settings put secure user_setup_complete 1')
    adb.sh('settings put global device_provisioned 1')
    for pkg in ('org.lineageos.setupwizard', 'com.android.launcher3'):
        adb.sh(f'pm disable-user --user 0 {pkg}', check=False)
    adb.sh('svc power stayon usb', check=False)


# --- root ---------------------------------------------------------------------------

def root(ctx: Ctx):
    adb = ctx.adb
    if magisk.version(adb) == ctx.magisk_code:
        ui.step('Magisk')
        ui.ok(f'Magisk {ctx.magisk_code} running')
        if adb.getprop(f'init.svc.{magisk.GRANT_SERVICE}'):
            _wait_shell_root(ctx)         # its boot image has the grant; just booted, maybe
        _magisk_rest(ctx)                 # its /data/adb/magisk too, when that is missing
        return
    slot = adb.getprop('ro.boot.slot_suffix').removeprefix('_')
    ctx.remember(slot=slot)
    raw = _stock_boot(ctx, slot)          # may power-cycle the R1 for the preloader read
    ui.step('Magisk')
    stock = ctx.work / 'stock-boot.img'   # trimmed, and the way back
    stock_bytes = bootimg.trim(raw)
    stock.write_bytes(stock_bytes)
    with ui.spinner('patching rabbit\'s boot image with Magisk'):
        kit = magisk.kit(ctx.magisk_apk, ctx.work / 'magisk-kit')
        magisk.patch(adb, stock_bytes, kit, ctx.work)

    with ui.spinner(f'flashing the patched boot_{slot} and restarting'):
        adb.reboot('fastboot')
        ctx.fb.wait(userspace=True)
        ctx.fb.run('flash', f'boot_{slot}', str(ctx.work / 'magisk-patched-boot.img'))
        ctx.fb.run('reboot', show=False)
        adb.wait_boot()
    if magisk.version(adb) != ctx.magisk_code:
        ui.fail(f'Magisk is not running after the reboot (magisk -V: {magisk.version(adb)!r}). '
                f'The stock boot image is {stock}: fastboot flash boot_{slot} it to go back.')
    ui.ok('Magisk running')
    _wait_shell_root(ctx)
    _magisk_rest(ctx)                     # its /data/adb/magisk too, as root through su


def _wait_shell_root(ctx: Ctx, script_s: int = 120, su_s: int = 45):
    """The boot script in the patched ramdisk (magisk.OVERLAY) grants the adb shell once
    Android has booted. Wait for it to finish before the first su: a su before the grant
    would ask Grant/Deny on the R1, and a prompt that times out is remembered as a deny."""
    adb, prop = ctx.adb, f'init.svc.{magisk.GRANT_SERVICE}'
    deadline, state = time.time() + script_s, ''
    with ui.spinner('waiting for the boot script to give the adb shell root'):
        while time.time() < deadline and (state := adb.getprop(prop)) != 'stopped':
            time.sleep(2)
        deadline = time.time() + su_s
        while time.time() < deadline:
            if adb.has_su():
                ui.ok('root for the adb shell (granted by the boot script)')
                return
            time.sleep(3)
    ui.fail(f'Magisk is running, but the adb shell has no root: the boot script '
            f'{magisk.GRANT_SERVICE} (overlay.d in the patched boot image) did not grant it '
            f'({prop}: {state or "never started"}). Reboot the R1 and run again: it grants at '
            'every boot, over a deny too. If it still fails, allow Shell in Magisk > Superuser on '
            'the R1, then run again.')


def _version_code(adb: Adb, pkg: str) -> str:
    m = re.search(r'versionCode=(\d+)', adb.sh(f'dumpsys package {pkg}', check=False))
    return m[1] if m else ''


def _magisk_rest(ctx: Ctx):
    adb = ctx.adb
    # magiskd installs its stub under the same package when no app is there, so the
    # package being there proves nothing: its version code has to be the full app's.
    if _version_code(adb, 'com.topjohnwu.magisk') != ctx.magisk_code:
        with ui.spinner('installing the Magisk app'):
            adb.run('install', '-r', '-d', str(ctx.magisk_apk), timeout=300)
            got = _version_code(adb, 'com.topjohnwu.magisk')
        if got != ctx.magisk_code:
            ui.fail(f'the Magisk app did not install: version code {got or "none"}, not {ctx.magisk_code}')
        ui.ok('Magisk app installed')
    if not magisk.env_ok(adb, ctx.magisk_code):
        with ui.spinner('setting up Magisk\'s files on /data'):
            magisk.fix_env(adb)
    if not magisk.granted(adb, 2000):
        with ui.spinner('granting root to adb'):
            magisk.grant(adb, 2000)      # adb's `su`
        ui.ok('root granted to adb')


# --- what goes on /data -------------------------------------------------------------

def _same(adb: Adb, remote: str, local: Path) -> bool:
    import hashlib
    want = hashlib.sha256(local.read_bytes()).hexdigest()
    got = adb.script(f'sha256sum {remote} 2>/dev/null', 'sum', check=False).split(' ')[0]
    return got == want


def device_files(ctx: Ctx):
    """Modules, key layouts, tailscaled: each by its own install.sh under device/."""
    adb = ctx.adb
    ui.step('Device files')

    mod = DEVICE / 'magisk/croissant-device-admin'
    xml = 'system/etc/permissions/android.software.device_admin.xml'
    with ui.spinner('the device_admin feature'):
        if not _same(adb, f'/data/adb/modules/croissant-device-admin/{xml}', mod / xml):
            adb.push(mod, f'{STAGE}/')
            adb.script(f'sh {STAGE}/croissant-device-admin/install.sh', 'device-admin')
            ctx.reboot_needed = True
    ui.ok('device_admin feature (for Device Owner)')

    kl = DEVICE / 'keylayout'
    with ui.spinner('the key layouts'):
        if not all(_same(adb, f'/data/system/devices/keylayout/{k}', kl / k) for k in KEYLAYOUTS):
            for f in KEYLAYOUTS + ('install.sh',):
                adb.push(kl / f, f'{STAGE}/{f}')
            adb.script(f'sh {STAGE}/install.sh', 'keylayout')
            ctx.reboot_needed = True
    ui.ok('side button F12, wheel F11/F10')

    with ui.spinner('the boot animation'):
        png = brand.logo_png(ctx.name)
        anim = brand.bootanim_module(ctx.name, png)
        zip_rel = 'system/product/media/bootanimation.zip'
        if not _same(adb, f'/data/adb/modules/{brand.MODULE}/{zip_rel}', anim / zip_rel):
            adb.push(anim, f'{STAGE}/')
            adb.script(f'sh {STAGE}/{brand.MODULE}/install.sh', 'bootanim')
            ctx.reboot_needed = True
    ui.ok(f'boot animation for "{ctx.name}"')

    ts = ctx.tailscale
    sd = DEVICE / 'service.d'
    files = {'tailscaled': ts / 'tailscaled', 'tailscale': ts / 'tailscale',
             'ts': sd / 'ts', 'tailscaled.sh': sd / 'tailscaled.sh', 'install.sh': sd / 'install.sh'}
    installed = {'tailscaled': '/data/adb/tailscale/tailscaled', 'tailscale': '/data/adb/tailscale/tailscale',
                 'ts': '/data/adb/tailscale/ts', 'tailscaled.sh': '/data/adb/service.d/tailscaled.sh'}
    with ui.spinner('tailscaled'):
        if not all(_same(adb, r, files[k]) for k, r in installed.items()):
            adb.sh(f'mkdir -p {STAGE}/croissant-tailscale')
            for n, p in files.items():
                adb.push(p, f'{STAGE}/croissant-tailscale/{n}')
            adb.script(f'sh {STAGE}/croissant-tailscale/install.sh', 'tailscale')
            ctx.reboot_needed = True
    ui.ok('tailscaled at boot (the login is in the app\'s setup)')

    with ui.spinner('turning the privacy indicators off, naming the device'):
        privacy_indicators(adb)
        adb.script(f"settings put global device_name '{ctx.name}'", 'name')
    ui.ok('privacy indicators off, device name set')
    with ui.spinner('setting the density, the clock and window transitions'):
        display(adb)
    ui.ok(f'density {DENSITY}, 24-hour clock, no window transitions')
    with ui.spinner('keeping the system bars away'):
        no_swipe_bars(adb)
    ui.ok('no system bars on an edge swipe')


# SystemUI would flash its location chip on every Wi-Fi scan and its camera/mic pill over
# the app (android/README.md "Privacy indicators"). The GSI's SimpleDeviceConfig sets its
# defaults again at every boot, location_indicators_enabled=true among them, so it goes
# first.
DEVICE_CONFIG = 'org.protonaosp.deviceconfig'
INDICATORS = ('location_indicators_enabled', 'camera_mic_icons_enabled')


def indicators_off(adb: Adb) -> bool:
    return all(adb.sh(f'device_config get privacy {f}', check=False) == 'false' for f in INDICATORS)


def privacy_indicators(adb: Adb):
    adb.sh(f'pm disable-user --user 0 {DEVICE_CONFIG}', check=False)
    for flag in INDICATORS:
        if adb.sh(f'device_config get privacy {flag}', check=False) != 'false':
            adb.sh(f'device_config put privacy {flag} false')


# The app is drawn for 480x640 at density 280 (274x366 dp); the panel's own 320 makes every
# dp 14% larger, so pages overflow and taps miss.
# And a 24-hour clock: without a locale Android falls back to en-US and shows "am".
# And no window transitions: when Android brings the app back after an update it slides the
# app's task in over SystemUI's light backdrop, a band down the left edge, and only the
# launcher may change a task's animation. The app's own
# pages are Compose, which this scale does not touch.
DENSITY = 280


def display_ok(adb: Adb) -> bool:
    return (f'Override density: {DENSITY}' in adb.sh('wm density', check=False)
            and adb.sh('settings get system time_12_24', check=False) == '24'
            and adb.sh('settings get global transition_animation_scale', check=False) in ('0', '0.0'))


def display(adb: Adb):
    if f'Override density: {DENSITY}' not in adb.sh('wm density', check=False):
        adb.sh(f'wm density {DENSITY}')
    adb.sh('settings put system time_12_24 24')
    adb.sh('settings put global transition_animation_scale 0')


# A swipe from the top or bottom edge would show the status and navigation bars for 2.25 s,
# and a touch on the status bar in that time leaves them up for good (lock task disables the
# shade, so SystemUI never hears the lift). The window
# manager ignores those swipes until setup is complete, so setup stays not complete.
def bars_ok(adb: Adb) -> bool:
    return adb.sh('settings get secure user_setup_complete', check=False) == '0'


def no_swipe_bars(adb: Adb):
    adb.sh('settings put secure user_setup_complete 0')


OFFMODE = ('croissant-offmode.sh', 'croissant-offmode.rc', 'croissant-chgres.sh', 'install.sh')


def offmode(ctx: Ctx):
    """Plugged into an off R1 it boots, Power off with the charger in keeps it charging, and
    charge-only mode shows our screen: the hook in the boot ramdisk,
    then LK's off-mode-charge to 0."""
    with ui.spinner('the boot ramdisk hook for charge-only mode'):
        _offmode_hook(ctx)
    ui.ok(f'boot ramdisk hook: charge-only after a plugged-in Power off, its screen for "{ctx.name}"')
    with ui.spinner('setting LK\'s off-mode-charge'):
        out = ctx.adb.script(f'sh {STAGE}/croissant-offmode/croissant-offmode.sh 0', 'offmode-flag', check=False)
    if re.search(r'off-mode-charge (already 0|1 -> 0)', out):
        ui.ok('LK boots when plugged in (off-mode-charge 0)')
    else:
        ui.warn(f'could not set LK\'s off-mode-charge, so plugging in will only charge: {out.strip()}')


def _offmode_hook(ctx: Ctx):
    adb = ctx.adb
    src, stage = DEVICE / 'offmode', f'{STAGE}/croissant-offmode'
    res = brand.charger_res(ctx.name, brand.logo_png(ctx.name))
    for f in OFFMODE:
        adb.push(src / f, f'{stage}/{f}')
    for r, p in res.items():
        adb.push(p, f'{stage}/res/{r}')
    # What magiskinit copied to its tmpfs at this boot, against what this run would add.
    live = {f: src / f for f in ('croissant-offmode.sh', 'croissant-chgres.sh', 'croissant-offmode.rc')}
    live |= {'croissant-res-' + r.rsplit('/', 1)[-1]: p for r, p in res.items()}
    if not all(_same(adb, f'"$(magisk --path)/{n}"', p) for n, p in live.items()):
        adb.script(f'sh {stage}/install.sh', 'offmode-hook')
        slot = adb.getprop('ro.boot.slot_suffix')
        adb.run('pull', f'{stage}/boot{slot}-before.img', str(ctx.work / 'boot-before-offmode.img'),
                timeout=300)
        ctx.reboot_needed = True


def read_logo(adb: Adb) -> bytes:
    """The logo partition, read as root through Magisk's su (the shell cannot open it)."""
    return adb.exec_out("su -c 'dd if=/dev/block/by-name/logo bs=1048576 2>/dev/null'")


def logo(ctx: Ctx):
    """The LK logo: the R1's own logo partition with images 0 and 38 replaced."""
    adb = ctx.adb
    template = ctx.work / 'logo-template.bin'
    with ui.spinner('reading the logo partition'):
        current = read_logo(adb)
    if not current:
        ui.fail('could not read the logo partition')
    if not template.exists():
        template.write_bytes(current)     # the first read keeps its charging screens
    new = brand.logo_partition(template.read_bytes(), brand.logo_png(ctx.name))
    if current[:len(new)] == new:
        ui.ok(f'boot logo "{ctx.name}"')
        return
    ui.step('Boot logo')                   # from LK: fastbootd does not see the partition
    path = ctx.work / 'logo-new.bin'
    path.write_bytes(new)
    with ui.spinner('flashing the logo from the bootloader and restarting'):
        adb.reboot('bootloader')
        ctx.fb.wait(userspace=False)
        ctx.fb.run('flash', 'logo', str(path))
        ctx.fb.run('reboot', show=False)
        adb.wait_boot()
    ctx.reboot_needed = False             # that reboot applied the device files too
    ui.ok(f'boot logo "{ctx.name}"')


def reboot_if_needed(ctx: Ctx):
    if ctx.reboot_needed:
        with ui.spinner('restarting to apply the modules, key layouts and tailscaled'):
            ctx.adb.reboot()
            time.sleep(5)
            ctx.adb.wait_boot()
        ctx.reboot_needed = False
        ui.ok('restarted: modules, key layouts and tailscaled applied')


# --- the app ------------------------------------------------------------------------

def _app_uid(adb: Adb) -> int | None:
    for line in adb.sh(f'dumpsys package {paths.PACKAGE}', check=False).splitlines():
        line = line.strip()
        if line.startswith('userId='):
            return int(line.split('=')[1].split()[0])
    return None


def _is_owner(adb: Adb) -> bool:
    out = adb.sh('dumpsys device_policy', check=False)
    i = out.find('Device Owner')
    return i >= 0 and paths.PACKAGE in out[i:i + 400]


def app(ctx: Ctx, apk_path: Path):
    adb = ctx.adb
    ui.step('Croissant')
    with ui.spinner('comparing the app on the R1 with this one'):
        path = adb.sh(f'pm path {paths.PACKAGE}', check=False).split('\n')[0].removeprefix('package:')
        fresh = not path or not _same(adb, path, apk_path)
    if fresh:
        with ui.spinner('installing the app'):
            adb.run('install', '-r', str(apk_path), timeout=600)
        ui.ok('installed')
    # Without this a fresh install runs interpreted until the nightly dexopt (PERFORMANCE.md).
    with ui.spinner('compiling it ahead of time (a minute or two)'):
        adb.sh(f'cmd package compile -m speed -f {paths.PACKAGE}', timeout=600, check=False)
    ui.ok('compiled ahead of time')
    if ctx.plan.update_repo:
        update_source(adb, ctx.plan.update_repo)
    # Running, it runs the code from before the compile (install -r relaunches it: it is HOME),
    # and it reads the source at start: it starts again after the stages (restart_app), or with
    # the reboot below.
    ctx.restart_needed = True

    with ui.spinner('giving the app root'):
        uid = _app_uid(adb)
        if uid and not magisk.granted(adb, uid):
            magisk.grant(adb, uid)        # Power off, the Tailscale screen, eSIM, the button from sleep
    ui.ok(f'root for the app (uid {uid})')

    with ui.spinner('making the app Device Owner and HOME'):
        if not _is_owner(adb):
            if 'android.software.device_admin' not in adb.sh('pm list features', check=False):
                ui.fail('the device_admin feature is not live yet. Reboot the R1 and run again.')
            adb.sh(f'dpm set-device-owner {paths.admin()}')
            adb.sh(f'am start -n {paths.PACKAGE}/{paths.NAMESPACE}.MainActivity')
            time.sleep(5)                 # Kiosk.apply sets the policies on the first start
            adb.reboot()
            time.sleep(5)
            adb.wait_boot()
            ctx.restart_needed = False
    ui.ok('Device Owner, HOME, lock task')


def _app_file(adb: Adb, name: str, text: str):
    """A one-line file in the app's device-protected files, which it takes into its settings at its
    next start, then deletes. Moved in as the fonts are, as the app's own data."""
    with tempfile.NamedTemporaryFile('w', delete=False) as f:
        f.write(text + '\n')
    adb.push(f.name, f'{STAGE}/croissant-{name}')
    Path(f.name).unlink()
    adb.script(f"""set -e
APPDIR=/data/user_de/0/{paths.PACKAGE}
U=$(stat -c %u $APPDIR)
C=$(stat -c %C $APPDIR)
mkdir -p $APPDIR/files
mv -f {STAGE}/croissant-{name} $APPDIR/files/{name}
chown $U:$U $APPDIR/files $APPDIR/files/{name}
chmod 0600 $APPDIR/files/{name}
chcon $C $APPDIR/files $APPDIR/files/{name}
""", name)


def update_source(adb: Adb, repo: str):
    """Where this C1 gets its updates (update/Updater.kt, adoptInstallerSource)."""
    with ui.spinner('telling the app where its updates come from'):
        _app_file(adb, SOURCE_FILE, repo)
    ui.ok(f'updates from {repo}')


def sound_pack(ctx: Ctx):
    """The interface sound pack (audio/Cues.kt, adoptInstallerPack); the app plays it from its next
    start (restart_app)."""
    with ui.spinner('setting the interface sounds'):
        _app_file(ctx.adb, sounds.FILE, ctx.plan.sounds)
    ctx.restart_needed = True
    ui.ok(f'sounds: {sounds.PACKS[ctx.plan.sounds].split(":")[0]}')


def _app_fonts() -> str:
    # Where ui/Theme.kt loads them from: device-protected storage, since the app starts at
    # boot before credential-encrypted storage is unlocked.
    return f'/data/user_de/0/{paths.PACKAGE}/files/fonts'


def font_on_device(adb: Adb) -> bool:
    return font.on_device(adb.script(f'sha256sum {_app_fonts()}/*.ttf 2>/dev/null', 'font-sums', check=False))


def fonts(ctx: Ctx):
    """The owner's Power Grotesk into the app's device-protected files/fonts, as its own data; the
    app loads it when it starts again (restart_app)."""
    adb = ctx.adb
    with ui.spinner('looking for Power Grotesk on the R1'):
        there = font_on_device(adb)
    if not there:
        font.require()
        with ui.spinner('putting Power Grotesk on the R1'):
            _push_fonts(adb)
        ctx.restart_needed = True
    ui.ok('Power Grotesk on the R1')


def _push_fonts(adb: Adb):
    """The font files into the app's device-protected files/fonts, as its own data."""
    for n in font.FILES:
        adb.push(font.FONTS / n, f'{STAGE}/croissant-fonts/{n}')
    # The app's uid and its label, categories included, as device/offline-models/install.sh.
    adb.script(f"""set -e
APPDIR=/data/user_de/0/{paths.PACKAGE}
U=$(stat -c %u $APPDIR)
C=$(stat -c %C $APPDIR)
mkdir -p $APPDIR/files/fonts
mv -f {STAGE}/croissant-fonts/*.ttf $APPDIR/files/fonts/
rmdir {STAGE}/croissant-fonts
chown $U:$U $APPDIR/files $APPDIR/files/fonts $APPDIR/files/fonts/*.ttf
chmod 0700 $APPDIR/files/fonts
chmod 0600 $APPDIR/files/fonts/*.ttf
chcon $C $APPDIR/files
chcon -R $C $APPDIR/files/fonts
""", 'fonts')
    if not font_on_device(adb):
        ui.fail(f'Power Grotesk did not land in {_app_fonts()}')


def restart_app(ctx: Ctx):
    """The app started again, once, for whatever the stages before asked it for: the compiled
    code, the update source, the font. The system relaunches it at once: it is HOME."""
    if ctx.restart_needed:
        with ui.spinner('starting the app again'):
            ctx.adb.script(f'killall {paths.PACKAGE}', 'restart-app', check=False)
        ctx.restart_needed = False
        ui.ok('the app started again')


def verify(ctx: Ctx):
    adb = ctx.adb
    ui.step('Checking')
    with ui.spinner('letting the app settle, then checking'):
        time.sleep(5)
        locked = 'LOCKED' in adb.sh('dumpsys activity activities | grep mLockTaskModeState', check=False)
    (ui.ok if locked else ui.warn)('the app is pinned' if locked else 'the app is not pinned yet')
    off = indicators_off(adb)
    (ui.ok if off else ui.warn)('privacy indicators off' if off else
                                f'a privacy indicator came back on. Run {paths.COMMAND} install again.')
    still = bars_ok(adb)
    (ui.ok if still else ui.warn)('no system bars on an edge swipe' if still else
                                  f'an edge swipe can show the system bars. Run {paths.COMMAND} install again.')
    shown = display_ok(adb)
    (ui.ok if shown else ui.warn)(f'density {DENSITY}, 24-hour clock, no window transitions' if shown else
                                  f'density, clock or window transitions not set. Run {paths.COMMAND} install again.')


def offline_models(ctx: Ctx):
    import subprocess
    ui.step('Offline Translator models (1.4 GB)')
    subprocess.run([str(DEVICE / 'offline-models/push.sh'), ctx.adb.serial, paths.PACKAGE], check=True)
