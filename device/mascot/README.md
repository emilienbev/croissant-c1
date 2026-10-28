# Mascot study

A static, isometric avocado half, drawn as an SVG. A design study only: it is not used
by the app, the boot screens or the installer.

- `mascot.svg`: the vector, transparent background, 320x320 viewBox.
- `preview.html`: the study at full size, 128 px and 64 px.

Render `build/mascot.png` and `build/contact-sheet.png` with:

```sh
uv run --with playwright device/mascot/preview.py
```
