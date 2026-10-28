"""
What a model costs: models.dev's catalogue, the one source of prices. Dollars
per 1M tokens as the provider bills them; a model it does not know has no price, and the
device says nothing about one.

The catalogue (~5 MB) is fetched at startup when the copy kept beside providers.json is a
day old, and only the providers this server knows are kept of it.
"""

from __future__ import annotations

import json
import logging
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

import httpx

from .providers import PRESETS, Provider

log = logging.getLogger(__name__)

URL = "https://models.dev/api.json"
MAX_AGE_S = 24 * 3600

# Gemini's counts, used as an approximation for every provider: a second of audio is 32
# tokens heard, a picture about 1,290 tokens written.
AUDIO_TOKENS_PER_S = 32
IMAGE_TOKENS = 1290


@dataclass(frozen=True)
class Price:
    """
    One model's rates in dollars per 1M tokens (None where models.dev gives none), its name,
    whether it thinks; and its context and output limits in tokens and whether it reads
    pictures, as models.dev gives them.
    """

    name: str
    input: float | None = None
    output: float | None = None
    cached: float | None = None
    audio: float | None = None
    thinks: bool = False
    deprecated: bool = False
    context: int | None = None
    max_out: int | None = None
    images: bool = False

    def cost(self, c: dict[str, float]) -> float | None:
        """
        What counts of a job come to (ledger.py): tokens read (`in`, of them `cached`),
        seconds of audio heard (`secs`, and `live` for the live words' passes, billed
        alike), tokens written (`out`), pictures made. None when a count it has needs a
        rate the model has none for. Seconds and pictures become tokens at Gemini's counts
        (`AUDIO_TOKENS_PER_S`, `IMAGE_TOKENS`), near enough for any provider.
        """
        read, cached = c.get("in", 0), c.get("cached", 0)
        heard = (c.get("secs", 0) + c.get("live", 0)) * AUDIO_TOKENS_PER_S
        written = c.get("out", 0) + c.get("pictures", 0) * IMAGE_TOKENS
        parts = [(read - cached, self.input), (cached, self.cached if self.cached is not None else self.input),
                 (heard, self.audio if self.audio is not None else self.input), (written, self.output)]
        if any(n and rate is None for n, rate in parts):
            return None
        return sum(n * rate for n, rate in parts if n) / 1e6


class Prices:
    def __init__(self, path: Path | None) -> None:
        self.path = path
        self.by_source: dict[str, dict[str, Price]] = {}
        # A copy kept before the limits were read has none: fetched again, however fresh.
        self.limitless = False
        try:
            if path and path.exists():
                kept = json.loads(path.read_text())
                self.by_source = {s: {m: Price(**p) for m, p in models.items()} for s, models in kept.items()}
                self.limitless = any("context" not in p for models in kept.values() for p in models.values())
        except (OSError, json.JSONDecodeError, TypeError) as e:
            log.warning("could not read %s: %s", path, e)

    def get(self, provider: Provider, model: str) -> Price | None:
        return self.by_source.get(provider.prices or "", {}).get(model)

    async def refresh(self) -> None:
        """Fetch the catalogue when the kept copy is a day old or missing."""
        if (self.path and self.path.exists() and time.time() - self.path.stat().st_mtime < MAX_AGE_S
                and not self.limitless):
            return
        try:
            async with httpx.AsyncClient(timeout=30) as client:
                r = await client.get(URL)
                r.raise_for_status()
                catalogue = r.json()
        except Exception as e:  # offline: the kept copy, or no prices
            log.warning("could not fetch prices from models.dev: %s", e)
            return
        self.by_source = {s: {m: _price(d) for m, d in (catalogue.get(s) or {}).get("models", {}).items()}
                          for s in {p.prices for p in PRESETS.values() if p.prices}}
        self.limitless = False
        log.info("prices from models.dev for %d models", sum(len(v) for v in self.by_source.values()))
        if self.path:
            self.path.write_text(json.dumps({s: {m: asdict(p) for m, p in v.items()}
                                             for s, v in self.by_source.items()}) + "\n")


def _price(d: dict[str, Any]) -> Price:
    cost, limit = d.get("cost") or {}, d.get("limit") or {}
    return Price(name=str(d.get("name") or d.get("id") or ""), input=cost.get("input"),
                 output=cost.get("output"), cached=cost.get("cache_read"), audio=cost.get("input_audio"),
                 thinks=bool(d.get("reasoning")), deprecated=d.get("status") == "deprecated",
                 context=_tokens(limit.get("context")), max_out=_tokens(limit.get("output")),
                 images="image" in ((d.get("modalities") or {}).get("input") or []))


def _tokens(n: Any) -> int | None:
    """A limit as models.dev gives it: a positive count, else unknown."""
    return int(n) if isinstance(n, (int, float)) and n > 0 else None
