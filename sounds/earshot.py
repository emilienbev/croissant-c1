#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy>=1.26", "scipy>=1.11", "soundfile>=0.12", "soxr>=0.3", "numba>=0.59"]
# ///
"""The interface sound packs, rendered with earshot, and a demo of each for the installer.

earshot (https://github.com/Leonxlnx/earshot, MIT) is used for its bank of sounds stored as code and its DSP
toolkit only: no models, nothing downloaded. Every pack fills the cues of `audio/Cue.kt` from one material and
one scale, with the same rules:

  - going in steps up, going out steps down; on sits a third to a fifth above off;
  - a link up rises, a link down falls; a failure is a dull close interval;
  - the most frequent cues (the wheel step, a tap, in and back, Home and the apps) are muffled: a low register,
    the highs rolled off from 1.2-2.6 kHz, a softened onset, quiet; the wheel step the quietest of all;
  - the cues that happen now and then (switches, the control centre, Ask, links, the shutter) keep their shine;
  - each cue is cut short by its role (MAX_S), from 50 ms for the wheel step to 600 ms for a link up.

The packs were made in three rounds, each its own function with its own random generator, a later round's cue
replacing an earlier one's: so a round leaves every cue already approved as it was, sample for sample.

Loudness is matched through an estimate of the R1 speaker (high-pass 500 Hz, low-pass 9 kHz, a guess, not a
measurement) and the ear (BS.1770 K-weighting), so a cue whose weight sits below what the speaker plays is not
judged louder than it sounds, nor a bright one let through louder than it is heard. The level is in each file:
the app plays every cue at the Sound level, nothing more.

Writes:
    android/app/src/main/assets/sounds/<pack>/<cue>.ogg   48 kHz mono Opus, one file per cue
    installer/croissant/sounds/<pack>.m4a                 the demo: a user going through the app (AAC,
                                                          by macOS's afconvert, so it plays with afplay)

Any other pack folder in assets/sounds is removed, so a pack dropped here leaves nothing behind, except the packs
made elsewhere (KEPT: Zen, by sounds/zen.py), whose demos are made here from their files. Rebuild the app
afterwards.

Usage (the earshot clone is --from, or $EARSHOT_FROM when it is not given):
    sounds/earshot.py --from <earshot clone>                every pack, and the demos
    sounds/earshot.py --from <earshot clone> bubble grove   those packs only (demos need every pack)
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile

import numpy as np
import soundfile as sf
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..'))
ASSETS = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'assets', 'sounds')
DEMOS = os.path.join(ROOT, 'installer', 'croissant', 'sounds')
SR = 48000

# The file names are audio/Cue.kt's.
CUES = ['step', 'list_in', 'list_out', 'tap', 'enter', 'back', 'open', 'panel_in', 'panel_out', 'on', 'off',
        'destroy', 'fail', 'link_up', 'link_down', 'shutter', 'listen', 'heard', 'arrived']

# Loudness, dB of the loudest 30 ms through the speaker and the ear. The wheel step fires on every step.
TIER = {'step': -38, 'list_in': -33, 'list_out': -34, 'tap': -29, 'enter': -29, 'back': -30, 'open': -23,
        'panel_in': -28, 'panel_out': -29, 'on': -24.5, 'off': -25, 'destroy': -23, 'fail': -22, 'link_up': -21,
        'link_down': -23, 'shutter': -22, 'listen': -25, 'heard': -28, 'arrived': -25}

# Longest each cue may ring, seconds: the tail is faded to nothing by then.
MAX_S = {'step': 0.05, 'list_in': 0.16, 'list_out': 0.16, 'tap': 0.16, 'enter': 0.22, 'back': 0.22, 'open': 0.34,
         'panel_in': 0.26, 'panel_out': 0.26, 'on': 0.3, 'off': 0.28, 'destroy': 0.45, 'fail': 0.4, 'link_up': 0.6,
         'link_down': 0.5, 'shutter': 0.3, 'listen': 0.3, 'heard': 0.22, 'arrived': 0.55}

NOTES = {'C': 0, 'D': 2, 'E': 4, 'F': 5, 'G': 7, 'A': 9, 'B': 11}
PENTA = [0, 2, 4, 7, 9]

bank = dsp = fx = None   # earshot's, imported once --from is known


def hz(name):
    """'C5', 'F#6', 'Bb4' -> Hz (A4 = 440)."""
    n = NOTES[name[0]]
    rest = name[1:]
    while rest and rest[0] in '#b':
        n += 1 if rest[0] == '#' else -1
        rest = rest[1:]
    return 440.0 * 2 ** ((n + 12 * (int(rest) + 1) - 69) / 12)


def scale(root, steps, octaves=3):
    """Hz from root upward along steps (semitones within an octave): degree k is scale(...)[k]."""
    base = hz(root)
    return [base * 2 ** ((12 * o + s) / 12) for o in range(octaves) for s in steps]


# ------------------------------------------------------------------------------------------------ finishing
def mono(y):
    y = np.asarray(y, np.float64)
    return y.mean(axis=1) if y.ndim == 2 else y


def speaker(y):
    """The R1 speaker, estimated: a small driver, little below ~500 Hz, soft above 9 kHz."""
    y = dsp.butter(y, 'high', 500, order=2)
    return dsp.butter(y, 'low', 9000, order=2)


# BS.1770 K-weighting at 48 kHz: the ear hears the treble about 4 dB louder (a high shelf from ~1.7 kHz) and
# little of the deep bass. Without it a bright cue matched flat comes out louder than the rest.
K_SHELF = ([1.53512485958697, -2.69169618940638, 1.19839281085285], [1.0, -1.69065929318241, 0.73248077421585])
K_HP = ([1.0, -2.0, 1.0], [1.0, -1.99004745483398, 0.99007225036621])


def loud_db(y):
    """The loudest 30 ms through the speaker estimate and the ear, dB RMS."""
    s = signal.lfilter(*K_HP, signal.lfilter(*K_SHELF, speaker(y)))
    k = int(0.03 * SR)
    p = np.convolve(s ** 2, np.ones(k) / k, mode='same')
    return 10 * np.log10(p.max() + 1e-20)


def finish(y, target, max_s):
    """Start on the onset, end where the tail falls 50 dB under the peak or at max_s, then set the loudness."""
    y = mono(y)
    on = dsp.onset(y, -40)
    y = y[max(0, int(on * SR) - int(0.001 * SR)):]
    y = dsp.trim_tail(y, -50, keep=0.004)
    if len(y) > max_s * SR:
        y = y[:int(max_s * SR)]
        y = dsp.fade(y, 0.0005, max_s * 0.4)
    y = dsp.fade(y, 0.0005, min(0.03, len(y) / SR * 0.3))
    y = y * 10 ** ((target - loud_db(y)) / 20)
    pk = np.abs(y).max()
    if pk > 10 ** (-1 / 20):
        y *= 10 ** (-1 / 20) / pk
    return y


def seq(parts):
    """Lay parts [(signal, t_seconds, gain_db), ...] on one line."""
    return dsp.stack([(mono(x), t, g) for x, t, g in parts])


def br(sound, **kw):
    """One bank sound, mono."""
    y, _ = bank.render(sound, **kw)
    return mono(y)


# ------------------------------------------------------------------------------------------------ voices
def marimba(f0, r, dur=0.6, soft=0.5, mute=1.0):
    """A rosewood bar over its tube: modes 1 : 3.93 : 9.2 (a tuned marimba bar), the high ones dying first, the
    tube lengthening the fundamental; soft 0..1 widens the mallet's contact (darker); mute < 1 damps it short."""
    tau = 0.32 * (523.0 / f0) ** 0.5 * mute
    body = dsp.modal([f0, f0 * 3.93, f0 * 9.2, f0 * 1.003],
                     [tau, tau * 0.22, tau * 0.07, tau * 1.6],
                     [1.0, 0.28 * (1 - soft) + 0.06, 0.12 * (1 - soft), 0.35], dur, r, jitter=0.0008)
    knock = dsp.modal_cloud(900, 4500, 10, 0.004, 0.0015, dur, r) * 0.25 * (1 - soft * 0.6)
    x = dsp.excite(body + knock, dsp.contact(0.0006 + 0.0022 * soft, r, bright=0.25))
    return dsp.room(x, r, rt60=0.22, size=0.3, wet=0.07)


