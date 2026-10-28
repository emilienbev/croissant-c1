"""
What each provider can run: its own API's list of models, sorted into the device's four
slots, so the device is offered only what can do the job.

A list is sorted by what the API says where it says it: Ollama's capabilities, Gemini's
supported actions, OpenRouter's modalities and parameters, Mistral's capability flags,
Together's type. OpenAI, Groq, DeepSeek and LM Studio list ids alone, so there it is the
name. Chat needs tools: the server's tool loop is how a hold sets a timer or searches.
Gemini says what a model is called for, not what comes out of it, so its names finish
the job (`-image`, `-tts`, `-transcribe`).

A listing also says whether the key it was asked with is on the provider's free tier,
where the provider lets that be seen: Gemini does; OpenAI's free tokens do
not show through its API. A free tier's terms are its provider's: when its day starts,
whether it may use what is sent.
"""

from __future__ import annotations

import asyncio
import re
from typing import Any

from google.genai import errors, types
from ollama import AsyncClient

from .clients import gemini_client, openai_client
from .providers import SLOTS, Listing, Provider

PICTURES = {"text_to_image", "image_to_image"}


async def listing(p: Provider) -> Listing:
    """Ask `p` what it offers its key. Raises what the provider says when it refuses."""
    return await _LISTERS[p.kind](p)


def _by_slot(found: dict[str, set[str]]) -> dict[str, list[str]]:
    """Each model under every slot it can serve, without the twins of another listed."""
    return {slot: _twinless(sorted(n for n, s in found.items() if slot in s)) for slot in SLOTS}


async def _list_ollama(p: Provider) -> Listing:
    client = AsyncClient(host=p.url) if p.url else AsyncClient()
    listed = [m for m in (await client.list()).models if m.model]
    shown = await asyncio.gather(*(client.show(m.model) for m in listed))
    caps = {m.model: set(s.capabilities or []) for m, s in zip(listed, shown)}
    return Listing(_by_slot({n: {"chat"} if {"completion", "tools"} <= c else set() for n, c in caps.items()}),
                   sizes={m.model: m.size for m in listed if m.size},
                   thinking=sorted(n for n, c in caps.items() if "thinking" in c))


async def _list_openai(p: Provider) -> Listing:
    return Listing(_by_slot({m.id: _openai(m.id, m.model_dump()) async for m in openai_client(p).models.list()}))


async def _list_gemini(p: Provider) -> Listing:
    client = gemini_client(p)
    found = {m.name.removeprefix("models/"): _gemini(m.name.removeprefix("models/"), m.supported_actions or [])
             async for m in await client.aio.models.list() if m.name}
    usable = await _gemini_open(client, [n for n, s in found.items() if s])
    models = _by_slot({n: s for n, s in found.items() if n in usable})
    free = await _gemini_free(client, models["chat"])
    # Gemini's free tier: a cap a day per model, back at midnight Pacific; Google may use
    # what is sent.
    return Listing(models, free=free, resets="America/Los_Angeles" if free else None, shares=free)


async def _gemini_open(client: Any, names: list[str]) -> set[str]:
    """
    The models this key may call. Gemini lists some it then refuses to new keys ("no
    longer available to new users", a 404); counting a word's tokens, which is free, says
    so without running the model.
    """
    gate = asyncio.Semaphore(8)

    async def closed(name: str) -> bool:
        async with gate:
            try:
                await client.aio.models.count_tokens(model=name, contents="hi")
            except errors.APIError as e:
                return e.code == 404
            return False

    shut = await asyncio.gather(*(closed(n) for n in names))
    return {n for n, s in zip(names, shut) if not s}


# Gemini: called with generateContent, but not for an answer in words; or an alias
# (`-latest`) of a model listed by its own name.
_GEMINI_NOT = re.compile(r"tts|lyria|embedding|computer-use|robotics|deep-research|antigravity|live|native-audio|aqa|"
                         r"-latest$")


