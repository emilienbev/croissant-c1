"""
One chat call, whoever runs the model: Ollama's own API, any OpenAI-compatible endpoint,
or Gemini's own API (providers.py). The tool loop (c1_loop.py) talks in `Msg` and gets back
text deltas and one `Reply`; each backend turns that into what its API wants.

The C1's chat clients in Python (android ask/Chat.kt and the three beside it), for
eval_tools.py, as c1_prompt.py is its prompt. This was the server's chat.py until Ask's loop
moved to the C1 (2026-10-16): the C1's were ported from it, and the two are held equal by
the prompt tokens each round reports.

What each backend does by default is what the measured holds were run with: temperature
0, so a tool call is decoded the same way every time, and no reasoning stream on a
push-to-talk device. An entry can say otherwise (`temperature`, `reasoning`), and an
OpenAI-compatible model that refuses one of the two gets it dropped, once, with a line
in the log, rather than failing every hold.
"""

from __future__ import annotations

import base64
import json
import logging
import time
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any

import openai
from google.genai import types
from ollama import AsyncClient

from croissant_server.clients import gemini_client, openai_client
from croissant_server.providers import Entry

log = logging.getLogger(__name__)


@dataclass
class Call:
    name: str
    args: dict[str, Any]
    # The provider's id for the call, echoed back with its result.
    id: str = ""


@dataclass
class Msg:
    role: str                                   # system, user, assistant, tool
    text: str = ""
    images: list[bytes] = field(default_factory=list)   # JPEG, on a user message
    calls: list[Call] = field(default_factory=list)     # on an assistant message
    call: Call | None = None                    # on a tool message: the call it answers
    value: Any = None                           # on a tool message: the result unflattened
    # On an assistant message: the backend's own record of the turn, sent back as it
    # came (Gemini's parts carry thought signatures that must go back untouched).
    raw: Any = None


@dataclass(frozen=True)
class Usage:
    """One model call's counts: the prompt it read and what it wrote, with the seconds."""

    prompt_tokens: int
    prompt_s: float
    out_tokens: int
    out_s: float
    # Of the prompt, what the provider read from its cache (billed lower).
    cached_tokens: int = 0


@dataclass
class Reply:
    """What a streamed call ends with, after its text."""

    calls: list[Call]
    raw: Any = None
    usage: Usage | None = None


class ChatBackend:
    def __init__(self, entry: Entry, default_temperature: float | None = 0.0) -> None:
        self.entry = entry
        self.model = entry.model
        t = entry.options.get("temperature", default_temperature)
        self.temperature: float | None = None if t is None else float(t)
        r = entry.options.get("reasoning")
        self.reasoning: str | None = None if r is None else str(r)

    def stream(self, messages: list[Msg], tools: list[dict[str, Any]] | None
               ) -> AsyncIterator[str | Reply]:
        """Text deltas as they come, then one `Reply`. `tools` are `Tool.spec()`s."""
        raise NotImplementedError


def make_chat(entry: Entry) -> ChatBackend:
    kind = entry.provider.kind
    if kind == "ollama":
        return OllamaChat(entry)
    if kind == "openai":
        return OpenAIChat(entry)
    if kind == "gemini":
        return GeminiChat(entry)
    raise ValueError(f"{entry.id}: a {kind} provider cannot run a chat model")


def _b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


class OllamaChat(ChatBackend):
    """Ollama's own API: how Ask's prompts and the catalog's fixed ones were measured."""

    def __init__(self, entry: Entry) -> None:
        super().__init__(entry)
        self._client = AsyncClient(host=entry.provider.url) if entry.provider.url else AsyncClient()

    def _messages(self, messages: list[Msg]) -> list[dict[str, Any]]:
        out: list[dict[str, Any]] = []
        for m in messages:
            if m.role == "tool":
                out.append({"role": "tool", "tool_name": m.call.name if m.call else "",
                            "content": m.text})
                continue
            d: dict[str, Any] = {"role": m.role, "content": m.text}
            if m.images:
                d["images"] = m.images
            if m.calls:
                d["tool_calls"] = [{"function": {"name": c.name, "arguments": c.args}}
                                   for c in m.calls]
            out.append(d)
        return out

    async def stream(self, messages, tools):
        options = {} if self.temperature is None else {"temperature": self.temperature}
        # qwen3.5 and Gemma are reasoning models and would stream their thinking first.
        # On a push-to-talk device that is latency with nothing to show for it.
        think: bool | str = False if self.reasoning in (None, "none") else self.reasoning
        stream = await self._client.chat(model=self.model, messages=self._messages(messages),
                                         tools=tools, stream=True, think=think, options=options)
        calls: list[Call] = []
        usage: Usage | None = None
        async for part in stream:
            if part.done:
                usage = Usage(part.prompt_eval_count or 0, (part.prompt_eval_duration or 0) / 1e9,
                              part.eval_count or 0, (part.eval_duration or 0) / 1e9)
            m = part.message
            if m.content:
                yield m.content
            for c in m.tool_calls or []:
                calls.append(Call(c.function.name, dict(c.function.arguments)))
        yield Reply(calls, usage=usage)


