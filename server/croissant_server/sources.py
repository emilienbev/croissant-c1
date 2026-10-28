"""
The questions that deserve a real answer rather than a page of search results.

A search engine returns pages; these return answers. Measured on 2026-09-20, the
general search answered "weather in london tomorrow" with five descriptions of
weather websites and not one temperature, while Open-Meteo answered it with the
temperature in 0.18 s. So a handful of questions are routed here first and the search
in `search.py` is what everything else falls through to.

**One tool still.** The model calls `web_search` and never learns where the answer
came from: routing happens here, in Python, on the words it passed. The alternative,
a tool per source, would add roughly 700 tokens of specs to a hold that already
spends 1581 on them, and the measured lesson from the picture tools is that an extra
tool disturbs the ones already there. So the digest comes back in the same shape
whichever road it took, and the probe prints which one that was.

**A route must be confident or it must not fire.** A wrong route answers the wrong
question with total assurance; a missed route just falls through to the search, which
is where it would have gone anyway. So every trigger below wants a strong signal, and
any source that cannot answer returns None rather than a guess.

Every source here is keyless, verified working from this Mac on 2026-09-20, and
answers in about 0.2 s.
"""

from __future__ import annotations

import json
import logging
import re
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from collections.abc import Callable
from datetime import date, datetime

log = logging.getLogger(__name__)

# A browser-ish agent: some of these refuse python-urllib, and the RSS feeds are
# served to whoever asks politely.
AGENT = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
         "(KHTML, like Gecko) Chrome/140.0 Safari/537.36")


def _get(url: str, timeout: float) -> bytes | None:
    req = urllib.request.Request(url, headers={"User-Agent": AGENT})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read()
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        log.debug("source fetch failed %s: %s", url, e)
        return None


def _json(url: str, timeout: float):
    raw = _get(url, timeout)
    if raw is None:
        return None
    try:
        return json.loads(raw)
    except (json.JSONDecodeError, UnicodeDecodeError) as e:
        log.debug("source returned unparseable json %s: %s", url, e)
        return None


# ---------------------------------------------------------------- weather

# The WMO codes Open-Meteo reports, in the words a person would use out loud. The
# model reads these, so "slight rain showers" beats "code 80".
WMO = {
    0: "clear", 1: "mainly clear", 2: "partly cloudy", 3: "overcast",
    45: "foggy", 48: "freezing fog",
    51: "light drizzle", 53: "drizzle", 55: "heavy drizzle",
    56: "freezing drizzle", 57: "heavy freezing drizzle",
    61: "light rain", 63: "rain", 65: "heavy rain",
    66: "freezing rain", 67: "heavy freezing rain",
    71: "light snow", 73: "snow", 75: "heavy snow", 77: "snow grains",
    80: "light showers", 81: "showers", 82: "heavy showers",
    85: "light snow showers", 86: "snow showers",
    95: "thunderstorms", 96: "thunderstorms with hail", 99: "thunderstorms with hail",
}

_WEATHER = re.compile(
    r"\b(weather|forecast|temperature|rain|raining|rainy|snow|snowing|sunny|"
    r"umbrella|how (?:hot|cold|warm)|degrees)\b", re.I)

# The place, when the question names one. Everything after "in", "at" or "for", minus
# the day words that usually trail it. No place found means the configured home.
_PLACE = re.compile(
    r"\b(?:in|at|for)\s+(.+?)\s*$", re.I)
_TRAILING = re.compile(
    r"\s*\b(today|tonight|tomorrow|this (?:morning|afternoon|evening|week|weekend)|"
    r"next week|right now|now|later|please|the next \w+ days?|this \w+day|"
    r"on \w+day)\b.*$", re.I)


def _place(query: str) -> str | None:
    m = _PLACE.search(_TRAILING.sub("", query).strip())
    if not m:
        return None
    place = re.sub(r"[^\w\s'-]", " ", m.group(1)).strip()
    place = re.sub(r"^(the|a)\s+", "", place, flags=re.I).strip()
    return place if 2 <= len(place) <= 60 else None


