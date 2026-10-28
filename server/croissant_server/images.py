"""Generated pictures: the job registry the `generate` frame starts (session.py) and the
device collects.

A hold is over in five seconds and a picture takes tens, so nothing waits for it.
`start` files a job and returns an id at once; the worker process, or a task waiting
on a provider (pictures.py), writes the JPEG when it is done; the device fetches it
over HTTP later (app.py) and claims it. Which model makes it is the entry the device
picked from the providers file, `text_to_image` or `image_to_image` by whether there
is a photo.

A picture does not outlive its errand. The device deletes it the moment the bytes
are on its disk (the DELETE that calls `claim`), which is the confirmation that it
really arrived; a serve whose DELETE never landed is dropped `serve_grace_s` after
the last time it was handed over, so the Mac keeps it only long enough for a lost
response to be fetched again. `keep_hours` is the outer bound, for a picture nobody
ever came for. `claim` is also Stop on the device: a job still queued is cancelled
before the GPU ever sees it, and one already running has its output thrown away the
moment it lands.

A job made from a photo (img2img) is the same job with a source beside it: the photo
lands as `<id>.src.jpg` because mflux takes a path, it runs with the transform's own
steps and strength, and its size follows the photo's shape rather than the
configured one.

One local job at a time (`max_workers=1`): the GPU is already shared with Whisper and
Ollama, and two generations at once would only make both slower. A provider's jobs
are tasks, and run side by side.
"""

from __future__ import annotations

import asyncio
import json
import logging
import multiprocessing
import secrets
import time
from concurrent.futures import BrokenExecutor, Future, ProcessPoolExecutor
from collections.abc import Callable
from pathlib import Path
from typing import Any

from . import image_worker, pictures
from .config import ImageModel
from .ledger import Ledger
from .providers import Entry, Providers

log = logging.getLogger(__name__)

QUEUED, RUNNING, DONE, ERROR = "queued", "running", "done", "error"

# How often the sweeper wakes to expire old jobs and unload an idle model.
TICK_S = 60.0


