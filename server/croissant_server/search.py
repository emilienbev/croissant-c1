"""
Web search: one request, one small digest, and nothing the user ever sees.

The digest goes into the model's context for the length of one hold, exactly as a
tool result from `agenda_list` does. No URL, no source list and no citation ever
reaches the device: the model reads the digest and answers in its own words, and the
Ask screen does not know a search happened (2026-09-20).

**Keyless.** `ddgs` makes ordinary HTTP requests to the search services while
impersonating a browser (it ships `primp` for that) and parses the results page.
No account, no API key, no quota, and no third party is told what the device was
asked. Brave's own API would be the alternative and its free tier was removed in
February 2026.

**Snippets only.** What comes back is the grey two lines the search engine itself
writes under each result. The pages are never fetched. One request, about a second,
and a snippet is already the length a small model wants; fetching a page instead
costs 0.2 s and yields ~2400 tokens from one article, which is the whole budget.

**The budget is attention, not truncation.** Measured 2026-09-20 on this Mac:
Ollama 0.34.2 auto-sizes and gives qwen3.5-abliterated:9b its full 262144-token
window, so nothing is being cut off. But the nine existing tool specs already cost
1581 tokens of the model's attention before the question arrives, and a small model
degrades with context long before its window fills. So the digest is capped hard
rather than as much as fits: five results of forty words, measured at 213 to 340
tokens over the probe's question set, 247 on average.

**One index, the plain one.** ~~An event question goes to the news index~~ (the
first design, 2026-09-20: `news()` was the only mode that answered "who won the last
f1 grand prix" in an early probe). Dropped the same day once it was measured
properly with `tools/probe_search.py`: the news index is an editorial feed, not an
answer engine. For "who won the last f1 grand prix" it returned four pieces of F1
gossip and never the winner, in every time window; for "bitcoin price" it returned
ETF commentary and price predictions and never the price; and its `timelimit` is
barely honoured, a `d` window returning articles five weeks old. The plain index
answered both. So the `news()` branch and the event-word table that chose it are
gone, and what is left is one index and one optional day window.
"""

from __future__ import annotations

import asyncio
import logging
import re
import time
from dataclasses import dataclass, field
from urllib.parse import urlsplit

from . import sources

log = logging.getLogger(__name__)

# Tried in this order until one answers, because **a comma-separated backend list
# does not fail over**: measured 2026-09-20, `backend="brave,bing"` raises when brave
# is the one that is down, even with bing answering on its own. Availability is
# volatile by the minute on one IP: brave answered 3/3 at 0.8 s, twenty minutes later
# 0/3 while bing, yahoo and yandex answered 3/3. A backend that is down says so in
# 0.16 to 0.57 s, so walking a couple of dead ones is cheap, and `_preferred`
# remembers the one that answered so the next hold starts there.
#
# **An unknown backend name is not an error, it silently becomes `auto`.** Measured
# 2026-09-20: ddgs 9.16.0 offers brave, duckduckgo, google, grokipedia, mojeek,
# startpage, wikipedia and yahoo for text and only bing, duckduckgo and yahoo for
# news, so the obvious-looking "bing" and "yandex" are not text backends at all. Given
# either, ddgs logs a warning and falls back to `auto`, which answers, which is why
# the first version of this list looked like it was working while grokipedia results
# kept arriving through a pool that excluded it. `_valid` checks the pool against
# ddgs's own registry at startup so that cannot happen quietly again.
#
# `auto` is never asked for: it includes grokipedia, which answered "price of bitcoin"
# with an article listing Bitcoin analysis YouTube channels, and wikipedia, which only
# ever answers with one encyclopedia article. Google goes last because it is the most
# aggressively blocked: it answers a bare datacentre IP with a 429 and a /sorry/ page.
BACKENDS = ("brave", "duckduckgo", "mojeek", "startpage", "yahoo", "google")

# Hosts whose snippets are never an answer, dropped before the cap is applied so they
# do not use up one of the five lines. All seen in real results on 2026-09-20:
# grokipedia offered a "proprietary on-chain cycle indicator" for "bitcoin price",
# bing.com offered "Install for instant news updates", and the social sites answer
# every question with somebody talking about it rather than with it.
DENY = frozenset({
    "grokipedia", "bing", "google", "yahoo", "duckduckgo", "facebook", "instagram",
    "twitter", "x", "tiktok", "pinterest", "quora", "youtube", "linkedin",
})