def weather(query: str, ctx: Context) -> str | None:
    """
    Two days from Open-Meteo, named and dated, in plain words.

    Two days rather than seven because the question is nearly always about today or
    tomorrow, and seven rows is most of the digest's budget spent on days nobody
    asked about.
    """
    place = _place(query) or ctx.home
    if not place:
        return None
    geo = _json("https://geocoding-api.open-meteo.com/v1/search?"
                + urllib.parse.urlencode({"name": place, "count": 1,
                                          "language": "en", "format": "json"}),
                ctx.timeout)
    hit = (geo or {}).get("results") or []
    if not hit:
        log.debug("weather: no such place %r", place)
        return None
    spot = hit[0]
    where = ", ".join(x for x in (spot.get("name"), spot.get("country")) if x)
    data = _json("https://api.open-meteo.com/v1/forecast?"
                 + urllib.parse.urlencode({
                     "latitude": spot["latitude"], "longitude": spot["longitude"],
                     "daily": "weather_code,temperature_2m_max,temperature_2m_min,"
                              "precipitation_probability_max",
                     "timezone": spot.get("timezone") or "auto", "forecast_days": 2}),
                 ctx.timeout)
    daily = (data or {}).get("daily") or {}
    days = daily.get("time") or []
    if not days:
        return None

    lines = [f"Weather for {where}, from open-meteo:"]
    for i, day in enumerate(days[:2]):
        try:
            when = datetime.strptime(day, "%Y-%m-%d").date()
        except ValueError:
            continue
        label = "Today" if i == 0 else "Tomorrow"
        sky = WMO.get(_at(daily, "weather_code", i), "unsettled")
        low, high = _at(daily, "temperature_2m_min", i), _at(daily, "temperature_2m_max", i)
        wet = _at(daily, "precipitation_probability_max", i)
        bits = [f"{label}, {when.strftime('%A %d %B')}: {sky}"]
        if low is not None and high is not None:
            bits.append(f"{round(low)} to {round(high)} degrees C")
        if wet is not None:
            bits.append(f"{round(wet)}% chance of rain")
        lines.append(", ".join(bits) + ".")
    return "\n".join(lines) if len(lines) > 1 else None


def _at(daily: dict, key: str, i: int):
    values = daily.get(key) or []
    return values[i] if i < len(values) else None


# ---------------------------------------------------------------- currency

# The names people say out loud, and the ISO codes Frankfurter wants. Only the
# currencies the ECB publishes are here, because those are the ones it can answer.
NAMES = {
    "dollar": "USD", "dollars": "USD", "usd": "USD", "us dollar": "USD",
    "euro": "EUR", "euros": "EUR", "eur": "EUR",
    "pound": "GBP", "pounds": "GBP", "sterling": "GBP", "gbp": "GBP", "quid": "GBP",
    "yen": "JPY", "jpy": "JPY",
    "franc": "CHF", "francs": "CHF", "chf": "CHF", "swiss franc": "CHF",
    "krona": "SEK", "sek": "SEK", "krone": "NOK", "nok": "NOK", "dkk": "DKK",
    "zloty": "PLN", "pln": "PLN", "forint": "HUF", "huf": "HUF",
    "koruna": "CZK", "czk": "CZK", "lira": "TRY", "try": "TRY",
    "rupee": "INR", "rupees": "INR", "inr": "INR",
    "yuan": "CNY", "renminbi": "CNY", "cny": "CNY",
    "real": "BRL", "brl": "BRL", "peso": "MXN", "mxn": "MXN",
    "rand": "ZAR", "zar": "ZAR", "won": "KRW", "krw": "KRW",
    "canadian dollar": "CAD", "cad": "CAD", "aussie dollar": "AUD", "aud": "AUD",
    "new zealand dollar": "NZD", "nzd": "NZD", "shekel": "ILS", "ils": "ILS",
    "ringgit": "MYR", "myr": "MYR", "baht": "THB", "thb": "THB",
    "rupiah": "IDR", "idr": "IDR", "peso philippines": "PHP", "php": "PHP",
    "leu": "RON", "ron": "RON", "lev": "BGN", "bgn": "BGN",
    "dollar singapore": "SGD", "sgd": "SGD", "hong kong dollar": "HKD", "hkd": "HKD",
    "krona iceland": "ISK", "isk": "ISK",
}
_NAME_RE = re.compile(
    r"\b(" + "|".join(sorted((re.escape(n) for n in NAMES), key=len, reverse=True)) + r")\b",
    re.I)
_MONEY = re.compile(r"\b(exchange rate|convert|conversion|worth|how much is|"
                    r"how many|rate for|in euros|in pounds|in dollars)\b", re.I)
_AMOUNT = re.compile(r"(\d[\d,]*(?:\.\d+)?)")

# Crypto is deliberately not here (2026-09-20). Frankfurter publishes ECB
# reference rates and nothing else, so a bitcoin question falls through to the search.


