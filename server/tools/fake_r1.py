#!/usr/bin/env python3
"""
Stand-in for the R1's speech path: streams a WAV at real-time pace as a hold does
(`"mode":"dictate"`) and prints what comes back, the live words, the final words and the
empty `answer_done`.

Lets the server be exercised without the device, which keeps server bugs and
device bugs from being confused for one another. Speech needs no extra tooling on
macOS: --say synthesises the sentence with `say` and `afconvert`. The model half of a
hold runs on the C1 since 2026-10-16, and without the device in eval_tools.py.

    uv run python tools/fake_r1.py --say "What is the capital of France?"
    uv run python tools/fake_r1.py /tmp/q.wav
    uv run python tools/fake_r1.py /tmp/bonjour.wav --from fr     the Translator's: Whisper told the language
    uv run python tools/fake_r1.py --say "Set a timer" --stt openai:whisper-1 --partials
"""

from __future__ import annotations

import argparse
import asyncio
import json
import subprocess
import tempfile
import time
import wave

from websockets.asyncio.client import connect

CHUNK_MS = 20  # what the device sends


def synthesise(text: str) -> str:
    d = tempfile.mkdtemp(prefix="fake_r1_")
    aiff, wav = f"{d}/q.aiff", f"{d}/q.wav"
    subprocess.run(["say", "-o", aiff, text], check=True)
    subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", aiff, wav],
                   check=True)
    return wav


async def main(a: argparse.Namespace) -> None:
    path = synthesise(a.say) if a.say else a.wav
    with wave.open(path, "rb") as w:
        assert w.getframerate() == 16000, f"need 16kHz, got {w.getframerate()}"
        assert w.getnchannels() == 1, "need mono"
        assert w.getsampwidth() == 2, "need s16"
        pcm = w.readframes(w.getnframes())
    print(f"{len(pcm)/32000:.1f}s of audio -> {a.url}")

    async with connect(a.url) as ws:
        start = {"type": "start", "mode": "dictate", "rate": 16000, "enc": "pcm_s16le", "ch": 1}
        if a.src:
            start["from"] = a.src
        if a.stt:
            start["stt"] = a.stt
        if a.partials is not None:
            start["partials"] = a.partials
        await ws.send(json.dumps(start))

        done = asyncio.Event()
        t0 = time.monotonic()

        async def reader() -> None:
            async for raw in ws:
                msg = json.loads(raw)
                dt = time.monotonic() - t0
                kind = msg.get("type")
                if kind == "partial":
                    print(f"  [{dt:5.2f}s] partial: {msg['text']}")
                elif kind == "final":
                    print(f"  [{dt:5.2f}s] FINAL:   {msg['text']}")
                elif kind == "answer_done":
                    print(f"  [{dt:5.2f}s] done")
                    done.set()
                    return
                elif kind == "error":
                    print(f"  [{dt:5.2f}s] ERROR: {msg['message']}")
                    done.set()
                    return

        task = asyncio.create_task(reader())

        step = 16000 * 2 * CHUNK_MS // 1000
        for i in range(0, len(pcm), step):
            await ws.send(pcm[i:i + step])
            await asyncio.sleep(CHUNK_MS / 1000)  # pace it like a real microphone
        await ws.send(json.dumps({"type": "end"}))
        print("  (end sent)")

        await asyncio.wait_for(done.wait(), timeout=180)
        task.cancel()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("wav", nargs="?", default="/tmp/q.wav")
    ap.add_argument("--say", help="synthesise this sentence instead of reading a WAV")
    ap.add_argument("--url", default="ws://127.0.0.1:8765/session")
    ap.add_argument("--from", dest="src", metavar="CODE",
                    help="the language Whisper is told, as the Translator's hold says it (en, fr, de, it, es, ja)")
    ap.add_argument("--stt", help="the speech model, a provider:model id (default: the server's)")
    ap.add_argument("--partials", action=argparse.BooleanOptionalAction, default=None,
                    help="live words on or off (default: the speech model's own)")
    asyncio.run(main(ap.parse_args()))