# The only routing left: a question whose answer moves during the day is asked of the
# last day, everything else of the whole index. Unproven rather than measured, so it
# is one table in one place and the fallback below widens it when it comes back thin.
_TODAY = re.compile(
    r"\b(today|tonight|now|currently|current|latest|price|prices|cost|worth|rate|"
    r"rates|weather|forecast|temperature|rain|snow|open|opens|opening|closed|closing|"
    r"stock|stocks|shares|traffic|delayed|delays|live|score|scores|standings)\b")

# A relative date the search engine put at the front of the snippet, which is worth
# keeping and worth moving out of the text so the word cap does not eat it. Measured
# shapes: "20 hours ago - ...", "1 day ago · ...", "22 May 2025 · ...".
_SEP = re.compile(r"\s+[-–—·|]\s+")
_DATEISH = re.compile(r"\bago\b|\b(?:19|20)\d{2}\b|"
                      r"\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)", re.I)

# Second-level labels that are part of a public suffix ("thesun.co.uk"), so the name
# before them is the one to show.
_SUFFIX2 = frozenset({"co", "com", "org", "net", "ac", "gov", "edu"})
_NOISE = frozenset({"www", "m", "amp", "mobile"})

# What the model is handed when nothing came back, which is roughly one search in
# seven on a keyless engine. The wording is measured (2026-09-20), because the obvious
# version is not the one that works:
#
#   "No web results came back for that."               the model invented an answer for
#     "who won the last formula one grand prix" and gave the 2026 Italian Grand Prix to
#     Max Verstappen, with no hedge at all. 1 of 3 sentences fabricated outright.
#   "The search found nothing. You do not know the       worse. Told it did not know, the
#    answer. Tell the user you could not find it out."   model set out to prove it did,
#     volunteering the 2024 and 2025 championships unasked.
#   the one below                                      3 of 3 clean: "I couldn't find
#     the result of the last Formula One Grand Prix", and no memory offered as fact.
#
# The same shape as the tool-description finding one level up: an instruction to do
# something lands, an assertion about what the model knows does not.
NOTHING = ("No web results came back for that. Say you could not find out, "
           "and do not answer from memory.")

# Words too common to prove a snippet is about the query.
_STOP = frozenset({
    "what", "when", "where", "which", "who", "whom", "whose", "why", "how", "does",
    "did", "was", "were", "will", "would", "should", "could", "have", "has", "had",
    "the", "that", "this", "these", "those", "there", "their", "they", "them", "with",
    "from", "into", "about", "just", "like", "much", "many", "more", "most", "some",
    "any", "and", "but", "for", "not", "you", "your", "its", "his", "her", "are",
    "can", "get", "got", "now", "today", "tell", "know", "time", "year", "today",
})


def _content_words(text: str) -> set[str]:
    return {w for w in re.findall(r"[a-z0-9]{4,}", text.lower()) if w not in _STOP}


@dataclass(frozen=True)
class Result:
    source: str          # "coingecko"
    when: str | None     # "20 hours ago", "22 May 2025", or None
    text: str


def source_of(url: str) -> str:
    """
    The site's name, for a model to say out loud. No URL ever leaves this module.

    en.wikipedia.org -> wikipedia, ca.finance.yahoo.com -> yahoo,
    www.thesun.co.uk -> thesun. Doubling as the dedupe key, so five results are
    never five pages of one site.
    """
    host = urlsplit(url).netloc.lower().split(":")[0]
    labels = [l for l in host.split(".") if l and l not in _NOISE]
    if len(labels) >= 3 and labels[-2] in _SUFFIX2:
        labels = labels[:-2]
    elif len(labels) >= 2:
        labels = labels[:-1]
    return labels[-1] if labels else host


def _split_date(body: str) -> tuple[str | None, str]:
    parts = _SEP.split(body.strip(), maxsplit=1)
    if len(parts) == 2 and len(parts[0]) <= 24 and _DATEISH.search(parts[0]):
        return parts[0].strip(), parts[1].strip()
    return None, body.strip()


