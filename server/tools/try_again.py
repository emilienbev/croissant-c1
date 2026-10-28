#!/usr/bin/env python3
"""
The shelf's Try again, without the device: ask a running server for one picture over
the socket and collect it over HTTP, exactly as the app does.

    uv run python tools/try_again.py "a red vintage bicycle against a white wall"
    uv run python tools/try_again.py --from photo.jpg "an oil painting of a blue sky"
    uv run python tools/try_again.py --host 127.0.0.1 --port 8766 "a fox"

Unlike tools/generate.py, which drives `Generations` in process, this goes through the
wire: the `generate` control frame (session.py), the `generation` frame back, then the
GET/DELETE of `/generated/<id>` (app.py). It is the check that the device's Try again
has a server to talk to, and it needs a server already running.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from websockets.asyncio.client import connect

# How long the server gets to file the job and answer; it does no work before that.
ASK_TIMEOUT_S = 10.0


async def ask(ws_url: str, prompt: str, photo: bytes | None) -> str:
    """Send the frame the app sends, and return the id the server minted."""
    async with connect(ws_url) as ws:
        frame = {"type": "generate", "prompt": prompt}
        if photo is not None:
            frame["image"] = True
        await ws.send(json.dumps(frame))
        if photo is not None:
            await ws.send(photo)
        answer = json.loads(await asyncio.wait_for(ws.recv(), timeout=ASK_TIMEOUT_S))
    if answer.get("type") != "generation":
        raise SystemExit(f"refused: {answer.get('message') or answer}")
    print(f"{answer['id']}: {answer['prompt']!r}"
          + (" from a photo" if answer.get("source") == "photo" else ""))
    return answer["id"]


def collect(base: str, job_id: str, out: Path, every: float, tries: int) -> int:
    """Poll as the device does until the bytes are there, then claim them."""
    for _ in range(tries):
        try:
            with urlopen(f"{base}/generated/{job_id}") as r:
                if r.status == 200:
                    out.write_bytes(r.read())
                    urlopen(Request(f"{base}/generated/{job_id}", method="DELETE")).read()
                    print(f"{out} ({out.stat().st_size / 1000:.0f} kB), claimed")
                    return 0
        except HTTPError as e:
            if e.code == 202:
                pass
            else:
                reason = json.loads(e.read() or b"{}").get("error", e.reason)
                print(f"failed: {reason}", file=sys.stderr)
                return 1
        time.sleep(every)
    print("still not ready", file=sys.stderr)
    return 1


async def main() -> int:
    p = argparse.ArgumentParser(prog="try_again")
    p.add_argument("prompt")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--from", dest="source", default=None, metavar="PHOTO",
                   help="transform this photo, as retrying a transform does")
    p.add_argument("--out", default=None, help="where to write the picture (default ./<id>.jpg)")
    p.add_argument("--every", type=float, default=5.0, help="seconds between fetches")
    p.add_argument("--tries", type=int, default=60)
    a = p.parse_args()

    photo = Path(a.source).read_bytes() if a.source else None
    base = f"http://{a.host}:{a.port}"
    t0 = time.monotonic()
    job_id = await ask(f"ws://{a.host}:{a.port}/session", a.prompt, photo)
    code = collect(base, job_id, Path(a.out or f"{job_id}.jpg"), a.every, a.tries)
    print(f"{time.monotonic() - t0:.0f}s")
    return code


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
