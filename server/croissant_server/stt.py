"""Speech-to-text, on the backend the hold's STT entry names (providers.py): Whisper here,
or a provider's transcription.

Whisper (whisper-large-v3-turbo on MLX) was chosen over Nemotron on 2026-09-13 by
replaying real R1 captures through both: Nemotron transcribed one of four correctly,
Whisper four of four.

Whisper is not a streaming model. Partials are produced by re-transcribing the
whole utterance so far every PARTIAL interval; they may rewrite themselves as the
sentence grows, which is accepted - a partial that changes into the right words
beats one that grows monotonically into the wrong ones. One pass over 5-10 s of
audio costs ~0.2 s on this Mac, ~1 s at 20 s. At a provider each pass is a billed
call, so they are on by default here and off at a provider, where the words arrive at
release; the device can turn them on or off per hold (`partials` on the start frame).

Whisper hallucinates on silence - it returns "Thank you." for digital zeros and
for room tone, with a no-speech probability of 0.0, so it cannot gate itself.
Utterances are gated on measured level before any backend sees them, a provider's
included: it is the same kind of model, and a gated hold costs no call.
"""

from __future__ import annotations

import asyncio
import io
import logging
import time
from concurrent.futures import Executor
from typing import Any

import numpy as np

from .capture import Metrics, measure, write_wav
from .clients import gemini_client, openai_client
from .config import APPLE_SILICON
from .ledger import Ledger
from .providers import Entry, Providers

log = logging.getLogger(__name__)

SAMPLE_RATE = 16_000
BYTES_PER_SECOND = SAMPLE_RATE * 2

# The languages a hold may name in `from` (the C1's Translator, android data/Translations.kt,
# the same words), by the code Whisper takes as its `language`. A limited set on purpose:
# each is one Whisper, qwen and the Mac all handle well.
LANGUAGES = {
    "en": "English",
    "fr": "French",
    "de": "German",
    "it": "Italian",
    "es": "Spanish",
    "ja": "Japanese",
}

# A recording is cut into pieces this long for a backend that gives timestamps
# (transcription.py), and this long for one that does not, where each piece becomes
# one segment: thirty seconds is what a tape question's blocks are anyway (the C1's
# ask/Calls.kt).
PIECE_S = 600
UNTIMED_PIECE_S = 30


def wav(pcm: bytes) -> bytes:
    buf = io.BytesIO()
    write_wav(buf, pcm)
    return buf.getvalue()


class SttBackend:
    """One STT entry. `transcribe` is a hold's pass; `segments` a piece of a recording."""

    partials_default = False
    timestamps = False

    def __init__(self, entry: Entry, language: str | None) -> None:
        self.entry = entry
        self.language = language
        self.partials = bool(entry.options.get("partials", self.partials_default))

    @property
    def piece_s(self) -> int:
        return PIECE_S if self.timestamps else UNTIMED_PIECE_S

    async def transcribe(self, pcm: bytes, language: str | None = None) -> str:
        raise NotImplementedError

    async def counted(self, pcm: bytes, language: str | None = None) -> tuple[str, dict[str, float] | None]:
        """
        `transcribe`, with what the provider counted for it (`in`, `out` tokens) where it
        says; None where it does not, and the meter estimates (`MeteredStt`).
        """
        return await self.transcribe(pcm, language), None

    async def segments(self, pcm: bytes, offset_s: float) -> list[dict[str, Any]]:
        """A piece of a recording as (start, end, text) segments, in seconds into the whole."""
        text = await self.transcribe(pcm)
        end = offset_s + len(pcm) / BYTES_PER_SECOND
        return [{"start": round(offset_s, 2), "end": round(end, 2), "text": text}] if text else []


