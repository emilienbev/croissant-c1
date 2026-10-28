# Why the app felt sluggish, and what fixed it

Written 2026-09-15, in plain terms. The build steps are in `android/README.md`.

## The symptom

Moving between screens stalled. Opening the menu, entering Settings or coming back to
Home each froze the picture for a third of a second before the slide began, and the
slide itself dropped most of its frames. The panel runs at 60 frames a second, so a
frame has about 16 milliseconds. We measured 34 milliseconds for a typical frame and
300 to 440 milliseconds for the first frame of every move.

## The culprit: the phone was reading the recipe out loud

Android apps ship as instructions in a portable form, not as code the processor can
run directly. The device normally translates them once, ahead of time, into native
machine code and keeps that on disk. If that translation is missing or unusable, the
app runs through an interpreter instead: every instruction is decoded again each time
it is executed, roughly like reading a recipe aloud line by line rather than knowing
it by heart. On a slow chip like the R1's that is several times slower.

The translated code existed. Android was throwing it away at every launch. The reason
is a path mismatch specific to our unofficial Android build (a "GSI"): a folder called
`/system_ext` is a shortcut to `/system/system_ext`. One optional system library that
our app pulls in through the camera code is recorded under the first spelling when the
translation is made and found under the second when the app starts. Android compares
the two, decides the translation was made against a different world, and ignores it.
The app then runs interpreted, and a profiler confirmed it: 71 percent of the time was
spent inside the interpreter itself.

Two things hid this. First, the debug build we used for development cannot be
translated ahead of time on Android 13 at all, so it was always interpreted anyway.
Second, asking Android to translate the release build reported success, and only a
look at the running process showed the result was never loaded.

## The fix

1. **Tell the app it does not need that library.** Two lines in the manifest remove
   the optional window libraries the camera code drags in. Nothing here uses them.
   With no library to compare paths for, Android accepts the translated code.
2. **Live with the release build.** It is signed with the same key as the debug build
   so it can replace it in place, and it is translated right after each install with
   one command (see "Build and install" in `android/README.md`).

That alone made every screen four to five times cheaper to build and put slide frames
back inside the 16 millisecond budget.

## Then, doing less work

With the interpreter out of the way the remaining cost was rebuilding screens from
scratch on every move. Three changes removed that:

- **Screens stay alive under the one on top.** Going back no longer rebuilds the
  previous screen, it is simply uncovered, so the edge drag now reveals the real page
  instead of a black void, and each screen keeps its wheel position.
- **Home and its menu stay alive too.** The menu parks just off the right edge when
  closed, so opening it moves a layer instead of building seven bands.
- **Numbers measure their digit width once.** Equal-width digits are drawn by
  measuring all ten digits; that used to happen for every clock, time and count on
  every rebuild, now once per style.

## The numbers

First frame of a move, the time the picture froze before the slide started:

| Move | Before | After |
|---|---|---|
| Open the menu | 300 to 390 ms | 8 ms |
| Open Settings | 160 to 300 ms | 10 to 20 ms |
| Back to Home | 300 to 440 ms | 2 ms |

Things we checked and ruled out: the display (a single 60 Hz mode), the processor's
clock speed ramping up from idle, memory pressure and garbage collection, and the
debug build on its own.

## A live counter costs a frame a second (2026-09-16)

The recorder's reels turn at 60 fps, and stuttered once a second. Frame stats
(`dumpsys gfxinfo framestats`) showed a 90 ms frame every second; `atrace` split it:
30 ms of recomposition, 25 of measure and layout, 30 of re-recording draw commands.

Three things added up, none of them the reels:

- **Home ticks under every screen.** Screens stay composed under the one on top (see
  above), so Home, showing the running recording's counter in its first row and in
  its menu band, recomposed its whole page every second: the 133 px clock, the ruler,
  the rows, the eight bands. A timer running had done the same on every screen.
- **A recomposition is dear here.** Reading the second, or the mic level, at the top
  of the recorder screen recomposed all of it: fifty times a second for the level.
- **One digit is a text measure.** Equal-width digits were a `Text` per character;
  a changing digit meant a recomposition and a 3 to 4 ms measure of a single glyph,
  and re-recording the draw commands of whatever layer held it, for Home the whole
  page.

