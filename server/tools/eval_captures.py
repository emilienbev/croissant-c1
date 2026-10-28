#!/usr/bin/env python3
"""
Run the server's STT (gate included) over the capture corpus and compare with
what was transcribed at the time. The corpus is server/captures/, written by the
server itself; edit `expected` in index.jsonl rows by hand to record what was
actually said, and this tool scores against that when present.

    uv run python tools/eval_captures.py                # all of captures/
    uv run python tools/eval_captures.py --lang fr       # force a language
    uv run python tools/eval_captures.py a.wav b.wav     # specific files
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
import time
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from croissant_server.config import Config  # noqa: E402
from croissant_server.providers import Providers  # noqa: E402
from croissant_server.stt import Stt  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="*")
    ap.add_argument("--dir", default="captures")
    ap.add_argument("--stt", default=None,
                    help="a provider:model id, or a local Whisper")
    ap.add_argument("--providers", help="the providers file (default: ./providers.json)")
    ap.add_argument("--lang", default=None, help="language code, or 'auto'")
    args = ap.parse_args()
    logging.basicConfig(level=logging.WARNING)

    cfg = Config()
    if args.lang:
        cfg.stt_language = None if args.lang == "auto" else args.lang
    providers = Providers.load(Path(args.providers or cfg.providers_path),
                               fallback={"stt": ("local", cfg.stt_model)}, pick={"stt": args.stt})
    stt = Stt(providers, cfg.stt_language, cfg.stt_partial_interval_s,
              cfg.stt_gate_min_active_dbfs, cfg.stt_gate_min_snr_db)
    print(stt.backend().entry.label())

    d = Path(args.dir)
    rows: dict[str, dict] = {}
    index = d / "index.jsonl"
    if index.exists():
        for line in index.read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                rows[r["file"]] = r
    files = [Path(f) for f in args.files] or sorted(d.glob("*.wav"))

    hits = scored = 0
    for f in files:
        with wave.open(str(f), "rb") as w:
            pcm = w.readframes(w.getnframes())
        s = stt.new_session()
        s.feed(pcm)
        t0 = time.monotonic()
        got = asyncio.run(s.finish())
        dt = time.monotonic() - t0
        r = rows.get(f.name, {})
        then, expected = r.get("transcript"), r.get("expected")
        line = f"{f.name}  ({dt:.2f}s)\n   now : {got!r}"
        if then is not None and then != got:
            line += f"\n   then: {then!r}"
        if expected:
            ok = _norm(got) == _norm(expected)
            scored += 1; hits += ok
            line += f"\n   want: {expected!r}  {'OK' if ok else 'MISS'}"
        print(line)
    if scored:
        print(f"\n{hits}/{scored} match the expected text")
    return 0


def _norm(s: str) -> str:
    return "".join(c for c in s.lower() if c.isalnum() or c == " ").split().__str__()


if __name__ == "__main__":
    sys.exit(main())
