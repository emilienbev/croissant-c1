"""
Prices (prices.py) put to the device's use (ledger.py).

For `GET /providers`, each model the device may pick (providers.py) with its rates, a
card's unit (an hour of speech, a picture) and its month at your pace: the slot's last 30
days of use, whatever ran it, priced at that model's rates. None under a week of use. A
slot's models come cheapest first within each provider, deprecated ones left out. For
`GET /usage`, the last 30 days: what each job took and cost, a total, a cost a day.

A model costs nothing on this machine (`local`, Ollama) or with a key on its provider's
free tier (catalog.py).
"""

from __future__ import annotations

import datetime
import math
import time
from collections import Counter
from typing import Any
from zoneinfo import ZoneInfo

from .ledger import DAY, Ledger
from .prices import Price, Prices
from .providers import SLOTS, Entry, Providers

WINDOW_DAYS = 30
MIN_DAYS = 7
# A card's unit for what is not priced per token: an hour of speech (about 12,000 words'
# tokens written), one picture.
UNIT = {"stt": {"secs": 3600, "out": 12000}, "text_to_image": {"pictures": 1}, "image_to_image": {"pictures": 1}}
# What a slot is sorted by before there is any use of it.
REFERENCE = {"chat": {"in": 1_000_000, "out": 50_000}, **UNIT}
COUNTS = ("in", "cached", "out", "secs", "pictures")


