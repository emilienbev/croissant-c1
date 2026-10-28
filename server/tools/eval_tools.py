#!/usr/bin/env python3
"""
Does the model call the right tool for a sentence, and how fast? Runs the tool loop
directly (no audio, no socket) against the in-memory fake device and prints every call
and the answer. Sentences form one conversation in order, so follow-ups can be tested.

    uv run python tools/eval_tools.py "Add a reminder to buy milk tomorrow" \\
        "Actually make that Friday" "What is the capital of France?"
    uv run python tools/eval_tools.py -f sentences.txt          # one per line
    uv run python tools/eval_tools.py --fresh ...               # no shared conversation
    uv run python tools/eval_tools.py --now 2026-09-14T18:00:00+01:00 ...
    uv run python tools/eval_tools.py --image x.jpg "What is this?"   # a photo hold
    uv run python tools/eval_tools.py --variant gemma ...       # a seed variant by name
    uv run python tools/eval_tools.py --variant-file mine.txt ...   # any text as the variant

With no sentences it runs the built-in set, the device's catalog, a group per feature as the
C1's test groups it (agenda, timers, web, pictures, tapes, questions), then photo holds. That
is a measurement, so it wants repeats and a table:

    uv run python tools/eval_tools.py --repeat 3 --score
    uv run python tools/eval_tools.py --repeat 3 --score --llm gemma4:26b-nvfp4
    uv run python tools/eval_tools.py --repeat 3 --score --llm gemini:gemini-3.8-flash
    uv run python tools/eval_tools.py --repeat 5 --score --only web --no-search

`--repeat N` runs the whole set N times, each pass with its own conversation and its
own fake device. `--score` prints one row per sentence saying how many of the N runs
called each tool, instead of the answers, gives every sentence its own conversation so
a row measures the sentence and not what came before it, and ends with the timings:
time to first text and to the end of the hold, and the two token rates Ollama reports,
prefill (the prompt read, mostly the tool specs) and decode (what the model wrote).
`--llm` is a provider:model id or an Ollama model, as on the server; for a
provider other than Ollama the two rates are timed here (prefill is the wait for the
first output, network included), so they compare runs of one provider, not providers.
`--no-search` runs the same set without the web search offered.

The prompt and the tools are the C1's, put together as it does (c1_prompt.py): the
variant, the list of the tools on offer, the clock; a photo hold the photo rules and no
tools. So is the loop (c1_loop.py on c1_chat.py: the C1's ask/Loop.kt in Python, held to it
by the prompt tokens each round reports). `--variant` names one of the catalog's seeds (default: the one it suggests for the
model, else "default"); `--variant-file` takes any text instead, such as one copied off a
device or the old unsplit rules, to tell what a wording changes from what the assembly
does. Nothing real runs: a picture or a search or a tape question is answered here at once.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import statistics
import sys
import time
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import c1_prompt  # noqa: E402
from c1_loop import Llm, RoundStats, Tool, as_messages, rates, scrub  # noqa: E402
from fake_device import FakeDevice  # noqa: E402
from croissant_server.config import Config  # noqa: E402
from croissant_server.providers import Providers  # noqa: E402

PICTURE_TOOLS = {"image_generate", "image_transform"}

# What a `web_search` call is answered with, and a `tape_ask` call, as the C1's own test
# answers them (android ask/Test.kt, `fake`). A real search would make a run slow and
# different every time. The shape is `WebSearch.format`'s, numbered lines and all, because a
# digest the model cannot read as one would change the thing being measured.
FAKE_DIGEST = (
    'Web results for "{query}":\n'
    "1. example.com, 2 hours ago: The short answer is twelve, and nothing has changed "
    "since this morning.\n"
    "2. example.org: The same figure again, twelve, with a line of detail after it."
)
FAKE_TAPE = "Tape 21: They agreed the budget is 40k, at 12:30."

# The built-in set is the device's catalog (android assets/ask/catalog.json), the same
# cases the C1's own test runs, with its tools and its prompts: a case is a sentence and the
# tool it should call and nothing else, "" for none, or "*" for some tool but never a
# picture; one marked no_at names no day or time, so an `at` on its agenda_add is a date
# made up. A sentence given on the command line has no expectation and is never marked.
# A case's group is the feature it tests (agenda, timers, web, pictures, tapes, questions),
# as on the C1; --only takes those, and "photo" for the photo holds below. Why each sentence
# is there (the web's positives and the ones it must not touch, the regression set, the
# agenda's reminders from the R1) is in the catalog's history: git log on it, and before
# 2026-10-16 here.
CATALOG = c1_prompt.load_catalog()
GROUPS: dict[str, list[tuple[str, str]]] = {}
for _c in CATALOG["cases"]:
    GROUPS.setdefault(_c["group"], []).append((_c["say"], _c["expect"]))
DATELESS = {c["say"] for c in CATALOG["cases"] if c.get("no_at")}


# PHOTO: holds carrying a photo, sent no tools, so every row expects none; what is
# being read is the answer, and the timings with an image in the prompt. They run with
# --image, or else matplotlib's portrait of Grace Hopper, which the venv already has.
PHOTO = [
    ("What is this?", ""),
    ("Describe this image", ""),
    ("Who is this?", ""),
    ("How do you call things like these? I'm trying to find another one I could buy on "
     "Amazon.", ""),
    ("What colour is her hat?", ""),
    ("Make this a watercolour", ""),                             # points to Transform
    ("Can you make this look like a watercolour?", ""),
    ("Turn this into an oil painting", ""),
    ("Remind me to buy one of these", ""),                       # says it cannot, here
]

GROUPS["photo"] = PHOTO


@dataclass
class Case:
    group: str
    sentence: str
    expect: str | None
    photo: bool


def sample_photo() -> Path | None:
    """matplotlib's sample portrait, when the venv has it (it is not a dependency)."""
    try:
        import matplotlib
    except ImportError:
        return None
    path = Path(matplotlib.__file__).parent / "mpl-data/sample_data/grace_hopper.jpg"
    return path if path.exists() else None


