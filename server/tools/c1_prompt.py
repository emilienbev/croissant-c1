"""
Ask's prompt put together the C1's way (android data/AskPrompt.kt), in Python, for the
tool that stands in for the device's loop: eval_tools.py. Nothing else: the server never
imports it, and since 2026-10-16 never sees a prompt.

A hold's system prompt is its blocks joined with a blank line, an empty one left out:

1. the variant's text, trimmed of spaces, tabs and line ends at both ends;
2. an Ask hold: `parts.tools` and a line per built-in tool on offer, then, when an app
   offers any, `parts.apps` and a line per app tool; a photo hold: the photo rules for
   the model instead (`photo`, by the seed the catalog suggests, else its default), and
   no tools at all;
3. the clock line (`parts.clock`), always last.

A tool's line is `- name: description`, the description on one line. The built-in tools
are the catalog's in file order, a server one (`"runs": "server"`) only when the server
says it runs it (`GET /providers`' `tools`; None before any list: all of them), then each
app's in the order given, named `<app id>_<tool name>`. An app's tool cannot take a
reserved name (`reserved`, as Atelier), and a name already on offer is not offered twice.

server/tests/test_c1_prompt.py checks this against the holds the C1's own test builds
(android/app/src/test/resources/ask/prompts.golden.json), byte for byte.
"""

from __future__ import annotations

import json
import re
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

CATALOG = Path(__file__).resolve().parents[2] / "android/app/src/main/assets/ask/catalog.json"

DEFAULT = "default"

# English whatever the machine's locale, as the C1 writes them (Locale.ENGLISH).
DAYS = ("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
MONTHS = ("January", "February", "March", "April", "May", "June", "July", "August",
          "September", "October", "November", "December")

# What a variant and the photo rules are trimmed of: only these, so Kotlin's trim and
# Python's strip cannot differ on other whitespace.
TRIM = " \t\r\n"
RUN = re.compile(r"[ \t\r\n]+")

# The device tool `tape_ask` reads its tape with (android Core.kt, Atelier's reserved names).
TAPE_READ = "tape_read"


def load_catalog() -> dict[str, Any]:
    return json.loads(CATALOG.read_text(encoding="utf-8"))


def reserved(catalog: dict[str, Any]) -> set[str]:
    """The names an app's tool cannot take: every built-in's and `tape_read`."""
    return {t["name"] for t in catalog["tools"]} | {TAPE_READ}


def suggested(catalog: dict[str, Any], entry: str | None) -> str:
    """The seed the catalog suggests for `entry` (`provider:model` or a model), matched on the model, lowercased; else "default"."""
    if entry is None:
        return DEFAULT
    model = (entry.split(":", 1)[1] if ":" in entry else entry).lower()
    return next((name for prefix, name in catalog.get("suggest", {}).items()
                 if model.startswith(prefix) and name in catalog["prompts"]), DEFAULT)


def clock(now: datetime, template: str) -> str:
    """The clock line: `{date}`, `{offset}` (never Z) and `{week}`, the next seven days, filled from `now`."""
    z = now.strftime("%z")
    week = ", ".join(f"{DAYS[d.weekday()]} {d:%Y-%m-%d}" for d in (now + timedelta(days=n) for n in range(1, 8)))
    return (template.replace("{date}", f"{DAYS[now.weekday()]} {now:%d} {MONTHS[now.month - 1]} {now:%Y, %H:%M}")
            .replace("{offset}", f"{z[:3]}:{z[3:5]}").replace("{week}", week))


def line(spec: dict[str, Any]) -> str:
    return f"- {spec['name']}: " + RUN.sub(" ", spec["description"]).strip(" ")


def hold(catalog: dict[str, Any], *, entry: str | None, now: datetime, photo: bool,
         server_tools: set[str] | list[str] | None, apps: list[dict[str, Any]],
         variant: str | None = None, text: str | None = None) -> dict[str, Any]:
    """
    One hold's `variant`, `system` and `tools` (the specs sent, `[]` for a photo hold).

    `variant` is the variant's name, else the seed suggested for `entry`; `text` its text,
    else the catalog's seed of that name. `apps` is each enabled app's tools in order,
    `{"id", "name", "tool": {name, description, parameters}}`. `now` has an offset.
    """
    name = variant or suggested(catalog, entry)
    body = (catalog["prompts"][name] if text is None else text).strip(TRIM)
    parts = catalog["parts"]
    blocks = [body]
    specs: list[dict[str, Any]] = []
    if photo:
        rules = catalog["photo"].get(suggested(catalog, entry), catalog["photo"].get(DEFAULT, ""))
        blocks.append(rules.strip(TRIM))
    else:
        # Each with the name of its app, None for a built-in one (AskPrompt.kt's `ToolSpec.app`).
        builtin = [({"name": t["name"], "description": t["description"], "parameters": t["parameters"]}, None)
                   for t in catalog["tools"]
                   if t.get("runs") != "server" or server_tools is None or t["name"] in server_tools]
        theirs = [({"name": f"{a['id']}_{a['tool']['name']}",
                    "description": f"{a['tool']['description']} (the {a['name']} app)",
                    "parameters": a["tool"]["parameters"]}, a["name"]) for a in apps]
        taken = reserved(catalog)
        offered: list[tuple[dict[str, Any], str | None]] = []
        for t, app in builtin + [(t, app) for t, app in theirs if t["name"] not in taken]:
            if t["name"] not in {s["name"] for s, _ in offered}:
                offered.append((t, app))
        specs = [t for t, _ in offered]
        own = [t for t, app in offered if app is None]
        others = [t for t, app in offered if app is not None]
        if own:
            blocks.append("\n".join([parts["tools"], *map(line, own)]))
        if others:
            blocks.append("\n".join([parts["apps"], *map(line, others)]))
    blocks.append(clock(now, parts["clock"]))
    return {"variant": name, "system": "\n\n".join(b for b in blocks if b), "tools": specs}
