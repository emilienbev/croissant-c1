# Timers

<p align="center">
  <img alt="The timers page: three running timers, pasta, laundry and bread, each its time left over a row of dots, pasta's in lavender" src="../images/features/timers-running.png" width="300">
  <img alt="A new timer named pasta: 0 hours, 10 minutes in lavender and 00 seconds over the play key" src="../images/features/timers-new.png" width="300">
</p>

Countdowns that keep running while you use the rest of the C1.

- The plus key starts one. The wheel turns the lavender column, minutes first. Tap another column to pick it, or drag on any column. Name it if you like and press play.
- Running timers are listed soonest first, with dots for the time left.
- Tap a timer's name to rename it, anywhere else on it to delete it after a confirm.
- When one ends, the screen turns lavender and the croissant rings. A tap, the wheel or the side button stops it.
- Home shows running timers as rings.

Ask starts, lists and cancels them too.

| You say | Ask calls |
|---|---|
| "a ten minute timer for the pasta" | `timers_add {"seconds": 600, "label": "pasta"}` |
