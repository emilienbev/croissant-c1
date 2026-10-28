"""Magisk without its app's screens.

The app patches a boot image by running its own `boot_patch.sh` beside the binaries it
ships; this does the same over adb, with the flags the app chose on this unit
(KEEPVERITY and KEEPFORCEENCRYPT true, read back from the patched ramdisk, 2026-09-29).
It then sets up `/data/adb/magisk` the way the app's `fix_env` does, which `service.d`
needs (its scripts run under that busybox), and grants root by policy row rather than
by the Superuser tab.

The adb shell's own row is written on the R1 by a boot script this adds to the patched
ramdisk (device/grant/, Magisk's overlay.d): the shell has no root to write it with until
then, `adb root` being refused on this GSI, and su from it would ask Grant/Deny on a screen
nobody is watching.
"""
import zipfile
from pathlib import Path

from . import bootimg, ui
from .paths import DEVICE, STAGE
from .tools import Adb

# What boot_patch.sh expects beside it, by the name it expects (its own header), plus
# what the app puts in /data/adb/magisk.
KIT = {
    'assets/boot_patch.sh': 'boot_patch.sh',
    'assets/util_functions.sh': 'util_functions.sh',
    'assets/addon.d.sh': 'addon.d.sh',
    'assets/stub.apk': 'stub.apk',
    'lib/arm64-v8a/libmagiskboot.so': 'magiskboot',
    'lib/arm64-v8a/libmagiskinit.so': 'magiskinit',
    'lib/arm64-v8a/libmagisk.so': 'magisk',
    'lib/arm64-v8a/libinit-ld.so': 'init-ld',
    'lib/arm64-v8a/libbusybox.so': 'busybox',
    'lib/arm64-v8a/libmagiskpolicy.so': 'magiskpolicy',
}
REMOTE = f'{STAGE}/croissant-magisk'
FLAGS = 'KEEPVERITY=true KEEPFORCEENCRYPT=true PATCHVBMETAFLAG=false RECOVERYMODE=false LEGACYSAR=false'

# Added to the ramdisk after Magisk's patch: ramdisk path: (mode, file in device/grant).
GRANT_SERVICE = 'croissant-grant'   # init's service, so its state is init.svc.croissant-grant
OVERLAY = {
    'overlay.d/croissant-grant.rc': ('0644', 'croissant-grant.rc'),
    'overlay.d/sbin/croissant-grant.sh': ('0755', 'croissant-grant.sh'),
}


def overlay_files() -> dict[str, bytes]:
    """What OVERLAY puts in the ramdisk, by ramdisk path."""
    return {path: (DEVICE / 'grant' / name).read_bytes() for path, (_, name) in OVERLAY.items()}


def overlay_script() -> str:
    """Run in REMOTE once boot_patch.sh has written new-boot.img: OVERLAY into its ramdisk,
    repacked as croissant-boot.img. The kernel is left packed, as boot_patch.sh leaves it,
    so it stays byte for byte."""
    adds = ' '.join(f'"add {mode} {path} ../{name}"' for path, (mode, name) in OVERLAY.items())
    return f'''rm -rf overlay && mkdir overlay && cd overlay
../magiskboot unpack ../new-boot.img >/dev/null 2>&1 || {{ echo "magiskboot unpack failed"; exit 1; }}
rm -f kernel
../magiskboot cpio ramdisk.cpio {adds} >/dev/null 2>&1 || {{ echo "magiskboot cpio failed"; exit 1; }}
../magiskboot repack ../new-boot.img ../croissant-boot.img >/dev/null 2>&1 || {{ echo "magiskboot repack failed"; exit 1; }}
cd .. && rm -rf overlay
[ -f croissant-boot.img ] || exit 1
'''


def kit(apk: Path, out: Path) -> Path:
    out.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(apk) as z:
        for src, name in KIT.items():
            (out / name).write_bytes(z.read(src))
    return out


def version(adb: Adb) -> str:
    return adb.sh('magisk -V', check=False)