class LocalWhisper(SttBackend):
    """Whisper on MLX, in this process for holds and in the worker for recordings."""

    partials_default = True
    timestamps = True

    def __init__(self, entry: Entry, language: str | None, pool: Executor | None) -> None:
        super().__init__(entry, language)
        self.pool = pool
        log.info("loading STT model %s ...", entry.model)
        t0 = time.monotonic()
        # mlx_whisper loads and caches the model on first use; pay that here rather
        # than on the first real utterance.
        self._pass(b"\x00\x00" * SAMPLE_RATE, None)
        log.info("STT ready in %.1fs (lang=%s)", time.monotonic() - t0, language or "auto")

    # MLX streams are THREAD-LOCAL: driving the model from asyncio.to_thread aborts
    # the process with "There is no Stream(gpu, 0) in current thread". Inference
    # therefore runs inline on the event loop, blocking it for the duration of a
    # pass (~0.2-1 s). Incoming frames queue in the socket buffer meanwhile.
    def _pass(self, pcm: bytes, language: str | None) -> str:
        import mlx_whisper  # only where a local Whisper is configured

        audio = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
        result = mlx_whisper.transcribe(
            audio,
            path_or_hf_repo=self.entry.model,
            language=language or self.language,
            fp16=True,
            # Each partial is a fresh pass over the whole buffer; there is no
            # "previous" to condition on, and conditioning invites repetition loops.
            condition_on_previous_text=False,
        )
        return result["text"].strip()

    async def transcribe(self, pcm: bytes, language: str | None = None) -> str:
        return self._pass(pcm, language)

    async def segments(self, pcm: bytes, offset_s: float) -> list[dict[str, Any]]:
        # An hour of audio is minutes of turbo, so recordings go to the worker process
        # with its own copy of the model (stt_worker.py) and the holds carry on.
        from .stt_worker import transcribe_piece
        if self.pool is None:
            raise RuntimeError("no transcription worker")
        return await asyncio.get_running_loop().run_in_executor(
            self.pool, transcribe_piece, pcm, offset_s, self.entry.model, self.language)


class FasterWhisper(SttBackend):
    """
    Whisper on CTranslate2, for a server that is not on Apple Silicon (`engine:
    "faster-whisper"` forces it anywhere). Thread-safe, unlike MLX, so a pass runs in a
    thread and the loop carries on; two workers let a recording and a hold run at once
    on the one model, with no worker process.
    """

    partials_default = True
    timestamps = True

    def __init__(self, entry: Entry, language: str | None) -> None:
        super().__init__(entry, language)
        from faster_whisper import WhisperModel

        log.info("loading STT model %s (faster-whisper) ...", entry.model)
        t0 = time.monotonic()
        self._model = WhisperModel(entry.model, device=entry.options.get("device", "auto"),
                                   compute_type=entry.options.get("compute_type", "default"),
                                   num_workers=2)
        log.info("STT ready in %.1fs (lang=%s)", time.monotonic() - t0, language or "auto")

    def _run(self, pcm: bytes, language: str | None) -> list[Any]:
        audio = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
        segments, _ = self._model.transcribe(audio, language=language or self.language,
                                             condition_on_previous_text=False)
        return list(segments)

    async def transcribe(self, pcm: bytes, language: str | None = None) -> str:
        segments = await asyncio.to_thread(self._run, pcm, language)
        return " ".join(s.text.strip() for s in segments if s.text.strip())

    async def segments(self, pcm: bytes, offset_s: float) -> list[dict[str, Any]]:
        return [{"start": round(s.start + offset_s, 2), "end": round(s.end + offset_s, 2),
                 "text": s.text.strip()}
                for s in await asyncio.to_thread(self._run, pcm, None) if s.text.strip()]


class OpenAIStt(SttBackend):
    """`/audio/transcriptions` at any OpenAI-compatible provider: OpenAI, Groq, a self-hosted server."""

    def __init__(self, entry: Entry, language: str | None) -> None:
        super().__init__(entry, language)
        self._client = openai_client(entry.provider)
        # Segments with times need `verbose_json`, which whisper models give and
        # gpt-4o-transcribe refuses; a refusal turns it off for good.
        self.timestamps = bool(entry.options.get("timestamps", True))

    def _args(self, pcm: bytes, language: str | None) -> dict[str, Any]:
        lang = language or self.language
        return dict(model=self.entry.model, file=("audio.wav", wav(pcm), "audio/wav"),
                    **({"language": lang} if lang else {}))

    async def transcribe(self, pcm: bytes, language: str | None = None) -> str:
        r = await self._client.audio.transcriptions.create(**self._args(pcm, language),
                                                           response_format="json")
        return (r.text or "").strip()

    async def segments(self, pcm: bytes, offset_s: float) -> list[dict[str, Any]]:
        if not self.timestamps:
            return await super().segments(pcm, offset_s)
        import openai
        try:
            r = await self._client.audio.transcriptions.create(
                **self._args(pcm, None), response_format="verbose_json",
                timestamp_granularities=["segment"])
        except openai.APIStatusError as e:
            if e.status_code not in (400, 422):
                raise
            log.warning("%s gives no timestamps (%s); a recording is cut into %d s pieces from "
                        "the next one on; set \"timestamps\": false on the entry",
                        self.entry.label(), str(e)[:120], UNTIMED_PIECE_S)
            self.timestamps = False
            return await super().segments(pcm, offset_s)
        out = []
        for s in getattr(r, "segments", None) or []:
            text = (s.text or "").strip()
            if text:
                out.append({"start": round(float(s.start) + offset_s, 2),
                            "end": round(float(s.end) + offset_s, 2), "text": text})
        return out


