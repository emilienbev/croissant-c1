#!/usr/bin/env python3
"""
The settings page alone: edit providers.json from a browser without starting a model.

    uv run python tools/settings_page.py                       # http://127.0.0.1:8767/settings
    uv run python tools/settings_page.py --providers other.json --port 8768

Serves exactly what the server serves for it (`/settings`, its fonts, `/providers`,
`/usage` and the `/config` socket; app.py, settings.py) and nothing else: no Whisper, no Ollama, no
pictures. For a new machine before its first start, and for working on the page while
the real server is up on 8765. Loopback only.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import sys
from pathlib import Path

from websockets.asyncio.server import ServerConnection, serve

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from croissant_server import settings  # noqa: E402
from croissant_server.app import http_routes  # noqa: E402
from croissant_server.config import APPLE_SILICON, IMAGE_MODELS, Config  # noqa: E402
from croissant_server.costs import Costs  # noqa: E402
from croissant_server.ledger import Ledger  # noqa: E402
from croissant_server.prices import Prices  # noqa: E402
from croissant_server.providers import Providers  # noqa: E402


async def main(a: argparse.Namespace) -> None:
    cfg = Config()
    pictures = list(IMAGE_MODELS) if APPLE_SILICON else []
    providers = Providers.load(
        Path(a.providers or cfg.providers_path).resolve(),
        fallback={"chat": ("ollama", cfg.llm_model), "stt": ("local", cfg.stt_model),
                  **({"text_to_image": ("local", cfg.image_model),
                      "image_to_image": ("local", cfg.image_model)} if APPLE_SILICON else {})},
        local={"stt": [cfg.stt_model], "text_to_image": pictures, "image_to_image": pictures})

    async def handler(ws: ServerConnection) -> None:
        if ws.request.path.rstrip("/") == "/config":
            await settings.handle(ws, providers)
        else:
            await ws.close(1008, "unknown path")

    # The server's own count, read and never written: nothing runs here to add to it, and
    # the server may be appending. Its prices, fetched again when a day old as it does.
    prices = Prices(providers.path.with_suffix(".prices.json") if providers.path else None)
    costs = Costs(providers, prices, Ledger(Path(cfg.data_dir).resolve() / "usage.jsonl", trim=False))
    await prices.refresh()
    async with serve(handler, "127.0.0.1", a.port, process_request=http_routes(None, costs)):
        print(f"http://127.0.0.1:{a.port}/settings  ({providers.path})", flush=True)
        await asyncio.get_running_loop().create_future()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--providers", help="the providers file (default: ./providers.json)")
    ap.add_argument("--port", type=int, default=8767)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-5s %(name)s: %(message)s",
                        datefmt="%H:%M:%S")
    try:
        asyncio.run(main(ap.parse_args()))
    except KeyboardInterrupt:
        pass