def woodtick(r, f=2600, tau=0.006):
    """A tiny dry wooden tick: a few short modes around f, struck hard."""
    body = dsp.modal([f, f * 1.73, f * 2.61], [tau, tau * 0.6, tau * 0.4], [1, 0.5, 0.3], 0.06, r)
    return dsp.excite(body, dsp.contact(0.0003, r, bright=0.4))


def bloop(f0, f1, r, glide=0.035, tau=0.05, dur=None, harm=0.12):
    """A round bubble: a sine whose pitch moves f0 -> f1 over `glide` seconds (rising reads as a drop of water,
    falling as a soft plop), a soft 3 ms attack, a touch of 2nd harmonic for body on a small speaker."""
    n = dsp.n_of(max(dur or 0, 8 * tau))
    t = dsp.t_of(n)
    f = f1 + (f0 - f1) * np.exp(-t / max(glide / 3, 1e-4))
    ph = 2 * np.pi * np.cumsum(f) / SR
    env = np.exp(-t / tau)
    a = dsp.n_of(0.003)
    env[:a] *= 0.5 - 0.5 * np.cos(np.linspace(0, np.pi, a))
    y = (np.sin(ph) + harm * np.sin(2 * ph)) * env
    return dsp.room(y, r, rt60=0.15, size=0.2, wet=0.05)


