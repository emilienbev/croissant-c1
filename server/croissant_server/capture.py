"""Save every utterance as a WAV, and measure how loud it was.

Transcription quality cannot be debugged by reading transcripts. "Quiet mic",
"clipped", "noisy room" and "fine audio, wrong words" each want a different fix and
all look the same in a log line. So each utterance is written to disk exactly as the
device sent it - the same bytes the STT saw, no padding - together with a handful of
level numbers that separate those cases without having to open the file.

The WAVs are also the evaluation corpus for choosing a tier-2 model later: right
person, right microphone, right acronyms.
"""

from __future__ import annotations

import json
import logging
import math
import time
import wave
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import BinaryIO

import numpy as np

log = logging.getLogger(__name__)

SAMPLE_RATE = 16_000
BYTES_PER_SAMPLE = 2
FULL_SCALE = 32768.0

# Frames for the level statistics. 20ms is short enough that a pause between words
# gives whole quiet frames and a vowel gives whole loud ones.
FRAME_MS = 20
FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS // 1000

# Share of frames used for the "active" (loudest) and "floor" (quietest) levels.
TAIL_FRACTION = 0.2


def _dbfs(power: float) -> float:
    """Mean-square power (full scale = 1.0) to dBFS. Digital silence is -inf."""
    return 10.0 * math.log10(power) if power > 0 else -math.inf


@dataclass
class Metrics:
    seconds: float
    peak_dbfs: float
    rms_dbfs: float
    # Mean power of the loudest fifth of 20ms frames: a rough speech level that
    # ignores pauses and the ring-buffer pre-roll.
    active_dbfs: float
    # Mean power of the quietest fifth: the room, and the mic's own noise.
    floor_dbfs: float
    # Samples pinned at either rail. Anything above a handful means clipping.
    clipped: int

    def summary(self) -> str:
        def f(v: float) -> str:
            return "-inf" if v == -math.inf else f"{v:.1f}"
        return (
            f"{self.seconds:.1f}s  peak {f(self.peak_dbfs)}  rms {f(self.rms_dbfs)}  "
            f"active {f(self.active_dbfs)}  floor {f(self.floor_dbfs)} dBFS  "
            f"clipped {self.clipped}"
        )


def measure(pcm_s16le: bytes) -> Metrics:
    samples = np.frombuffer(pcm_s16le, dtype="<i2")
    seconds = len(samples) / SAMPLE_RATE
    if len(samples) == 0:
        return Metrics(0.0, -math.inf, -math.inf, -math.inf, -math.inf, 0)

    x = samples.astype(np.float32) / FULL_SCALE
    peak = float(np.max(np.abs(x)))
    rms_power = float(np.mean(x * x))
    clipped = int(np.count_nonzero((samples >= 32767) | (samples <= -32768)))

    n_frames = len(x) // FRAME_SAMPLES
    if n_frames >= 5:
        frames = x[: n_frames * FRAME_SAMPLES].reshape(n_frames, FRAME_SAMPLES)
        powers = np.sort(np.mean(frames * frames, axis=1))
        k = max(1, int(n_frames * TAIL_FRACTION))
        active_power = float(np.mean(powers[-k:]))
        floor_power = float(np.mean(powers[:k]))
    else:
        # Too short for tails to mean anything; report the whole clip for both.
        active_power = floor_power = rms_power

    return Metrics(
        seconds=seconds,
        peak_dbfs=20.0 * math.log10(peak) if peak > 0 else -math.inf,
        rms_dbfs=_dbfs(rms_power),
        active_dbfs=_dbfs(active_power),
        floor_dbfs=_dbfs(floor_power),
        clipped=clipped,
    )


def write_wav(path: Path | BinaryIO, pcm_s16le: bytes) -> None:
    with wave.open(path if hasattr(path, "write") else str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(BYTES_PER_SAMPLE)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(pcm_s16le)


class UtteranceCapture:
    """Accumulates one utterance's PCM; on close writes the WAV and an index row."""

    INDEX = "index.jsonl"

    def __init__(self, directory: Path, peer: str) -> None:
        self.directory = directory
        self.peer = peer
        self.started = time.time()
        self._buf = bytearray()
        self._closed = False

    @property
    def closed(self) -> bool:
        return self._closed

    def feed(self, data: bytes) -> None:
        self._buf.extend(data)

    def close(self, transcript: str | None, reason: str = "end") -> Path | None:
        """
        Write everything received. Safe to call once; later calls are no-ops.

        `reason` is "end" for a normal utterance, or why it was cut short
        ("disconnect", "error") so a truncated file is not mistaken for a bad take.
        Returns the WAV path, or None when nothing was received.
        """
        if self._closed:
            return None
        self._closed = True
        if not self._buf:
            return None

        pcm = bytes(self._buf)
        self._buf = bytearray()
        self.directory.mkdir(parents=True, exist_ok=True)
        path = self._unique_path()
        write_wav(path, pcm)

        m = measure(pcm)
        row = {
            "file": path.name,
            "time": time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(self.started)),
            "peer": self.peer,
            "reason": reason,
            "bytes": len(pcm),
            **asdict(m),
            "transcript": transcript,
        }
        with open(self.directory / self.INDEX, "a", encoding="utf-8") as f:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")

        log.info("captured %s  %s", path.name, m.summary())
        return path

    def _unique_path(self) -> Path:
        stem = time.strftime("%Y%m%d-%H%M%S", time.localtime(self.started))
        path = self.directory / f"{stem}.wav"
        n = 1
        while path.exists():
            path = self.directory / f"{stem}-{n}.wav"
            n += 1
        return path