Fixed by:

- **Covered screens are not drawn and their clocks stop.** `Slides` gives a layer
  wholly under another alpha 0, and `LocalLayerVisible` tells `rememberSecondTick`
  to wait until the screen is back in view, when its first tick is immediate.
- **The second and the level are read at the leaves.** The counter, the level bars
  and the ruler each read their own state, each in its own `graphicsLayer`, and each
  reel's hub turns by its layer's `rotationZ`, so a frame of the turn re-records
  nothing. On Home a live row and a live band tick alone.
- **Equal-width digits are one canvas.** `TabularText` lays each glyph out once per
  style and draws the cached layouts; a changing digit is a draw, not a measure.

While recording, frames over 33 ms went from one a second to none in five seconds,
the second's frame from 90 ms of main-thread work to about 20, and the frame count
over five seconds from 278 to 291 (the ceiling is 300). Same tools as above:
`dumpsys gfxinfo <pkg> reset`, wait, `dumpsys gfxinfo <pkg> framestats`, and
`atrace -a <pkg> -t 3 view` pulled from `/data/local/tmp` and parsed for the longest
slices under `Choreographer#doFrame`.

## The menu on a spring (2026-09-17)

The menu this describes is gone. It is kept because the room (below) is built on what it
found, and because a screen that measures or lays out anything per frame will cost the same
here whatever it draws.

The menu's bands grew (80 px, the focused one 120), the menu scrolls, and a wheel
tick moves the focus on a spring: the focused band's height, its word's size and the
scroll. Measured with the wheel driven by `sendevent` (`input keyevent` starts a JVM
per tick and skews everything), `dumpsys gfxinfo` and `atrace` with
sections of our own in the composables and draw lambdas.

Three things were slow, in the order found:

- **Every tick recomposed the whole shell.** The shell read the menu's focus to decide
  which rail tab to light, so a tick rebuilt AppShell, Slides and Home: 45 ms of a
  60 ms first frame, and this was already true of the old menu. Now the rail is one
  canvas in its own layer that asks which tabs are lit as it draws, and nothing reads
  the focus in composition: the bands' springs and the scroll follow it through a
  snapshot flow.
- **Measuring text at each frame's size.** Laying the word out at the interpolated
  size cost 17 to 35 ms of draw a frame. The word is laid out once in each style and a
  frame draws the nearer one scaled about the band's left centre.
- **Eight nodes in a Column.** Even with each band a single node in its own layer, a
  frame of the spring measured and placed the eight and re-recorded the two moving:
  9 to 17 ms of measure and layout plus 7 to 11 of draw. Each Compose node costs
  about 1.5 ms to measure here and a cached `drawText` 1.5 ms, because the main thread
  animates at 1.0 to 1.6 GHz under schedutil. So the menu is one canvas
  of constant height: each band's glyph, word (both styles) and value are recorded once
  into graphics layers (`BandArt`), and a frame draws eight rects and twenty-four
  layers at the heights eight `Animatable`s say. No measure, no layout, no recomposition.

| Fourteen ticks, main thread | Old menu (68 px, tween) | First spring build | Now |
|---|---|---|---|
| First frame of a tick | 50 to 63 ms | 35 to 53 ms | 5 to 8 ms |
| A frame of the motion | 20 to 25 ms | 25 to 35 ms | 8 to 16 ms |
| Janky frames (`gfxinfo`) | 50 percent | 65 percent | 10 percent |
| Median frame, vsync to done | 46 ms | 57 ms | 18 ms |

What is left is the render thread: 6 to 7 ms a frame to rasterise the two words at
each frame's size, and Compose's own frame overhead. An option not taken, by
choice: a floor on the big cluster's clock would make every frame cheaper at a battery
cost.

## What the room costs (2026-09-18)

The room is the shell's four numbers on springs (`ui/RoomState.kt`), advanced by one frame
loop (`RoomLoop` in `ui/Motion.kt`). Home, the side wheel and the open feature move by them.