def chime(f0, r, dur=0.5, decay=0.16, glint=0.25, index=1.2):
    """A soft synthetic chime: a sine carrier with a decaying 2:1 FM shimmer, a detuned twin for width and a short
    glassy glint at 4x; the airy, slightly digital tone of a magic tablet's interface."""
    a = dsp.fm(f0, 2.0, index, dur, decay=decay, index_decay=0.025, attack=0.003)
    b = dsp.fm(f0 * 1.0025, 2.0, index * 0.6, dur, decay=decay * 0.9, index_decay=0.02, attack=0.004)
    g = dsp.bell(f0 * 4, dur, r, 'bar', decay=0.05, bright=0.3, strike=0.0) * glint
    return a + 0.5 * b + g


def airy(y, r, wet=0.2, rt60=0.7):
    """Shrine's room: a soft bright tail, the lows kept dry."""
    return mono(fx.reverb(y, r, rt60=rt60, size=0.5, predelay=0.012, damp=0.35, wet=wet, lowcut=400, ir_seed=7))


# ------------------------------------------------------------------------------------------------ packs
# The order of each dict is the order its random generator is drawn from: keep it, or the sounds change.
# Round 1 (2026-10-20): the first twelve cues. Its step, tap, enter and back are replaced in round 3.
def grove():
    """Wood: soft marimba bars and dry wooden ticks, C major pentatonic around C6. Animal Crossing's woody menus."""
    r = dsp.rng(11)
    d = scale('C5', PENTA)                                   # d[5] = C6
    m = lambda k, **kw: marimba(d[k], r, **kw)               # noqa: E731
    return {
        'step': woodtick(r, 2900, 0.005),
        'tap': m(6, dur=0.25, mute=0.35),
        'enter': seq([(m(5, soft=0.7, mute=0.5), 0, -9), (m(7, soft=0.6, mute=0.6), 0.03, 0)]),
        'open': seq([(m(5, mute=0.5), 0, -3), (m(7, mute=0.6), 0.055, 0)]),
        'back': seq([(m(7, soft=0.7, mute=0.5), 0, -2), (m(5, soft=0.8, mute=0.5), 0.055, -1)]),
        'on': seq([(woodtick(r, 2600), 0, -10), (m(6, soft=0.65, mute=0.45), 0.008, 0)]),
        'off': seq([(woodtick(r, 2200), 0, -6), (m(4, soft=0.8, mute=0.35), 0.008, 0)]),
        'destroy': seq([(m(4, soft=0.9, mute=0.4), 0, 0), (m(2, soft=0.9, mute=0.4), 0.07, -2),
                        (m(0, soft=0.95, mute=0.5), 0.14, -3)]),
        'fail': seq([(m(1, soft=0.95, mute=0.3), 0, 0),
                     (marimba(d[1] * 2 ** (-1 / 12), r, soft=0.95, mute=0.3), 0.11, -1)]),
        'link_up': seq([(m(5, mute=0.6), 0, -3), (m(7, mute=0.6), 0.07, -2), (m(9, mute=0.9), 0.14, 0)]),
        'link_down': seq([(m(9, soft=0.7, mute=0.6), 0, -2), (m(7, soft=0.7, mute=0.6), 0.08, -2),
                          (m(5, soft=0.8, mute=0.8), 0.16, -1)]),
        'shutter': seq([(br('fit/hit/bamboo_tok', seed=3), 0, 0), (m(10, mute=0.3), 0.004, -8)]),
    }


