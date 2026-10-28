"""The exchange log: one JSON row per utterance.

Kept only when the server was started with `--debug-keep` (config.py). Normally there
is no log at all: what was asked is answered and forgotten, nothing is written to the
Mac and nothing is held in memory, so a follow-up question starts from nothing.
"""

from __future__ import annotations

import json
import logging
import secrets
import time
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

class History:
    """
    Append-only `history.jsonl`, for reading after a debugging run. ~~A follow-up
    replayed its conversation from here~~ (until 2026-10-06): the device keeps its
    conversations, so a conversation needs no log.

    Row: {id, conv, at, transcript, mode: "dictate", from?}. ~~An Ask hold's answer, its
    tools and its photo~~ (until 2026-10-16): Ask's loop runs on the C1 now, and the
    server hears a hold's words and nothing else (session.py).

    A `None` path is the normal case: nothing is read, nothing is written, nothing is
    remembered, and every method answers as if the log were empty.
    """

    def __init__(self, path: Path | None) -> None:
        self.path = path
        self._rows: list[dict[str, Any]] = []
        if path is None:
            log.info("history: not kept")
            return
        if path.exists():
            for line in path.read_text(encoding="utf-8").splitlines():
                if line.strip():
                    try:
                        self._rows.append(json.loads(line))
                    except json.JSONDecodeError:
                        log.warning("skipping bad history line: %r", line[:80])
        log.info("history: %d rows in %s", len(self._rows), path)

    def append(self, **row: Any) -> dict[str, Any]:
        row.setdefault("id", "x_" + secrets.token_hex(3))
        row.setdefault("at", time.strftime("%Y-%m-%dT%H:%M:%S%z"))
        if self.path is None:
            return row
        self._rows.append(row)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with open(self.path, "a", encoding="utf-8") as f:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
        return row