def currency(query: str, ctx: Context) -> str | None:
    """
    One conversion, or one rate, from the European Central Bank via Frankfurter.

    Needs two currencies to be worth doing. One currency and nothing to turn it into
    is not a conversion, so it falls through rather than guessing at a target.
    """
    found: list[str] = []
    for m in _NAME_RE.finditer(query):
        code = NAMES[m.group(1).lower()]
        if code not in found:
            found.append(code)
    if len(found) < 2:
        return None
    base, target = found[0], found[1]
    amount = 1.0
    m = _AMOUNT.search(query)
    if m:
        try:
            amount = float(m.group(1).replace(",", ""))
        except ValueError:
            amount = 1.0
    if not (0 < amount < 1e12):
        amount = 1.0

    data = _json("https://api.frankfurter.dev/v1/latest?"
                 + urllib.parse.urlencode({"base": base, "symbols": target,
                                           "amount": amount}), ctx.timeout)
    rate = ((data or {}).get("rates") or {}).get(target)
    if rate is None:
        return None
    when = (data or {}).get("date") or ""
    shown = f"{amount:,.2f}".rstrip("0").rstrip(".")
    got = f"{rate:,.2f}" if rate >= 0.01 else f"{rate:.6f}"
    return (f"Exchange rate from the European Central Bank, {when}:\n"
            f"{shown} {base} = {got} {target}.")


# ---------------------------------------------------------------- headlines

# Asked in order until one answers. Trusted in the sense that matters here: each is a
# newsroom publishing its own front page, not an aggregator ranking someone else's.
FEEDS = (
    ("bbc", "https://feeds.bbci.co.uk/news/world/rss.xml"),
    ("the guardian", "https://www.theguardian.com/world/rss"),
    ("npr", "https://feeds.npr.org/1004/rss.xml"),
)

_NEWS = re.compile(
    r"\b(news|headlines|happening in the world|going on in the world|"
    r"what(?:'s| is| has) happened|current events|top stories)\b", re.I)


def headlines(query: str, ctx: Context) -> str | None:
    """
    Five headlines from one newsroom's own front page.

    This is the category where the general search was worst: asked what happened
    today it returned five news sites describing themselves. A front page is a list
    of what happened, already written as one line each, which is the shape wanted.
    """
    for name, url in FEEDS:
        raw = _get(url, ctx.timeout)
        if raw is None:
            continue
        try:
            root = ET.fromstring(raw)
        except ET.ParseError as e:
            log.debug("headlines: %s gave unparseable xml: %s", name, e)
            continue
        lines = []
        for item in root.iter("item"):
            title = (item.findtext("title") or "").strip()
            if not title:
                continue
            summary = re.sub(r"<[^>]+>", " ", item.findtext("description") or "")
            summary = " ".join(summary.split())
            words = summary.split(" ")
            if len(words) > 25:
                summary = " ".join(words[:25]) + "..."
            lines.append(f"{len(lines) + 1}. {title}" + (f" - {summary}" if summary else ""))
            if len(lines) == 5:
                break
        if lines:
            today = date.today().strftime("%A %d %B %Y")
            return f"Top headlines from {name}, {today}:\n" + "\n".join(lines)
    return None


# ---------------------------------------------------------------- routing


class Context:
    """What a source gets besides the query: the fallback place and a time budget."""

    __slots__ = ("home", "timeout")

    def __init__(self, home: str | None, timeout: float) -> None:
        self.home = home
        self.timeout = timeout


Source = Callable[[str, Context], "str | None"]

# Checked in order. Currency comes before weather because "how much is a degree of
# frost worth" is nobody's question but "what is the rate in euros today" would
# otherwise be caught by nothing, and because the two triggers do not overlap.
ROUTES: tuple[tuple[str, re.Pattern[str], Source], ...] = (
    ("currency", _MONEY, currency),
    ("weather", _WEATHER, weather),
    ("headlines", _NEWS, headlines),
)


def route(query: str) -> tuple[str, Source] | None:
    """
    The source this question belongs to, or None to let the search have it.

    The currency route needs its trigger *and* two currencies, which `currency`
    checks for itself and returns None on, so a money-shaped sentence naming no
    money still reaches the search.
    """
    for name, trigger, source in ROUTES:
        if trigger.search(query):
            return name, source
    # A bare "euros to pounds" carries no verb for _MONEY to catch, so two currency
    # names on their own are enough to try the conversion.
    if len({NAMES[m.group(1).lower()] for m in _NAME_RE.finditer(query)}) >= 2:
        return "currency", currency
    return None