class _Clock:
    """Timings for an API that reports tokens but not seconds: to first output, then to the end."""

    def __init__(self) -> None:
        self.t0 = time.monotonic()
        self.first: float | None = None

    def mark(self) -> None:
        if self.first is None:
            self.first = time.monotonic()

    def usage(self, prompt_tokens: int, out_tokens: int, cached_tokens: int = 0) -> Usage:
        end = time.monotonic()
        first = self.first or end
        return Usage(prompt_tokens, first - self.t0, out_tokens, end - first, cached_tokens)


class OpenAIChat(ChatBackend):
    """Any OpenAI-compatible chat completions endpoint: OpenAI, Groq, OpenRouter, LM Studio..."""

    # Sent unless the model refuses them; a refusal drops one for good (`_create`).
    OPTIONAL = ("temperature", "reasoning_effort", "stream_options")

    def __init__(self, entry: Entry) -> None:
        super().__init__(entry)
        self._client = openai_client(entry.provider)
        self._omit: set[str] = set()

    @staticmethod
    def _messages(messages: list[Msg]) -> list[dict[str, Any]]:
        out: list[dict[str, Any]] = []
        for m in messages:
            if m.role == "tool":
                out.append({"role": "tool", "tool_call_id": m.call.id if m.call else "",
                            "content": m.text})
            elif m.role == "assistant":
                d: dict[str, Any] = {"role": "assistant", "content": m.text or None}
                if m.calls:
                    d["tool_calls"] = [{"id": c.id, "type": "function", "function": {
                        "name": c.name, "arguments": json.dumps(c.args, ensure_ascii=False)}}
                        for c in m.calls]
                out.append(d)
            elif m.images:
                out.append({"role": m.role, "content": [{"type": "text", "text": m.text}] + [
                    {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + _b64(i)}}
                    for i in m.images]})
            else:
                out.append({"role": m.role, "content": m.text})
        return out

    async def _create(self, **kw: Any) -> Any:
        while True:
            try:
                return await self._client.chat.completions.create(
                    **{k: v for k, v in kw.items() if k not in self._omit})
            except openai.APIStatusError as e:
                said = str(e)
                drop = next((p for p in self.OPTIONAL if p in said and p in kw and p not in self._omit), None)
                if e.status_code not in (400, 422) or drop is None:
                    raise
                log.warning("%s refuses %s; sending it without from now on", self.entry.label(), drop)
                self._omit.add(drop)

    async def stream(self, messages, tools):
        kw: dict[str, Any] = dict(model=self.model, messages=self._messages(messages), stream=True,
                                  stream_options={"include_usage": True})
        if tools:
            kw["tools"] = tools
        if self.temperature is not None:
            kw["temperature"] = self.temperature
        if self.reasoning is not None:
            kw["reasoning_effort"] = self.reasoning
        clock = _Clock()
        stream = await self._create(**kw)
        # Tool calls arrive in pieces: the id and name first, the arguments as fragments
        # of a JSON string, each piece carrying the index of the call it belongs to.
        parts: dict[int, dict[str, str]] = {}
        usage = None
        async for chunk in stream:
            if chunk.usage:
                usage = chunk.usage
            for choice in chunk.choices:
                d = choice.delta
                if d.content:
                    clock.mark()
                    yield d.content
                for tc in d.tool_calls or []:
                    clock.mark()
                    p = parts.setdefault(tc.index, {"id": "", "name": "", "args": ""})
                    if tc.id:
                        p["id"] = tc.id
                    if tc.function and tc.function.name:
                        p["name"] += tc.function.name
                    if tc.function and tc.function.arguments:
                        p["args"] += tc.function.arguments
        calls = []
        for i, p in sorted(parts.items()):
            try:
                args = json.loads(p["args"] or "{}")
            except json.JSONDecodeError:
                log.warning("%s: unreadable arguments for %s: %r", self.entry.id, p["name"], p["args"][:200])
                args = {}
            calls.append(Call(p["name"], args if isinstance(args, dict) else {}, p["id"] or f"call_{i}"))
        cached = getattr(usage.prompt_tokens_details, "cached_tokens", None) if usage else None
        yield Reply(calls, usage=clock.usage(usage.prompt_tokens, usage.completion_tokens, cached or 0)
                    if usage else None)


