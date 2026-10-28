"""
The settings page: providers.json edited from a browser, on a phone on the tailnet that
scanned the R1's QR code.

`GET /settings` serves the page (settings.html) and its fonts; the page then talks over
a WebSocket on `/config`, as the device does on `/session`, because the server answers
plain HTTP before the upgrade and never reads a request body. Frames, JSON both ways:

    -> {"type":"load"}                               <- {"type":"state", ...}
    -> {"type":"models","provider":{"id":"groq","key":null|"..."}}
                                                     <- {"type":"models","provider":"groq","listed":{"models":{slot:[...]},"free":false}}
    -> {"type":"add","provider":{"id":"gemini","key":"...",...}}
                                                     <- a fresh "state" then {"type":"added","provider":"gemini","listed":{...}}
    -> {"type":"save","file":{...providers.json...}} <- {"type":"saved"} then a fresh "state"
    <- {"type":"error","message":"...","provider":"groq"?}  instead, when one fails

`add` is a provider being added: listed with the key the page has and, if it answers,
saved alone (the page's Test). `save` is the rest of the file, the providers already there.

A listing is answered when its provider has answered, which can take seconds; the frames
sent meanwhile are answered first.

No key ever goes to the page: a provider says whether it has one, where it came from (the
file or an environment variable) and the last four characters. A key the page leaves
alone comes back as null and the file keeps it (providers.py, `save`).

A provider's models are its own API's list sorted by slot, with whether its key is on its
free tier (catalog.py), asked once (at
startup, a key saved, or the page finding none) and kept beside providers.json in
providers.models.json; Refresh asks again. The state carries them as `listed`, so opening
the page asks no provider anything. The device picks among them; the page only says how
many each provider offers.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
from dataclasses import asdict
from pathlib import Path
from typing import Any

from websockets.asyncio.server import ServerConnection
from websockets.exceptions import ConnectionClosed

from .catalog import listing
from .providers import PRESETS, Listing, Providers, ProvidersError, _provider

log = logging.getLogger(__name__)

PAGE = Path(__file__).with_name("settings.html")
# The R1's own typeface, the owner's copy where the installer keeps it (`./croissant font`, the
# checkout's cache/fonts; CROISSANT_FONTS points elsewhere). Without it the page drops its
# @font-face rules and is set in the system's font.
FONTS = Path(os.environ.get("CROISSANT_FONTS") or Path(os.environ.get("CROISSANT_CACHE") or Path(__file__).resolve().parents[2] / "cache") / "fonts")
FONT_FILES = {"regular": "PowerGrotesk-Regular.ttf", "light": "PowerGrotesk-Light.ttf"}


def page() -> bytes:
    html = PAGE.read_bytes()
    if all((FONTS / f).is_file() for f in FONT_FILES.values()):
        return html
    return re.sub(rb"[ \t]*@font-face[^}]*}\n?", b"", html)


def font(name: str) -> bytes | None:
    f = FONT_FILES.get(name)
    if f is None or not (FONTS / f).is_file():
        return None
    return (FONTS / f).read_bytes()


def _hint(key: str | None) -> str | None:
    return key[-4:] if key and len(key) >= 12 else None


def state(providers: Providers) -> dict[str, Any]:
    raw = providers.raw
    known = {}
    for pid in dict.fromkeys([*PRESETS, *(raw.get("providers") or {})]):
        p = providers.providers.get(pid)
        spec = (raw.get("providers") or {}).get(pid) or {}
        if p is None:   # a preset nobody uses yet
            try:
                p = _provider(pid, {})
            except ProvidersError:
                continue
        preset = PRESETS.get(pid)
        known[pid] = {
            "title": p.title, "kind": p.kind, "url": spec.get("url"),
            "preset": preset is not None, "preset_url": preset.url if preset else None,
            "key_env": list(preset.key_env) if preset else [],
            "key_from": p.key_from, "key_hint": _hint(p.key),
            "in_file": pid in (raw.get("providers") or {}),
        }
    return {
        "type": "state",
        "path": str(providers.path) if providers.path else None,
        "providers": known,
        "file_providers": {pid: {k: v for k, v in spec.items() if k != "key"}
                           for pid, spec in (raw.get("providers") or {}).items()},
        "listed": {pid: asdict(v) for pid, v in providers.listed.items()},
    }


async def models(providers: Providers, spec: dict[str, Any]) -> Listing:
    """
    What a provider offers, asked of its API with what the page has (a key just pasted,
    else the saved one), and kept.
    """
    pid = str(spec.get("id") or "")
    current = providers.providers.get(pid)
    given = {k: v for k, v in spec.items() if k in ("kind", "url", "title") and v}
    if spec.get("key"):
        given["key"] = spec["key"]
    elif current is not None and current.key:
        given["key"] = current.key
    found = await listing(_provider(pid, given))
    providers.keep(pid, found)
    return found


async def add(providers: Providers, spec: dict[str, Any]) -> Listing:
    """
    A provider being added, tried with what the page has (its key, its address) and, if it
    answers, written to the file beside what is there; refused, nothing is written.
    """
    pid = str(spec.get("id") or "")
    entry = {k: v for k, v in spec.items() if k in ("preset", "kind", "url", "title", "key") and v}
    found = await listing(_provider(pid, entry))
    providers.save({"providers": {**(providers.raw.get("providers") or {}), pid: entry}})
    providers.keep(pid, found)
    return found


async def fill(providers: Providers) -> None:
    """Ask every provider that can answer and has no list yet, one at a time (at startup, after a save)."""
    for p in providers.unlisted():
        try:
            providers.keep(p.id, await listing(p))
            found = providers.listed[p.id]
            log.info("%s lists %s%s", p.title, ", ".join(f"{len(v)} {k}" for k, v in found.models.items()),
                     ", free tier" if found.free else "")
        except Exception as e:  # a provider down, a key refused
            log.warning("could not list %s's models: %s", p.title, e)


# Listings under way, held here so a page that leaves does not take them with it: what a
# provider lists is kept all the same.
_running: set[asyncio.Task[None]] = set()


async def handle(ws: ServerConnection, providers: Providers) -> None:
    async def send(**payload: Any) -> None:
        await ws.send(json.dumps(payload, ensure_ascii=False))

    async def answer(msg: dict[str, Any]) -> None:
        kind = msg.get("type")
        try:
            if kind == "load":
                await send(**state(providers))
            elif kind == "models":
                spec = msg.get("provider") or {}
                await send(type="models", provider=spec.get("id"), listed=asdict(await models(providers, spec)))
            elif kind == "add":
                spec = msg.get("provider") or {}
                found = await add(providers, spec)
                await send(**state(providers))
                await send(type="added", provider=spec.get("id"), listed=asdict(found))
            elif kind == "save":
                providers.save(msg.get("file") or {})
                await send(type="saved")
                await send(**state(providers))
        except ConnectionClosed:
            pass    # the page left (a phone locked) before its answer
        except Exception as e:  # a file refused; a provider down, or its key refused, while listing
            if not isinstance(e, ProvidersError):
                log.warning("settings %s failed: %s", kind, e)
                e = f"{type(e).__name__}: {e}"[:300]
            which = (msg.get("provider") or {}).get("id") if kind in ("models", "add") else None
            try:
                await send(type="error", message=str(e), **({"provider": which} if which else {}))
            except ConnectionClosed:
                pass

    try:
        async for raw in ws:
            if isinstance(raw, bytes):
                continue
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                continue
            if msg.get("type") in ("models", "add"):
                # A provider can take a while to list; the page's next frames, a Save among
                # them, are answered meanwhile.
                task = asyncio.create_task(answer(msg))
                _running.add(task)
                task.add_done_callback(_running.discard)
            else:
                await answer(msg)
    except ConnectionClosed:
        pass
