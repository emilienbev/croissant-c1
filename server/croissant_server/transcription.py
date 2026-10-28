"""A recording sent for transcription.

The device streams the file's PCM as the binary frames it already sends for a hold,
under a start frame with `mode: transcribe`. The audio is held whole in memory and
transcribed in pieces, each cut at the quietest 20 ms in the last five seconds of its
window so words are not split: about ten minutes for a backend that times its segments
(the local worker process, a provider's Whisper), thirty seconds for one that does not,
each piece then one segment (stt.py, `piece_s`).
Segments go back as each piece is done, with progress by audio position, so the
device's page fills while it runs, and nothing of the recording stays on the Mac:
the audio and the transcript both live on the device. `--debug-keep` writes a copy
to `data/recordings/<id>.wav` with a JSON beside it (config.py).

Whisper hallucinates on silence ("Thank you.", no-speech probability 0.0), and a
meeting has silence: each piece passes the same level gate as an utterance before
the model sees it, and a piece that fails it contributes nothing.
"""

from __future__ import annotations

import json
import logging
import time
from pathlib import Path
from typing import Any, Awaitable, Callable

import numpy as np

from .capture import FRAME_SAMPLES, Metrics, measure, write_wav
from .stt import SttBackend

log = logging.getLogger(__name__)

SAMPLE_RATE = 16_000
BYTES_PER_SECOND = SAMPLE_RATE * 2
PIECE_S = 600
SEARCH_S = 5

Send = Callable[..., Awaitable[None]]


def pieces(pcm: bytes, from_s: float = 0.0, piece_s: int = PIECE_S) -> list[tuple[float, bytes]]:
    """
    Cut `pcm` from `from_s` into pieces of at most `piece_s` seconds, each ending at the
    quietest 20 ms frame in the last `SEARCH_S` seconds of its window. Returns (offset
    in seconds, bytes) pairs.
    """
    samples = np.frombuffer(pcm, dtype="<i2")
    start = int(from_s * SAMPLE_RATE)
    out: list[tuple[float, bytes]] = []
    n = len(samples)
    while start < n:
        end = min(n, start + piece_s * SAMPLE_RATE)
        if end < n:
            lo = max(start + SAMPLE_RATE, end - SEARCH_S * SAMPLE_RATE)
            window = samples[lo:end].astype(np.float32)
            frames = len(window) // FRAME_SAMPLES
            if frames > 0:
                powers = np.mean(window[: frames * FRAME_SAMPLES].reshape(frames, FRAME_SAMPLES) ** 2, axis=1)
                end = lo + int(np.argmin(powers)) * FRAME_SAMPLES
        if end <= start:
            break
        out.append((start / SAMPLE_RATE, samples[start:end].tobytes()))
        start = end
    return out


class Transcription:
    """One `mode: transcribe` session: collect, store, transcribe in pieces, stream back."""

    def __init__(
        self, send: Send, backend: SttBackend, recordings_dir: Path | None,
        rec_id: str, started_at: str | None, total_bytes: int, from_s: float,
        gate_min_active_dbfs: float, gate_min_snr_db: float,
    ) -> None:
        self.send = send
        self.backend = backend
        self.dir = recordings_dir
        self.id = "".join(c for c in rec_id if c.isalnum() or c in "-_") or time.strftime("%Y%m%d-%H%M%S")
        self.started_at = started_at
        self.total = max(1, total_bytes)
        self.from_s = from_s
        self.gate = (gate_min_active_dbfs, gate_min_snr_db)
        self._buf = bytearray()
        self._last_progress = 0.0

    async def feed(self, data: bytes) -> None:
        self._buf.extend(data)
        done = min(1.0, len(self._buf) / self.total)
        # Progress during the upload is the device's own count; a frame every 5% keeps the wire quiet.
        if done - self._last_progress >= 0.05:
            self._last_progress = done
            await self.send(type="progress", done=round(done, 3))

    def _speech(self, pcm: bytes) -> tuple[bool, Metrics]:
        m = measure(pcm)
        ok = m.active_dbfs >= self.gate[0] and m.active_dbfs - m.floor_dbfs >= self.gate[1]
        return ok, m

    async def finish(self) -> None:
        pcm = bytes(self._buf)
        self._buf = bytearray()
        if not pcm:
            await self.send(type="error", message="no audio received")
            return
        seconds = len(pcm) / BYTES_PER_SECOND
        record: dict[str, Any] = {"id": self.id, "startedAt": self.started_at, "seconds": round(seconds, 2),
                                  "transcribedAt": time.strftime("%Y-%m-%dT%H:%M:%S"), "segments": []}
        meta: Path | None = None
        if self.dir is not None:
            self.dir.mkdir(parents=True, exist_ok=True)
            meta = self.dir / f"{self.id}.json"
            write_wav(self.dir / f"{self.id}.wav", pcm)
            if meta.exists():
                try:
                    earlier = json.loads(meta.read_text(encoding="utf-8"))
                    record["segments"] = [s for s in earlier.get("segments", []) if s["end"] <= self.from_s]
                except (OSError, ValueError, KeyError):
                    pass
        log.info("transcribing %s with %s: %.0fs of audio from %.0fs", self.id,
                 self.backend.entry.id, seconds, self.from_s)
        t0 = time.monotonic()
        for offset, piece in pieces(pcm, self.from_s, self.backend.piece_s):
            ok, m = self._speech(piece)
            if ok:
                segments = await self.backend.segments(piece, offset)
            else:
                log.info("piece at %.0fs gated: %s", offset, m.summary())
                segments = []
            if segments:
                record["segments"].extend(segments)
                await self.send(type="segments", at=offset, items=segments)
            done = min(1.0, (offset + len(piece) / BYTES_PER_SECOND) / seconds)
            await self.send(type="progress", done=round(done, 3))
            if meta is not None:
                meta.write_text(json.dumps(record, ensure_ascii=False, indent=1), encoding="utf-8")
        log.info("transcribed %s: %d segments in %.1fs", self.id, len(record["segments"]), time.monotonic() - t0)
        await self.send(type="done", id=self.id)
