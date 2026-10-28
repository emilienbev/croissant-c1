# Boot animation

`loader.py` makes the boot animation: the boot logo from `device/logo/` with a thin track
under the wordmark and an orange segment crossing it every 1.2 s, looped until boot
completes. 480x640, 30 fps.

```sh
uv run --with pillow device/bootanim/loader.py
```

It writes the zip into the Magisk module `device/magisk/croissant-bootanim/` (committed)
and `build/loader-sheet.png` to look at. `./croissant install` installs it.

`anim.html`, `render.py`, `preview.py` and `kamado-static.svg` are an earlier,
abandoned smoke-loop design, kept as prior work and not installed.
