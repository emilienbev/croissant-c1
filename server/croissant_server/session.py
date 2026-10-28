"""One WebSocket connection = one hold's words, one recording or one picture."""

from __future__ import annotations

import asyncio
import json
import logging
import re
from pathlib import Path
from typing import Any

from websockets.asyncio.server import ServerConnection
from websockets.exceptions import ConnectionClosed, ConnectionClosedError

from .capture import UtteranceCapture
from .history import History
from .images import Generations
from .stt import LANGUAGES, Stt, SttSession
from .transcription import Transcription

log = logging.getLogger(__name__)

# The ids a device may give a picture it asks for directly (app.py serves the same).
GENERATION_ID = re.compile(r"^g_[0-9a-f]{6}$")

# What an app from before Ask's loop moved to the C1 (2026-10-16) is told when it asks for
# what the server no longer does: an Ask hold (its `system` and `tools`), a photo hold, a
# Transform or a Translator hold, an app's `ask` or the model test's `bench`.
OLD_APP = "the app is older than the server: update the app"


class Session:
    """
    Protocol (see server/README.md):

        <- {"type":"start","mode":"dictate","rate":16000,"enc":"pcm_s16le","ch":1,
            "from":"fr"?, "stt":"<provider:model>"?, "partials":true|false?, "conv":"..."?}
        <- binary PCM frames
        -> {"type":"partial","text":...}      (while held, with live words on)
        <- {"type":"end"}
        -> {"type":"final","text":...}
        -> {"type":"answer_done"}
                                              or {"type":"error","message":...} at any point

    A hold's words and nothing else. Ask's loop and every model call run on the C1 since
    2026-10-16: it starts the model on `final`, and `answer_done`, empty, closes the
    exchange. `from` is the language Whisper is told, one of `LANGUAGES` (the Translator's);
    without it the server's own (`--lang`). `stt` is the device's pick of
    speech model, else the default; `partials` its say on live words, else the model's own
    (on here, off at a provider). `conv` only names the hold in `--debug-keep`'s history.

    `"mode":"transcribe"` is instead a recording sent whole for its transcript, its frames
    in transcription.py.

    A start in any other mode, an Ask hold with its `system` and `tools`, a photo hold,
    `transform` or `translate` among them, is from an app older than this server, and so
    are the `ask` and `bench` frames: each is answered with `OLD_APP` at once.

    A connection may instead ask for one picture and nothing else: the C1's every picture
    (Ask's `image_generate`, the photo page's Transform with its photo, the shelf's Try
    again), the prompt final, any description pass already made on the C1:

        <- {"type":"generate","id":"g_ab12cd","prompt":"...","image":true?,
            "text_to_image":"<provider:model>"?,"image_to_image":"<provider:model>"?}
        <- binary JPEG (only when image is true)
        -> {"type":"generation","id":"g_ab12cd","prompt":"...","source":"photo"?}
                                              or {"type":"error","message":...}

    The device fetches the picture later by its id (app.py, `/generated/<id>`).

    WebSocket already separates text frames from binary ones, so control and audio
    need no framing of their own.
    """

    def __init__(
        self, ws: ServerConnection, stt: Stt, history: History, capture_dir: Path | None,
        recordings_dir: Path | None = None, generations: Generations | None = None,
    ) -> None:
        self.ws = ws
        self.stt = stt
        # The pictures (images.py) and the device's picks for them, from the generate
        # frame: {"text_to_image": id, "image_to_image": id}.
        self.generations = generations
        self.pictures: dict[str, str] = {}
        self.history = history
        self.capture_dir = capture_dir
        self.capture: UtteranceCapture | None = None
        # A recording sent for transcription instead of a hold (transcription.py).
        self.recordings_dir = recordings_dir
        self.transcription: Transcription | None = None
        self.peer = "?"
        self.audio: SttSession | None = None
        # The partial pass in flight, if any: it runs beside the socket's reads, so a
        # provider's call never holds up the frames behind it, and `end` cancels it.
        self.partial_task: asyncio.Task | None = None
        self.bytes_in = 0
        # From the start frame: the conversation, for the history only, and the language.
        self.conv: str | None = None
        self.src: str | None = None
        # The device went away. Sends become no-ops.
        self.gone = False
        # The id the device made for a picture, and its photo when one follows.
        self.job_id: str | None = None
        self.image: bytes | None = None
        # A "generate" whose photo has not arrived yet (_on_generate).
        self.pending_prompt: str | None = None

    async def send(self, **payload) -> None:
        if self.gone:
            return
        try:
            await self.ws.send(json.dumps(payload, ensure_ascii=False))
        except ConnectionClosed:
            self.gone = True
            log.info("peer left")

    async def run(self) -> None:
        self.peer = self.ws.remote_address[0] if self.ws.remote_address else "?"
        log.info("session open from %s", self.peer)
        try:
            async for message in self.ws:
                if isinstance(message, bytes):
                    await self._on_binary(message)
                else:
                    if await self._on_control(message):
                        break
        except ConnectionClosedError as e:
            # The device lost the link (wifi, sleep, crash). Expected, not a bug:
            # whatever audio arrived is kept below.
            log.warning("peer %s dropped: %s", self.peer, e)
        finally:
            if self.partial_task is not None:
                self.partial_task.cancel()
            # A socket that dropped mid-utterance still leaves a WAV behind: a
            # truncated take is evidence too, and is marked as such in the index.
            if self.capture is not None and not self.capture.closed:
                self.capture.close(None, reason="disconnect")
            log.info("session closed (%d bytes of audio)", self.bytes_in)

    async def _on_binary(self, data: bytes) -> None:
        if self.transcription is not None:
            self.bytes_in += len(data)
            await self.transcription.feed(data)
            return
        # The photo a "generate" said was coming. Before the audio guard below,
        # because such a connection never sent "start" and so has no audio session.
        if self.pending_prompt is not None:
            self.image = data
            prompt, self.pending_prompt = self.pending_prompt, None
            log.info("photo for the picture: %d bytes", len(data))
            await self._generate(prompt)
            await self.ws.close()
            return
        if self.audio is None:
            # Audio before "start" is a client bug; ignore rather than crash.
            log.warning("binary frame before start, dropping %d bytes", len(data))
            return
        self.bytes_in += len(data)
        if self.capture is not None:
            self.capture.feed(data)
        if self.audio.feed(data) and (self.partial_task is None or self.partial_task.done()):
            self.partial_task = asyncio.create_task(self._partial(self.audio))

    async def _partial(self, audio: SttSession) -> None:
        try:
            text = await audio.partial()
        except Exception as e:  # a partial is a preview; the final pass will say what failed
            log.warning("partial pass failed: %s", e)
            return
        if text:
            await self.send(type="partial", text=text)

    async def _on_control(self, raw: str) -> bool:
        """Returns True when the session is finished."""
        try:
            msg = json.loads(raw)
        except json.JSONDecodeError:
            log.warning("non-JSON text frame: %r", raw[:120])
            return False

        kind = msg.get("type")
        if kind == "start":
            rate, enc = msg.get("rate"), msg.get("enc")
            if rate != 16_000 or enc != "pcm_s16le":
                await self.send(
                    type="error", message=f"unsupported audio format {enc}@{rate}"
                )
                return True
            stt = msg.get("stt") if isinstance(msg.get("stt"), str) else None
            if msg.get("mode") == "transcribe":
                try:
                    backend = self.stt.backend(stt)
                except Exception as e:
                    log.error("no speech-to-text for the recording: %s", e)
                    await self.send(type="error", message=f"STT error: {e}")
                    return True
                self.transcription = Transcription(
                    self.send, backend, self.recordings_dir,
                    rec_id=str(msg.get("id") or ""), started_at=msg.get("startedAt"),
                    total_bytes=int(msg.get("bytes") or 0), from_s=float(msg.get("from") or 0.0),
                    gate_min_active_dbfs=self.stt.gate_min_active_dbfs, gate_min_snr_db=self.stt.gate_min_snr_db,
                )
                log.info("start: transcribe %s (%d bytes, from %.0fs)", self.transcription.id, int(msg.get("bytes") or 0), float(msg.get("from") or 0.0))
                return False
            if msg.get("mode") != "dictate":
                log.warning("start from an older app (mode %s%s): told to update",
                            msg.get("mode") or "ask", ", with a prompt" if "system" in msg else "")
                await self.send(type="error", message=OLD_APP)
                return True
            src = msg.get("from")
            if src is not None and src not in LANGUAGES:
                await self.send(type="error", message=f"unsupported language {src}")
                return True
            self.src = src
            try:
                partials = msg.get("partials")
                self.audio = self.stt.new_session(self.src, stt, partials if isinstance(partials, bool) else None)
            except Exception as e:  # a provider with no key, a kind that cannot transcribe
                log.error("no speech-to-text for %r: %s", stt, e)
                await self.send(type="error", message=f"STT error: {e}")
                return True
            if self.capture_dir is not None:
                self.capture = UtteranceCapture(self.capture_dir, self.peer)
            conv = msg.get("conv")
            self.conv = conv if isinstance(conv, str) else None
            log.info("start: dictate %s @ %dHz conv=%s%s", enc, rate, self.conv,
                     f" from {self.src}" if self.src else "")
            return False

        if kind == "generate":
            return await self._on_generate(msg)

        if kind in ("ask", "bench"):
            log.warning("%s frame from an older app: told to update", kind)
            await self.send(type="error", message=OLD_APP)
            return True

        if kind == "end":
            if self.transcription is not None:
                await self.transcription.finish()
                return True
            await self._finish()
            return True

        log.warning("unknown control frame: %s", kind)
        return False

    async def _on_generate(self, msg: dict[str, Any]) -> bool:
        """
        One picture and nothing else: no audio, no assistant, no answer.

        Every picture the C1 asks for comes this way, its prompt final: Ask's
        `image_generate` (the C1 has answered the model with the id already), the
        Transform button (the description pass, when the model wants one, made on the C1)
        and the shelf's Try again, which must not have its prompt reworded on the way.

        `image` true means one binary JPEG frame follows: the photo a transform starts
        from, sent again on a retry, because the server kept the prompt and nothing else.
        """
        prompt = (msg.get("prompt") or "").strip()
        if not prompt:
            await self.send(type="error", message="prompt is required")
            return True
        for slot in ("text_to_image", "image_to_image"):
            if isinstance(msg.get(slot), str):
                self.pictures[slot] = msg[slot]
        job_id = msg.get("id")
        if isinstance(job_id, str) and GENERATION_ID.match(job_id):
            self.job_id = job_id
        elif job_id is not None:
            log.warning("ignoring a malformed generation id: %r", str(job_id)[:40])
        if msg.get("image"):
            self.pending_prompt = prompt
            log.info("generate: %r, the photo follows", prompt[:120])
            return False
        await self._generate(prompt)
        return True

    async def _generate(self, prompt: str) -> None:
        """
        File the picture for `prompt`, from `image` when there is one, and say its id in a
        `generation` frame. It does not wait: a picture takes tens of seconds, and the
        device collects it later (images.py).
        """
        if self.generations is None:
            await self.send(type="error", message="pictures are disabled on this server")
            return
        slot = "image_to_image" if self.image is not None else "text_to_image"
        try:
            job_id = self.generations.start(prompt, source=self.image, job_id=self.job_id,
                                            entry_id=self.pictures.get(slot))
        except Exception as e:  # no entry, a provider with no key
            await self.send(type="error", message=str(e))
            return
        log.info("asked for a picture: %r%s", prompt[:120], " from a photo" if self.image is not None else "")
        await self.send(type="generation", id=job_id, prompt=prompt,
                        **({"source": "photo"} if self.image is not None else {}))

    async def _finish(self) -> None:
        if self.audio is None:
            await self.send(type="error", message="no audio received")
            return

        if self.partial_task is not None:
            self.partial_task.cancel()
        try:
            transcript = await self.audio.finish()
        except Exception as e:  # the provider down, the key refused
            log.exception("speech-to-text failed")
            if self.capture is not None:
                self.capture.close(None, reason="stt error")
            await self.send(type="error", message=f"STT error: {e}")
            return
        log.info("final after %.1fs audio: %r", self.bytes_in / 32_000, transcript)  # 16kHz * 2 bytes
        if self.capture is not None:
            self.capture.close(transcript or None, reason="end")
        if not transcript:
            await self.send(type="error", message="No speech detected")
            return
        await self.send(type="final", text=transcript)
        await self.send(type="answer_done")
        self.history.append(conv=self.conv, transcript=transcript, mode="dictate",
                            **({"from": self.src} if self.src else {}))