class GeminiStt(SttBackend):
    """Gemini asked for a transcript: the audio and one instruction, nothing else."""

    PROMPT = ("Transcribe the speech in this audio exactly as it was said{lang}. Reply with "
              "the transcript only: no preamble, no notes, no timestamps, no description of "
              "sounds. If nobody speaks, reply with nothing.")

    def __init__(self, entry: Entry, language: str | None) -> None:
        super().__init__(entry, language)
        self._client = gemini_client(entry.provider)

    async def transcribe(self, pcm: bytes, language: str | None = None) -> str:
        return (await self.counted(pcm, language))[0]

    async def counted(self, pcm: bytes, language: str | None = None) -> tuple[str, dict[str, float] | None]:
        from google.genai import types

        lang = language or self.language
        said = f", in {LANGUAGES.get(lang, lang)}" if lang else ""
        r = self.entry.options.get("reasoning")
        thinking = (types.ThinkingConfig(thinking_budget=0) if r == "none" else
                    types.ThinkingConfig(thinking_level=str(r).upper()) if r else None)
        resp = await self._client.aio.models.generate_content(
            model=self.entry.model,
            contents=[types.Part.from_bytes(data=wav(pcm), mime_type="audio/wav"),
                      types.Part.from_text(text=self.PROMPT.format(lang=said))],
            config=types.GenerateContentConfig(thinking_config=thinking),
        )
        # The audio is counted by its seconds (prices.py); the instruction, and what came
        # out with the thinking, as Gemini counted them.
        m = resp.usage_metadata
        counts = {"in": sum(d.token_count or 0 for d in m.prompt_tokens_details or []
                            if d.modality != types.MediaModality.AUDIO),
                  "out": (m.candidates_token_count or 0) + (m.thoughts_token_count or 0)} if m else None
        return (resp.text or "").strip(), counts


def engine(entry: Entry) -> str:
    """A local Whisper's engine: MLX on Apple Silicon, faster-whisper anywhere else."""
    return entry.options.get("engine") or ("mlx" if APPLE_SILICON else "faster-whisper")


def make_stt(entry: Entry, language: str | None, pool: Executor | None) -> SttBackend:
    kind = entry.provider.kind
    if kind == "local":
        if engine(entry) == "mlx":
            return LocalWhisper(entry, language, pool)
        return FasterWhisper(entry, language)
    if kind == "openai":
        return OpenAIStt(entry, language)
    if kind == "gemini":
        return GeminiStt(entry, language)
    raise ValueError(f"{entry.id}: a {kind} provider cannot transcribe")


