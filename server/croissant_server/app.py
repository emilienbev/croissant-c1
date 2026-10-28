"""WebSocket entry point."""

from __future__ import annotations

import asyncio
import http
import json
import logging
import multiprocessing
import re
import sys
from collections.abc import Awaitable
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path
from urllib.parse import parse_qs

from websockets.asyncio.server import ServerConnection, serve
from websockets.datastructures import Headers
from websockets.http11 import Request, Response

from . import __version__, pairing, settings, stt_worker
from .config import APPLE_SILICON, IMAGE_MODELS, Config
from .costs import Costs
from .history import History
from .images import Generations
from .ledger import Ledger
from .prices import Prices
from .providers import Providers
from .search import WebSearch
from .session import Session
from .stt import Stt, engine

log = logging.getLogger(__name__)

# Only an id this server minted can be read back, so nothing else on disk is reachable.
GENERATED = re.compile(r"^/generated/(g_[0-9a-f]{6})/?$")


def _response(code: http.HTTPStatus, body: bytes, content_type: str | None = None) -> Response:
    headers = Headers([("Connection", "close"), ("Content-Length", str(len(body)))])
    if content_type is not None:
        headers["Content-Type"] = content_type
    return Response(code.value, code.phrase, headers, body)


def _json(code: http.HTTPStatus, **payload) -> Response:
    return _response(code, json.dumps(payload).encode(), "application/json")


def _arg(request: Request, name: str) -> str:
    """One query parameter, decoded; "" when it is not there."""
    return (parse_qs(request.path.partition("?")[2]).get(name) or [""])[0]


async def _digest(search: WebSearch, query: str) -> Response:
    """`GET /search`'s answer: the digest as text, written to be read (search.py, `format`)."""
    return _response(http.HTTPStatus.OK, (await search.digest(query)).encode(), "text/plain; charset=utf-8")


def http_routes(generations: Generations | None, costs: Costs, search: WebSearch | None = None,
                tools: tuple[str, ...] = ()):
    """
    The plain-HTTP side of the same port, answered before the WebSocket upgrade.

    `GET /` is the liveness check: `croissant-server ok <version>`, which the device's
    ServerFinder matches on its first word, and `GET /version` the same as JSON. The two `/generated/<id>` routes are
    how the device collects a picture: it polls the GET until it has the bytes, then
    DELETEs to say it has them. `GET /search?q=<words>` is Ask's `web_search` for the C1,
    which runs the loop: the digest as plain text, what the tool answered when it ran here,
    400 with no words and 404 with the search off. `GET /providers` is what the device may
    pick from, with what each costs, and `tools`, the Ask tools this server runs for it and
    so the device offers (`image_generate` when something can draw, by the `generate`
    frame; `web_search` while the search is on); `GET /usage` the last 30 days of use
    (costs.py), and `/settings` the page that edits the providers (settings.py), which then
    talks on `/config`. Everything else falls through and upgrades.
    """

    def process(connection: ServerConnection, request: Request) -> Response | Awaitable[Response] | None:
        path = request.path.split("?", 1)[0]
        if path == "/" and "Upgrade" not in request.headers:
            return connection.respond(http.HTTPStatus.OK, f"croissant-server ok {__version__}\n")
        if request.method == "GET" and "Upgrade" not in request.headers:
            if path == "/version":
                return _json(http.HTTPStatus.OK, name="croissant-server", version=__version__)
            if path == "/providers":
                return _json(http.HTTPStatus.OK, **costs.public(), tools=list(tools))
            if path == "/search":
                if search is None:
                    return _json(http.HTTPStatus.NOT_FOUND, error="web search is disabled on this server")
                query = " ".join(_arg(request, "q").split())
                if not query:
                    return _json(http.HTTPStatus.BAD_REQUEST, error="query is required")
                return _digest(search, query)
            if path == "/usage":
                return _json(http.HTTPStatus.OK, **costs.usage())
            if path.rstrip("/") == "/settings":
                return _response(http.HTTPStatus.OK, settings.page(), "text/html; charset=utf-8")
            if path.startswith("/settings/font/"):
                data = settings.font(path.rsplit("/", 1)[-1])
                if data is None:
                    return _json(http.HTTPStatus.NOT_FOUND, error="no such font")
                return _response(http.HTTPStatus.OK, data, "font/ttf")

        m = GENERATED.match(path)
        if m is None:
            return None
        if generations is None:
            return _json(http.HTTPStatus.NOT_FOUND, error="images are disabled")
        job_id = m.group(1)

        if request.method == "DELETE":
            generations.claim(job_id)
            return _response(http.HTTPStatus.NO_CONTENT, b"")
        if request.method != "GET":
            resp = connection.respond(http.HTTPStatus.METHOD_NOT_ALLOWED, "GET or DELETE\n")
            resp.headers["Allow"] = "GET, DELETE"
            return resp

        status, jpeg, error = generations.get(job_id)
        if status == "done" and jpeg is not None:
            return _response(http.HTTPStatus.OK, jpeg, "image/jpeg")
        if status == "error":
            return _json(http.HTTPStatus.INTERNAL_SERVER_ERROR, error=error or "failed")
        if status == "gone":
            return _json(http.HTTPStatus.NOT_FOUND, error="no such generation")
        return _json(http.HTTPStatus.ACCEPTED, status="running")

    return process