def kalimba():
    """A thumb piano: earshot's physical kalimba tine, D major pentatonic around D6, notes damped short."""
    d = scale('D5', PENTA)                                   # d[5] = D6

    def t(k, dur=0.35, soft=False, f=None):
        return br('pluck/kalimba_soft' if soft else 'pluck/kalimba', pitch=f or d[k], dur=dur)
    return {
        'step': t(9, dur=0.035),
        'tap': t(6, dur=0.25),
        'enter': seq([(t(5, dur=0.2, soft=True), 0, -9), (t(7, dur=0.35, soft=True), 0.03, 0)]),
        'open': seq([(t(5, dur=0.25), 0, -3), (t(7, dur=0.4), 0.055, 0)]),
        'back': seq([(t(7, dur=0.25, soft=True), 0, -2), (t(5, dur=0.35, soft=True), 0.055, -1)]),
        'on': t(6, dur=0.3, soft=True),
        'off': t(4, dur=0.25, soft=True),
        'destroy': seq([(t(4, dur=0.2, soft=True), 0, 0), (t(2, dur=0.2, soft=True), 0.07, -2),
                        (t(0, dur=0.35, soft=True), 0.14, -3)]),
        'fail': seq([(t(1, dur=0.18, soft=True), 0, 0),
                     (t(0, dur=0.25, soft=True, f=d[1] * 2 ** (-1 / 12)), 0.11, -1)]),
        'link_up': seq([(t(5, dur=0.3), 0, -3), (t(7, dur=0.3), 0.07, -2), (t(9, dur=0.6), 0.14, 0)]),
        'link_down': seq([(t(9, dur=0.3, soft=True), 0, -2), (t(7, dur=0.3, soft=True), 0.08, -2),
                          (t(5, dur=0.5, soft=True), 0.16, -1)]),
        'shutter': seq([(br('pro_tap/glass', seed=2), 0, -2), (t(9, dur=0.15), 0.004, -4)]),
    }


def bubble():
    """Soft round bloops, playful and wet: rising ones go in, falling ones go out. G major pentatonic."""
    r = dsp.rng(5)
    d = scale('G4', PENTA)                                   # d[5] = G5
    up = lambda k, w=0.82, **kw: bloop(d[k] * w, d[k], r, **kw)      # noqa: E731
    down = lambda k, w=1.22, **kw: bloop(d[k] * w, d[k], r, **kw)    # noqa: E731
    return {
        'step': bloop(2200, 2700, r, glide=0.01, tau=0.008, dur=0.04, harm=0.0),
        'tap': up(6, tau=0.035, dur=0.12),
        'enter': up(7, w=0.7, glide=0.04, tau=0.045),
        'open': seq([(up(5, tau=0.035), 0, -3), (up(8, tau=0.05), 0.06, 0)]),
        'back': seq([(down(8, w=1.15, tau=0.035), 0, -2), (down(5, tau=0.05), 0.06, -1)]),
        'on': up(7, w=0.75, glide=0.03, tau=0.05),
        'off': down(4, w=1.5, glide=0.03, tau=0.045),
        'destroy': seq([(bloop(d[7], d[3], r, glide=0.12, tau=0.09, dur=0.3), 0, 0),
                        (dsp.butter(dsp.noise(0.12, r, 'pink') * dsp.env_exp(0.12, 0.03), 'band', (700, 3000)),
                         0.0, -24)]),
        'fail': seq([(down(4, w=1.08, tau=0.05), 0, 0),
                     (bloop(d[4] * 1.05, d[4] * 2 ** (-1 / 12), r, tau=0.06), 0.12, -1)]),
        'link_up': seq([(up(5, tau=0.03), 0, -4), (up(7, tau=0.03), 0.065, -3), (up(10, tau=0.07), 0.13, 0)]),
        'link_down': seq([(down(10, tau=0.03), 0, -3), (down(7, tau=0.03), 0.07, -3), (down(5, tau=0.06), 0.14, -1)]),
        'shutter': seq([(br('pro_pop/tink', seed=4), 0, 0), (up(11, w=0.75, tau=0.02, dur=0.08), 0.01, -6)]),
    }


def switch():
    """Precise mechanics, a well-made device: detents, latches and keys, one switch body. Not tonal."""
    return {
        'step': br('pro_hover_tick/detent', seed=1),
        'tap': br('click/soft', seed=2),
        'enter': br('click/snap', seed=3, pitch=hz('D7')),
        'open': br('toggle/latch', seed=1),
        'back': br('click/thock', seed=2),
        'on': br('toggle/on', seed=1, pitch=hz('G7')),
        'off': br('toggle/off', seed=1),
        'destroy': br('fit/key/thock', seed=2),
        'fail': br('click/double', seed=1, pitch=hz('G6')),
        'link_up': seq([(br('click/soft', seed=4, pitch=hz('B6')), 0, -3),
                        (br('click/soft', seed=5, pitch=hz('E7')), 0.075, 0)]),
        'link_down': seq([(br('click/soft', seed=6, pitch=hz('E7')), 0, -2),
                          (br('click/soft', seed=7, pitch=hz('B6')), 0.085, -1)]),
        'shutter': br('fit/snap/switch_flip', seed=1),
    }


