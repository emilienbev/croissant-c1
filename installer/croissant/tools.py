"""adb and fastboot, bound to one R1 by serial, with the waits this device needs."""
import re
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

from . import artifacts, ui
from .paths import STAGE

MIN_FASTBOOT = (34, 0)   # snapshot-update, is-userspace and a sane fastbootd dance


def _version(fastboot: str) -> tuple:
    out = subprocess.run([fastboot, '--version'], capture_output=True, text=True).stdout
    m = re.search(r'version (\d+)\.(\d+)', out)
    return (int(m[1]), int(m[2])) if m else (0, 0)


def binaries() -> tuple[str, str]:
    """adb and fastboot: the ones on PATH when recent enough, else Google's, pinned."""
    adb, fastboot = shutil.which('adb'), shutil.which('fastboot')
    if adb and fastboot and _version(fastboot) >= MIN_FASTBOOT:
        return adb, fastboot
    d = artifacts.platform_tools()
    return str(d / 'adb'), str(d / 'fastboot')


def _run(argv, timeout=None, show=False) -> subprocess.CompletedProcess:
    """Run a tool. With show, its output is passed through as it comes (flashes are long)."""
    if not show:
        return subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
    p = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    lines = []
    for line in p.stdout:
        lines.append(line)
        print('    ' + line.rstrip())
    p.wait(timeout)
    return subprocess.CompletedProcess(argv, p.returncode, ''.join(lines), '')


class Fastboot:
    def __init__(self, exe: str, serial: str | None):
        self.exe, self.serial = exe, serial

    def devices(self) -> list[str]:
        out = _run([self.exe, 'devices'], timeout=10).stdout
        return [l.split()[0] for l in out.splitlines() if l.strip()]

    def present(self) -> bool:
        return self.serial in self.devices() if self.serial else bool(self.devices())

    def _argv(self, *args):
        return [self.exe] + (['-s', self.serial] if self.serial else []) + list(args)

    def getvar(self, name: str) -> str:
        # fastboot prints variables on stderr as "name: value".
        p = _run(self._argv('getvar', name), timeout=20)
        for line in (p.stderr + p.stdout).splitlines():
            if line.startswith(name + ':'):
                return line.split(':', 1)[1].strip()
        return ''

    def userspace(self) -> bool:
        return self.getvar('is-userspace') == 'yes'

    def run(self, *args, timeout=None, show=True, check=True) -> str:
        p = _run(self._argv(*args), timeout=timeout, show=show)
        out = p.stdout + p.stderr
        if check and p.returncode != 0:
            ui.fail(f'fastboot {" ".join(args)} failed:\n{out.strip()}')
        return out

    def wait(self, userspace: bool | None = None, timeout=180):
        """Wait until the R1 is in fastboot (LK when userspace is False, fastbootd when True)."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.present() and (userspace is None or self.userspace() == userspace):
                return
            time.sleep(1)
        want = {None: 'fastboot', False: 'the bootloader (LK fastboot)', True: 'fastbootd'}[userspace]
        ui.fail(f'the R1 did not show up in {want} within {timeout} s')


class Adb:
    def __init__(self, exe: str, serial: str | None):
        self.exe, self.serial = exe, serial

    def _argv(self, *args):
        return [self.exe] + (['-s', self.serial] if self.serial else []) + list(args)

    def devices(self) -> list[tuple[str, str]]:
        out = _run([self.exe, 'devices'], timeout=10).stdout
        return [tuple(l.split()[:2]) for l in out.splitlines()[1:] if l.strip()]

    def present(self) -> bool:
        return any(s == self.serial and st == 'device' for s, st in self.devices())

    def run(self, *args, timeout=120, check=True, show=False) -> str:
        p = _run(self._argv(*args), timeout=timeout, show=show)
        if check and p.returncode != 0:
            ui.fail(f'adb {" ".join(args)} failed:\n{(p.stdout + p.stderr).strip()}')
        return p.stdout

    def sh(self, cmd: str, timeout=60, check=True, show=False) -> str:
        """One simple command on the device. Anything with `&&`, quotes or pipes as root
        goes through script(): adb re-parses the line on the device."""
        return self.run('shell', cmd, timeout=timeout, check=check, show=show).strip()

    def getprop(self, name: str) -> str:
        return self.sh(f'getprop {name}', check=False)

    def push(self, local, remote: str):
        self.run('push', str(local), remote, timeout=600)

    def exec_out(self, cmd: str, timeout=300) -> bytes:
        return subprocess.run(self._argv('exec-out', cmd), capture_output=True, timeout=timeout).stdout

    def reboot(self, target: str = ''):
        self.run(*(['reboot', target] if target else ['reboot']), check=False)

    def wait_boot(self, timeout=600):
        """Wait for adb, then for Android to finish booting (the first boot is slow)."""
        deadline = time.time() + timeout
        while time.time() < deadline and not self.present():
            # The serial came from fastboot; not yet seen whether adb reports the same one.
            # With exactly one other R1 in adb, that is it.
            others = [s for s, st in self.devices() if st == 'device' and not s.startswith('emulator-')]
            if len(others) == 1 and others[0] != self.serial:
                ui.info(f'adb calls the R1 {others[0]} (fastboot said {self.serial})')
                self.serial = others[0]
                break
            time.sleep(2)
        while time.time() < deadline:
            if self.present() and self.getprop('sys.boot_completed') == '1':
                return
            time.sleep(2)
        ui.fail(f'the R1 did not finish booting within {timeout} s')

    def root(self) -> bool:
        """Root on the R1 means Magisk's su for the adb shell, which the patched boot image
        grants at boot (device/grant). Never `adb root`: LineageOS refuses it unless
        "Rooted debugging" is on, and each attempt restarts adbd and drops USB, sometimes
        until the R1 restarts (2026-10-05, again 2026-10-10)."""
        return self.has_su()

    def has_su(self) -> bool:
        return self.sh('su -c id -u', check=False, timeout=15) == '0'

    def script(self, text: str, name: str, timeout=300, check=True, need_root=True, show=False) -> str:
        """Run a shell script on the device as root (Magisk's su): pushed, then `sh`, never
        inlined. With need_root False it runs as the shell user when there is no su; with
        show, its output is printed as it comes."""
        with tempfile.NamedTemporaryFile('w', suffix='.sh', delete=False) as f:
            f.write(text)
        remote = f'{STAGE}/croissant-{name}.sh'
        self.push(f.name, remote)
        Path(f.name).unlink()
        if self.has_su():
            cmd = f'su -c "sh {remote}"'
        elif not need_root:
            cmd = f'sh {remote}'
        else:
            ui.fail('no root on the R1: Magisk has not granted su to the adb shell. Open '
                    'Magisk > Superuser on the R1 and turn on [SharedUID] com.android.shell, '
                    'then run again.')
        return self.sh(cmd, timeout=timeout, check=check, show=show)
