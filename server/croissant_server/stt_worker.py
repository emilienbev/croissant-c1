"""Whisper in a worker process, for whole recordings.

MLX runs inline on the event loop for the push-to-talk sessions (stt.py: its
streams are thread-local, so a thread is out), and that is fine for a five-second
utterance. An hour of a meeting is two to four minutes of turbo on this Mac and
would freeze every hold meanwhile, so recordings go to one worker process with its
own copy of the model (about 1.6 GB more memory for turbo). The functions here are
what that process runs; `init` is the pool's initializer, `transcribe_piece` the
task, which names its model (a local STT entry's, stt.py). One worker, one piece at a
time.
"""

from __future__ import annotations

import logging

import numpy as np

log = logging.getLogger(__name__)

SAMPLE_RATE = 16_000

_model_id: str | None = None
_language: str | None = None


def init(model_id: str, language: str | None) -> None:
    global _model_id, _language
    _model_id, _language = model_id, language
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-5s worker: %(message)s", datefmt="%H:%M:%S")


def warm() -> None:
    """Load and cache the model now rather than on the first real recording."""
    transcribe_piece(b"\x00\x00" * SAMPLE_RATE, 0.0)


def transcribe_piece(pcm: bytes, offset_s: float, model_id: str | None = None,
                     language: str | None = None) -> list[dict]:
    """
    One piece of a recording, as (start, end, text) segments with `offset_s` added,
    so they read as seconds into the whole recording. Each piece is a fresh pass:
    nothing is conditioned on the piece before, which invites repetition loops.
    """
    import mlx_whisper  # in the worker only

    audio = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
    result = mlx_whisper.transcribe(
        audio,
        path_or_hf_repo=model_id or _model_id,
        language=language or _language,
        fp16=True,
        condition_on_previous_text=False,
    )
    out = []
    for s in result.get("segments", []):
        text = (s.get("text") or "").strip()
        if not text:
            continue
        out.append({"start": round(float(s["start"]) + offset_s, 2),
                    "end": round(float(s["end"]) + offset_s, 2),
                    "text": text})
    return out