class Generations:
    def __init__(
        self, dir: Path, providers: Providers, local_model: Callable[[Entry], ImageModel],
        width: int, height: int, keep_hours: float, idle_unload_s: float,
        serve_grace_s: float = 300.0, ledger: Ledger | None = None,
    ) -> None:
        self.dir = dir
        self.providers = providers
        # A local entry's model with its settings (config.py, `Config.image`).
        self.local_model = local_model
        # Where a picture made is counted (ledger.py).
        self.ledger = ledger
        self.width, self.height = width, height
        self.keep_s = keep_hours * 3600
        self.serve_grace_s = serve_grace_s
        self.idle_unload_s = idle_unload_s
        self.dir.mkdir(parents=True, exist_ok=True)
        self._pool: ProcessPoolExecutor | None = None
        self._pool_model: ImageModel | None = None
        self._inflight = 0
        self._idle_since = 0.0
        # The pool's own futures (and a provider's tasks) by id, so a job the device
        # gives up on can be cancelled rather than made for nobody.
        self._jobs: dict[str, Future | asyncio.Task] = {}

    def entry(self, slot: str, entry_id: str | None = None) -> Entry | None:
        return self.providers.pick(slot, entry_id)

    # ---- jobs --------------------------------------------------------------

    def start(self, prompt: str, source: bytes | None = None,
              strength: float | None = None, job_id: str | None = None,
              entry_id: str | None = None) -> str:
        """
        File a job and return its id immediately.

        `source` is a JPEG to transform rather than start from nothing, and `strength`
        how much of it to keep (None takes the configured default; a local model's
        only). `job_id` is the id the device made when it asked for this picture
        directly; otherwise one is minted. `entry_id` is the device's pick for the slot.
        """
        slot = "image_to_image" if source is not None else "text_to_image"
        entry = self.entry(slot, entry_id)
        if entry is None:
            raise RuntimeError(f"no {slot.replace('_', ' ')} model is configured")
        job_id = job_id or "g_" + secrets.token_hex(3)
        if entry.provider.kind != "local":
            row: dict[str, Any] = {"id": job_id, "prompt": prompt, "at": time.time(),
                                   "status": RUNNING, "model": entry.id}
            if source is not None:
                row["source"] = True
            self._write(job_id, row)
            task = asyncio.create_task(self._remote(job_id, entry, prompt, source))
            self._inflight += 1
            self._jobs[job_id] = task
            task.add_done_callback(lambda f: self._finished(job_id, f))
            log.info("%s %s with %s: %r", "transforming" if source else "generating", job_id,
                     entry.label(), prompt)
            return job_id

        model = self.local_model(entry)
        seed = secrets.randbelow(2**31)
        row = {"id": job_id, "prompt": prompt, "at": time.time(),
               "seed": seed, "status": QUEUED, "model": entry.id}
        src_path: str | None = None
        if source is not None:
            self.src(job_id).write_bytes(source)
            src_path = str(self.src(job_id))
            strength = model.transform_strength if strength is None else strength
            steps = model.transform_steps
            width, height = self._fit(source)
            row["source"] = True
            row["strength"] = strength
        else:
            steps, width, height = model.steps, self.width, self.height

        self._write(job_id, row)
        # Submitted to the pool directly and then wrapped, rather than through
        # run_in_executor: cancelling the asyncio wrapper would only mark the wrapper
        # cancelled and leave the worker to render a picture nobody is waiting for, so
        # `claim` needs the pool's own future to call cancel on. The wrapper is what
        # carries the result back onto the loop thread.
        pool_job = self._ensure_pool(model).submit(
            image_worker.generate, prompt, seed, str(self.jpg(job_id)),
            steps, width, height, src_path, strength, model.guidance,
        )
        self._inflight += 1
        self._jobs[job_id] = pool_job
        asyncio.wrap_future(pool_job).add_done_callback(lambda f: self._finished(job_id, f))
        log.info("%s %s: %r", "transforming" if source else "generating", job_id, prompt)
        return job_id

    async def _remote(self, job_id: str, entry: Entry, prompt: str, source: bytes | None) -> dict:
        jpeg, row = await pictures.render(entry, prompt, source, self.width, self.height)
        self.jpg(job_id).write_bytes(jpeg)
        return row

    def _fit(self, source: bytes) -> tuple[int, int]:
        """
        The size to render a transform at: the photo's own shape, at the same pixel
        budget as a made-up picture.

        mflux scales the source to the target with a plain resize and no crop, so a
        4:3 photo rendered into a 3:4 frame would come out squashed. Rounded to a
        multiple of 32 for the VAE. An R1 photo is 1224x1632, exactly 3:4, so the
        usual case lands back on the configured size.
        """
        from io import BytesIO

        from PIL import Image

        budget = self.width * self.height
        try:
            with Image.open(BytesIO(source)) as im:
                w, h = im.size
        except Exception as e:
            log.warning("unreadable source photo (%s), using the configured size", e)
            return self.width, self.height
        if w <= 0 or h <= 0:
            return self.width, self.height
        scale = (budget / (w * h)) ** 0.5
        return max(32, round(w * scale / 32) * 32), max(32, round(h * scale / 32) * 32)

    def _finished(self, job_id: str, fut: asyncio.Future) -> None:
        self._inflight -= 1
        self._idle_since = time.monotonic()
        self._jobs.pop(job_id, None)
        self.src(job_id).unlink(missing_ok=True)   # the model has read it by now
        row = self._read(job_id)
        if fut.cancelled():  # stopped before it started (or, at a provider, while it ran)
            self.json(job_id).unlink(missing_ok=True)
            self.jpg(job_id).unlink(missing_ok=True)
            return
        if row is None:  # swept, or stopped from the device, while it ran
            fut.exception()  # retrieved, or asyncio logs a failure nobody is waiting for
            self.jpg(job_id).unlink(missing_ok=True)
            return
        try:
            row.update(fut.result())
            row["status"] = DONE
            log.info("generated %s in %.1fs", job_id, row.get("seconds", 0.0))
            if self.ledger is not None and row.get("model"):
                self.ledger.add("image_to_image" if row.get("source") else "text_to_image", row["model"], pictures=1)
        except Exception as e:
            row["status"] = ERROR
            row["error"] = str(e)
            log.error("generation %s failed: %s", job_id, e)
            # A worker that died (out of memory, a hard MLX failure) leaves the pool
            # broken for good, so it is dropped: the next picture starts a fresh one
            # rather than failing for ever after the first bad prompt.
            if isinstance(e, BrokenExecutor) and self._pool is not None:
                log.warning("image worker died; a new one starts with the next picture")
                self._pool.shutdown(wait=False, cancel_futures=True)
                self._pool = None
        self._write(job_id, row)

    def get(self, job_id: str) -> tuple[str, bytes | None, str | None]:
        """(status, jpeg, error) for the HTTP layer. Unknown ids read as gone."""
        row = self._read(job_id)
        if row is None:
            return "gone", None, None
        status = row.get("status", QUEUED)
        if status == DONE:
            try:
                jpeg = self.jpg(job_id).read_bytes()
            except OSError as e:  # the row says done but the file went: treat as gone
                log.warning("%s.jpg unreadable: %s", job_id, e)
                return "gone", None, None
            # Handed over: the clock the sweeper reads runs from here, so this copy
            # goes a few minutes from now whether or not the device's DELETE lands.
            row["served"] = time.time()
            self._write(job_id, row)
            return DONE, jpeg, None
        if status == ERROR:
            return ERROR, None, row.get("error") or "generation failed"
        return RUNNING, None, None

    def claim(self, job_id: str) -> bool:
        """
        The device has it, or has given up on it: drop our copy, and the job with it.

        The same call does both because they ask for the same thing. A job that has
        not started is cancelled here, so Stop on the device spares the GPU the work;
        one already running is left to finish, because mflux cannot be interrupted
        without taking the worker and its loaded weights down with it, and `_finished`
        throws the picture away the moment it lands. A provider's job is a task, and
        cancelling it drops the request wherever it is.
        """
        row = self._read(job_id)
        job = self._jobs.get(job_id)
        stopped = job is not None and job.cancel()
        if row is None and not stopped:
            return False
        self.jpg(job_id).unlink(missing_ok=True)
        self.src(job_id).unlink(missing_ok=True)
        self.json(job_id).unlink(missing_ok=True)
        if stopped:
            log.info("stopped %s before it ran", job_id)
        elif job is not None:
            log.info("stopped %s; it is running and its picture will be dropped", job_id)
        else:
            log.info("claimed %s", job_id)
        return True

    # ---- housekeeping ------------------------------------------------------

    async def maintain(self) -> None:
        """Expire old jobs, and unload the model when nothing has used it."""
        while True:
            try:
                self.sweep()
                self._unload_if_idle()
            except Exception:  # never take the server down over housekeeping
                log.exception("generation housekeeping failed")
            await asyncio.sleep(TICK_S)

    def sweep(self) -> int:
        """
        Delete every job past its time: one handed to the device more than
        `serve_grace_s` ago, and one nobody ever came for after `keep_hours`.
        """
        now = time.time()
        old, collected, gone = now - self.keep_s, now - self.serve_grace_s, 0
        for path in self.dir.glob("g_*.json"):
            row = self._load(path)
            at = (row or {}).get("at", path.stat().st_mtime)
            served = (row or {}).get("served")
            if at < old or (served is not None and served < collected):
                job_id = path.stem
                self.jpg(job_id).unlink(missing_ok=True)
                self.src(job_id).unlink(missing_ok=True)
                path.unlink(missing_ok=True)
                gone += 1
        if gone:
            log.info("swept %d generation(s)", gone)
        return gone

    def _ensure_pool(self, model: ImageModel) -> ProcessPoolExecutor:
        if self._pool is not None and self._pool_model is not None and self._pool_model.repo != model.repo:
            # One local model loaded at a time: another entry's swaps it, after the
            # job in hand.
            log.info("switching the image worker to %s", model.repo)
            self._pool.shutdown(wait=False)
            self._pool = None
        if self._pool is None:
            log.info("starting the image worker (%s)", model.repo)
            self._pool = ProcessPoolExecutor(
                max_workers=1, mp_context=multiprocessing.get_context("spawn"),
                initializer=image_worker.init,
                initargs=(model.repo, model.family, model.variant),
            )
            self._pool_model = model
        return self._pool

    def _unload_if_idle(self) -> None:
        if self._pool is None or self._inflight or self.idle_unload_s <= 0:
            return
        if time.monotonic() - self._idle_since < self.idle_unload_s:
            return
        log.info("image worker idle for %.0f s, unloading", self.idle_unload_s)
        self._pool.shutdown(wait=False, cancel_futures=True)
        self._pool = None

    def close(self) -> None:
        if self._pool is not None:
            self._pool.shutdown(wait=False, cancel_futures=True)
            self._pool = None

    # ---- files -------------------------------------------------------------

    def jpg(self, job_id: str) -> Path:
        return self.dir / f"{job_id}.jpg"

    def src(self, job_id: str) -> Path:
        """The photo a transform started from; mflux takes a path, not bytes."""
        return self.dir / f"{job_id}.src.jpg"

    def json(self, job_id: str) -> Path:
        return self.dir / f"{job_id}.json"

    def _read(self, job_id: str) -> dict[str, Any] | None:
        return self._load(self.json(job_id))

    @staticmethod
    def _load(path: Path) -> dict[str, Any] | None:
        try:
            return json.loads(path.read_text())
        except (OSError, ValueError):
            return None

    def _write(self, job_id: str, row: dict[str, Any]) -> None:
        tmp = self.json(job_id).with_suffix(".json.tmp")
        tmp.write_text(json.dumps(row, ensure_ascii=False, indent=1))
        tmp.replace(self.json(job_id))