- **Composition and layout stay out of paging.** The four numbers (`pos`, `open`, `home`,
  `pull`) are read inside draw lambdas and in `graphicsLayer` blocks, so a wheel tick and
  every frame of its spring recompose nothing. This is the menu's lesson applied from the
  start rather than found afterwards.
- The room parks its frame loop when all four numbers have settled, so a still panel costs
  no frames at all. That matters on a device whose launcher is always on screen.

## The mic level (2026-09-18)

**The mic level was redrawing the Ask panel 50 times a second.** The microphone is open
for the life of the service, so `VoiceService.meter()` wrote its level from every 20 ms
chunk for ever, whether or not anything was listening, and the panel's 6 px meter reads
that level in a draw. Recomposition was already at the leaves and cost 1.2 a second; it
was the draw that ran. The level is now written only while an utterance is being sent, and
parked at a flat 0 otherwise. An answer sitting on the open panel went from **50 full
draws a second at 10.8 ms to 1.1 a second**, which is the top bar's clock and nothing else.

## The model picker on the wheel (2026-10-10)

The wheel's scroll is one glide now, which fixed its leaps and
steps but not its frame rate. Spinning Settings > server > chat, warm, 12 ticks:

| Per frame, main thread | |
|---|---|
| Recomposition | about 6 ms |
| `Record View#draw()` (measure, layout, draw) | about 14 ms |
| RenderThread | 13 to 16 ms |
| Frame to frame | 34.8 ms median (about 29 fps) |

~~What costs is the card: the row that opens and the one that closes recompose and re-record
both their canvases at a new height every frame of their 180 ms. Not touched yet.~~
**Corrected the same day:** those numbers were the interpreter. `cmd package compile` does
not reach the app already running, so after an install it runs uncompiled until it is
restarted (simpleperf: 87% under `Nterp`). On compiled code,
restarted, warm, 3 runs of 12 ticks, the card now read only in layout and draw
(`ModelRow`: laid out once at the card's height and clipped, the words recorded once):

| Per frame | Before | After |
|---|---|---|
| Frame to frame, median and p90 | 17.4 and 17.5 ms | 17.4 and 17.5 ms |
| Vsync to done, median | 27 to 31 ms | 17 to 19 ms |
| UI thread, median | 12 to 14 ms | 6 to 7 ms |
| Recomposition (atrace) | 2.1 ms | 0.35 ms |
| Compose's draw (atrace) | 2.5 ms | 1.1 ms |
| Measure and layout (atrace) | 2.6 ms | 2.7 ms |
| Frames over 33 ms, of about 110 | 14 to 30 | 0 to 2 |
| RenderThread | 11.2 ms | 10.9 ms |

The picker already held 60 fps once compiled, each frame shown one vsync late; it now
lands on the next one. The RenderThread's 11 ms (a full-window composite) is the floor.

## Home's croissant (2026-10-27)

Beam's Home draws the croissant mascot in Compose (`ui/beam/Croissant.kt`) and plays its idle
loop. ~~It played two loops when Home came up, the screen woke or a finger landed, then
rested.~~ Since 2026-10-27 it plays all the while Home is in view, so Home
costs the Playing column the whole time it is up: 1153 frames in 20 s read from 4 s after a
wake, none past its deadline. It stops with the screen off and under another screen. Measured
on the R1, release build plus `cmd package compile -m speed`, `dumpsys gfxinfo` reset at a
wake (no touch), six seconds read while it plays and five seconds after it has rested:

| | Playing | Resting |
|---|---|---|
| Frames | 316 in 6 s, so 53 fps | 5 in 5 s |
| Frame deadline missed | 2 to 3 | none |
| 50th percentile | 30 ms | |
| 50th GPU percentile | 15 ms | |

- **The paths are not the cost.** The same build drawing only the eyes, the five parts left
  out, rendered as many frames and read 20 ms of GPU, not less: the governor clocks the GPU
  down when it has less to do, so its milliseconds are not a measure of the work. What a
  moving croissant costs is the full-window composite every frame, the floor found above.
- **Its own layer changes nothing measurable.** The croissant's canvas has a `graphicsLayer`
  so a frame of the loop records it alone rather than the page's words with it; the numbers
  read the same either way.