def _clip(text: str, words: int) -> str:
    text = " ".join(text.split())
    parts = text.split(" ")
    return text if len(parts) <= words else " ".join(parts[:words]) + "..."


def timelimit_for(query: str) -> str | None:
    """"d" for a question whose answer moves during the day, else None."""
    return "d" if _TODAY.search(query.lower()) else None


_quiet = False


def _quieten() -> None:
    """
    Keep the libraries out of the server log.

    `primp` logs every HTTP request at INFO and `ddgs` logs a warning per backend it
    skips, which is a dozen lines a search and buries the one line this module writes.
    Their warnings are still reachable with `-v`, where the root level is DEBUG.
    """
    global _quiet
    if _quiet:
        return
    for name in ("primp", "ddgs"):
        logging.getLogger(name).setLevel(
            logging.DEBUG if log.isEnabledFor(logging.DEBUG) else logging.ERROR)
    _quiet = True


@dataclass
class WebSearch:
    """
    One search, one digest. Sync inside, because `ddgs` is sync.

    The public entry point is `digest`, which runs the whole thing in a thread. That
    is safe here in a way it is not for MLX: this is network I/O and holds no stream,
    so unlike a Whisper or mflux pass it can leave the event loop (`app.py`).
    """

    results: int = 5
    words: int = 40
    # Per HTTP request, and never more than the budget has left: checking the budget
    # only between backends let one slow request overrun it, 5.48 s against a 4 s
    # budget in the probe on 2026-09-20. The owner is holding the button meanwhile.
    timeout: float = 4.0
    budget_s: float = 5.0          # for the whole search, walk and retry included
    max_chars: int = 1200          # backstop on the digest, about 300 tokens
    backends: tuple[str, ...] = BACKENDS
    deny: frozenset[str] = DENY
    # Route the handful of questions that have a real data source behind them
    # (sources.py) before falling through to the general search. `home` is the place
    # a weather question means when it names none.
    sources: bool = True
    home: str | None = None
    # The backend that answered last. Availability moves by the minute, so starting
    # with the one that worked saves walking the dead ones again.
    _preferred: str | None = field(default=None, init=False, repr=False)
    _checked: tuple[str, ...] | None = field(default=None, init=False, repr=False)

    def _client(self, seconds: float):
        from ddgs import DDGS       # imported late: a --no-search run never needs it
        _quieten()
        return DDGS(timeout=max(1.0, min(self.timeout, seconds)))

    def _valid(self) -> tuple[str, ...]:
        """
        The pool, less any name this ddgs does not have a text backend for.

        Checked against ddgs's own registry rather than trusted, because the failure
        it prevents is silent: an unknown name falls back to `auto` and answers.
        """
        if self._checked is not None:
            return self._checked
        try:
            from ddgs.engines import ENGINES
            known = set(ENGINES["text"])
        except Exception as e:                      # a future ddgs may move it
            log.debug("cannot read the ddgs backend registry (%s); using the pool as given", e)
            self._checked = self.backends
            return self._checked
        good = tuple(b for b in self.backends if b in known)
        for b in self.backends:
            if b not in known:
                log.warning("search backend %r is not one of %s; dropped",
                            b, ", ".join(sorted(known)))
        if not good:
            log.error("no usable search backend in %s", self.backends)
        self._checked = good
        return good

    def _order(self) -> list[str]:
        pool = self._valid()
        if self._preferred in pool:
            return [self._preferred, *(b for b in pool if b != self._preferred)]
        return list(pool)

    def _ask(self, query: str, timelimit: str | None,
             deadline: float) -> tuple[list[dict], str | None]:
        """
        Walk the backends until one answers or the budget runs out.

        The budget matters more than it looks: a walk that ends with nothing took 4.8 s
        in the probe, and the same silence after 4 s is a hold the owner is still
        holding. Better a short "nothing came back" than a long one.
        """
        for backend in self._order():
            left = deadline - time.monotonic()
            if left < 0.5:
                log.debug("search budget spent before %s", backend)
                break
            try:
                rows = self._client(left).text(query, max_results=self.results * 3,
                                   timelimit=timelimit, backend=backend)
            except Exception as e:   # every backend failure is a DDGSException
                log.debug("search backend %s: %s", backend, e)
                continue
            if rows:
                self._preferred = backend
                return list(rows), backend
        return [], None

    def search(self, query: str) -> tuple[list[Result], str]:
        """
        The rows, and a one-line account of where they came from for the log.

        A day-limited search that comes back thin is asked again without the limit:
        "what happened in the news today" returned nothing at all inside a day. Two
        attempts share the one budget.
        """
        deadline = time.monotonic() + self.budget_s
        timelimit = timelimit_for(query)
        attempts = [timelimit, None] if timelimit is not None else [None]
        last = "nothing"
        for i, tl in enumerate(attempts):
            rows, backend = self._ask(query, tl, deadline)
            out = self._rows(rows, query)
            last = f"{'day' if tl else 'any'} via {backend or 'nobody'}"
            if len(out) >= 2 or (out and i == len(attempts) - 1):
                return out, last
        return [], last

    def _rows(self, rows: list[dict], query: str = "") -> list[Result]:
        """
        Five lines at most, one per site, each of them plausibly about the question.

        The relevance check is the crudest one that works and deliberately not a
        reranker: a snippet must share one content word of four letters or more with
        the query, or with the title if the snippet is a bare page blurb. It exists
        because of a measured case, not a theory: on 2026-09-20 "bitcoin price"
        came back with a Wikipedia snippet about Donald Trump's media appearances in
        first place, and a small model weights what it reads first. Nothing in the
        probe's question set was lost to it.
        """
        wanted = _content_words(query)
        out: list[Result] = []
        seen: set[str] = set()
        for row in rows:
            body = (row.get("body") or "").strip()
            if not body:
                continue
            source = source_of(row.get("href") or row.get("url") or "")
            if not source or source in self.deny or source in seen:
                continue
            when, text = _split_date(body)
            if not text:
                continue
            if wanted and not (wanted & _content_words(text + " " + (row.get("title") or ""))):
                log.debug("dropped %s, nothing in common with the query", source)
                continue
            seen.add(source)
            out.append(Result(source, when, _clip(text, self.words)))
            if len(out) == self.results:
                break
        return out

    def format(self, query: str, rows: list[Result]) -> str:
        """
        What the model actually reads. Numbered lines, not JSON.

        JSON spends tokens on punctuation a small model has to look past, and the
        model has no use for a URL it cannot open and the device will never show. So
        each line is the site's name, when it was written, and the snippet.
        """
        if not rows:
            return NOTHING
        lines = [f'Web results for "{query}":']
        for i, r in enumerate(rows, 1):
            when = f", {r.when}" if r.when else ""
            lines.append(f"{i}. {r.source}{when}: {r.text}")
        digest = "\n".join(lines)
        while len(digest) > self.max_chars and len(lines) > 2:
            lines.pop()
            digest = "\n".join(lines)
        return digest

    def digest_sync(self, query: str) -> str:
        """
        The one entry point, whichever road the answer comes down.

        A question with a real data source behind it goes there first (`sources.py`);
        everything else, and anything a source declines or fails to answer, falls
        through to the general search. The model is never told which happened: it
        called one tool and got one digest.
        """
        query = " ".join((query or "").split())
        if not query:
            return "No search words were given."
        started = time.monotonic()

        if self.sources:
            picked = sources.route(query)
            if picked is not None:
                name, source = picked
                ctx = sources.Context(self.home, self.timeout)
                try:
                    answer = source(query, ctx)
                except Exception:       # a source must never take the answer down
                    log.exception("source %s failed", name)
                    answer = None
                if answer:
                    log.info("answered %r from %s  %.2fs  %d chars",
                             query, name, time.monotonic() - started, len(answer))
                    return answer
                log.debug("source %s declined %r, falling through to search", name, query)

        rows, how = self.search(query)
        digest = self.format(query, rows)
        log.info("searched %r  %s  %d results  %.2fs  %d chars",
                 query, how, len(rows), time.monotonic() - started, len(digest))
        return digest

    async def digest(self, query: str) -> str:
        return await asyncio.to_thread(self.digest_sync, query)