def shrine():
    """An ancient tablet's interface: soft FM chimes with a glassy glint and an airy room, E major pentatonic around
    E6. In the spirit of the slate menus of Breath of the Wild and Tears of the Kingdom."""
    r = dsp.rng(3)
    d = scale('E5', PENTA)                                   # d[5] = E6
    c = lambda k, **kw: chime(d[k], r, **kw)                 # noqa: E731
    tick = dsp.fm(hz('B7'), 2.0, 0.8, 0.03, decay=0.004, index_decay=0.003, attack=0.0008)
    return {
        'step': airy(tick, r, wet=0.1, rt60=0.3),
        'tap': airy(c(6, decay=0.07), r, wet=0.15),
        'enter': airy(seq([(c(4, decay=0.05, glint=0.1), 0, -10), (c(7, decay=0.11), 0.03, 0)]), r),
        'open': airy(seq([(c(5, decay=0.07), 0, -4), (c(7, decay=0.07), 0.045, -2), (c(9, decay=0.14), 0.09, 0)]), r),
        'back': airy(seq([(c(8, decay=0.07, glint=0.1), 0, -2), (c(5, decay=0.12, glint=0.1), 0.055, -1)]), r),
        'on': airy(seq([(c(3, decay=0.06, glint=0.05), 0, -6), (c(5, decay=0.12, glint=0.1, index=0.9), 0.035, 0)]), r),
        'off': airy(seq([(c(5, decay=0.06, glint=0.05), 0, -6), (c(3, decay=0.1, glint=0.05), 0.035, 0)]), r),
        'destroy': airy(seq([(c(6, decay=0.05, glint=0.05), 0, -2), (c(4, decay=0.05, glint=0.05), 0.05, -3),
                             (c(1, decay=0.08, glint=0.0), 0.1, -3),
                             (bloop(d[6], d[6] * 0.5, r, glide=0.2, tau=0.12, dur=0.35, harm=0.3), 0.1, -4)]),
                        r, wet=0.25),
        'fail': airy(seq([(chime(hz('A5'), r, decay=0.09, glint=0.0, index=2.5), 0, 0),
                          (chime(hz('Ab5'), r, decay=0.11, glint=0.0, index=2.5), 0.12, -1)]), r, wet=0.12),
        'link_up': airy(seq([(c(5, decay=0.06), 0, -5), (c(7, decay=0.06), 0.06, -4), (c(9, decay=0.06), 0.12, -3),
                             (c(10, decay=0.25, glint=0.4), 0.18, 0)]), r, wet=0.28, rt60=1.0),
        'link_down': airy(seq([(c(10, decay=0.06), 0, -3), (c(8, decay=0.06), 0.07, -3),
                               (c(5, decay=0.16, glint=0.1), 0.14, -2)]), r, wet=0.22),
        'shutter': airy(seq([(br('pro_click/glass', seed=2), 0, 0), (c(11, decay=0.05, glint=0.5), 0.006, -5)]),
                        r, wet=0.12),
    }


# Round 2 (2026-10-20): Home and the apps (replaced in round 3), the control centre, Ask.
def swish(f0, f1, r, dur=0.16, q=3.0):
    """A soft band of air sweeping f0 -> f1: a shade sliding."""
    n = dsp.n_of(dur)
    env = np.sin(np.linspace(0, np.pi, n)) ** 2
    return dsp.svf(dsp.noise(dur, r, 'pink') * env, dsp.sweep(f0, f1, dur), q=q, mode='bandn')


def bubble2():
    r = dsp.rng(21)
    d = scale('G4', PENTA)
    up = lambda k, w=0.82, **kw: bloop(d[k] * w, d[k], r, **kw)      # noqa: E731
    down = lambda k, w=1.22, **kw: bloop(d[k] * w, d[k], r, **kw)    # noqa: E731
    return {
        'list_in': up(3, w=0.85, tau=0.03, harm=0.2),
        'list_out': down(3, w=1.15, tau=0.03, harm=0.2),
        'panel_in': seq([(swish(700, 2400, r), 0, -10), (up(7, tau=0.025), 0.11, 0)]),
        'panel_out': seq([(swish(2400, 700, r), 0, -10), (down(4, tau=0.025), 0.11, 0)]),
        'listen': up(8, w=0.5, glide=0.06, tau=0.05),
        'heard': down(5, w=1.5, glide=0.04, tau=0.035),
        'arrived': seq([(up(10, tau=0.07), 0, -2), (up(12, tau=0.08), 0.075, 0)]),
    }