def _clean(expect: str | None, c: Counter, any_call: int, dated: int, n: int,
           search: bool) -> bool:
    """
    Did every run of this sentence go the way it was supposed to?

    `c` counts the runs that called each tool, `any_call` the runs that called anything,
    `dated` the runs that gave a dateless reminder an `at`. Under --no-search a
    positive expects a tool that is not on offer, so the row is left unmarked.
    """
    if expect is None:          # from the command line: nothing is expected of it
        return True
    if expect == "web_search" and not search:
        return True
    if dated:
        return False
    if expect == "":            # a plain question: no tool at all, in any run
        return sum(c.values()) == 0
    if expect == "*":           # some tool every run, never a picture
        return any_call == n and not PICTURE_TOOLS & set(c)
    return c[expect] == n and all(v == 0 for k, v in c.items() if k != expect)


def _table(cases: list[Case], counts: list[Counter], any_calls: list[int],
           dated: list[int], n: int, search: bool) -> None:
    """
    One row per sentence: how many of the n runs called each tool.

    The expected tool comes first so the eye lands on it, then `web_search` whether it
    fired or not (0/5 on a negative is the number being looked for, and a tool that
    never fired would otherwise leave no trace), then anything else that was called,
    then the runs that put a made-up date on a dateless reminder.
    """
    rows = []
    for case, c, anyc, d in zip(cases, counts, any_calls, dated):
        expect = case.expect
        names = [expect] if expect and expect != "*" and (search or expect != "web_search") else []
        if search and not case.photo and "web_search" not in names:
            names.append("web_search")
        names += sorted(k for k in c if k not in names)
        cells = "  ".join(f"{c[k]}/{n} {k}" for k in names) or "no tools"
        if d:
            cells += f"  {d}/{n} DATED"
        rows.append((case.group, cells, case.sentence, _clean(expect, c, anyc, d, n, search)))
    width = min(max(len(r[1]) for r in rows), 48)
    group = None
    for g, cells, s, ok in rows:
        if g != group:
            group = g
            print(f"\n{g.upper()}" if g else "")
        short = s if len(s) <= 72 else s[:69] + "..."
        print(f"{'  ' if ok else '! '}{cells.ljust(width)}  \"{short}\"")
    bad = sum(not r[3] for r in rows)
    print(f"\n{len(rows) - bad}/{len(rows)} sentences went the expected way in every run"
          + ("" if not bad else f"; ! marks the {bad} that did not"))


@dataclass
class Hold:
    """One hold's timings: seconds to first text and to the end, and Ollama's counts."""

    first: float | None
    total: float
    rounds: list[RoundStats]


def _timings(holds: list[Hold]) -> None:
    """
    Medians and 90th percentiles over every hold, and the token rates over all of them
    together: one hold writes ten to forty tokens, too few for a rate of its own to mean
    much, while the sum over a run is a steady figure.
    """
    def spread(xs: list[float]) -> str:
        xs = sorted(xs)
        return f"median {statistics.median(xs):.2f}s, p90 {xs[int(len(xs) * 0.9)]:.2f}s"

    firsts = [h.first for h in holds if h.first is not None]
    rounds = [r for h in holds for r in h.rounds]
    pt, pre, ot, dec = rates(rounds)
    print(f"first text: {spread(firsts)}" if firsts else "first text: none")
    print(f"whole hold: {spread([h.total for h in holds])}")
    # Ollama counts a cached prefix in the prompt's tokens but not in its time, so this
    # is the rate a hold sees, not the model's cold one: a tool round re-reads the whole
    # conversation and most of it is cached by then.
    print(f"prefill: {pre:.0f} tok/s effective ({pt / len(holds):.0f} prompt tok per hold, "
          f"{len(rounds) / len(holds):.1f} rounds)")
    print(f"decode: {dec:.1f} tok/s ({ot / len(holds):.0f} tok written per hold)")


