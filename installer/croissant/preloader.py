"""The stock boot images, read over the MediaTek preloader by mtkclient.

Magisk patches rabbit's own boot image, so the installer needs a copy of it. fastbootd on
the R1 has no `fetch`, and LineageOS refuses `adb root` unless "Rooted debugging" is turned
on in its settings, so neither can hand it over. The preloader can: on the R1 it is
unprotected (no SBC, SLA or DAA), so mtkclient reads partitions from a powered-off R1 with
the bootloader still locked, writing nothing to it. One session reads boot_a, boot_b and
frp, whose last byte is rabbit's unlock permission.

mtkclient (GPL-3, https://github.com/bkerler/mtkclient) is not part of this repo. It is
installed at a pinned commit into its own venv in the cache, made by uv, and run as a
separate program. Two small files are written beside it there, never kept in the repo: a
stub for `mfusepy` (mtkclient imports it for a FUSE mount it does not need here, and macOS
has no libfuse) and a launcher that points pyusb at the libusb from PyPI's libusb-package,
so nothing comes from Homebrew.

The boot images depend on the firmware, not the unit, but they are kept per R1 under
`stock-boot/<serial>/`, both slots (the live one is only known later), each with the kernel
it carries (`boot_<slot>.kernel`, its release and build), which is what ties a cached image
to the firmware the R1 runs (its /proc/version). On a stock R1 the serial is only known once
it is in fastboot, so a read lands in `stock-boot/pending/` first and `adopt` moves it.
"""
import os
import platform
import queue
import re
import shutil
import subprocess
import threading
import time
from dataclasses import dataclass
from pathlib import Path

from . import bootimg, ui
from .paths import CACHE

COMMIT = 'cd25cf9c1ff6d36e82697ac2c798e69e9cfb78c3'
SOURCE = f'mtkclient @ git+https://github.com/bkerler/mtkclient@{COMMIT}'
# mtkclient's own dependencies at the versions it was proven with, less PySide6 and
# shiboken6 (its GUI only) and mfusepy (stubbed). libusb-package is ours: libusb from PyPI.
DEPS = ('pyusb==1.3.1', 'pycryptodome==3.23.0', 'pycryptodomex==3.23.0', 'colorama==0.4.6',
        'pyserial==3.5')
LIBUSB = 'libusb-package==1.0.26.3'
PYTHON = '3.12'

HOME = CACHE / 'mtkclient'
VENV = HOME / 'venv'
SHIMS = HOME / 'shims'
STAMP = HOME / 'installed'
LAUNCHER = SHIMS / 'croissant_mtk.py'

STOCK = CACHE / 'stock-boot'
PENDING = STOCK / 'pending'
LOG = STOCK / 'mtkclient.log'

PARTS = {'boot_a': 32 << 20, 'boot_b': 32 << 20, 'frp': 1 << 20}   # name: size on the R1
SLOTS = ('a', 'b')

MFUSEPY = '''# Written by croissant: mtkclient imports mfusepy only for its FUSE mount, unused here.
class Operations: pass
class LoggingMixIn: pass
class FUSE:
    def __init__(self, *a, **k): raise RuntimeError("mfusepy stubbed")
'''

# Run as `python croissant_mtk.py <mtk arguments>`: its directory comes first on sys.path,
# so the mfusepy stub is found; pyusb's libusb1 backend is pinned to libusb-package's
# library before mtkclient asks for one (it asks for "libusb-1.0.so" by name on macOS too).
LAUNCHER_TEXT = '''# Written by croissant: mtkclient, with pyusb on libusb-package's libusb.
import sys
import usb.backend.libusb1 as libusb1
try:
    import libusb_package
    LIB = libusb_package.get_library_path()
except ImportError:
    LIB = None
if LIB:
    _get_backend = libusb1.get_backend
    def get_backend(find_library=None):
        return _get_backend(find_library=lambda name: str(LIB))
    libusb1.get_backend = get_backend
if sys.argv[1:] == ["--croissant-libusb"]:
    b = libusb1.get_backend()
    print(b.lib._name if b else "none")
    sys.exit(0 if b else 1)
from mtkclient.mtk import main
sys.argv[0] = "mtk"
sys.exit(main() or 0)
'''

UDEV_RULE = 'SUBSYSTEM=="usb", ATTR{idVendor}=="0e8d", MODE="0666", TAG+="uaccess"'
LINUX_ACCESS = f'''mtkclient could not open the R1's preloader (USB vendor 0e8d): this user has no access
to it. mtkclient's own udev rule (Setup/Linux/52-mtk.rules in its repo) gives it:

  echo '{UDEV_RULE}' | sudo tee /etc/udev/rules.d/52-mtk.rules
  sudo udevadm control -R && sudo udevadm trigger
  sudo usermod -aG plugdev,dialout $USER     (then log out and back in)

ModemManager can also take the preloader's serial port. Stop it for the read:
  sudo systemctl stop ModemManager

Nothing was written to the R1.'''

