"""
Ask's answer with the tool loop, on whichever chat backend the entry names (c1_chat.py).

The C1's loop in Python (android ask/Loop.kt), for eval_tools.py, as c1_prompt.py is its
prompt. This was the server's llm.py until the loop moved to the C1 (2026-10-16); Loop.kt
was ported from it, and the two are held equal by the prompt tokens each round reports.
"""

from __future__ import annotations

import json
import logging
import re
from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass
from typing import Any

from c1_chat import Msg, Reply, Usage, make_chat
from croissant_server.providers import Entry

log = logging.getLogger(__name__)

# Markdown the model may still emit, removed per delta. Asking for "plain text" in the
# prompt costs tool calls (android data/AskPrompt.kt), so this is done here instead. Bold/italic
# markers and inline code can be dropped anywhere; heading marks and list bullets only
# open a line, which a streamed delta may or may not start, hence the lookbehind on a
# newline or the start of the delta.
_INLINE = re.compile(r"\*{1,3}|`+|_{2}")
_LINE_START = re.compile(r"(?:(?<=\n)|^)[ \t]*(?:#{1,6}[ \t]+|[-*]\s+|\d+\.\s+)")


def scrub(text: str) -> str:
    return _LINE_START.sub("", _INLINE.sub("", text))

# Rounds of "model asks for tools, tools answer" before the answer is forced. Two is
# the normal case (call, then confirm); more is the model chaining a list with an
# update. Beyond this it is looping.
MAX_TOOL_ROUNDS = 4

# Earlier turns of a conversation the model is shown, as on the C1 (android
# data/Conversations.kt, `PRIOR`). Bounds prefill; a long Ask conversation still follows
# on from its most recent turns.
PRIOR_TURNS = 6


@dataclass(frozen=True)
class Tool:
    """One tool on offer, a spec as the C1 sends it (c1_prompt.py, `hold`)."""

    name: str
    description: str
    parameters: dict[str, Any]

    def spec(self) -> dict[str, Any]:
        """The shape Ollama and OpenAI take as `tools`; Gemini's is made from it (c1_chat.py)."""
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.parameters,
            },
        }


Execute = Callable[[Tool, dict[str, Any]], Awaitable[Any]]


# One model call's counts: the prompt it read, what it wrote. Ollama reports the seconds
# itself; for the other backends they are timed here, to first output and to the end.
RoundStats = Usage


def rates(rounds: list[RoundStats]) -> tuple[int, float, int, float]:
    """A hold's totals: prompt tokens, prefill tok/s, output tokens, decode tok/s."""
    pt, ps = sum(r.prompt_tokens for r in rounds), sum(r.prompt_s for r in rounds)
    ot, os_ = sum(r.out_tokens for r in rounds), sum(r.out_s for r in rounds)
    return pt, (pt / ps if ps else 0.0), ot, (ot / os_ if os_ else 0.0)


def as_messages(prior: Any) -> list[dict[str, Any]]:
    """
    A conversation so far, `[{"q":..., "a":...}]` oldest first, as chat turns: text only.
    Tool payloads are left out on purpose. A small model degrades with context, lists are
    tiny and re-fetchable, so "delete that one" is answered with a fresh list call rather
    than an id remembered from three turns ago.
    """
    out: list[dict[str, Any]] = []
    if not isinstance(prior, list):
        return out
    for t in prior[-PRIOR_TURNS:]:
        if not isinstance(t, dict) or not isinstance(t.get("q"), str):
            continue
        out.append({"role": "user", "content": t["q"]})
        out.append({"role": "assistant", "content": str(t.get("a") or "")})
    return out


class Llm:
    def __init__(self, entry: Entry) -> None:
        self.entry = entry
        self.model = entry.model
        self.backend = make_chat(entry)

    async def answer(
        self,
        question: str,
        *,
        system: str,
        prior: list[dict[str, Any]],
        execute: Execute,
        tools: list[Tool],
        image: bytes | None = None,
        stats: list[RoundStats] | None = None,
    ) -> AsyncIterator[str]:
        """
        Yield answer text as it is produced.

        `prior` is the conversation so far as chat messages (`as_messages`). `tools` is
        what the hold offers, exactly and in order, none when empty. `execute` runs one
        tool call and returns a JSON-able result.

        A vision question (image given) comes with no tools: the C1 offers none.
        ~~Only the transform~~ (2026-09-18 to 2026-09-30): with a real photo in front
        of it the model called whatever it was offered for plain questions, the
        transform 6/6 and the agenda or the web as readily (server/README.md, "The
        prompt"). A picture from a photo is the Transform button's.

        The first round streams live: a plain question is answered there and the
        model, told to call first and confirm after, rarely chatters before a call.
        `stats`, when given, gets one `RoundStats` per round, from the counts the backend
        reports (and the timings, which Ollama reports itself and c1_chat.py times otherwise).

        Later rounds only happen after tools ran; their text is held back and sent
        only if the round ends without another call, because in a round that does
        call again the text is the model thinking aloud ("let me check the
        calendar"), which the device must not show.
        """
        history = [Msg(m["role"], m["content"]) for m in prior]
        messages = [Msg("system", system), *history,
                    Msg("user", question, images=[image] if image is not None else [])]
        known = {t.name: t for t in tools}
        specs = [t.spec() for t in tools] or None
        sent_any = False

        for round_no in range(MAX_TOOL_ROUNDS + 1):
            # Last round: no tools offered, so the model has to answer in words.
            offered = specs if round_no < MAX_TOOL_ROUNDS else None
            content = ""
            reply = Reply([])
            live = round_no == 0
            async for ev in self.backend.stream(messages, offered):
                if isinstance(ev, Reply):
                    reply = ev
                    continue
                if live:
                    if content == "" and sent_any:
                        yield " "
                    yield ev
                content += ev
            if reply.usage is not None and stats is not None:
                stats.append(reply.usage)
            calls = reply.calls
            if live and content:
                sent_any = True
            if not calls:
                if not live and content:
                    yield (" " if sent_any else "") + content
                return

            messages.append(Msg("assistant", content, calls=calls, raw=reply.raw))
            for c in calls:
                tool = known.get(c.name)
                if tool is None:
                    result: Any = {"error": f"no such tool {c.name}"}
                    log.warning("model called unknown tool %s", c.name)
                else:
                    log.info("tool %s(%s)", c.name, json.dumps(c.args, ensure_ascii=False))
                    try:
                        result = await execute(tool, c.args)
                    except Exception as e:  # a tool must never take the answer down
                        log.exception("tool %s failed", c.name)
                        result = {"error": str(e)}
                # A result that is already text goes in as it is. The search digest is
                # written to be read (search.py, `format`), and json.dumps would wrap
                # it in quotes and turn every newline into \n, which spends tokens on
                # punctuation a small model then has to look past.
                messages.append(Msg("tool", result if isinstance(result, str)
                                    else json.dumps(result, ensure_ascii=False),
                                    call=c, value=result))
