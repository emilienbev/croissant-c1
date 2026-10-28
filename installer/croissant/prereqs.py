"""What this machine needs besides uv (./croissant sees to uv itself), checked before it is
needed and offered when missing:

- To build the app from source: Java to start Gradle, offered through SDKMAN!, and the
  Android SDK with its NDK, offered where Android Studio keeps it (~/Library/Android/sdk on
  macOS, ~/Android/Sdk on Linux) so a later Android Studio finds the same one.
- On Linux, access to the R1 over USB: a udev rule for its two USB vendors.
"""
import os
import platform
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

from . import artifacts, ui
from .paths import CACHE, REPO

JAVA_MIN = 17                     # Gradle 9 runs on 17 or newer
SDKMAN = Path.home() / '.sdkman'
SDKMAN_JAVA = SDKMAN / 'candidates/java/current'
SDKMAN_URL = 'https://get.sdkman.io'

NDK = '28.2.13676358'
# What the build asks of the SDK: compileSdk, the build-tools AGP 9.4 uses, lpac-jni's NDK.
PACKAGES = ('platforms;android-37.0', 'build-tools;36.0.0', f'ndk;{NDK}')
LOCAL_PROPERTIES = REPO / 'android/local.properties'

# Google's command-line tools 22.0 (sdkmanager), checksums from repository2-3.xml, kept in
# the cache and used whatever the SDK holds: 23.0's sdkmanager hands over to the Android CLI,
# which installs without showing the licences (2026-10-15). The Linux zip is Java only, so it
# runs on any architecture.
CMDLINE_TOOLS = {
    ('Darwin', 'arm64'): artifacts.Artifact(
        'commandlinetools-mac_arm64-15859902_latest.zip',
        'https://dl.google.com/android/repository/commandlinetools-mac_arm64-15859902_latest.zip',
        'c4e0dbc53f1a4ce04fc2b11d4e2c4675001a95af', 'sha1'),
    ('Darwin', 'x86_64'): artifacts.Artifact(
        'commandlinetools-mac_x86_64-15859902_latest.zip',
        'https://dl.google.com/android/repository/commandlinetools-mac_x86_64-15859902_latest.zip',
        '1a79e61677b99cb0ce622792713cbea7ff2dc5de', 'sha1'),
    ('Linux', ''): artifacts.Artifact(
        'commandlinetools-linux-15859902_latest.zip',
        'https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip',
        '040d3996a65543d22ec4bf73e4c37aa37a8d4af4', 'sha1'),
}
CMDLINE_TOOLS_DIR = CACHE / 'cmdline-tools-22.0'

UDEV_FILE = Path('/etc/udev/rules.d/52-croissant.rules')
UDEV_DIRS = ('/etc/udev/rules.d', '/run/udev/rules.d', '/usr/lib/udev/rules.d', '/lib/udev/rules.d')
USB_VENDORS = {'0e8d': 'MediaTek: the preloader, and the R1 in fastboot',
               '18d1': 'Google: adb and fastbootd'}
# The R1's USB devices and the preloader's serial port for this user (uaccess) and anyone
# (0666), and kept from ModemManager, which otherwise probes the preloader's port.
UDEV_RULES = '''# Written by ./croissant: the Rabbit R1 over USB (installer/README.md).
SUBSYSTEM=="usb", ATTR{idVendor}=="0e8d", MODE="0666", TAG+="uaccess", ENV{ID_MM_DEVICE_IGNORE}="1"
SUBSYSTEM=="tty", ATTRS{idVendor}=="0e8d", MODE="0666", TAG+="uaccess", ENV{ID_MM_DEVICE_IGNORE}="1"
SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", MODE="0666", TAG+="uaccess"
'''


# --- Java ---------------------------------------------------------------------------------

def _major(version: str) -> int:
    """The major version from java.version: '21.0.4' is 21, '1.8.0_402' is 8."""
    m = re.match(r'(\d+)(?:\.(\d+))?', version)
    if not m:
        return 0
    return int(m[2] or 0) if m[1] == '1' else int(m[1])