NO_LIBUSB = '''pyusb found no libusb (libusb-package has none for this machine). Install one:
  macOS:          brew install libusb
  Debian, Ubuntu: sudo apt install libusb-1.0-0
  Fedora:         sudo dnf install libusb1
  Arch:           sudo pacman -S libusb
Nothing was written to the R1.'''


# --- mtkclient in the cache ---------------------------------------------------------

def _python() -> Path:
    return VENV / 'bin/python'


def _want() -> str:
    return '\n'.join((COMMIT, PYTHON, *DEPS, LIBUSB))


def installed() -> bool:
    return _python().exists() and STAMP.exists() and STAMP.read_text().split('\n')[:-1] == _want().split('\n')


def _uv() -> str:
    uv = os.environ.get('UV') or shutil.which('uv')   # `uv run` sets UV to itself
    if not uv:
        ui.fail('uv is needed to install mtkclient: https://docs.astral.sh/uv/')
    return uv


def _run_uv(*args):
    p = subprocess.run([_uv(), *args], capture_output=True, text=True)
    return p.returncode, (p.stdout + p.stderr).strip()


def ensure() -> list[str]:
    """mtkclient installed in the cache (once); returns the command that runs it."""
    if not installed():
        if not shutil.which('git'):
            ui.fail('git is needed to install mtkclient (it comes from GitHub at a pinned commit)')
        shutil.rmtree(HOME, ignore_errors=True)
        HOME.mkdir(parents=True)
        with ui.spinner(f'installing mtkclient {COMMIT[:7]} into {HOME} (once)'):
            for args in (('venv', '--quiet', '--python', PYTHON, str(VENV)),
                         ('pip', 'install', '--quiet', '--python', str(_python()), *DEPS),
                         ('pip', 'install', '--quiet', '--python', str(_python()), '--no-deps', SOURCE)):
                code, out = _run_uv(*args)
                if code:
                    ui.fail(f'installing mtkclient failed (uv {" ".join(args[:2])}):\n{out}')
            # A wheel only: building libusb here would need a compiler. Without it, pyusb
            # falls back to a libusb on the system, and the read says how to get one.
            code, out = _run_uv('pip', 'install', '--quiet', '--python', str(_python()),
                                '--only-binary', ':all:', LIBUSB)
        if code:
            ui.warn(f'libusb-package has no build for this machine, so mtkclient needs a libusb '
                    f'installed on the system.\n{out}')
        STAMP.write_text(_want() + '\n' + ('libusb-package' if not code else 'system libusb'))
        ui.ok(f'mtkclient {COMMIT[:7]}')
    SHIMS.mkdir(parents=True, exist_ok=True)
    (SHIMS / 'mfusepy.py').write_text(MFUSEPY)
    LAUNCHER.write_text(LAUNCHER_TEXT)
    return [str(_python()), str(LAUNCHER)]


def libusb() -> str:
    """The libusb pyusb loads through the launcher, as a path ('none' when there is none)."""
    p = subprocess.run(ensure() + ['--croissant-libusb'], capture_output=True, text=True)
    return p.stdout.strip() or 'none'


# --- reading what mtkclient says ----------------------------------------------------

ANSI = re.compile(r'\x1b\[[0-9;]*[A-Za-z]')
PROGRESS = re.compile(r'\|[^|]*\|\s*[\d.]+%')
# Lines that name this unit: never written to a file (the terminal shows them).
UNIQUE = re.compile(r'\b(ME_?ID|SOC_?ID|(EMMC |UFS )?CID)\b', re.I)
HANDSHAKE = re.compile(r'Device detected|Handshake successful|Detected (regular|iot) mode')
# mtkclient's hint for phones with volume keys: noise on an R1 (its waiting dots are dropped too).
NOISE = ('Hint:', 'Power off the phone', 'For brom mode', 'connect usb.', 'For preloader mode',
         'If it is already connected')


@dataclass
class Outcome:
    waited: bool = False          # it reached "Waiting for PreLoader"
    unprotected: bool = False     # "Device is unprotected."
    protected: bool = False       # it said the device is (or was) protected
    stale: bool = False           # "Please disconnect, start mtkclient and reconnect."
    access: bool = False          # the USB device could not be opened (Linux permissions)
    no_libusb: bool = False       # pyusb found no backend
    dumped: bool = False          # "All partitions were dumped"