def grove2():
    r = dsp.rng(31)
    d = scale('C5', PENTA)
    m = lambda k, **kw: marimba(d[k], r, **kw)            # noqa: E731
    return {
        'list_in': seq([(woodtick(r, 1900, 0.004), 0, -8), (m(4, soft=0.9, mute=0.25), 0.004, 0)]),
        'list_out': seq([(woodtick(r, 1600, 0.004), 0, -8), (m(2, soft=0.95, mute=0.25), 0.004, 0)]),
        'panel_in': seq([(m(4, soft=0.8, mute=0.25), 0, -6), (m(5, soft=0.8, mute=0.25), 0.025, -3),
                         (m(6, soft=0.75, mute=0.35), 0.05, 0)]),
        'panel_out': seq([(m(6, soft=0.85, mute=0.25), 0, -5), (m(5, soft=0.85, mute=0.25), 0.025, -3),
                          (m(4, soft=0.9, mute=0.35), 0.05, 0)]),
        'listen': seq([(m(5, mute=0.4), 0, -4), (m(9, mute=0.5), 0.04, 0)]),
        'heard': m(5, soft=0.9, mute=0.3),
        'arrived': seq([(m(5, soft=0.7, mute=0.7), 0, -4), (m(7, soft=0.7, mute=0.7), 0.03, -3),
                        (m(9, soft=0.65, mute=0.9), 0.06, 0)]),
    }


def kalimba2():
    d = scale('D5', PENTA)

    def t(k, dur=0.15, soft=True):
        return br('pluck/kalimba_soft' if soft else 'pluck/kalimba', pitch=d[k], dur=dur)
    return {
        'list_in': t(3),
        'list_out': t(1),
        'panel_in': seq([(t(4), 0, -6), (t(5), 0.025, -3), (t(6, dur=0.2), 0.05, 0)]),
        'panel_out': seq([(t(6), 0, -5), (t(5), 0.025, -3), (t(4, dur=0.2), 0.05, 0)]),
        'listen': seq([(t(5, soft=False), 0, -4), (t(8, dur=0.25, soft=False), 0.04, 0)]),
        'heard': t(4, dur=0.2),
        'arrived': seq([(t(5, dur=0.4), 0, -4), (t(7, dur=0.4), 0.03, -3), (t(9, dur=0.5), 0.06, 0)]),
    }


def shrine2():
    r = dsp.rng(41)
    d = scale('E5', PENTA)
    c = lambda k, **kw: chime(d[k], r, **kw)               # noqa: E731
    return {
        'list_in': airy(c(4, decay=0.05, glint=0.05), r, wet=0.22),
        'list_out': airy(c(2, decay=0.05, glint=0.03), r, wet=0.22),
        'panel_in': airy(seq([(c(5, decay=0.04, glint=0.1), 0, -6), (c(6, decay=0.04, glint=0.1), 0.02, -4),
                                (c(7, decay=0.04, glint=0.1), 0.04, -2), (c(8, decay=0.08, glint=0.15), 0.06, 0)]),
                           r, wet=0.25),
        'panel_out': airy(seq([(c(8, decay=0.04, glint=0.05), 0, -5), (c(7, decay=0.04, glint=0.05), 0.02, -4),
                                 (c(6, decay=0.04, glint=0.05), 0.04, -2), (c(5, decay=0.08, glint=0.05), 0.06, 0)]),
                            r, wet=0.25),
        'listen': airy(seq([(c(5, decay=0.06, index=1.6), 0, -3), (c(9, decay=0.12, index=1.6), 0.05, 0)]), r),
        'heard': airy(c(5, decay=0.06, glint=0.05), r, wet=0.15),
        'arrived': airy(seq([(c(7, decay=0.12, glint=0.2), 0, -3), (c(10, decay=0.22, glint=0.3), 0.08, 0)]),
                          r, wet=0.3, rt60=1.0),
    }


def switch2():
    return {
        'list_in': br('pro_click/soft', seed=3, pitch=hz('D7')),
        'list_out': br('pro_click/soft', seed=4, pitch=hz('A6')),
        'panel_in': br('toggle/rocker', seed=1),
        'panel_out': br('toggle/rocker', seed=2, pitch=hz('A6')),
        'listen': br('click/tink', seed=1, pitch=hz('E7')),
        'heard': br('click/tink', seed=2, pitch=hz('B6')),
        'arrived': br('pro_notify/ding_1822', seed=1),
    }


# Round 3 (2026-10-20): the most frequent cues, muffled.
def muffle(y, fc, attack=0.004):
    """Behind a cloth: highs rolled off from fc, the onset eased in over a few ms."""
    y = dsp.butter(mono(y), 'low', fc, order=2)
    a = dsp.n_of(attack)
    y[:a] *= 0.5 - 0.5 * np.cos(np.linspace(0, np.pi, a))
    return y


