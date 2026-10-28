"""
Where each model the server uses runs: here on the Mac, or at an inference provider.

`providers.json` (gitignored, it holds keys) names the providers the server may reach.
It is edited on the server's own page (`/settings`, settings.py), which saves it and
reloads it in place, or by hand:

    {"providers": {"openai": {"key": "sk-..."}, "gemini": {"key": "..."},
                   "myserver": {"kind": "openai", "url": "http://100.x.y.z:8000/v1",
                                "title": "My server"}}}

A provider whose id is a preset's (below) needs only its key, or nothing at all when the
key is in the preset's environment variable; any other id says its `kind` and `url`.
`local` (this machine) and `ollama` are always there.

What each provider can run is its own API's list of models, sorted into the device's four
slots (catalog.py) and kept beside the file in providers.models.json, with whether its key
is on the provider's free tier. The device picks one
per slot among them (`GET /providers`) and sends it with each hold as `provider:model`
(the last part of a local model's path). A hold that picks none gets the server's
default: the flags (`--llm`, `--stt`, `--image-model`), else config. The options a model
runs with are its backend's defaults; a speech model's live words are the device's to
turn on or off (`partials` on the start frame).
"""

from __future__ import annotations

import json
import logging
import os
import re
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from .config import on_disk

log = logging.getLogger(__name__)

# What a backend speaks. `ollama` is Ollama's own API, as the measured prompts were run;
# `openai` is any OpenAI-compatible endpoint; `gemini` is Google's own API (its OpenAI
# layer has no transcription and no picture edits); `local` runs in this process.
KINDS = ("local", "ollama", "openai", "gemini")

# The device's choices, one list each.
SLOTS = ("chat", "stt", "text_to_image", "image_to_image")


# What providers.models.json is written as; bumped when catalog.py lists differently,
# so a file from before is listed again rather than read.
LISTING_VERSION = 4


@dataclass(frozen=True)
class Listing:
    """
    What a provider offers the key it was asked with (catalog.py): its models by slot,
    whether that key is on its free tier, and for a provider on this machine what each
    model takes in memory and which think.

    A free tier's limit is requests a day: `resets` is the time zone whose midnight
    starts its day (an IANA name), and `shares` says the provider may use what is sent.
    """
    models: dict[str, list[str]]
    free: bool = False
    sizes: dict[str, int] = field(default_factory=dict)
    thinking: list[str] = field(default_factory=list)
    resets: str | None = None
    shares: bool = False


@dataclass(frozen=True)
class Preset:
    kind: str
    url: str | None
    key_env: tuple[str, ...]
    title: str
    # models.dev's name for it (prices.py); None where models.dev has no prices for it.
    prices: str | None = None


PRESETS: dict[str, Preset] = {
    "local": Preset("local", None, (), "this server"),
    "ollama": Preset("ollama", None, (), "Ollama"),  # None: OLLAMA_HOST, else localhost
    "lmstudio": Preset("openai", "http://127.0.0.1:1234/v1", (), "LM Studio"),
    "openai": Preset("openai", "https://api.openai.com/v1", ("OPENAI_API_KEY",), "OpenAI", "openai"),
    "gemini": Preset("gemini", None, ("GEMINI_API_KEY", "GOOGLE_API_KEY"), "Google Gemini", "google"),
    "groq": Preset("openai", "https://api.groq.com/openai/v1", ("GROQ_API_KEY",), "Groq", "groq"),
    "openrouter": Preset("openai", "https://openrouter.ai/api/v1", ("OPENROUTER_API_KEY",),
                         "OpenRouter", "openrouter"),
    "mistral": Preset("openai", "https://api.mistral.ai/v1", ("MISTRAL_API_KEY",), "Mistral", "mistral"),
    "together": Preset("openai", "https://api.together.xyz/v1", ("TOGETHER_API_KEY",),
                       "Together", "togetherai"),
    "deepseek": Preset("openai", "https://api.deepseek.com/v1", ("DEEPSEEK_API_KEY",),
                       "DeepSeek", "deepseek"),
}


@dataclass(frozen=True)
class Provider:
    id: str
    kind: str
    url: str | None
    key: str | None
    title: str
    # Where the key came from: "file", or the environment variable that held it.
    key_from: str | None = None
    # Whether it can answer: it needs no key, or has one.
    ready: bool = True
    # Its preset's name at models.dev (prices.py).
    prices: str | None = None

    @property
    def local(self) -> bool:
        """This machine, or Ollama: it costs nothing."""
        return self.kind in ("local", "ollama")


@dataclass(frozen=True)
class Entry:
    """One model at one provider, as a hold runs it."""
    id: str
    name: str
    provider: Provider
    model: str
    options: dict[str, Any] = field(default_factory=dict)

    def label(self) -> str:
        """For the log: which model, at which provider."""
        return f"{self.id} ({self.provider.title}, {self.model})"


class ProvidersError(ValueError):
    pass


