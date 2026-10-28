#!/usr/bin/env python3
"""
Stand-in for the R1's Transcribe button: sends a WAV to the server the way the
device does (`mode: transcribe`, 64 kB PCM frames as fast as the socket takes
them) and prints the progress and the segments as they come back.

    uv run python tools/send_recording.py recording.wav
    uv run python tools/send_recording.py recording.wav --url ws://127.0.0.1:8765/session
"""

from __future__ import annotations

import argparse
import asyncio
import json
import time
import wave
from datetime import datetime
from pathlib import Path

from websockets.asyncio.client import connect

FRAME = 64 * 1024


async def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("wav")
    p.add_argument("--url", default="ws://127.0.0.1:8765/session")
    p.add_argument("--from", dest="from_s", type=float, default=0.0, help="seconds already transcribed")
    a = p.parse_args()

    with wave.open(a.wav, "rb") as w:
        assert w.getframerate() == 16_000 and w.getnchannels() == 1 and w.getsampwidth() == 2, "16 kHz mono 16-bit only"
        pcm = w.readframes(w.getnframes())
    rec_id = Path(a.wav).stem
    t0 = time.monotonic()
    async with connect(a.url, max_size=4 * 1024 * 1024) as ws:
        await ws.send(json.dumps({
            "type": "start", "mode": "transcribe", "rate": 16000, "enc": "pcm_s16le", "ch": 1,
            "id": rec_id, "startedAt": datetime.now().astimezone().isoformat(timespec="seconds"),
            "bytes": len(pcm), "from": a.from_s,
        }))
        for i in range(0, len(pcm), FRAME):
            await ws.send(pcm[i:i + FRAME])
        await ws.send(json.dumps({"type": "end"}))
        print(f"sent {len(pcm) / 32_000:.0f}s of audio in {time.monotonic() - t0:.1f}s")
        async for raw in ws:
            msg = json.loads(raw)
            kind = msg.get("type")
            if kind == "progress":
                print(f"  {msg['done'] * 100:5.1f}%")
            elif kind == "segments":
                for s in msg["items"]:
                    print(f"  [{s['start']:7.1f} - {s['end']:7.1f}] {s['text']}")
            elif kind == "done":
                print(f"done: {msg['id']} in {time.monotonic() - t0:.1f}s")
                break
            elif kind == "error":
                print(f"error: {msg['message']}")
                break


if __name__ == "__main__":
    asyncio.run(main())
