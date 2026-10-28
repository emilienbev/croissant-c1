from __future__ import annotations

import argparse
import asyncio
import logging

from . import __version__
from .app import run
from .config import IMAGE_MODELS, Config


def main() -> None:
    p = argparse.ArgumentParser(prog="croissant-server")
    p.add_argument("--host", action="append", dest="hosts",
                   help="bind address (repeatable). Default: loopback + tailnet.")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--llm", default=None,
                   help="the default chat model: a provider:model id (gemini:gemini-3.8-flash), or an "
                        "Ollama model name")
    p.add_argument("--providers", default=None, metavar="FILE",
                   help="which models run where (default: ./providers.json; missing is fine)")
    p.add_argument("--stt", default=None,
                   help="the default speech to text: a provider:model id (gemini:gemini-3.8-flash), or "
                        "a local Whisper (path or repo)")
    p.add_argument("--lang", dest="stt_language", default=None,
                   help="force the STT language, e.g. en, fr; 'auto' to detect")
    p.add_argument("--debug-keep", action="store_true",
                   help="keep what the device said on the Mac: history.jsonl, the "
                        "utterance WAVs and a copy of every recording. Off by default, "
                        "and a follow-up question only works with it on.")
    p.add_argument("--capture-dir", default=None,
                   help="where utterance WAVs go under --debug-keep (default: ./captures)")
    p.add_argument("--data-dir", default=None,
                   help="where the generated pictures and, under --debug-keep, "
                        "history.jsonl and the recordings live (default: ./data)")
    p.add_argument("--no-search", action="store_true",
                   help="do not offer the web search tool")
    p.add_argument("--search-home", default=None, metavar="CITY",
                   help="what a weather question means when it names no place "
                        "(default: London)")
    p.add_argument("--no-sources", action="store_true",
                   help="do not route weather, currency and headlines to their own "
                        "data sources; put everything through the general search")
    p.add_argument("--no-images", action="store_true",
                   help="do not offer the picture tool, and load no image model")
    p.add_argument("--image-model", default=None, metavar="NAME",
                   help="which image model to run: " + ", ".join(IMAGE_MODELS) +
                        ", or a HuggingFace repo. Default: z-image")
    p.add_argument("--image-guidance", type=float, default=None,
                   help="override the model's guidance (klein refuses anything but 1.0)")
    p.add_argument("--list-image-models", action="store_true",
                   help="print the image models this server knows and exit")
    p.add_argument("--image-steps", type=int, default=None)
    p.add_argument("--transform-steps", type=int, default=None,
                   help="steps for a photo transform (default 20)")
    p.add_argument("--transform-keep", type=float, default=None,
                   help="how much of the photo a transform keeps, 0.1 to 0.6 (default 0.2)")
    p.add_argument("--image-size", default=None, metavar="WxH",
                   help="generated picture size, e.g. 768x1024")
    p.add_argument("-v", "--verbose", action="store_true")
    p.add_argument("--version", action="version", version=f"croissant-server {__version__}")
    args = p.parse_args()

    if args.list_image_models:
        for key, m in IMAGE_MODELS.items():
            print(f"{key:10} {m.repo:42} {m.steps:>3} steps  "
                  f"guidance {'default' if m.guidance is None else m.guidance}")
            print(f"{'':10} {m.note}")
        return

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)-5s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )

    cfg = Config()
    if args.hosts:
        cfg.hosts = args.hosts
    cfg.port = args.port
    if args.llm:
        cfg.llm = args.llm
    if args.providers:
        cfg.providers_path = args.providers
    if args.stt:
        cfg.stt = args.stt
    if args.stt_language:
        cfg.stt_language = None if args.stt_language == "auto" else args.stt_language
    if args.debug_keep:
        cfg.debug_keep = True
    if args.capture_dir:
        cfg.capture_dir = args.capture_dir

    if args.data_dir:
        cfg.data_dir = args.data_dir

    if args.no_search:
        cfg.search = False
    if args.search_home:
        cfg.search_home = args.search_home
    if args.no_sources:
        cfg.search_sources = False

    if args.no_images:
        cfg.images = False
    if args.image_model:
        cfg.image_model = cfg.image_pick = args.image_model
    if args.image_steps:
        cfg.image_steps = args.image_steps
    if args.image_guidance is not None:
        cfg.image_guidance = args.image_guidance
    if args.transform_steps:
        cfg.image_transform_steps = args.transform_steps
    if args.transform_keep:
        cfg.image_transform_strength = args.transform_keep
    if args.image_size:
        w, _, h = args.image_size.lower().partition("x")
        cfg.image_width, cfg.image_height = int(w), int(h)

    try:
        asyncio.run(run(cfg))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
