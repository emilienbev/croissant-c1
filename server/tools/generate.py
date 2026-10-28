#!/usr/bin/env python3
"""
One picture through the server's own generation path, with no socket and no model.

    uv run python tools/generate.py "a red fox resting in fresh snow"
    uv run python tools/generate.py --repeat 3 "a city at night"
    uv run python tools/generate.py --from photo.jpg "an oil painting of ..."

This is how the generation times are measured: the first run pays the model
load, the ones after it do not, and the device's polling delays come from the warm
time. The picture is left in data/generated/ as the server would leave it.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from croissant_server.config import Config  # noqa: E402
from croissant_server.images import DONE, ERROR, Generations  # noqa: E402
from croissant_server.providers import Providers  # noqa: E402


async def main() -> int:
    p = argparse.ArgumentParser(prog="generate")
    p.add_argument("prompt")
    p.add_argument("--repeat", type=int, default=1, help="how many pictures in a row")
    p.add_argument("--size", default=None, metavar="WxH")
    p.add_argument("--steps", type=int, default=None)
    p.add_argument("--image-model", dest="image_model", default=None,
                   help="which image model: a provider:model id (gemini:gemini-3.8-flash), or a local "
                        "one (z-image, klein, fhdr, a HuggingFace repo)")
    p.add_argument("--providers", help="the providers file (default: ./providers.json)")
    p.add_argument("--from", dest="source", default=None, metavar="PHOTO",
                   help="transform this photo (img2img) instead of starting from nothing")
    p.add_argument("--keep", type=float, default=None,
                   help="how much of the photo to keep, 0.1 to 0.6")
    p.add_argument("--data-dir", default=None)
    args = p.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-5s %(name)s: %(message)s",
                        datefmt="%H:%M:%S")
    cfg = Config()
    if args.data_dir:
        cfg.data_dir = args.data_dir
    if args.steps:
        cfg.image_steps = args.steps
    if args.size:
        w, _, h = args.size.lower().partition("x")
        cfg.image_width, cfg.image_height = int(w), int(h)

    providers = Providers.load(
        Path(args.providers or cfg.providers_path),
        fallback={"text_to_image": ("local", cfg.image_model), "image_to_image": ("local", cfg.image_model)},
        pick={"text_to_image": args.image_model, "image_to_image": args.image_model})
    gen = Generations(
        Path(cfg.data_dir).resolve() / "generated", providers, lambda e: cfg.image(e.model, e.options),
        cfg.image_width, cfg.image_height, cfg.image_keep_hours, idle_unload_s=0,
    )
    print("model:", gen.entry("image_to_image" if args.source else "text_to_image").label())
    source = Path(args.source).read_bytes() if args.source else None
    failed = False
    try:
        for n in range(args.repeat):
            t0 = time.monotonic()
            job_id = gen.start(args.prompt, source=source, strength=args.keep)
            while True:
                await asyncio.sleep(0.5)
                status, jpeg, error = gen.get(job_id)
                if status in (DONE, ERROR, "gone"):
                    break
            wall = time.monotonic() - t0
            if status != DONE or jpeg is None:
                print(f"{n + 1}/{args.repeat} {job_id} FAILED: {error}")
                failed = True
                continue
            row = gen._read(job_id) or {}
            print(f"{n + 1}/{args.repeat} {job_id}  {wall:.1f}s wall  "
                  f"{len(jpeg) / 1024:.0f} kB  {row.get('width')}x{row.get('height')}"
                  f"{'  keeping ' + str(row['strength']) if 'strength' in row else ''}"
                  f"  {gen.jpg(job_id)}")
    finally:
        gen.close()
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