def bubble3():
    r = dsp.rng(51)
    d = scale('G4', PENTA)
    up = lambda k, w=0.85, **kw: bloop(d[k] * w, d[k], r, **kw)      # noqa: E731
    down = lambda k, w=1.2, **kw: bloop(d[k] * w, d[k], r, **kw)     # noqa: E731
    return {
        'step': muffle(bloop(700, 850, r, glide=0.012, tau=0.012, dur=0.05, harm=0.25), 1500, 0.003),
        # The deeper step heard on the page, not used; drawn still, so the cues after it come out as approved.
        'step_deeper': muffle(bloop(560, 680, r, glide=0.014, tau=0.015, dur=0.05, harm=0.3), 1200, 0.003),
        'tap': muffle(up(3, tau=0.03, harm=0.2), 1800),
        'enter': muffle(up(5, w=0.8, tau=0.035, harm=0.2), 2000),
        'back': muffle(down(3, tau=0.035, harm=0.2), 1800),
        'list_in': muffle(up(2, tau=0.03, harm=0.25), 1600),
        'list_out': muffle(down(1, tau=0.03, harm=0.25), 1600),
    }


def grove3():
    r = dsp.rng(52)
    d = scale('C5', PENTA)
    m = lambda k, **kw: marimba(d[k], r, **kw)            # noqa: E731
    return {
        'step': muffle(woodtick(r, 1100, 0.004), 1800, 0.002),
        'tap': muffle(m(2, soft=0.95, mute=0.25), 2000),
        'enter': muffle(seq([(m(2, soft=0.95, mute=0.25), 0, -10), (m(4, soft=0.9, mute=0.3), 0.03, 0)]), 2200),
        'back': muffle(seq([(m(4, soft=0.95, mute=0.25), 0, -10), (m(2, soft=0.95, mute=0.3), 0.03, 0)]), 2000),
        'list_in': muffle(m(3, soft=0.95, mute=0.2), 1800),
        'list_out': muffle(m(1, soft=0.95, mute=0.2), 1800),
    }


def kalimba3():
    d = scale('D5', PENTA)

    def t(k, dur=0.15):
        return br('pluck/kalimba_soft', pitch=d[k], dur=dur)
    return {
        'step': muffle(t(2, dur=0.03), 1800, 0.002),
        'tap': muffle(t(1), 2200),
        'enter': muffle(seq([(t(0, dur=0.1), 0, -10), (t(2, dur=0.2), 0.03, 0)]), 2400),
        'back': muffle(seq([(t(2, dur=0.1), 0, -10), (t(0, dur=0.2), 0.03, 0)]), 2200),
        'list_in': muffle(t(1, dur=0.12), 2000),
        'list_out': muffle(t(0, dur=0.12), 2000),
    }


def shrine3():
    r = dsp.rng(53)
    d = scale('E5', PENTA)
    c = lambda k, **kw: chime(d[k], r, glint=0.0, **kw)    # noqa: E731
    return {
        'step': muffle(airy(c(0, decay=0.01, index=0.3), r, wet=0.05, rt60=0.3), 2000, 0.002),
        'tap': muffle(airy(c(1, decay=0.04, index=0.5), r, wet=0.1), 2500),
        'enter': muffle(airy(seq([(c(0, decay=0.03, index=0.4), 0, -10), (c(2, decay=0.06, index=0.6), 0.03, 0)]),
                               r, wet=0.15), 2600),
        'back': muffle(airy(seq([(c(2, decay=0.03, index=0.4), 0, -10), (c(0, decay=0.06, index=0.6), 0.03, 0)]),
                              r, wet=0.15), 2400),
        'list_in': muffle(airy(c(1, decay=0.04, index=0.4), r, wet=0.15), 2200),
        'list_out': muffle(airy(c(0, decay=0.04, index=0.4), r, wet=0.15), 2200),
    }


def switch3():
    return {
        'step': muffle(br('hover_tick/detent', seed=1), 1500, 0.001),
        'tap': muffle(br('pro_click/mouse', seed=2), 2000, 0.002),
        'enter': muffle(br('click/snap', seed=3, pitch=hz('D7')), 2500, 0.002),
        'back': muffle(br('click/thock', seed=2), 2000, 0.002),
        'list_in': muffle(br('pro_click/soft', seed=3, pitch=hz('D7')), 1500, 0.002),
        'list_out': muffle(br('pro_click/soft', seed=4, pitch=hz('A6')), 1500, 0.002),
    }


ROUNDS = {'bubble': (bubble, bubble2, bubble3), 'grove': (grove, grove2, grove3),
          'kalimba': (kalimba, kalimba2, kalimba3), 'shrine': (shrine, shrine2, shrine3),
          'switch': (switch, switch2, switch3)}
PACKS = list(ROUNDS)
# Packs made elsewhere: never removed here, their demos made from their files in assets.
KEPT = ['zen']