def patch(adb: Adb, stock: bytes, kit_dir: Path, work: Path) -> bytes:
    """Patch [stock] on the R1, add OVERLAY, and bring the result back, checked."""
    (work / 'boot.img').write_bytes(stock)
    with ui.spinner('sending Magisk and the stock boot image to the R1'):
        adb.sh(f'rm -rf {REMOTE} && mkdir -p {REMOTE}')
        for f in kit_dir.iterdir():
            adb.push(f, f'{REMOTE}/{f.name}')
        for _, name in OVERLAY.values():
            adb.push(DEVICE / 'grant' / name, f'{REMOTE}/{name}')
        adb.push(work / 'boot.img', f'{REMOTE}/boot.img')
    with ui.spinner('patching it on the R1 with Magisk, then adding the shell\'s grant (about a minute)'):
        adb.script(f'cd {REMOTE}\nchmod 755 *\n{FLAGS} sh boot_patch.sh boot.img\n'
                   f'[ -f new-boot.img ] || exit 1\n' + overlay_script(), 'magisk-patch',
                   check=False, need_root=False, show=True)   # as the app does it: root when there is some, else the shell
    with ui.spinner('bringing it back and checking it'):
        patched = adb.exec_out(f'cat {REMOTE}/croissant-boot.img')
    if not patched.startswith(bootimg.MAGIC):
        ui.fail('Magisk did not produce a boot image (its log is above)')
    changed = bootimg.same_except_ramdisk(stock, patched)
    if changed:
        ui.fail(f'Magisk changed {", ".join(changed)}, not just the ramdisk. Not flashing it.')
    wrong = bootimg.patch_problems(stock, patched, overlay_files())
    if wrong:
        ui.fail(f'the patched ramdisk is not Magisk\'s patch plus the shell\'s grant: {", ".join(wrong)}. '
                'Not flashing it.')
    cfg = bootimg.magisk_config(patched)
    if cfg.get('KEEPVERITY') != 'true' or cfg.get('KEEPFORCEENCRYPT') != 'true':
        ui.fail(f'Magisk wrote unexpected flags: {cfg}')
    if cfg.get('PREINITDEVICE') != 'md_udc':
        ui.warn(f'Magisk chose {cfg.get("PREINITDEVICE")!r} for pre-init storage, where the R1 '
                'this was proven on had md_udc')
    ui.ok('patched: kernel and dtb untouched, only the ramdisk changed (Magisk, and the shell\'s grant)')
    (work / 'magisk-patched-boot.img').write_bytes(patched)
    return patched


def fix_env(adb: Adb):
    """What the app's "additional setup" does: its binaries into /data/adb/magisk."""
    names = ' '.join(KIT.values())
    adb.script(f'''set -e
B=/data/adb/magisk
mkdir -p $B
chmod 700 /data/adb
rm -rf $B/*
cd {REMOTE}
cp -f {names} $B/
chmod -R 755 $B
chown -R 0:0 $B
''', 'magisk-env')


def env_ok(adb: Adb, code: str) -> bool:
    out = adb.script('for f in busybox magiskboot magiskinit magiskpolicy util_functions.sh '
                     'boot_patch.sh; do [ -f /data/adb/magisk/$f ] || { echo missing; exit 0; }; '
                     f'done; grep -q "^MAGISK_VER_CODE={code}$" '
                     '/data/adb/magisk/util_functions.sh && echo ok', 'magisk-envcheck', check=False)
    return out.strip().endswith('ok')


def grant(adb: Adb, uid: int):
    """Root for [uid], for good, without a prompt: Magisk's prompt cannot show under lock
    task, and a prompt that times out is remembered as a deny."""
    adb.script(f'magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) '
               f'VALUES({uid},2,0,0,0)"', f'magisk-grant-{uid}')


def granted(adb: Adb, uid: int) -> bool:
    out = adb.script(f'magisk --sqlite "SELECT policy FROM policies WHERE uid={uid}"',
                     f'magisk-granted-{uid}', check=False)
    return 'policy=2' in out
