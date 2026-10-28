"""Which R1, and what it is doing right now: the installer resumes from whatever it finds."""
import json
import platform
import subprocess
from dataclasses import dataclass
from pathlib import Path

from . import ui
from .tools import Adb, Fastboot

STOCK_USB = ('0e8d', '2304')     # booted rabbitOS: a USB device with no interfaces, no adb
R1_PRODUCT = 'k65v1_64_bsp'      # fastboot's `product`, LK and fastbootd
R1_VENDOR = 'Rabbit/'            # the vendor fingerprint's start: the installer never flashes vendor
ONLY_R1 = 'Leave only the R1 plugged in, or pick it with --serial.'

# The vendor the installer was proven on (rabbitOS 2.3.0's).
KNOWN_VENDORS = {
    'Rabbit/vnd_k65v1_64_bsp/k65v1_64_bsp:12/SP1A.210812.016/20260910192544:user/release-keys',
}


@dataclass
class Where:
    mode: str            # 'android', 'lk', 'fastbootd', 'stock', 'none'
    serial: str | None


def _usb_ids() -> set[tuple[str, str]]:
    ids = set()
    if platform.system() == 'Darwin':
        try:
            out = subprocess.run(['/usr/sbin/system_profiler', 'SPUSBDataType', '-json'],
                                 capture_output=True, text=True, timeout=20).stdout
        except (OSError, subprocess.TimeoutExpired):
            return ids
        def walk(items):
            for it in items or []:
                v, p = it.get('vendor_id', ''), it.get('product_id', '')
                ids.add((v.split()[0].removeprefix('0x').lower(), p.removeprefix('0x').lower()))
                walk(it.get('_items'))
        try:
            walk(json.loads(out).get('SPUSBDataType'))
        except json.JSONDecodeError:
            pass
    else:
        for d in Path('/sys/bus/usb/devices').glob('*'):
            try:
                ids.add(((d / 'idVendor').read_text().strip(), (d / 'idProduct').read_text().strip()))
            except OSError:
                pass
    return ids


def locate(adb: Adb, fb: Fastboot, serial: str | None) -> Where:
    """Find the one R1 to work on. Emulators are never it."""
    in_adb = [s for s, st in adb.devices() if st == 'device' and not s.startswith('emulator-')]
    in_fb = fb.devices()
    seen = sorted(set(in_adb) | set(in_fb))
    if serial is None:
        if len(seen) > 1:
            ui.fail(f'more than one device: {", ".join(seen)}. {ONLY_R1}')
        serial = seen[0] if seen else None
    adb.serial = fb.serial = serial
    if serial in in_fb:
        _is_r1(serial, 'product', fb.getvar('product'), lambda v: v == R1_PRODUCT)
        return Where('fastbootd' if fb.userspace() else 'lk', serial)
    if serial in in_adb:
        _is_r1(serial, 'vendor', adb.getprop('ro.vendor.build.fingerprint'), lambda v: v.startswith(R1_VENDOR))
        return Where('android', serial)
    if STOCK_USB in _usb_ids():
        return Where('stock', serial)
    return Where('none', serial)


def _is_r1(serial: str, what: str, value: str, fits) -> None:
    """A light check, not a proof: refuse only a device that answers as something else.
    An empty answer passes, so an R1 in an odd state is not turned away."""
    if value and not fits(value):
        ui.fail(f'{serial} does not look like a Rabbit R1 ({what} {value!r}). {ONLY_R1}')


def on_gsi(adb: Adb) -> bool:
    """A GSI is running. rabbitOS has no adb at all, so an R1 reached over adb already runs
    someone else's system; this only rules out rabbit's own (its fingerprint says Rabbit)."""
    fp = adb.getprop('ro.build.fingerprint')
    return bool(fp) and not fp.startswith('Rabbit/')


def check_vendor(adb: Adb):
    v = adb.getprop('ro.vendor.build.fingerprint')
    if v in KNOWN_VENDORS:
        ui.ok(f'vendor {v.split("/")[-2]}, the one this was proven on')
    else:
        ui.warn(f'vendor {v!r} is not one this installer was proven on. The GSI runs on the '
                'R1\'s own kernel and vendor, and another rabbitOS version may behave differently. '
                'Check the side button, wheel, camera, mic and Wi-Fi.')