def _key(preset: Preset | None, given: Any) -> tuple[str | None, str | None]:
    if isinstance(given, str) and given.strip():
        return given.strip(), "file"
    for env in preset.key_env if preset else ():
        if os.environ.get(env):
            return os.environ[env], env
    return None, None


def _provider(pid: str, spec: dict[str, Any]) -> Provider:
    preset = PRESETS.get(pid) or PRESETS.get(str(spec.get("preset") or ""))
    kind = spec.get("kind") or (preset.kind if preset else None)
    if kind not in KINDS:
        raise ProvidersError(f"provider {pid!r}: kind must be one of {', '.join(KINDS)}")
    url = spec.get("url") or (preset.url if preset else None)
    if kind == "openai" and not url:
        raise ProvidersError(f"provider {pid!r}: an OpenAI-compatible provider needs a url")
    title = spec.get("title") or (preset.title if preset else pid)
    key, key_from = _key(preset, spec.get("key"))
    # A hosted preset is nothing without its key; a server of your own may need none.
    needs_key = kind == "gemini" or bool(preset and preset.key_env)
    return Provider(pid, kind, url.rstrip("/") if url else None, key, title, key_from,
                    ready=bool(key) or not needs_key, prices=preset.prices if preset else None)


_ID = re.compile(r"^[A-Za-z0-9._:/-]{1,80}$")


def short(model: str) -> str:
    """A model by its last part: a local path's folder, a hosted model without its maker."""
    return model.rstrip("/").rsplit("/", 1)[-1]


