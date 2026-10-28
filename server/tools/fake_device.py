"""
What the R1 will do with one of its tool calls, in memory, for eval_tools.py: Ask's
loop without the device. Mirrors the device's rules (ids, colours, the
50-reminder cap, default event length) closely enough that the model's behaviour
can be judged; the Kotlin implementation is the real one.
"""

from __future__ import annotations

import secrets
import time
from datetime import datetime, timedelta
from typing import Any

PALETTE = ["orange", "lavender", "pink", "cyan", "violet", "green", "red", "yellow"]
MAX_REMINDERS = 50


class FakeDevice:
    def __init__(self, now: str | None = None) -> None:
        self.reminders: list[dict[str, Any]] = []
        self.events: list[dict[str, Any]] = []
        self.timers: list[dict[str, Any]] = []
        self._colour = {"r": 0, "e": 0, "t": 0}
        self.now = now
        self.calls: list[tuple[str, dict[str, Any]]] = []

    # -- plumbing -----------------------------------------------------------------

    def _clock(self) -> datetime:
        return datetime.fromisoformat(self.now) if self.now else datetime.now().astimezone()

    def _new(self, kind: str) -> tuple[str, str]:
        c = PALETTE[self._colour[kind] % len(PALETTE)]
        self._colour[kind] += 1
        return f"{kind}_{secrets.token_hex(3)}", c

    @staticmethod
    def _find(items: list[dict[str, Any]], id_: str) -> dict[str, Any] | None:
        return next((i for i in items if i["id"] == id_), None)

    def run(self, name: str, args: dict[str, Any]) -> Any:
        self.calls.append((name, args))
        fn = getattr(self, "t_" + name, None)
        if fn is None:
            return {"error": f"unknown tool {name}"}
        try:
            return fn(**args)
        except TypeError as e:
            return {"error": f"bad arguments: {e}"}

    # -- agenda: reminders and events, the date decides ---------------------------

    def t_agenda_list(self, **rng: str) -> Any:
        today = self._clock().date()
        lo = rng.get("from") or today.isoformat()
        hi = rng.get("to") or (today + timedelta(days=14)).isoformat()
        return {"reminders": list(self.reminders),
                "events": [dict(e) for e in self.events if e["end"][:10] >= lo[:10] and e["start"][:10] <= hi[:10]]}

    def t_agenda_add(self, title: str, at: str | None = None, end: str | None = None,
                     location: str | None = None, notes: str | None = None) -> Any:
        title = (title or "").strip()
        if not title:
            return {"error": "title is required"}
        if at is None:
            if len(self.reminders) >= MAX_REMINDERS:
                return {"error": "the reminder list is full (50); delete or complete some first"}
            id_, colour = self._new("r")
            r = {"id": id_, "title": title[:60], "color": colour,
                 "done": False, "createdAt": self._clock().isoformat(timespec="seconds")}
            self.reminders.append(r)
            return r
        all_day = len(at) == 10
        if end is None:
            end = at if all_day else (datetime.fromisoformat(at) + timedelta(hours=1)).isoformat(timespec="minutes")
        id_, colour = self._new("e")
        e = {"id": id_, "title": title[:60], "start": at, "end": end,
             "allDay": all_day, "location": location, "notes": notes, "color": colour}
        self.events.append(e)
        return e

    def t_agenda_update(self, id: str, **changes: Any) -> Any:
        if id.startswith("e_"):
            e = self._find(self.events, id)
            if e is None:
                return {"error": f"no event {id}"}
            if changes.get("done") is not None:
                return {"error": "an event is not ticked off; delete it instead"}
            for k, key in (("title", "title"), ("at", "start"), ("end", "end"), ("location", "location"), ("notes", "notes")):
                if changes.get(k) is not None:
                    e[key] = changes[k]
            return e
        r = self._find(self.reminders, id)
        if r is None:
            return {"error": f"no reminder {id}"}
        if changes.get("at") is not None:
            self.reminders.remove(r)
            return self.t_agenda_add(changes.get("title") or r["title"], changes["at"], changes.get("end"),
                                     changes.get("location"), changes.get("notes"))
        for k in ("title", "done"):
            if changes.get(k) is not None:
                r[k] = changes[k]
        return r

    def t_agenda_delete(self, id: str) -> Any:
        items = self.events if id.startswith("e_") else self.reminders
        it = self._find(items, id)
        if it is None:
            return {"error": f"no {'event' if id.startswith('e_') else 'reminder'} {id}"}
        items.remove(it)
        return {"ok": True}

    # -- timers -------------------------------------------------------------------

    def t_timers_list(self) -> Any:
        now = time.time()
        return [{**t, "secondsLeft": max(0, int(t["_ends"] - now))} for t in self.timers]

    def t_timers_add(self, seconds: int, label: str | None = None) -> Any:
        if not isinstance(seconds, int) or seconds <= 0:
            return {"error": "seconds must be a positive integer"}
        id_, colour = self._new("t")
        t = {"id": id_, "label": label, "seconds": seconds, "color": colour,
             "_ends": time.time() + seconds}
        self.timers.append(t)
        return {k: v for k, v in t.items() if not k.startswith("_")}

    def t_timers_cancel(self, id: str) -> Any:
        t = self._find(self.timers, id)
        if t is None:
            return {"error": f"no timer {id}"}
        self.timers.remove(t)
        return {"ok": True}