class GeminiChat(ChatBackend):
    """
    Gemini through Google's own SDK. Its OpenAI layer would do for chat, but not for
    transcription or picture edits, so one Gemini speaks Gemini throughout.

    Temperature is left to the model unless the entry sets one: Google advises against
    lowering it on Gemini 3. `reasoning` is `none` (a thinking budget of 0, which only
    2.5 models accept) or a thinking level (`minimal`, `low`, `medium`, `high`).
    """

    def __init__(self, entry: Entry) -> None:
        super().__init__(entry, default_temperature=None)
        self._client = gemini_client(entry.provider)

    @staticmethod
    def _contents(messages: list[Msg]) -> tuple[str, list[types.Content]]:
        system: list[str] = []
        contents: list[types.Content] = []

        def add(role: str, parts: list[types.Part]) -> None:
            if not parts:
                return
            # Consecutive turns of one role go in one content: the function responses
            # of one round together, as the API wants them.
            if contents and contents[-1].role == role:
                contents[-1].parts.extend(parts)
            else:
                contents.append(types.Content(role=role, parts=parts))

        for m in messages:
            if m.role == "system":
                system.append(m.text)
            elif m.role == "user":
                add("user", ([types.Part.from_text(text=m.text)] if m.text else [])
                    + [types.Part.from_bytes(data=i, mime_type="image/jpeg") for i in m.images])
            elif m.role == "assistant":
                if m.raw:
                    add("model", list(m.raw))
                else:
                    add("model", ([types.Part.from_text(text=m.text)] if m.text else [])
                        + [types.Part(function_call=types.FunctionCall(
                            name=c.name, args=c.args, id=c.id or None)) for c in m.calls])
            elif m.role == "tool" and m.call is not None:
                v = m.value if isinstance(m.value, dict) else {"result": m.value if m.value is not None else m.text}
                add("user", [types.Part(function_response=types.FunctionResponse(
                    name=m.call.name, response=v, id=m.call.id or None))])
        return "\n\n".join(system), contents

    async def stream(self, messages, tools):
        system, contents = self._contents(messages)
        thinking = None
        if self.reasoning == "none":
            thinking = types.ThinkingConfig(thinking_budget=0)
        elif self.reasoning:
            thinking = types.ThinkingConfig(thinking_level=self.reasoning.upper())
        config = types.GenerateContentConfig(
            system_instruction=system or None,
            temperature=self.temperature,
            thinking_config=thinking,
            tools=[types.Tool(function_declarations=[types.FunctionDeclaration(
                name=t["function"]["name"], description=t["function"]["description"],
                parameters_json_schema=t["function"]["parameters"]) for t in tools])] if tools else None,
        )
        clock = _Clock()
        parts: list[types.Part] = []
        calls: list[Call] = []
        meta = None
        async for chunk in await self._client.aio.models.generate_content_stream(
                model=self.model, contents=contents, config=config):
            meta = chunk.usage_metadata or meta
            for cand in chunk.candidates or []:
                for part in (cand.content.parts if cand.content else None) or []:
                    parts.append(part)
                    if part.thought:
                        continue
                    if part.text:
                        clock.mark()
                        yield part.text
                    if part.function_call:
                        clock.mark()
                        fc = part.function_call
                        calls.append(Call(fc.name or "", dict(fc.args or {}), fc.id or ""))
                reason = cand.finish_reason
                if reason and reason not in (types.FinishReason.STOP, types.FinishReason.MAX_TOKENS):
                    log.warning("%s finished with %s", self.entry.id, reason)
        usage = clock.usage(meta.prompt_token_count or 0,
                            (meta.candidates_token_count or 0) + (meta.thoughts_token_count or 0),
                            meta.cached_content_token_count or 0) if meta else None
        yield Reply(calls, raw=parts, usage=usage)