def _gemini(name: str, actions: list[str]) -> set[str]:
    n = name.lower()
    if n.startswith("imagen") and "predict" in actions:
        return {"text_to_image"}            # words only (pictures.py)
    if "generateContent" not in actions or _GEMINI_NOT.search(n):
        return set()
    if "image" in n or "nano-banana" in n:
        return PICTURES
    if "transcribe" in n:
        return {"stt"}
    # A Gemini hears audio as well as it reads (stt.py sends it the hold); Gemma does not.
    return {"chat", "stt"} if n.startswith("gemini") else {"chat"}


# A free key is refused a Pro model outright: Google's 429 names a quota of its free tier
# ("...PerModel-FreeTier", "free_tier_..."), its limit 0. A paid key is answered, for a
# fraction of a cent. A Pro model closed to new keys (404) or failing (5xx) passes to the
# next; the newest first, its plain name before its variants. The page waits on this and
# Google's Pro previews can take a minute to say 504, so it has PROBE_S in all, after which
# it cannot tell: not free.
_FREE_TIER = re.compile(r"free.?tier", re.I)
_PLAIN_PRO = re.compile(r"-pro(?:-preview)?$")
PROBE_S = 10


async def _gemini_free(client: Any, chat: list[str]) -> bool:
    newest = sorted((m for m in chat if "-pro" in m), reverse=True)
    try:
        async with asyncio.timeout(PROBE_S):
            for model in sorted(newest, key=lambda m: not _PLAIN_PRO.search(m)):
                try:
                    await client.aio.models.generate_content(
                        model=model, contents="hi", config=types.GenerateContentConfig(max_output_tokens=16))
                    return False
                except errors.APIError as e:
                    if e.code != 404 and e.code < 500:
                        return e.code == 429 and bool(_FREE_TIER.search(str(e)))
    except TimeoutError:
        pass
    return False


# Together's type, where it gives one.
_TOGETHER = {"chat": {"chat"}, "image": {"text_to_image"}, "transcribe": {"stt"}}
# Named for something other than chat with tools on /chat/completions.
_OPENAI_NOT = re.compile(r"embed|tts|moderation|realtime|audio|search|sora|davinci|babbage|guard|"
                         r"rerank|computer-use|codex|ocr|^chatgpt-|chat-latest|turbo-instruct")


def _openai(model: str, d: dict[str, Any]) -> set[str]:
    n = model.lower()
    arch = d.get("architecture")
    if isinstance(arch, dict):              # OpenRouter: chat only (no /images, no /audio there)
        text_out = (arch.get("output_modalities") or []) == ["text"]
        return {"chat"} if text_out and "tools" in (d.get("supported_parameters") or []) else set()
    # Mistral before Together: a Mistral model also has a `type` ("base"), its capabilities say more.
    caps = d.get("capabilities")
    if isinstance(caps, dict) and "completion_chat" in caps:   # Mistral
        return (({"chat"} if caps.get("completion_chat") and caps.get("function_calling") else set())
                | ({"stt"} if "voxtral" in n else set()))
    if isinstance(d.get("type"), str):      # Together
        return set(_TOGETHER.get(d["type"], set()))
    # The rest say nothing but the name.
    if "realtime" not in n and re.search(r"whisper|transcri|voxtral", n):
        return {"stt"}
    if re.search(r"gpt-image|dall-e-2", n):
        return PICTURES
    if "dall-e" in n:
        return {"text_to_image"}
    if "image" in n or _OPENAI_NOT.search(n):
        return set()
    return {"chat"}


# A dated snapshot, or a preview, of a model listed by its plain name too
# (gpt-4o-2024-08-06 and gemini-3.1-flash-lite-preview beside gpt-4o and gemini-3.1-flash-lite).
_TWIN = re.compile(r"^(.+?)(?:-preview)?(?:-(\d{4}-\d{2}-\d{2}|\d{4}|\d{2}-\d{4}))?$")


def _twinless(names: list[str]) -> list[str]:
    """Without the twins (`_TWIN`) of a model also listed by its plain name."""
    have = set(names)
    return [n for n in names if not ((m := _TWIN.match(n)) and m.group(1) != n and m.group(1) in have)]


_LISTERS = {"ollama": _list_ollama, "openai": _list_openai, "gemini": _list_gemini}