async def main(a: argparse.Namespace) -> None:
    cfg = Config()
    # The tools are offered exactly as the C1 offers them, so an eval measures the tool
    # set a hold actually has: the catalog's server ones (`"runs": "server"`) as a server
    # running all of them, less those turned off here. Nothing is generated, fetched or
    # read: `execute` below answers each at once, as the C1's own test does.
    # --no-search runs the same sentences without the web search, which is the only way
    # to tell a tool that slipped when it arrived from one that was always going to slip.
    server_tools = {t["name"] for t in CATALOG["tools"] if t.get("runs") == "server"
                    and not (a.no_images and t["name"] == "image_generate")
                    and not (a.no_search and t["name"] == "web_search")}
    providers = Providers.load(Path(a.providers or cfg.providers_path),
                               fallback={"chat": ("ollama", cfg.llm_model)}, pick={"chat": a.llm})
    llm = Llm(providers.default("chat"))
    text = Path(a.variant_file).read_text() if a.variant_file else None
    variant = a.variant or (Path(a.variant_file).stem if a.variant_file
                            else c1_prompt.suggested(CATALOG, llm.entry.id))
    if text is None and variant not in CATALOG["prompts"]:
        sys.exit(f"no variant {variant!r} in the catalog: {', '.join(CATALOG['prompts'])}")
    start = datetime.fromisoformat(a.now) if a.now else datetime.now().astimezone()
    if start.tzinfo is None:
        start = start.astimezone()

    def prompt(now: datetime, photo: bool) -> tuple[str, list[Tool]]:
        """A hold's system prompt and tools, as the C1 sends them; all answered in `execute`."""
        h = c1_prompt.hold(CATALOG, entry=llm.entry.id, now=now, photo=photo, server_tools=server_tools,
                           apps=[], variant=variant, text=text)
        return h["system"], [Tool(t["name"], t["description"], t["parameters"]) for t in h["tools"]]

    # A photo hold gets the photo rules and no tools, as the C1 sends it.
    # ~~--photo~~ only swapped the tool set and never sent a picture, so the 2026-09-18
    # photo rows were measured blind; with a photo the model reached for the transform
    # 6/6 (2026-09-30).
    photo_path = Path(a.image) if a.image else sample_photo()
    image = photo_path.read_bytes() if photo_path else None

    sentences = list(a.sentences)
    if a.file:
        sentences += [l.strip() for l in Path(a.file).read_text().splitlines() if l.strip()]
    cases = [Case("", s, None, a.image is not None) for s in sentences]
    if not cases:
        for g in a.only or GROUPS:
            if g == "photo" and image is None:
                print("photo: skipped, no photo (pass --image)")
                continue
            cases += [Case(g, s, e, g == "photo") for s, e in GROUPS[g]]
    print(f"{llm.entry.label()}, variant {variant}" + (f" ({a.variant_file})" if a.variant_file else "")
          + (f", photo {photo_path.name}" if any(c.photo for c in cases) else ""))

    # A score row is about one sentence, so in --score each sentence gets its own
    # conversation: otherwise a negative is measured after whatever the sentence above
    # it left in the history, which is how the narration finding of 2026-09-14 happened.
    fresh = a.fresh or a.score
    counts = [Counter() for _ in cases]
    any_calls = [0] * len(cases)
    dated = [0] * len(cases)
    holds: list[Hold] = []

    for run_no in range(a.repeat):
        # A pass is independent of the one before it: its own device, its own
        # conversation. Nothing carries over, so run 5 is another measurement of the
        # same thing rather than the tail of a long session.
        #
        # Each pass is also a minute later. The clock line is in the system prompt, and
        # Ollama answers a prompt it has seen whole from its cache in a few ms while
        # still counting every token, so a repeat of the same prompt would time a cache
        # hit as a hold. A real hold never repeats a prompt (2026-10-01).
        at = start + timedelta(minutes=run_no)
        now = at.isoformat(timespec="seconds")
        held = {photo: prompt(at, photo) for photo in (False, True)}
        device = FakeDevice(now)
        rows: list[dict] = []
        if a.repeat > 1 and not a.score:
            print(f"\n===== run {run_no + 1}/{a.repeat}")

        for i, case in enumerate(cases):
            # `log` and `device` are read by `execute`, which never outlives this
            # iteration, so rebinding either of them between sentences or between runs
            # cannot leak a call into the wrong row.
            log: list[dict] = []

            async def execute(tool: Tool, args: dict) -> object:
                if tool.name in PICTURE_TOOLS:
                    result: object = {"id": "g_eval01", "status": "started"}
                elif tool.name == "web_search":
                    result = FAKE_DIGEST.format(query=(args.get("query") or "").strip())
                elif tool.name == "tape_ask":
                    result = FAKE_TAPE
                else:
                    result = device.run(tool.name, args)
                log.append({"name": tool.name, "args": args, "result": result})
                return result

            s = case.sentence
            if not a.score:
                print(f"\n> {s}")
            system, tools = held[case.photo]
            stats: list[RoundStats] = []
            t0 = time.monotonic()
            first = None
            answer = ""
            async for delta in llm.answer(s, system=system, prior=as_messages(rows),
                                          execute=execute, tools=tools,
                                          image=image if case.photo else None, stats=stats):
                if first is None:
                    first = time.monotonic() - t0
                answer += scrub(delta)
            dt = time.monotonic() - t0
            holds.append(Hold(first, dt, stats))
            counts[i].update({c["name"] for c in log})
            any_calls[i] += bool(log)
            dated[i] += s in DATELESS and any(
                c["name"] == "agenda_add" and c["args"].get("at") for c in log)
            if not a.score:
                for c in log:
                    print(f"  TOOL {c['name']}({json.dumps(c['args'], ensure_ascii=False)})"
                          f" -> {json.dumps(c['result'], ensure_ascii=False)[:160]}")
                print(f"  {answer.strip()}")
                pt, pre, ot, dec = rates(stats)
                print(f"  ({dt:.1f}s, " + (f"first text at {first:.1f}s, " if first is not None
                                            else "no text, ")
                      + f"{len(log)} tool call(s), prompt {pt} tok at {pre:.0f} tok/s, "
                      f"{ot} tok out at {dec:.0f} tok/s)")
            if not fresh:
                rows.append({"q": s, "a": answer})

        if not a.score:
            print(f"\nstore: {len(device.reminders)} reminders, {len(device.events)} events, "
                  f"{len(device.timers)} timers")
            for r in device.reminders:
                print(f"  reminder {r['id']} {r['color']:8} {r['title']}")
            for e in device.events:
                print(f"  event    {e['id']} {e['color']:8} {e['start']} -> {e['end']}  {e['title']}")
            for t in device.timers:
                print(f"  timer    {t['id']} {t['color']:8} {t['seconds']}s  {t['label'] or ''}")

    if a.score:
        _table(cases, counts, any_calls, dated, a.repeat, not a.no_search)
    print(f"\n{len(cases)} sentences x {a.repeat} run(s) = {len(holds)} holds"
          f"{'' if a.no_search else ', web_search offered'}")
    _timings(holds)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("sentences", nargs="*")
    ap.add_argument("-f", "--file")
    ap.add_argument("--llm", help="a provider:model id, or an Ollama model")
    ap.add_argument("--providers", help="the providers file (default: ./providers.json)")
    ap.add_argument("--now", help="device time to pretend, ISO with offset")
    ap.add_argument("--variant", help="Ask's prompt, a seed variant in the catalog (default: the one it "
                                       "suggests for the model, else default); with --variant-file, its name")
    ap.add_argument("--variant-file", metavar="PATH", help="any text as the variant instead of a seed")
    ap.add_argument("--fresh", action="store_true", help="each sentence in its own conversation")
    ap.add_argument("--no-images", action="store_true", help="do not offer the picture tool")
    ap.add_argument("--no-search", action="store_true", help="do not offer the web search tool")
    ap.add_argument("--image", metavar="JPEG",
                    help="the photo for photo holds; with sentences, every one is a photo hold")
    ap.add_argument("--repeat", type=int, default=1, metavar="N",
                    help="run the sentences N times, each pass fresh (default 1)")
    ap.add_argument("--score", action="store_true",
                    help="a table of tool calls per sentence instead of the answers")
    ap.add_argument("--only", action="append", choices=list(GROUPS),
                    help="only this group of the built-in set; repeatable")
    ap.add_argument("-v", action="store_true")
    a = ap.parse_args()
    logging.basicConfig(level=logging.INFO if a.v else logging.WARNING,
                        format="%(levelname)-5s %(name)s: %(message)s")
    asyncio.run(main(a))
