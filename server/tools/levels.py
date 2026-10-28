#!/usr/bin/env python3
"""
Print the level metrics for one or more 16kHz mono WAVs.

Same numbers the server logs when it captures an utterance, so a file can be
re-checked by hand, and other recordings (e.g. `say` output, a phone memo) can be
compared against what the R1 produces:

    uv run python tools/levels.py captures/*.wav
"""

from __future__ import annotations

import sys
import wave

sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parents[1]))

from croissant_server.capture import SAMPLE_RATE, measure  # noqa: E402


def main(paths: list[str]) -> int:
    if not paths:
        print(__doc__)
        return 2
    for p in paths:
        with wave.open(p, "rb") as w:
            if (w.getframerate(), w.getnchannels(), w.getsampwidth()) != (SAMPLE_RATE, 1, 2):
                print(f"{p}: need 16kHz mono s16, got "
                      f"{w.getframerate()}Hz/{w.getnchannels()}ch/{w.getsampwidth()*8}bit")
                continue
            pcm = w.readframes(w.getnframes())
        print(f"{p}: {measure(pcm).summary()}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