def _probe(exe: Path | str) -> tuple[Path | None, int]:
    """[exe]'s java.home and major version; (None, 0) when it does not run (macOS has a
    /usr/bin/java that only says no Java is installed)."""
    try:
        p = subprocess.run([str(exe), '-XshowSettings:properties', '-version'],
                           capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        return None, 0
    props = dict(re.findall(r'^\s*(java\.home|java\.version) = (.*)$', p.stderr + p.stdout, re.M))
    if p.returncode != 0 or 'java.home' not in props:
        return None, 0
    return Path(props['java.home'].strip()), _major(props.get('java.version', '').strip())


def java() -> tuple[Path | None, int]:
    """The Java home Gradle should start on and its major version: JAVA_HOME's, the PATH's,
    then SDKMAN!'s current one (there even when this shell has not loaded SDKMAN!). The newest
    of them when none is new enough; (None, 0) when there is none."""
    candidates = []
    if home := os.environ.get('JAVA_HOME'):
        candidates.append(Path(home) / 'bin/java')
    if on_path := shutil.which('java'):
        candidates.append(on_path)
    candidates.append(SDKMAN_JAVA / 'bin/java')
    best = (None, 0)
    for exe in candidates:
        if not Path(exe).exists():
            continue
        found = _probe(exe)
        if found[1] >= JAVA_MIN:
            return found
        best = max(best, found, key=lambda f: f[1])
    return best


def _java_ok() -> bool:
    return java()[1] >= JAVA_MIN


def _install_java() -> bool:
    """SDKMAN! if it is not here yet, then its default Java (the current Temurin LTS)."""
    # Its installers print and ask as they go: no spinner over them.
    with ui.aside():
        if not (SDKMAN / 'bin/sdkman-init.sh').exists():
            ui.info(f'SDKMAN! installs into {SDKMAN} and adds two lines to your shell profile.')
            if subprocess.run(['bash', '-c', f'curl -s "{SDKMAN_URL}" | bash']).returncode != 0:
                ui.warn('SDKMAN! did not install (its output is above).')
                return False
        r = subprocess.run(['bash', '-c', f'source "{SDKMAN}/bin/sdkman-init.sh" && sdk install java'])
        if r.returncode != 0 or not _java_ok():
            ui.warn('sdk install java did not finish (its output is above).')
            return False
        ui.ok(f'Java {java()[1]}, from SDKMAN!. New shells pick it up; this one may need: sdk use java')
        return True


# --- the Android SDK ------------------------------------------------------------------------

def default_sdk() -> Path:
    """Where Android Studio puts the SDK."""
    if platform.system() == 'Darwin':
        return Path.home() / 'Library/Android/sdk'
    return Path.home() / 'Android/Sdk'


def sdk() -> Path | None:
    """The SDK in use: ANDROID_HOME, ANDROID_SDK_ROOT, android/local.properties, the default."""
    for p in (os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT')):
        if p and Path(p).is_dir():
            return Path(p)
    if LOCAL_PROPERTIES.exists():
        for line in LOCAL_PROPERTIES.read_text().splitlines():
            if line.startswith('sdk.dir='):
                return Path(line.split('=', 1)[1].strip())
    return default_sdk() if default_sdk().is_dir() else None


def _sdk_missing(root: Path | None) -> list[str]:
    if not root:
        return list(PACKAGES)
    return [p for p in PACKAGES if not (root / p.replace(';', '/')).is_dir()]


def _no_ndk_here() -> str | None:
    if platform.system() == 'Linux' and platform.machine() in ('aarch64', 'arm64'):
        return 'Google publishes no NDK for Linux on arm64, so the app cannot be built here'
    return None


def _sdkmanager() -> Path:
    """The pinned sdkmanager, unpacked in the cache once."""
    exe = CMDLINE_TOOLS_DIR / 'bin/sdkmanager'
    if exe.exists():
        return exe
    system, machine = platform.system(), platform.machine()
    a = CMDLINE_TOOLS.get((system, machine if system == 'Darwin' else ''))
    if not a:
        ui.fail(f'no Android command-line tools pinned for {system} {machine}')
    zip_path = artifacts.fetch(a)
    tmp = CACHE / 'cmdline-tools.part'
    shutil.rmtree(tmp, ignore_errors=True)
    with zipfile.ZipFile(zip_path) as z:       # unpacks as cmdline-tools/
        z.extractall(tmp)
    shutil.rmtree(CMDLINE_TOOLS_DIR, ignore_errors=True)
    (tmp / 'cmdline-tools').rename(CMDLINE_TOOLS_DIR)
    tmp.rmdir()
    for f in (CMDLINE_TOOLS_DIR / 'bin').iterdir():
        f.chmod(0o755)
    return exe


def _install_sdk(root: Path) -> bool:
    # sdkmanager prints and asks (the licences) as it goes: no spinner over it.
    with ui.aside():
        exe = _sdkmanager()
        want = _sdk_missing(root)
        ui.info(f'sdkmanager: {", ".join(want)}. Google\'s licences come up first, to accept or not.')
        env = os.environ | _java_env()
        r = subprocess.run([str(exe), f'--sdk_root={root}', *want], env=env)
        if r.returncode != 0 or _sdk_missing(root):
            ui.warn('sdkmanager did not install everything (its output is above).')
            return False
        if not LOCAL_PROPERTIES.exists():          # so a build by hand finds it too
            LOCAL_PROPERTIES.write_text(f'sdk.dir={root}\n')
        ui.ok(f'Android SDK in {root}')
        return True


# --- building -------------------------------------------------------------------------------

def _java_env() -> dict:
    home, _ = java()
    return {'JAVA_HOME': str(home)} if home else {}


def build_env() -> dict:
    """The environment ./gradlew runs in: the Java and the SDK found here."""
    env = os.environ | _java_env()
    if root := sdk():
        env['ANDROID_HOME'] = str(root)
    return env


def missing_for_build() -> list[str]:
    """What stops this machine building the app, as short phrases; empty when it can."""
    if why := _no_ndk_here():
        return [why]
    out = []
    _, v = java()
    if v < JAVA_MIN:
        out.append(f'Java {JAVA_MIN} or newer (found {v})' if v else f'Java {JAVA_MIN} or newer')
    root = sdk()
    if not root:
        out.append('the Android SDK')
    elif lacking := _sdk_missing(root):
        out.append(f'{", ".join(lacking)} in the Android SDK ({root})')
    return out


def ensure_build() -> bool:
    """Everything a build needs, offering what is missing. False when it is still missing
    (declined or failed); the caller offers the other ways to the app."""
    if not (lacking := missing_for_build()):
        return True
    if _no_ndk_here():
        ui.warn(lacking[0])
        return False
    ui.warn(f'Building needs {"; ".join(lacking)}.')
    if not ui.INTERACTIVE:
        return False
    if not _java_ok():
        ui.info('SDKMAN! (https://sdkman.io) is the recommended way to install and switch JDKs.')
        if not ui.confirm('Install SDKMAN! and Java with it now?') or not _install_java():
            return False
    root = sdk() or default_sdk()
    if _sdk_missing(root):
        ui.info(f'About 3 GB, into {root}, where Android Studio would put it.')
        if not ui.confirm(f'Install {", ".join(_sdk_missing(root))} there now?') or not _install_sdk(root):
            return False
    return not missing_for_build()


# --- Linux: the R1 over USB -------------------------------------------------------------------

def _rule_files() -> list[Path]:
    return [f for d in UDEV_DIRS if Path(d).is_dir() for f in sorted(Path(d).glob('*.rules'))]


def usb_vendors_missing() -> list[str]:
    """The R1's USB vendors no udev rule mentions. Linux only; empty elsewhere."""
    if platform.system() != 'Linux' or UDEV_FILE.exists():
        return []
    text = ''
    for f in _rule_files():
        try:
            text += f.read_text(errors='replace').lower()
        except OSError:
            continue
    return [v for v in USB_VENDORS if not re.search(rf'idvendor\}}=="{v}"', text)]


def ensure_usb():
    """On Linux, the udev rule that lets this user reach the R1 over USB, offered with sudo.
    Never fatal: the steps that need it say so again if they fail."""
    if not (lacking := usb_vendors_missing()):
        return
    ui.warn('No udev rule gives this user the R1 over USB: '
            + '; '.join(f'{v} ({USB_VENDORS[v]})' for v in lacking) + '.')
    commands = [f'sudo tee {UDEV_FILE}', 'sudo udevadm control --reload-rules', 'sudo udevadm trigger']
    ui.info(f'The fix writes {UDEV_FILE}:')
    for line in UDEV_RULES.strip().splitlines()[1:]:
        ui.info('  ' + line)
    ui.info('then runs: ' + ', '.join(commands[1:]))
    if not ui.INTERACTIVE or not ui.confirm('Run it with sudo now?'):
        ui.info('Without it, the preloader read, fastboot and adb fail with a permission error.')
        return
    ok = subprocess.run(['sudo', 'tee', str(UDEV_FILE)], input=UDEV_RULES, text=True,
                        stdout=subprocess.DEVNULL).returncode == 0
    ok = ok and all(subprocess.run(c.split()).returncode == 0 for c in commands[1:])
    if ok:
        ui.ok(f'{UDEV_FILE} written. If the R1 is plugged in, unplug it and plug it in again.')
    else:
        ui.warn('the udev rule did not go in (the output is above)')


# --- doctor -----------------------------------------------------------------------------------

def report():
    """One line each, for doctor."""
    home, v = java()
    if v >= JAVA_MIN:
        ui.ok(f'Java {v}' + (f' ({home})' if home else ''))
    else:
        ui.info(f'no Java {JAVA_MIN}+ (only needed to build the app; SDKMAN! installs it)')
    root = sdk()
    if why := _no_ndk_here():
        ui.info(why)
    elif root and not _sdk_missing(root):
        ui.ok(f'Android SDK and NDK {NDK} ({root})')
    else:
        lacking = _sdk_missing(root)
        ui.info(f'Android SDK: missing {", ".join(lacking)} (only needed to build the app)')
    if platform.system() == 'Linux':
        lacking = usb_vendors_missing()
        (ui.warn if lacking else ui.ok)(
            f'no udev rule for USB vendor {", ".join(lacking)}' if lacking else 'udev rules for the R1 over USB')
