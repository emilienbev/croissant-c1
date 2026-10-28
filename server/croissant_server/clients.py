"""
One API client per provider, shared by everything that talks to it (chat, STT,
pictures), made the first time something needs it.
"""

from __future__ import annotations

import openai
from google import genai
from google.genai import types

from .providers import Provider

TIMEOUT_S = 60.0

# By everything that makes a client, so a key changed on the settings page makes a new one.
_openai: dict[tuple, openai.AsyncOpenAI] = {}
_gemini: dict[tuple, genai.Client] = {}


def openai_client(p: Provider) -> openai.AsyncOpenAI:
    c = _openai.get((p.url, p.key))
    if c is None:
        # Local servers take any key, and the SDK refuses to start without one.
        c = _openai[(p.url, p.key)] = openai.AsyncOpenAI(base_url=p.url, api_key=p.key or "none",
                                               timeout=TIMEOUT_S, max_retries=1)
    return c


def gemini_client(p: Provider) -> genai.Client:
    c = _gemini.get((p.key,))
    if c is None:
        if not p.key:
            raise ValueError(f"provider {p.id!r} has no key")
        c = _gemini[(p.key,)] = genai.Client(api_key=p.key, http_options=types.HttpOptions(
            timeout=int(TIMEOUT_S * 1000)))   # milliseconds, here
    return c
