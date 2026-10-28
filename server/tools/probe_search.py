#!/usr/bin/env python
"""
Print the exact digest a model would be handed, with no model running at all.

This is the retrieval layer on its own, so that "the answer was wrong" can be split
into "the web said the wrong thing" and "the model read it badly" before either is
blamed. Nothing is loaded, nothing is generated, one HTTP request per sentence.

    uv run python tools/probe_search.py "what is bitcoin at"
    uv run python tools/probe_search.py --results 3 --words 25 "who won the f1 race"
    uv run python tools/probe_search.py --file questions.txt

With no sentences it runs a built-in set covering the cases the tool exists for and
the ones it must not make worse. `--tokens` counts the digest with the server's own
model through Ollama instead of guessing at four characters each.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from croissant_server.config import Config             # noqa: E402
from croissant_server.search import WebSearch, timelimit_for  # noqa: E402
from croissant_server import sources                   # noqa: E402

# The cases worth watching, and why each is here.
DEFAULT = [
    # The three routed to a real data source (sources.py).
    "weather in london tomorrow",            # -> weather
    "what is the weather like",              # -> weather, no place named, uses search_home
    "will it rain in edinburgh tomorrow",    # -> weather
    "how much is 50 euros in pounds",        # -> currency, with an amount
    "what is the exchange rate for the dollar in yen",   # -> currency
    "what is in the news today",             # -> headlines
    # These must still fall through to the general search.
    "what is bitcoin at",                    # crypto is deliberately not a source
    "who is the prime minister of france",   # plain text gives the office, not the holder
    "what time does the tate modern close",  # local and specific
    "f1 spanish grand prix result",          # search gets this when the query is words
    "what is the capital of france",         # the model knows this; the tool should not help
]


async def main() -> int:
    p = argparse.ArgumentParser(prog="probe_search.py")
    p.add_argument("queries", nargs="*", help="sentences to search; default: a built-in set")
    p.add_argument("--file", type=Path, help="read sentences from a file, one per line")
    p.add_argument("--results", type=int, default=None, help="cap on results (default 5)")
    p.add_argument("--words", type=int, default=None, help="cap per snippet (default 40)")
    p.add_argument("--tokens", action="store_true",
                   help="count each digest with the server's model through Ollama")
    p.add_argument("--no-sources", action="store_true",
                   help="skip the data sources and put every question through the search")
    p.add_argument("-v", "--verbose", action="store_true", help="show backend failures")
    args = p.parse_args()

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.WARNING,
                        format="%(levelname)-5s %(name)s: %(message)s")

    cfg = Config()
    queries = list(args.queries)
    if args.file:
        queries += [l.strip() for l in args.file.read_text().splitlines() if l.strip()]
    if not queries:
        queries = DEFAULT

    search = WebSearch(
        results=args.results if args.results is not None else cfg.search_results,
        words=args.words if args.words is not None else cfg.search_words,
        timeout=cfg.search_timeout_s,
        sources=cfg.search_sources and not args.no_sources,
        home=cfg.search_home,
    )

    count = _counter(cfg.llm_model) if args.tokens else None
    widths, total = [], 0.0
    for q in queries:
        picked = sources.route(q) if search.sources else None
        print("=" * 72)
        print(f"Q  {q}")
        if picked is not None:
            print(f"   route {picked[0]} (a real source; falls through if it declines)")
        else:
            print(f"   route search, window "
                  f"{'last day' if timelimit_for(q) else 'any'} (chosen from the words)")
        started = time.monotonic()
        digest = await search.digest(q)
        elapsed = time.monotonic() - started
        total += elapsed
        print()
        for line in digest.splitlines():
            print("   " + line)
        print()
        tokens = await count(digest) if count else None
        shown = f"{tokens} tokens" if tokens is not None else f"~{len(digest) // 4} tokens"
        widths.append(tokens if tokens is not None else len(digest) // 4)
        print(f"   {elapsed:.2f}s   {len(digest)} chars   {shown}")

    print("=" * 72)
    print(f"{len(queries)} searches, {total:.2f}s total, {total / len(queries):.2f}s each")
    if widths:
        print(f"digest cost: {min(widths)} to {max(widths)} tokens, "
              f"{sum(widths) // len(widths)} on average")
    return 0


def _counter(model: str):
    """
    Exact token counts, from Ollama's own prompt_eval_count.

    One generation of a single token per digest, on a model that is already resident
    for the server. Nothing new is loaded.
    """
    from ollama import AsyncClient
    client = AsyncClient()

    async def count(text: str) -> int | None:
        try:
            r = await client.chat(model=model, messages=[{"role": "user", "content": text}],
                                  think=False, options={"temperature": 0, "num_predict": 1})
            return r.prompt_eval_count
        except Exception as e:
            print(f"   (could not count tokens: {e})")
            return None

    return count


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
