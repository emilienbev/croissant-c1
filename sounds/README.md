# Interface sounds

The app's interface sounds come in packs, all shipped in the APK under
`android/app/src/main/assets/sounds/<pack>/`, one Opus file per cue of
`android/app/src/main/java/com/bleural/croissant/audio/Cue.kt`. Which interaction plays
which cue is set in `audio/Cues.kt`. Which pack plays is picked when installing
(`./croissant install`), or later with `./croissant sounds` or on the R1 in Settings >
Device > Tones, where the play key plays a short phrase in the highlighted pack and a tap
on a pack switches to it at once; Shrine until one is picked.

| Pack | What it sounds like |
|---|---|
| `bubble` | Round bloops: rising ones go in, falling ones go out. G major pentatonic. |
| `grove` | Soft marimba bars and dry wooden ticks, C major pentatonic around C6. |
| `kalimba` | A physical thumb-piano tine, damped short, D major pentatonic around D6. |
| `shrine` | Soft FM chimes with a glass glint and an airy room, E major pentatonic around E6. |
| `switch` | Detents, latches and keys from one switch body. Not tuned. |
| `zen` | The sounds Croissant shipped first: the Zen pack of [uisfx](https://github.com/uisfx) (CC0). |

Every pack follows the same rules: one material and one scale; going in steps up and
going out steps down; on a third to a fifth above off; a link up rises and a link down
falls; a failure is a dull close interval. The most frequent cues (the wheel step, a
tap, into a screen and back, Home to the apps and back) are muffled and quiet, the wheel
step quietest of all; the ones that come now and then (switches, the control centre,
Ask listening and its answer, links, the shutter) keep their shine. Loudness is matched
across cues and packs and written into each file.

`earshot.py` renders them with [earshot](https://github.com/Leonxlnx/earshot) (MIT), its
bank of sounds stored as code and its DSP only: no models, nothing downloaded. It also
renders each pack's demo for the installer's picker, `installer/croissant/sounds/<pack>.m4a`:
a user going down to the apps, opening Settings, flipping a switch off and on, pulling
the control centre, the Wi-Fi dropping and coming back, a photo kept and one deleted, back
up to Home, and a question asked and answered.

```bash
git clone https://github.com/Leonxlnx/earshot ../cache/earshot   # tried at 925ebe5
./earshot.py --from ../cache/earshot           # every pack and the demos (the demos need macOS)
./earshot.py --from ../cache/earshot grove     # one pack
```

`zen.py` writes Zen from the files the app played until commit bda58b6, each at the level it
had then; Home and the apps and the control centre take its forward and back as they did, and
Ask's cues are left out: a cue a pack leaves out is silent. `earshot.py` keeps it and makes its
demo with the others.

## The metronome's tones

The Metronome's sound page has six: Click, Woodblock and Hi-hat, synthesised by
`metronome/Clicks.kt`, and Tine (the kalimba), Chime (Shrine's) and Bamboo, which
`metronome.py` renders into `android/app/src/main/assets/metronome/<tone>/`, a WAV for each
pulse: the bar's first beat (a fifth up), a beat, a subdivision. Each starts at once and
peaks within a few milliseconds, so the beat lands on the click; `Clicks.kt` sets the level
of each pulse, as it does for its own three.

```bash
./metronome.py --from ../cache/earshot
```

A pack is a function in `earshot.py` returning every cue, added to `ROUNDS` and to the
installer's list (`installer/croissant/sounds.py`); a new cue is a name in `CUES` and
`audio/Cue.kt`, a row in `Cues.cueFor`, and a function per pack in a new round, so the
cues already made stay as they are. A pack removed from `ROUNDS` is removed from the
assets on the next full run. Rebuild the app afterwards.