class Costs:
    def __init__(self, providers: Providers, prices: Prices, ledger: Ledger) -> None:
        self.providers, self.prices, self.ledger = providers, prices, ledger

    def costs_nothing(self, e: Entry) -> bool:
        return e.provider.local or self.providers.free_tier(e.provider.id)

    def price(self, e: Entry) -> Price | None:
        return None if e.provider.local else self.prices.get(e.provider, e.model)

    def cost(self, e: Entry, counts: dict[str, float]) -> float | None:
        if self.costs_nothing(e):
            return 0.0
        p = self.price(e)
        return p.cost(counts) if p else None

    def pace(self, slot: str) -> dict[str, float] | None:
        """
        A month of the slot's use at the last 30 days' rate, the live words' passes left
        out; None under a week of use.
        """
        first, now = self.ledger.first(), time.time()
        days = (now - first) / DAY if first else 0
        if days < MIN_DAYS:
            return None
        totals = dict.fromkeys(COUNTS, 0.0)
        for r in self.ledger.since(now - WINDOW_DAYS * DAY):
            if r.get("slot") == slot and not r.get("live"):
                for k in COUNTS:
                    totals[k] += r.get(k, 0)
        scale = WINDOW_DAYS / min(WINDOW_DAYS, days)
        return {k: v * scale for k, v in totals.items()}

    def today(self) -> Counter[str]:
        """Requests today per model on a free tier, from midnight where its provider's day starts."""
        starts = {pid: _midnight(found.resets) for pid, found in self.providers.listed.items() if found.free}
        return Counter(r["model"] for r in self.ledger.since(time.time() - DAY)
                       if r.get("t", 0) >= starts.get(str(r.get("model")).partition(":")[0], math.inf))

    def public(self) -> dict[str, Any]:
        """
        What the device is told (`GET /providers`): per slot, the default and every model
        it may pick, cheapest first, with what each costs.
        """
        today = self.today()
        out: dict[str, Any] = {}
        for slot in SLOTS:
            d, pace = self.providers.default(slot), self.pace(slot)
            out[slot] = {"default": d.id if d else None,
                         "entries": [{**self.providers.public(slot, e), **self.about(slot, e, pace, today)}
                                     for e in self.order(slot, pace)]}
        return out

    def about(self, slot: str, e: Entry, pace: dict[str, float] | None, today: Counter[str]) -> dict[str, Any]:
        """
        What a card shows of a model: its name, rates per 1M tokens, unit price, month at
        your `pace`, whether it thinks, its size here, and on a free tier `today`'s requests.
        """
        out: dict[str, Any] = {}
        p = self.price(e)
        if p is not None:
            out["title"] = _short(p.name, e.provider.title)
            if p.input is not None or p.output is not None:
                out["rates"] = {"in": p.input, "out": p.output, "cached": p.cached}
            out["thinks"] = p.thinks
        listing = self.providers.listing(e.provider)
        if listing is not None and e.provider.local:
            out["thinks"] = e.model in listing.thinking
        if listing is not None and e.model in listing.sizes:
            out["bytes"] = listing.sizes[e.model]
        if self.providers.free_tier(e.provider.id):
            out["today"] = today[e.id]
        if slot in UNIT and (u := self.cost(e, UNIT[slot])) is not None:
            out["unit"] = u
        if pace is not None and (m := self.cost(e, pace)) is not None:
            out["month"] = m
        return out

    def order(self, slot: str, pace: dict[str, float] | None) -> list[Entry]:
        """
        The slot's models cheapest first within each provider (free, the smaller in memory
        first; then priced; then unknown). Deprecated ones are left out, but
        for the default, which a hold that picks nothing still gets.
        """
        default = self.providers.default(slot)
        entries = [e for e in self.providers.entries(slot)
                   if not ((p := self.price(e)) and p.deprecated) or (default and e.id == default.id)]
        mix = pace or REFERENCE[slot]
        groups = list(dict.fromkeys(e.provider.id for e in entries))

        def key(e: Entry) -> tuple[int, float, int]:
            c = self.cost(e, mix)
            listing = self.providers.listing(e.provider)
            size = listing.sizes.get(e.model, 0) if listing else 0
            return groups.index(e.provider.id), math.inf if c is None else c, size

        return sorted(entries, key=key)

    def usage(self) -> dict[str, Any]:
        """
        The last 30 days from midnight (Settings > Usage): per job its counts, its cost and
        whether all of it could be priced; the total; a cost a day.
        """
        first = datetime.date.today() - datetime.timedelta(days=WINDOW_DAYS - 1)
        daily = [0.0] * WINDOW_DAYS
        slots: dict[str, dict[str, Any]] = {}
        for r in self.ledger.since(datetime.datetime.combine(first, datetime.time()).timestamp()):
            slot = r.get("slot")
            if slot not in SLOTS:
                continue
            e = self.providers.find(slot, r.get("model"))
            s = slots.setdefault(slot, {"cost": 0.0, "free": True, "priced": True, **dict.fromkeys(COUNTS, 0)})
            for k in COUNTS:
                s[k] += r.get(k, 0)
            s["free"] = s["free"] and e is not None and self.costs_nothing(e)
            c = self.cost(e, r) if e is not None else None
            if c is None:
                s["priced"] = False
                continue
            s["cost"] += c
            day = (datetime.date.fromtimestamp(r["t"]) - first).days
            if 0 <= day < WINDOW_DAYS:
                daily[day] += c
        return {"days": WINDOW_DAYS, "total": sum(s["cost"] for s in slots.values()), "daily": daily,
                "slots": {k: {"cost": v["cost"], "free": v["free"], "priced": v["priced"],
                              "tokens": int(v["in"] + v["out"]), "secs": int(v["secs"]),
                              "pictures": int(v["pictures"])} for k, v in slots.items()}}


def _midnight(zone: str | None) -> float:
    """When today began in `zone` (an IANA name), or here."""
    now = datetime.datetime.now(ZoneInfo(zone) if zone else None)
    return now.replace(hour=0, minute=0, second=0, microsecond=0).timestamp()


def _short(name: str, provider: str) -> str:
    """The name without its maker when the provider's says it ("Gemini 2.5 Flash" under Google Gemini)."""
    first, _, rest = name.partition(" ")
    return rest if rest and first.lower() in provider.lower().split() else name
