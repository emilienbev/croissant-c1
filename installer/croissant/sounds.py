"""The app's interface sound packs, and their demos for picking one by ear.

The packs ship in the APK (android/app/src/main/assets/sounds/, sounds/README.md); the app plays the one
named in its files/sound-pack (audio/Cues.kt). Each pack's demo, <pack>.m4a beside this file, is a user
going through the app: moving the highlight onto a pack plays it, on this machine.
"""
import shutil
import subprocess
import sys
from pathlib import Path

from . import ui

DEMOS = Path(__file__).parent / 'sounds'
FILE = 'sound-pack'            # the app's name for it, Cues.PACK_FILE
DEFAULT = 'shrine'             # Cues.DEFAULT_PACK

PACKS = {
    'bubble': 'Bubble: round bloops, playful and wet',
    'grove': 'Grove: soft marimba and dry wooden ticks',
    'kalimba': 'Kalimba: a thumb-piano tine, gentle and musical',
    'shrine': 'Shrine: soft chimes with an airy room',
    'switch': 'Switch: detents, latches and keys, no pitch',
    'zen': 'Zen: the sounds Croissant shipped first',
}


def check(pack: str) -> str:
    if pack not in PACKS:
        ui.fail(f'No sound pack {pack}: {", ".join(PACKS)}')
    return pack


def _player() -> list[str] | None:
    """A command that plays an .m4a and exits: afplay on a Mac, ffplay elsewhere when there is one."""
    if sys.platform == 'darwin' and shutil.which('afplay'):
        return ['afplay']
    if shutil.which('ffplay'):
        return ['ffplay', '-nodisp', '-autoexit', '-loglevel', 'quiet']
    return None


class Demo:
    """One demo at a time: a new one stops the one playing."""

    def __init__(self):
        self.cmd = _player()
        self.proc = None

    def play(self, pack: str):
        self.stop()
        if self.cmd:
            self.proc = subprocess.Popen(self.cmd + [str(DEMOS / f'{pack}.m4a')],
                                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
        self.proc = None


def pick(default: str = DEFAULT) -> str:
    """Asked with a demo playing for the highlighted pack."""
    demo = Demo()
    if demo.cmd:
        ui.info('Each pack plays a demo as you move onto it: scrolling, Settings, the Wi-Fi, the camera.')
    else:
        ui.info('No player here for the demos (afplay, or ffplay): pick by name.')
    try:
        return ui.select_live('Which sounds?', [(t, p) for p, t in PACKS.items()],
                              default if default in PACKS else DEFAULT, demo.play)
    finally:
        demo.stop()