async def run(cfg: Config) -> None:
    # What this machine runs itself, per slot: the device may pick these too.
    pictures = list(IMAGE_MODELS) if cfg.images and APPLE_SILICON else []
    local = {"stt": [cfg.stt_model] if APPLE_SILICON else
             ["large-v3-turbo", "large-v3", "medium", "small", "base", "tiny"],
             "text_to_image": pictures, "image_to_image": pictures}
    # Which models run where (providers.py); the settings page saves and reloads it in place.
    providers = Providers.load(
        Path(cfg.providers_path).resolve(),
        fallback={"chat": ("ollama", cfg.llm_model), "stt": ("local", cfg.stt_model),
                  # Local pictures are MLX: anywhere else they come from the file alone.
                  **({"text_to_image": ("local", cfg.image_model),
                      "image_to_image": ("local", cfg.image_model)}
                     if cfg.images and APPLE_SILICON else {})},
        pick={"chat": cfg.llm, "stt": cfg.stt,
              "text_to_image": cfg.image_pick, "image_to_image": cfg.image_pick},
        local=local,
    )
    data_dir = Path(cfg.data_dir).resolve()
    # Every model call counted (ledger.py) and priced (prices.py, costs.py): counts and
    # model ids only, never what was said.
    ledger = Ledger(data_dir / "usage.jsonl")
    prices = Prices(providers.path.with_suffix(".prices.json") if providers.path else None)
    costs = Costs(providers, prices, ledger)
    # Whole recordings on a local Whisper are transcribed in a worker process with its
    # own model, so an hour of a meeting cannot freeze the holds (stt_worker.py). Only
    # when a local Whisper on MLX is configured: a provider's needs no process and no
    # weights, and faster-whisper runs a recording in a thread (stt.py).
    local_stt = [e for e in providers.entries("stt") if e.provider.kind == "local" and engine(e) == "mlx"]
    pool = ProcessPoolExecutor(
        max_workers=1, mp_context=multiprocessing.get_context("spawn"),
        initializer=stt_worker.init, initargs=(local_stt[0].model, cfg.stt_language),
    ) if local_stt else None
    stt = Stt(
        providers,
        cfg.stt_language,
        cfg.stt_partial_interval_s,
        cfg.stt_gate_min_active_dbfs,
        cfg.stt_gate_min_snr_db,
        pool,
        ledger,
    )
    # Made now: a local Whisper loads here rather than on the first hold, and a
    # provider with no key says so at startup (and again on every hold, as an error).
    try:
        stt.backend()
    except Exception as e:
        log.error("the default speech to text cannot start: %s", e)
    drawing = bool(providers.entries("text_to_image"))
    generations = Generations(
        data_dir / "generated", providers, lambda e: cfg.image(e.model, e.options),
        cfg.image_width, cfg.image_height,
        cfg.image_keep_hours, cfg.image_idle_unload_s, cfg.image_serve_grace_s, ledger,
    ) if cfg.images and (drawing or providers.entries("image_to_image")) else None
    search = WebSearch(
        results=cfg.search_results, words=cfg.search_words, timeout=cfg.search_timeout_s,
        sources=cfg.search_sources, home=cfg.search_home,
    ) if cfg.search else None
    # Ask's tools this server runs for the C1, which offers them while `GET /providers`
    # lists them (its catalog's `"runs": "server"`): a picture by the `generate` frame
    # when something can draw, the web by `GET /search`.
    ask_tools = tuple(n for n, on in (("image_generate", generations is not None and drawing),
                                      ("web_search", search is not None)) if on)
    # What the device said is not kept (config.py, `debug_keep`): no exchange log, no
    # utterance WAVs, no copy of a recording. All three come back together, for a
    # debugging run only.
    history = History(data_dir / "history.jsonl" if cfg.debug_keep else None)
    capture_dir = Path(cfg.capture_dir).resolve() if cfg.debug_keep else None
    recordings_dir = data_dir / "recordings" if cfg.debug_keep else None

    # The recordings' worker is warmed in the background so the first recording does
    # not pay the load.
    if pool is not None:
        warming = asyncio.get_running_loop().run_in_executor(pool, stt_worker.warm)
        warming.add_done_callback(lambda f: log.info("transcription worker ready") if f.exception() is None
                                  else log.error("transcription worker failed: %s", f.exception()))

    async def handler(ws: ServerConnection) -> None:
        if ws.request.path.rstrip("/") == "/config":
            await settings.handle(ws, providers)
            return
        if ws.request.path.rstrip("/") != cfg.path.rstrip("/"):
            await ws.close(1008, "unknown path")
            return
        await Session(ws, stt, history, capture_dir, recordings_dir, generations).run()

    housekeeping: asyncio.Task | None = None
    if generations is not None:
        generations.sweep()
        housekeeping = asyncio.create_task(generations.maintain())
    # Each provider that has not listed its models yet is asked once, in the background.
    listing = asyncio.create_task(settings.fill(providers))
    pricing = asyncio.create_task(prices.refresh())

    async with serve(
        handler,
        cfg.hosts,
        cfg.port,
        process_request=http_routes(generations, costs, search, tools=ask_tools),
        max_size=4 * 1024 * 1024,
        ping_interval=20,
    ):
        for h in cfg.hosts:
            log.info("listening on ws://%s:%d%s", h, cfg.port, cfg.path)
        log.info("settings page on http://%s:%d/settings", cfg.hosts[-1], cfg.port)
        pairing.announce(cfg.hosts, cfg.port, color=sys.stdout.isatty())
        h = providers.default("stt")
        log.info("stt=%s  Ask's tools run here: %s", h.label() if h else "none", ", ".join(ask_tools) or "none")
        log.info("  the device may pick among %s", ", ".join(
            f"{len(providers.entries(s))} {s}" for s in ("chat", "stt", "text_to_image", "image_to_image")))
        log.info("  providers from %s", providers.path if providers.path and providers.path.exists()
                 else "the defaults (no providers file)")
        if cfg.debug_keep:
            log.warning("--debug-keep: history to %s, utterances to %s, recordings to %s",
                        history.path, capture_dir, recordings_dir)
        else:
            log.info("nothing the device says is kept: no history, no utterance WAVs, "
                     "no recording copies")
        if search is not None:
            log.info("web search on  home=%s  sources=%s", cfg.search_home or "none",
                     "on" if cfg.search_sources else "off")
        else:
            log.info("web search disabled")
        if generations is not None:
            # Said loudly and in one place: this is the line to read to know which
            # model a test on the device is actually testing.
            for slot, what in (("text_to_image", "IMAGE MODEL"), ("image_to_image", "TRANSFORM MODEL")):
                e = generations.entry(slot)
                if e is None:
                    log.info("%s: none", what)
                elif e.provider.kind != "local":
                    log.info("%s: %s", what, e.label())
                else:
                    image = cfg.image(e.model, e.options)
                    log.info("%s: %s  (%s)", what, e.model, image.repo)
                    log.info("  %s", image.note)
                    log.info("  %dx%d, %d steps, guidance %s; transform %d steps keeping %.2f",
                             cfg.image_width, cfg.image_height, image.steps,
                             "model default" if image.guidance is None else image.guidance,
                             image.transform_steps, image.transform_strength)
            log.info("pictures kept %.0f h, to %s", cfg.image_keep_hours, generations.dir)
        else:
            log.info("image generation disabled")
        try:
            await asyncio.get_running_loop().create_future()  # run forever
        finally:
            if pool is not None:
                pool.shutdown(wait=False, cancel_futures=True)
            listing.cancel()
            pricing.cancel()
            if housekeeping is not None:
                housekeeping.cancel()
            if generations is not None:
                generations.close()