def parse(output: str) -> Outcome:
    o = Outcome()
    o.waited = 'Waiting for PreLoader' in output
    o.unprotected = 'Device is unprotected.' in output
    o.protected = bool(re.search(r'Device (is|was|is still) protected', output))
    o.stale = 'Please disconnect, start mtkclient and reconnect.' in output
    o.access = bool(re.search(r'Access denied|insufficient permissions|LIBUSB_ERROR_ACCESS|'
                              r'\[Errno 13\]|Permission denied|Resource busy|LIBUSB_ERROR_BUSY', output))
    o.no_libusb = 'No backend available' in output or 'NoBackendError' in output
    o.dumped = 'All partitions were dumped' in output
    return o


def clean(line: str) -> str:
    return ANSI.sub('', line).rstrip()


def shown(line: str) -> bool:
    s = line.strip()
    return bool(s) and set(s) != {'.'} and not PROGRESS.search(s) and not s.startswith(NOISE)


def redact(line: str) -> str:
    return '(a line naming this unit, not kept)' if UNIQUE.search(line) else line


# --- checking what it read ----------------------------------------------------------

def boot_problem(raw: bytes) -> str | None:
    """Why [raw] is not a usable read of a boot partition, or None."""
    if len(raw) != PARTS['boot_a']:
        return f'{len(raw)} bytes, not {PARTS["boot_a"]}'
    if not raw.startswith(bootimg.MAGIC):
        return 'no ANDROID! header'
    try:
        bootimg.ramdisk_files(bootimg.trim(raw))
    except ValueError as e:
        return str(e)
    return None


def patched(raw: bytes) -> list[str]:
    """What in the boot image's ramdisk is not rabbit's (Magisk's, or the off-mode hook's)."""
    return bootimg.foreign(bootimg.trim(raw))


def frp_allows(raw: bytes) -> bool | None:
    """rabbit's unlock permission: frp's last byte; None on a bad read."""
    if len(raw) != PARTS['frp']:
        return None
    return raw[-1] == 1


@dataclass
class Read:
    boots: dict[str, Path]         # slot: raw image, stock, in the read's directory
    notes: dict[str, str]          # slot: why it was not kept
    frp: bool | None


def check(d: Path) -> Read:
    """The files mtkclient wrote into [d], checked: the boot images that are good and rabbit's,
    why the others are not (those are deleted), and frp's verdict."""
    boots, notes = {}, {}
    for s in SLOTS:
        f = d / f'boot_{s}.bin'
        raw = f.read_bytes() if f.exists() else b''
        why = 'not read' if not raw else boot_problem(raw)
        if not why and (marks := patched(raw)):
            why = f'already patched ({", ".join(marks[:3])} in its ramdisk)'
        if why:
            notes[s] = why
            f.unlink(missing_ok=True)
        else:
            boots[s] = f
    frp = d / 'frp.bin'
    allows = frp_allows(frp.read_bytes()) if frp.exists() else None
    frp.unlink(missing_ok=True)           # this unit's; nothing needs it once read
    return Read(boots, notes, allows)


# --- the cache ----------------------------------------------------------------------

def slot_dir(serial: str) -> Path:
    return STOCK / serial


def firmware_problem(raw: bytes, running: str | None) -> str | None:
    """Why [raw] is not a boot image of the firmware whose /proc/version is [running], or
    None (also when [running] is None: not on Android, nothing to compare with)."""
    if running is None:
        return None
    want, got = bootimg.kernel_id(running), bootimg.kernel_version(raw)
    if not want:
        return f'the R1\'s /proc/version is not a Linux banner: {running.strip()[:80]!r}'
    if got != want:
        return f'its kernel is {got or "unknown"}, the R1 runs {want}'
    return None


def stock_problem(raw: bytes, running: str | None) -> str | None:
    """Why [raw], a full partition read or a trimmed image, is not rabbit's boot image for
    the firmware running, or None."""
    if not raw.startswith(bootimg.MAGIC):
        return 'no ANDROID! header'
    try:
        marks = patched(raw)
    except ValueError as e:
        return str(e)
    if marks:
        return f'already patched ({", ".join(marks[:3])} in its ramdisk)'
    return firmware_problem(raw, running)


def cached(serial: str | None, slot: str, running: str | None = None) -> Path | None:
    """The stock boot image of [slot] read from this R1, when there is a good one, and one
    of the firmware whose /proc/version is [running] when that is given."""
    if not serial:
        return None
    p = slot_dir(serial) / f'boot_{slot}.bin'
    if p.exists():
        raw = p.read_bytes()
        if not boot_problem(raw) and not stock_problem(raw, running):
            return p
    return None


def adopt(boots: dict[str, Path], serial: str) -> dict[str, Path]:
    """Move a read's boot images under the R1's serial, each with its kernel's id beside it."""
    out = slot_dir(serial)
    out.mkdir(parents=True, exist_ok=True)
    kept = {}
    for s, p in boots.items():
        kept[s] = out / f'boot_{s}.bin'
        shutil.move(str(p), kept[s])
        (out / f'boot_{s}.kernel').write_text((bootimg.kernel_version(kept[s].read_bytes()) or 'unknown') + '\n')
    return kept


