"""
What the server ran for the device, counted: one line per model call in
data/usage.jsonl, with the model and its counts, never what was said. Settings > Usage
reads it, and so does each model's month at your pace (costs.py).

Speech is counted where every pass goes, by wrapping the backend (stt.py `MeteredStt`);
a picture when it is done (images.py). Counts are what the provider reports where it does
(Gemini's speech), or near enough: a second of audio is a second, a transcript a token
per four characters. The live words' passes are counted apart (`live`), billed but not
what was said. Chat runs on the C1 since 2026-10-16 and is counted there; the server's
rows of it age out like any other.
"""

from __future__ import annotations

import json
import logging
import time
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

# Kept twice the window Usage shows; older lines are dropped from the file at startup.
KEEP_DAYS = 60
DAY = 86400


class Ledger:
    def __init__(self, path: Path | None, trim: bool = True) -> None:
        """
        Read `path` and, with `trim`, drop its old lines. A reader beside the running
        server (tools/settings_page.py) passes False: the server may be appending.
        """
        self.path = path
        self.rows: list[dict[str, Any]] = []
        if path is None or not path.exists():
            return
        for line in path.read_text().splitlines():
            try:
                self.rows.append(json.loads(line))
            except json.JSONDecodeError:
                continue
        kept = self.since(time.time() - KEEP_DAYS * DAY)
        if trim and len(kept) < len(self.rows):
            self.rows = kept
            path.write_text("".join(json.dumps(r) + "\n" for r in kept))

    def add(self, slot: str, model: str, **counts: float) -> None:
        """
        One call of `model` (a `provider:model` id) for `slot`: `in`, `cached`, `out`
        tokens, `secs` heard (`live` for a live words' pass), `pictures`.
        """
        row = {"t": int(time.time()), "slot": slot, "model": model,
               **{k: round(v, 1) for k, v in counts.items() if v}}
        # Old rows leave memory as they leave the file, at most about once a day.
        if self.rows and self.rows[0].get("t", 0) < row["t"] - (KEEP_DAYS + 1) * DAY:
            self.rows = self.since(row["t"] - KEEP_DAYS * DAY)
        self.rows.append(row)
        if self.path is not None:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with self.path.open("a") as f:
                f.write(json.dumps(row) + "\n")

    def since(self, start: float) -> list[dict[str, Any]]:
        """The lines written at `start` (a time) or after."""
        return [r for r in self.rows if r.get("t", 0) >= start]

    def first(self) -> float | None:
        """When the oldest line kept was written."""
        return min((r["t"] for r in self.rows if "t" in r), default=None)
