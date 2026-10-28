#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy>=1.26", "soundfile>=0.12"]
# ///
"""Zen, the interface sounds Croissant shipped first, as a pack beside the ones earshot.py renders.

Zen is a pack of uisfx (https://github.com/uisfx, CC0 audio): the app played it from res/raw until commit bda58b6,
each cue at the pack's level for it. This takes those files from that commit, as they were, and writes them in the
packs' format: one Opus file per cue of audio/Cue.kt, the level in the file. Each cue sounds as Zen made it then:
Home and the apps and the control centre take Zen's forward and back, as they did, and Ask's three cues are left
out, silent, as they were.

Writes android/app/src/main/assets/sounds/zen/<cue>.ogg. Its demo comes with the others, from earshot.py.

    sounds/zen.py
"""
import io
import os
import re
import subprocess

import numpy as np
import soundfile as sf

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..'))
OUT = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'assets', 'sounds', 'zen')
REV = 'bda58b6'                       # the last commit that played Zen from res/raw
RAW = 'android/app/src/main/res/raw/ui_{}.ogg'
CUE_KT = 'android/app/src/main/java/com/bleural/croissant/audio/Cue.kt'

# Each cue of audio/Cue.kt and the Zen sound the app played for it then.
ZEN = {'step': 'typing', 'list_in': 'forward', 'list_out': 'back', 'tap': 'press', 'enter': 'hover', 'back': 'back',
       'open': 'forward', 'panel_in': 'forward', 'panel_out': 'back', 'on': 'toggle_on', 'off': 'toggle_off',
       'destroy': 'delete', 'fail': 'error', 'link_up': 'connect', 'link_down': 'disconnect', 'shutter': 'snap'}


def show(path):
    return subprocess.run(['git', '-C', ROOT, 'show', f'{REV}:{path}'], check=True, capture_output=True).stdout


def main():
    levels = dict(re.findall(r'R\.raw\.ui_(\w+), ([0-9.]+)f', show(CUE_KT).decode()))
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        os.remove(os.path.join(OUT, f))
    for cue, name in ZEN.items():
        y, sr = sf.read(io.BytesIO(show(RAW.format(name))), dtype='float32')
        y = y.mean(axis=1) if y.ndim == 2 else y
        sf.write(os.path.join(OUT, cue + '.ogg'), y * float(levels[name]), sr, format='OGG', subtype='OPUS')
        print(f'  zen {cue:10s} {name:11s} level {levels[name]}')


if __name__ == '__main__':
    main()
