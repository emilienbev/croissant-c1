"""The device's own name, apart from the boot-screen name: Android's device_name, which the
app shows in Settings > Device and turns into its Tailscale hostname.

The rule matches the app's (`DeviceName.kt`), which renames it later.
"""
import re

from . import ui

DEFAULT = 'Croissant C1'           # its Tailscale hostname, croissant-c1, is what every R1 had before
MAX = 24


def problem(name: str) -> str | None:
    """Why [name] cannot be the device name, or None."""
    if not 1 <= len(name) <= MAX or not re.fullmatch(r"[\w .'&+-]+", name):
        return f'device name {name!r}: 1 to {MAX} letters, digits, spaces or . \' & + -'
    if not re.search(r'[A-Za-z0-9]', name):
        return f'device name {name!r}: needs a letter or digit from A to Z or 0 to 9, for its Tailscale hostname'
    return None


def check(name: str) -> str:
    if (why := problem(name)) is not None:
        ui.fail(why)
    return name