class MeteredStt:
    """
    A speech backend whose every pass goes in the ledger (ledger.py): the seconds it heard,
    a live words' pass apart (`live`), and what it wrote as the provider counted it, else
    a token per four characters.
    """

    def __init__(self, inner: SttBackend, ledger: Ledger) -> None:
        self._inner, self._ledger = inner, ledger

    def __getattr__(self, name: str) -> Any:
        return getattr(self._inner, name)

    async def transcribe(self, pcm: bytes, language: str | None = None, *, live: bool = False) -> str:
        """`live`: a pass for the live words (`SttSession.partial`), billed but not what was said."""
        text, counts = await self._inner.counted(pcm, language)
        self._count(pcm, text, counts, live)
        return text

    async def segments(self, pcm: bytes, offset_s: float) -> list[dict[str, Any]]:
        found = await self._inner.segments(pcm, offset_s)
        self._count(pcm, " ".join(str(s.get("text", "")) for s in found))
        return found

    def _count(self, pcm: bytes, text: str, counts: dict[str, float] | None = None, live: bool = False) -> None:
        self._ledger.add("stt", self._inner.entry.id, **{"live" if live else "secs": len(pcm) / BYTES_PER_SECOND},
                         **(counts or {"out": len(text) // 4}))


class Stt:
    """
    The level gate and one backend per STT entry, made the first time a hold asks for
    it. `pool` is the recordings' worker process, there only when a local Whisper is.
    """

    def __init__(
        self,
        providers: Providers,
        language: str | None,
        partial_interval_s: float,
        gate_min_active_dbfs: float,
        gate_min_snr_db: float,
        pool: Executor | None = None,
        ledger: Ledger | None = None,
    ) -> None:
        self.providers = providers
        self.language = language
        self.partial_interval_bytes = int(partial_interval_s * BYTES_PER_SECOND)
        self.gate_min_active_dbfs = gate_min_active_dbfs
        self.gate_min_snr_db = gate_min_snr_db
        self.pool = pool
        # None (a tool's run) counts in memory only; every pass still goes through the meter.
        self.ledger = ledger or Ledger(None)
        self._made: dict[str, MeteredStt] = {}

    def backend(self, entry_id: str | None = None) -> MeteredStt:
        entry = self.providers.pick("stt", entry_id)
        if entry is None:
            raise RuntimeError("no speech-to-text model is configured")
        b = self._made.get(entry.id)
        # A saved settings page makes a new Entry; a local Whisper whose entry did not
        # change keeps its loaded model rather than loading it again.
        if b is None or b.entry != entry:
            self._made[entry.id] = b = MeteredStt(make_stt(entry, self.language, self.pool), self.ledger)
        return b

    def has_speech(self, pcm: bytes) -> tuple[bool, Metrics]:
        """Level gate: enough energy, and enough of it above the room."""
        m = measure(pcm)
        ok = (m.active_dbfs >= self.gate_min_active_dbfs
              and m.active_dbfs - m.floor_dbfs >= self.gate_min_snr_db)
        return ok, m

    def new_session(self, language: str | None = None, entry_id: str | None = None,
                    partials: bool | None = None) -> SttSession:
        """
        `language` forces this utterance's language: the Translator's `from`. `partials`
        is the device's say on live words; None leaves it to the model (on here, off at a
        provider).
        """
        return SttSession(self, self.backend(entry_id), language, partials)


class SttSession:
    def __init__(self, owner: Stt, backend: MeteredStt, language: str | None = None,
                 partials: bool | None = None) -> None:
        self._owner = owner
        self.backend = backend
        self.partials = backend.partials if partials is None else partials
        self._language = language
        self._buf = bytearray()
        self._last_pass_at = 0     # buffer length when the last partial pass ran
        self.text = ""

    def feed(self, data: bytes) -> bool:
        """Buffer incoming PCM. True when a partial pass is due (`partial`)."""
        self._buf.extend(data)
        return (self.partials
                and len(self._buf) - self._last_pass_at >= self._owner.partial_interval_bytes)

    async def partial(self) -> str | None:
        """Re-transcribe everything so far. The new transcript when it changed, else None."""
        self._last_pass_at = len(self._buf)
        pcm = bytes(self._buf)
        ok, _ = self._owner.has_speech(pcm)
        if not ok:
            return None       # nothing to say yet; do not invite "Thank you."
        text = await self.backend.transcribe(pcm, self._language, live=True)
        if text != self.text:
            self.text = text
            return text
        return None

    async def finish(self) -> str:
        """Final pass over the complete utterance. Empty string when gated out."""
        pcm = bytes(self._buf)
        ok, m = self._owner.has_speech(pcm)
        if not ok:
            log.info("gated: no speech (%s)", m.summary())
            self.text = ""
            return ""
        t0 = time.monotonic()
        self.text = await self.backend.transcribe(pcm, self._language)
        log.debug("final pass by %s: %.2fs over %.1fs audio", self.backend.entry.id,
                 time.monotonic() - t0, m.seconds)
        return self.text