class Providers:
    """
    The providers file, what each provider lists, and the server's defaults. Read at
    startup; `apply` swaps in a new file (the settings page's save) and bumps `version`,
    which is what tells the cached clients and backends to make themselves again.
    """

    def __init__(self, path: Path | None, fallback: dict[str, tuple[str, str]],
                 flags: dict[str, str | None], local: dict[str, list[str]]) -> None:
        self.path = path
        self.fallback = fallback
        self.flags = flags
        # What this machine runs itself, as a listing: per slot, and what each takes on disk.
        self.local = Listing(local, sizes={m: n for ms in local.values() for m in ms if (n := on_disk(m))})
        self.raw: dict[str, Any] = {}
        self.providers: dict[str, Provider] = {}
        # Provider id -> what its API last listed (catalog.py).
        self.listed: dict[str, Listing] = {}
        self.version = 0

    @classmethod
    def load(cls, path: Path | None, *, fallback: dict[str, tuple[str, str]],
             pick: dict[str, str | None] | None = None,
             local: dict[str, list[str]] | None = None) -> Providers:
        """
        Read `path` (missing is fine: Ollama and this machine only).

        `fallback` is, per slot, the (provider, model) a hold that picks nothing gets:
        Ollama and `llm_model` for chat, local Whisper for STT (config.py). `pick` is the
        flags (`--llm`, `--stt`): a `provider:model` id, or a model for the fallback's
        provider, as the flags always meant. `local` is, per slot, what this machine runs
        itself.
        """
        raw: dict[str, Any] = {}
        if path is not None and path.exists():
            try:
                raw = json.loads(path.read_text())
            except json.JSONDecodeError as e:
                raise ProvidersError(f"{path}: {e}") from None
            if not isinstance(raw, dict):
                raise ProvidersError(f"{path}: expected a JSON object")
        self = cls(path, fallback, pick or {}, local or {})
        self.listed = self._read_listed()
        self.apply(raw)
        for p in self.providers.values():
            if not p.ready:
                log.warning("provider %s has no key: set it on the settings page or in %s",
                            p.id, " or ".join(PRESETS[p.id].key_env) if p.id in PRESETS else "the file")
        if any(raw.get(slot) for slot in SLOTS):
            log.info("%s: its per-slot lists are no longer read; the device picks among what "
                     "each provider offers", path)
        return self

    def apply(self, raw: dict[str, Any]) -> None:
        """Make `raw` (the file's contents) the live configuration, or raise and change nothing."""
        providers = {pid: _provider(pid, {}) for pid in ("local", "ollama")}
        # A preset whose key is in the environment is there without a line in the file.
        for pid, preset in PRESETS.items():
            if pid not in providers and any(os.environ.get(e) for e in preset.key_env):
                providers[pid] = _provider(pid, {})
        for pid, spec in (raw.get("providers") or {}).items():
            if not isinstance(spec, dict):
                raise ProvidersError(f"provider {pid!r}: expected an object")
            if not _ID.match(pid):
                raise ProvidersError(f"provider {pid!r}: an id is letters, digits and ._:-")
            providers[pid] = _provider(pid, spec)
        self.raw = {"providers": raw.get("providers") or {}}
        self.providers = providers
        self.version += 1

    def save(self, raw: dict[str, Any]) -> None:
        """
        Check `raw`, make it live and write it to the file (owner-only: it holds keys).
        The settings page never sees a key: one it left alone arrives as null (or not at
        all) and keeps the file's; an empty one removes it.
        """
        raw = {"providers": json.loads(json.dumps(raw.get("providers") or {}))}
        old = self.raw.get("providers") or {}
        for pid, spec in raw["providers"].items():
            if isinstance(spec, dict) and spec.get("key") is None:
                spec.pop("key", None)
                kept = (old.get(pid) or {}).get("key")
                if kept:
                    spec["key"] = kept
            elif isinstance(spec, dict) and spec.get("key") == "":
                del spec["key"]
        self.apply(raw)
        if self.path is None:
            return
        tmp = self.path.with_suffix(".json.tmp")
        # Owner-only from its first byte, not after: it holds keys.
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(json.dumps(raw, ensure_ascii=False, indent=2) + "\n")
        os.chmod(tmp, 0o600)   # a temp file left by an older run keeps its own mode
        tmp.replace(self.path)
        log.info("providers saved to %s", self.path)

    # ---- what each provider lists ------------------------------------------------

    def _listed_path(self) -> Path | None:
        return self.path.with_suffix(".models.json") if self.path else None

    def _read_listed(self) -> dict[str, Listing]:
        path = self._listed_path()
        try:
            d = json.loads(path.read_text()) if path and path.exists() else {}
        except (OSError, json.JSONDecodeError):
            return {}
        # Only listings as this catalog.py makes them; an older file is asked again.
        if not isinstance(d, dict) or d.get("version") != LISTING_VERSION:
            return {}
        return {pid: Listing(**v) for pid, v in (d.get("listed") or {}).items()}

    def keep(self, pid: str, listing: Listing) -> None:
        """What a provider's API just listed (catalog.py)."""
        self.listed[pid] = listing
        path = self._listed_path()
        if path is not None:
            path.write_text(json.dumps({"version": LISTING_VERSION,
                                        "listed": {k: asdict(v) for k, v in self.listed.items()}},
                                       ensure_ascii=False, indent=1) + "\n")

    def listing(self, p: Provider) -> Listing | None:
        """What a provider offers: this machine's own, or what its API last listed."""
        return self.local if p.kind == "local" else self.listed.get(p.id)

    def free_tier(self, pid: str) -> bool:
        """Whether the provider's key is on its free tier, as its listing found."""
        listing = self.listed.get(pid)
        return listing is not None and listing.free

    def unlisted(self) -> list[Provider]:
        """The providers that can answer and have not been asked for their models yet."""
        return [p for p in self.providers.values()
                if p.kind != "local" and p.ready and p.id not in self.listed]

    # ---- what a hold runs ----------------------------------------------------------

    def entry(self, pid: str, model: str) -> Entry | None:
        p = self.providers.get(pid)
        if p is None or not model:
            return None
        return Entry(f"{pid}:{short(model) if p.kind == 'local' else model}", short(model), p, model)

    def entries(self, slot: str) -> list[Entry]:
        """
        What the device may pick for `slot`, grouped by provider: this machine's own, then
        each provider that can answer. The default is always among them, with its provider.
        """
        groups: dict[str, list[str]] = {}
        for pid, p in self.providers.items():
            if p.ready:
                listing = self.listing(p)
                groups[pid] = list(listing.models.get(slot, [])) if listing is not None else []
        d = self.default(slot)
        if d is not None and d.model not in groups.get(d.provider.id, []):
            groups.setdefault(d.provider.id, []).insert(0, d.model)
        return [e for pid, models in groups.items() for m in models if (e := self.entry(pid, m))]

    def default(self, slot: str) -> Entry | None:
        """What a hold that picks nothing gets: the flag's, else config's."""
        flag = self.flags.get(slot)
        if flag:
            e = self.find(slot, flag)
            if e is not None:
                return e
        if slot not in self.fallback:
            return None
        pid, model = self.fallback[slot]
        return self.entry(pid, flag or model)

    def find(self, slot: str, entry_id: str | None) -> Entry | None:
        """`provider:model` as the device sends it, for any provider the server has."""
        pid, sep, model = (entry_id or "").partition(":")
        p = self.providers.get(pid)
        if not sep or p is None:
            return None
        if p.kind == "local":
            mine = [*self.local.models.get(slot, []), *([self.fallback[slot][1]] if slot in self.fallback else [])]
            model = next((m for m in mine if short(m) == model), model)
        return self.entry(pid, model)

    def pick(self, slot: str, entry_id: str | None) -> Entry | None:
        """The model the device asked for, or the default when it named none or one gone."""
        if entry_id:
            e = self.find(slot, entry_id)
            if e is not None:
                return e
            log.warning("%s: no provider for %r, using the default", slot, entry_id)
        return self.default(slot)

    def public(self, slot: str, e: Entry) -> dict[str, Any]:
        """
        What the device is told of one model it may pick (`GET /providers`; costs.py adds
        what it costs). No keys.
        """
        out: dict[str, Any] = {"id": e.id, "name": e.name, "provider": e.provider.title,
                               "local": e.provider.local, "free": self.free_tier(e.provider.id)}
        if slot == "stt":
            out["partials"] = e.provider.kind == "local"
        if out["free"]:
            listing = self.listed[e.provider.id]
            if listing.resets:
                out["resets"] = listing.resets
            if listing.shares:
                out["shares"] = True
        return out
