"""
A picture made at a provider rather than by the local worker (images.py files the job
either way).

- **OpenAI-compatible:** `/images/generations` from words, `/images/edits` from a photo.
- **Gemini:** an Imagen model (`imagen-...`) through `generate_images`, any other through
  `generate_content` asking for an image back, which is also how a photo is edited.

Whatever comes back is saved as a JPEG, as the local worker saves its own, so the
device collects the same thing from every backend. The size is the configured one
(768x1024, the R1's 3:4) unless the entry says `size` (`WxH`, OpenAI) or `aspect`
(`3:4`, Gemini); a provider that refuses the size gets the request again without it.
"""

from __future__ import annotations

import base64
import io
import logging
import math
import time
from typing import Any

import httpx

from .clients import gemini_client, openai_client
from .providers import Entry

log = logging.getLogger(__name__)


def _jpeg(data: bytes) -> tuple[bytes, int, int]:
    from PIL import Image

    with Image.open(io.BytesIO(data)) as im:
        out = io.BytesIO()
        im.convert("RGB").save(out, "JPEG", quality=92)
        return out.getvalue(), im.width, im.height


def _aspect(width: int, height: int) -> str:
    g = math.gcd(width, height)
    return f"{width // g}:{height // g}"


async def render(entry: Entry, prompt: str, source: bytes | None,
                 width: int, height: int) -> tuple[bytes, dict[str, Any]]:
    """The JPEG and the row's fields (seconds, size, model), from `entry`'s provider."""
    t0 = time.monotonic()
    kind = entry.provider.kind
    if kind == "openai":
        data = await _openai(entry, prompt, source, width, height)
    elif kind == "gemini":
        data = await _gemini(entry, prompt, source, width, height)
    else:
        raise ValueError(f"{entry.id}: a {kind} provider cannot make pictures")
    jpeg, w, h = _jpeg(data)
    return jpeg, {"seconds": round(time.monotonic() - t0, 1), "width": w, "height": h,
                  "model": entry.id}


async def _openai(entry: Entry, prompt: str, source: bytes | None, width: int, height: int) -> bytes:
    import openai

    client = openai_client(entry.provider)
    kw: dict[str, Any] = {"model": entry.model, "prompt": prompt, "n": 1}
    # An edit follows its photo's shape unless told otherwise; a new picture is the
    # configured size.
    size = entry.options.get("size") or (None if source is not None else f"{width}x{height}")
    if size:
        kw["size"] = size
    if entry.options.get("b64", True):
        kw["response_format"] = "b64_json"
    while True:
        try:
            if source is not None:
                r = await client.images.edit(image=("photo.jpg", source, "image/jpeg"), **kw)
            else:
                r = await client.images.generate(**kw)
            break
        except openai.APIStatusError as e:
            said = str(e)
            # gpt-image models refuse response_format (they always answer in base64)
            # and take three sizes; a refusal drops the parameter and asks again.
            drop = next((k for k in ("response_format", "size") if k in kw and k in said), None)
            if e.status_code not in (400, 422) or drop is None:
                raise
            log.warning("%s refuses %s=%r; asking without it (set \"size\" on the entry)",
                        entry.label(), drop, kw[drop])
            del kw[drop]
    item = r.data[0]
    if item.b64_json:
        return base64.b64decode(item.b64_json)
    if item.url:
        async with httpx.AsyncClient(timeout=60.0) as http:
            got = await http.get(item.url)
            got.raise_for_status()
            return got.content
    raise RuntimeError("the provider sent no picture")


async def _gemini(entry: Entry, prompt: str, source: bytes | None, width: int, height: int) -> bytes:
    from google.genai import types

    client = gemini_client(entry.provider)
    aspect = entry.options.get("aspect") or _aspect(width, height)
    if entry.model.startswith("imagen"):
        if source is not None:
            raise ValueError(f"{entry.id}: Imagen makes pictures from words only")
        r = await client.aio.models.generate_images(
            model=entry.model, prompt=prompt,
            config=types.GenerateImagesConfig(number_of_images=1, aspect_ratio=aspect))
        if not r.generated_images or r.generated_images[0].image is None:
            raise RuntimeError("Imagen sent no picture (refused by its filter?)")
        return r.generated_images[0].image.image_bytes
    parts: list[Any] = [types.Part.from_text(text=prompt)]
    if source is not None:
        parts.insert(0, types.Part.from_bytes(data=source, mime_type="image/jpeg"))
    r = await client.aio.models.generate_content(
        model=entry.model, contents=parts,
        config=types.GenerateContentConfig(
            response_modalities=["IMAGE"],
            # An edit keeps its photo's shape; a new picture is the configured one.
            image_config=None if source is not None and "aspect" not in entry.options
            else types.ImageConfig(aspect_ratio=aspect)))
    for cand in r.candidates or []:
        for part in (cand.content.parts if cand.content else None) or []:
            if part.inline_data and part.inline_data.data:
                return part.inline_data.data
    said = (r.text or "").strip()
    raise RuntimeError(f"Gemini sent no picture{': ' + said[:200] if said else ''}")
