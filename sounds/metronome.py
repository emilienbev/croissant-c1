#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy>=1.26", "scipy>=1.11", "soundfile>=0.12", "soxr>=0.3", "numba>=0.59"]
# ///
"""The metronome's tones that are rendered rather than synthesised in the app: Tine, Chime and Bamboo.

Click, Woodblock and Hi-hat are synthesised by metronome/Clicks.kt itself. These three need more than a few sines:
earshot's physical kalimba, the Shrine chime and a hollow tube's modes (sounds/earshot.py's voices). Each has the
three pulses Clicks.kt plays: the accent (the bar's first beat, a fifth up), a beat, and a subdivision (softer,
shorter). For a metronome rather than a cue: an onset at once and a peak within 3 ms, so the beat lands on the
click; short, so sixteenths at 208 bpm do not smear; the weight above 500 Hz, where the R1 speaker plays.

Writes android/app/src/main/assets/metronome/<tone>/{accent,beat,sub}.wav, 48 kHz mono 16-bit, each peaking at
-1 dBFS: Clicks.kt sets the level of each pulse, as it does for the three it makes.

Usage (the earshot clone is --from, or $EARSHOT_FROM when it is not given):
    sounds/metronome.py --from <earshot clone>
"""
import argparse
import importlib.util
import os
import shutil
import sys

import numpy as np
import soundfile as sf

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..'))
OUT = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'assets', 'metronome')
PULSES = ('accent', 'beat', 'sub')
MAX_S = {'accent': 0.3, 'beat': 0.25, 'sub': 0.1}
FIFTH = 1.5

E = bank = dsp = fx = None   # sounds/earshot.py and earshot's, loaded once --from is known


def tine(pulse, r):
    """earshot's kalimba tine: A5 on the beat, E6 on the one; a tine stopped by the thumb on the subdivision."""
    if pulse == 'sub':
        y, _ = bank.render('pluck/kalimba_soft', pitch=E.hz('A5'), dur=0.07)
        return E.mono(y)
    y, _ = bank.render('pluck/kalimba', pitch=E.hz('E6' if pulse == 'accent' else 'A5'), dur=0.3)
    # the thumb's release is soft (10 ms to the peak): brought forward so the beat lands on the click
    return fx.transient(E.mono(y), attack_db=9.0, attack_ms=2.0)


def chime(pulse, r):
    """The Shrine chime, short: E6 on the beat, B6 on the one, a muted touch between."""
    if pulse == 'sub':
        return E.airy(E.chime(E.hz('E6'), r, dur=0.15, decay=0.02, glint=0.0, index=0.6), r, wet=0.05, rt60=0.3)
    f = E.hz('B6' if pulse == 'accent' else 'E6')
    return E.airy(E.chime(f, r, dur=0.35, decay=0.06, glint=0.3 if pulse == 'accent' else 0.18, index=1.2), r,
                  wet=0.08, rt60=0.4)


def bamboo(pulse, r):
    """A length of bamboo knocked: a hollow tube's modes, a breath of the air inside; a fifth up on the one."""
    f = 650 * (FIFTH if pulse == 'accent' else 1.0)
    tau = 0.012 if pulse == 'sub' else 0.04
    body = dsp.modal([f, f * 2.3, f * 4.1, f * 1.004], [tau, tau * 0.4, tau * 0.15, tau * 1.2],
                     [1.0, 0.45, 0.2, 0.3], 0.25, r, jitter=0.001)
    knock = dsp.modal_cloud(1500, 5000, 8, 0.004, 0.0015, 0.25, r) * 0.3
    return dsp.excite(body + knock, dsp.contact(0.0005, r, bright=0.5))


TONES = {'tine': (tine, 1), 'chime': (chime, 2), 'bamboo': (bamboo, 3)}   # each its own random seed


def finish(y, max_s):
    """Start on the onset with a 0.3 ms ramp (crisp, no click), end where it falls 50 dB or at max_s, peak -1 dBFS."""
    y = E.mono(y)
    y = y[max(0, int(dsp.onset(y, -40) * E.SR) - int(0.001 * E.SR)):].copy()
    a = dsp.n_of(0.0003)
    y[:a] *= np.linspace(0, 1, a)
    y = dsp.trim_tail(y, -50, keep=0.004)
    if len(y) > max_s * E.SR:
        y = dsp.fade(y[:int(max_s * E.SR)], 0.0, max_s * 0.4)
    y = dsp.fade(y, 0.0, min(0.03, len(y) / E.SR * 0.3))
    return y * 10 ** (-1 / 20) / np.abs(y).max()


def main():
    global E, bank, dsp, fx
    ap = argparse.ArgumentParser(description='Render the metronome tones Clicks.kt loads from the assets.')
    ap.add_argument('--from', dest='src', default=os.environ.get('EARSHOT_FROM'),
                    help='a clone of github.com/Leonxlnx/earshot (default $EARSHOT_FROM)')
    a = ap.parse_args()
    if not a.src or not os.path.isfile(os.path.join(a.src, 'earshot', 'forge', 'bank.py')):
        sys.exit('--from must name a clone of github.com/Leonxlnx/earshot')
    sys.path.insert(0, os.path.abspath(a.src))
    from earshot.forge import bank as b, dsp as d, fx as f
    bank, dsp, fx = b, d, f
    spec = importlib.util.spec_from_file_location('packs', os.path.join(HERE, 'earshot.py'))
    E = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(E)
    E.bank, E.dsp, E.fx = b, d, f

    shutil.rmtree(OUT, ignore_errors=True)
    for name, (make, seed) in TONES.items():
        os.makedirs(os.path.join(OUT, name))
        r = dsp.rng(seed)
        for p in PULSES:
            y = finish(make(p, r), MAX_S[p])
            sf.write(os.path.join(OUT, name, p + '.wav'), y.astype(np.float32), E.SR, subtype='PCM_16')
        print(f'  {name:7s} ' + ', '.join(f'{p} {round(1000 * sf.info(os.path.join(OUT, name, p + ".wav")).duration)} ms'
                                           for p in PULSES))


if __name__ == '__main__':
    main()