# The demo: a user going through the app, at its pace. (seconds, cue)
DEMO = [(0.30, 'list_in'),                               # Home down to the apps
        (0.75, 'step'), (0.86, 'step'), (0.97, 'step'), (1.08, 'step'),
        (1.45, 'enter'),                                 # into Settings
        (2.00, 'step'), (2.45, 'off'), (3.20, 'on'),     # a switch off, then on
        (3.95, 'back'),
        (4.60, 'panel_in'), (5.40, 'panel_out'),         # the control centre pulled down, put away
        (6.10, 'link_down'), (7.40, 'link_up'),          # the Wi-Fi drops and comes back
        (8.10, 'step'), (8.22, 'step'), (8.60, 'enter'),  # into Camera
        (9.30, 'shutter'), (10.05, 'tap'),               # a photo kept
        (10.80, 'shutter'), (11.40, 'open'), (11.95, 'destroy'),  # a photo deleted from its sheet
        (12.60, 'back'), (13.10, 'list_out'),            # back up to Home
        (13.70, 'listen'), (15.00, 'heard'), (16.30, 'arrived')]  # a question asked, its answer
DEMO_S = 17.2


def render(name):
    cues = {}
    for made in ROUNDS[name]:
        cues |= made()
    return {c: finish(cues[c], TIER[c], MAX_S[c]) for c in CUES}


def write_pack(name, cues):
    out = os.path.join(ASSETS, name)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    for c in CUES:
        sf.write(os.path.join(out, c + '.ogg'), cues[c].astype(np.float32), SR, format='OGG', subtype='OPUS')
    print(f'  {name:8s} ' + ' '.join(f'{c} {round(1000 * len(cues[c]) / SR)}' for c in CUES))


def from_assets(name):
    """A pack's cues from its files; a cue it leaves out is silent."""
    d = os.path.join(ASSETS, name)
    return {c: mono(sf.read(os.path.join(d, c + '.ogg'), dtype='float64')[0]) for c in CUES
            if os.path.exists(os.path.join(d, c + '.ogg'))}


def write_demos(rendered):
    """One gain for every demo, the loudest peaking at -1 dBFS, so the packs compare as they will sound."""
    if not shutil.which('afconvert'):
        sys.exit('the demos need macOS\'s afconvert (AAC that afplay plays); run this on a Mac')
    lines = {}
    for name, cues in rendered.items():
        y = np.zeros(int(DEMO_S * SR))
        for t, c in DEMO:
            if c in cues:
                dsp.place(y, cues[c], t)
        lines[name] = y
    g = 10 ** (-1 / 20) / max(np.abs(y).max() for y in lines.values())
    os.makedirs(DEMOS, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for name, y in lines.items():
            wav = os.path.join(tmp, name + '.wav')
            sf.write(wav, (y * g).astype(np.float32), SR, subtype='PCM_16')
            m4a = os.path.join(DEMOS, name + '.m4a')
            subprocess.run(['afconvert', '-f', 'm4af', '-d', 'aac', '-b', '96000', wav, m4a], check=True)
            print(f'  demo {name}: {os.path.getsize(m4a) // 1024} KB')


def main():
    global bank, dsp, fx
    ap = argparse.ArgumentParser(description='Render the sound packs into the app and their demos into the installer.')
    ap.add_argument('--from', dest='src', default=os.environ.get('EARSHOT_FROM'),
                    help='a clone of github.com/Leonxlnx/earshot (default $EARSHOT_FROM)')
    ap.add_argument('packs', nargs='*', metavar='PACK', help=f'only these, of {", ".join(PACKS)} (default: every '
                                                              'pack, and the demos)')
    a = ap.parse_args()
    if unknown := [n for n in a.packs if n not in PACKS]:
        ap.error(f'no pack {", ".join(unknown)}: {", ".join(PACKS)}')
    if not a.src or not os.path.isfile(os.path.join(a.src, 'earshot', 'forge', 'bank.py')):
        sys.exit('--from must name a clone of github.com/Leonxlnx/earshot')
    sys.path.insert(0, os.path.abspath(a.src))
    from earshot.forge import bank as b, dsp as d, fx as f
    bank, dsp, fx = b, d, f

    every = not a.packs
    names = list(PACKS) if every else a.packs
    rendered = {}
    for name in names:
        rendered[name] = render(name)
        write_pack(name, rendered[name])
    if every:
        for old in os.listdir(ASSETS):
            if old not in PACKS + KEPT and os.path.isdir(os.path.join(ASSETS, old)):
                shutil.rmtree(os.path.join(ASSETS, old))
        write_demos(rendered | {name: from_assets(name) for name in KEPT})


if __name__ == '__main__':
    main()