# --- the read -----------------------------------------------------------------------

def _modemmanager() -> bool:
    try:
        return subprocess.run(['systemctl', 'is-active', '--quiet', 'ModemManager'],
                              timeout=10).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


# What mtkclient leaves in its working directory that names this unit: its ME_ID, SOC_ID
# and CID (hwparam.json), and its session state.
UNIT_FILES = ('hwparam.json', '.state')


def read(again: str, wait_s: int = 180, total_s: int = 600) -> Path:
    """Run mtkclient once: boot_a, boot_b and frp into a fresh `PENDING`. The R1 must be off
    and unplugged; this says when to plug it in. Fails, with what to do ([again] says how to
    get the R1 out of download mode and retry), unless mtkclient said the preloader is
    unprotected and dumped all three. Returns the directory."""
    argv = ensure() + ['r', ','.join(PARTS), ','.join(f'{p}.bin' for p in PARTS)]
    if platform.system() == 'Linux' and _modemmanager():
        ui.warn('ModemManager is running and can take the preloader\'s serial port. If the read '
                'hangs: sudo systemctl stop ModemManager, then run again.')
    shutil.rmtree(PENDING, ignore_errors=True)
    PENDING.mkdir(parents=True)
    env = os.environ | {'PYTHONUNBUFFERED': '1', 'PYTHONDONTWRITEBYTECODE': '1'}
    p, done = None, False
    try:
        # Its working directory is where it writes the dumps (and its own hwparam.json).
        p = subprocess.Popen(argv, cwd=PENDING, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             text=True, errors='replace', bufsize=1)
        out, timed_out = _follow(p, wait_s, total_s)
        _verdict(parse('\n'.join(out)), timed_out, wait_s, again)
        done = True
    finally:
        # However it ends (a failure, Ctrl-C): no process left, and nothing naming this unit.
        if p and p.poll() is None:
            p.kill()
            p.wait()
        if p:
            p.stdout.close()
        for n in UNIT_FILES:
            (PENDING / n).unlink(missing_ok=True)
        if not done:
            (PENDING / 'frp.bin').unlink(missing_ok=True)   # check() deletes it after a good read
    return PENDING


def _follow(p: subprocess.Popen, wait_s: int, total_s: int) -> tuple[list[str], str | None]:
    """mtkclient's output as it comes, shown and logged (redacted), until it exits or a
    timeout: ('wait', no preloader in [wait_s]) or ('total')."""
    lines: queue.Queue = queue.Queue()

    def pump():
        for line in p.stdout:
            lines.put(line)
        lines.put(None)
    reader = threading.Thread(target=pump, daemon=True)
    reader.start()

    out, told, seen, timed_out, started = [], False, False, None, time.time()
    with open(LOG, 'w') as log:
        while True:
            try:
                line = lines.get(timeout=1)
            except queue.Empty:
                line = ''
            if line is None:
                break
            if line:
                line = clean(line)
                out.append(line)
                log.write(redact(line) + '\n')
                if shown(line):
                    print('    ' + line, flush=True)
                if not told and 'Waiting for PreLoader' in line:
                    ui.todo('Plug the R1 in now.')
                    told = True
                seen = seen or bool(HANDSHAKE.search(line))
            if not seen and time.time() - started > wait_s:
                timed_out = 'wait'
            elif time.time() - started > total_s:
                timed_out = 'total'
            if timed_out:
                p.kill()
                break
    p.wait()
    reader.join(5)
    p.stdout.close()
    return out, timed_out


def _verdict(o: Outcome, timed_out: str | None, wait_s: int, again: str):
    if o.no_libusb:
        ui.fail(f'{NO_LIBUSB} {again}')
    if o.access and platform.system() == 'Linux':
        ui.fail(f'{LINUX_ACCESS} {again}')
    if o.stale:
        ui.fail('The R1 was still in download mode from an earlier read. ' + again)
    if timed_out == 'wait':
        ui.fail(f'No preloader seen in {wait_s} s. Nothing was written to the R1. Use a cable that '
                'carries data, or another port, and make sure the R1 is fully off (unplugged, side '
                f'button held about 15 s) before it is plugged in. {again}')
    if o.protected and not o.unprotected:
        ui.fail('mtkclient says this R1\'s preloader is protected on its firmware, so the stock boot '
                'image cannot be read this way, and this installer stops here. Nothing was written '
                'to the R1. ' + again + f' (mtkclient\'s output: {LOG})')
    if timed_out == 'total' or not o.unprotected or not o.dumped:
        ui.fail(f'mtkclient did not finish the read (its output is above, and in {LOG}). Nothing was '
                'written to the R1. ' + again)
