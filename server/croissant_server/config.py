"""Configuration, and the address-binding policy."""

from __future__ import annotations

import ipaddress
import logging
import platform
import sys
import re
import subprocess
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

# The project's downloaded model assets. Resolve from this file rather than the
# process working directory so `croissant-server` behaves the same from any folder.
MODEL_DIR = Path(__file__).resolve().parents[1] / "models"

# Where MLX runs: the local Whisper and the local pictures. Anywhere else the local
# Whisper is faster-whisper and pictures come from a provider.
APPLE_SILICON = sys.platform == "darwin" and platform.machine() == "arm64"

# Tailscale hands out addresses from the CGNAT range.
TAILNET = ipaddress.ip_network("100.64.0.0/10")


def tailnet_addresses() -> list[str]:
    """Every local IPv4 address that belongs to the tailnet."""
    try:
        out = subprocess.run(
            ["/sbin/ifconfig"], capture_output=True, text=True, timeout=5
        ).stdout
    except (OSError, subprocess.SubprocessError) as e:  # pragma: no cover
        log.warning("could not enumerate interfaces: %s", e)
        return []
    found = []
    for m in re.finditer(r"inet (\d+\.\d+\.\d+\.\d+)", out):
        addr = m.group(1)
        try:
            if ipaddress.ip_address(addr) in TAILNET:
                found.append(addr)
        except ValueError:
            continue
    return found


def default_hosts() -> list[str]:
    """
    Bind to loopback and the tailnet, never 0.0.0.0.

    0.0.0.0 would expose the microphone endpoint on whatever network the laptop
    happens to join. Loopback is needed because the Android emulator reaches the
    host through its 10.0.2.2 alias, which arrives on 127.0.0.1.
    """
    hosts = ["127.0.0.1", *tailnet_addresses()]
    if len(hosts) == 1:
        log.warning("no tailnet address found - the R1 will not be able to connect")
    return hosts


@dataclass(frozen=True)
class ImageModel:
    """
    One image model the server can run, and the settings that go with it.

    They differ enough that a bare repo name is not enough: a distilled checkpoint
    wants four steps and refuses any guidance but 1.0, a base one wants forty and a
    real guidance, and each family is a different mflux class. Switching model means
    switching all of it at once, which is what a key in IMAGE_MODELS does.
    """

    repo: str
    family: str               # which mflux class: z-image, flux2-klein or flux1
    variant: str              # the ModelConfig factory on that family, by name
    steps: int                # for a picture made from nothing
    guidance: float | None    # None leaves the model's own default alone
    transform_steps: int      # for a picture made from a photo
    transform_strength: float
    note: str = ""


IMAGE_MODELS: dict[str, ImageModel] = {
    # The one the picture feature was built and measured on, and the
    # default again since 2026-09-19. Local, like every other default asset.
    "z-image": ImageModel(
        str(MODEL_DIR / "z-image-turbo-mflux-4bit"), "z-image", "z_image_turbo",
        steps=9, guidance=None, transform_steps=20, transform_strength=0.2,
        note="Z-Image Turbo, 4-bit, 5.9 GB. Distilled, no guidance. 28 s a picture.",
    ),
    # FLUX.2 Klein 4B, 4-bit. Distilled: mflux exits on any guidance but 1.0, so the
    # base variants are the only FLUX.2 ones that take a real guidance.
    "klein": ImageModel(
        str(MODEL_DIR / "flux2-klein-4b-mflux-4bit"), "flux2-klein", "flux2_klein_4b",
        steps=4, guidance=1.0, transform_steps=8, transform_strength=0.2,
        note="FLUX.2 Klein 4B, 4-bit, 4.6 GB. Four steps, guidance fixed at 1.0.",
    ),
    # A Z-Image Turbo finetune, so every setting measured for z-image carries over and
    # it needs no new code path. Not gated, unlike fhdr. Not downloaded by default.
    "cyber": ImageModel(
        "avlp12/CyberRealistic-Z-Image-Turbo-v4-mflux-4bit", "z-image", "z_image_turbo",
        steps=9, guidance=None, transform_steps=20, transform_strength=0.2,
        note="CyberRealistic, a Z-Image Turbo finetune, 4-bit, 5.9 GB. Ungated.",
    ),
    # A FLUX.1-dev finetune, so it does take a real guidance; its card asks for 40
    # steps at guidance 4.0, which is slow on a 12B model. Gated on HuggingFace:
    # request access on the model page and put a token in HF_TOKEN before choosing it.
    "fhdr": ImageModel(
        "PocketAiHub/FHDR-Uncensored-MFLUX", "flux1", "dev",
        steps=40, guidance=4.0, transform_steps=40, transform_strength=0.35,
        note="FHDR, a FLUX.1-dev finetune, 4-bit, 9.6 GB. Gated; needs HF_TOKEN.",
    ),
}

def on_disk(model: str) -> int | None:
    """
    What one of this machine's models takes on disk, near what it holds in memory once
    loaded: its folder, or its download in the Hugging Face cache (an image model by its
    IMAGE_MODELS name). None when it is not here.
    """
    where = IMAGE_MODELS[model].repo if model in IMAGE_MODELS else model
    folder = Path(where).expanduser()
    if not folder.is_dir():
        from huggingface_hub.constants import HF_HUB_CACHE
        folder = Path(HF_HUB_CACHE) / f"models--{where.replace('/', '--')}" / "blobs"
    if not folder.is_dir():
        return None
    return sum(f.stat().st_size for f in folder.rglob("*") if f.is_file()) or None


# The transform steps and strengths above are guesses for everything but z-image,
# which is the only one measured. Turn them with --transform-steps/--transform-keep.


@dataclass
class Config:
    hosts: list[str] = field(default_factory=default_hosts)
    port: int = 8765
    path: str = "/session"

    # The MLX weights in models/ on Apple Silicon; faster-whisper's own name for the
    # same model anywhere else (it fetches the CTranslate2 build on first use).
    stt_model: str = str(MODEL_DIR / "whisper-large-v3-turbo") if APPLE_SILICON else "large-v3-turbo"
    # `--stt`: a provider:model id, or a local Whisper (a path or a
    # repo), which is what it always was. None: `stt_model`.
    stt: str | None = None
    # Forced language. None lets Whisper detect it, which is unreliable on a short
    # clip and costs a pass; set explicitly when the device is used in one language.
    stt_language: str | None = "en"
    # Whisper is re-run over the whole utterance so far this often for a partial.
    stt_partial_interval_s: float = 1.0
    # Level gate applied before Whisper sees anything (it hallucinates on silence).
    # Real R1 speech measures ~-32 dBFS active, ~37 dB above the floor; the ring
    # pre-roll alone measures ~-64 and ~6 dB above. Both thresholds sit mid-way.
    stt_gate_min_active_dbfs: float = -50.0
    stt_gate_min_snr_db: float = 15.0

    # The stock Qwen3.5-9B again since 2026-09-24. The abliterated import
    # (`qwen3.5-abliterated:9b`, still installed, `--llm` picks it) is blind: its
    # vision patch embedding kept MLX's channels-last layout, so every photo reached
    # the model as stripes.
    # Ask's system prompt is the device's, one picked per model there (android data/AskPrompt.kt).
    llm_model: str = "qwen3.5:9b-mlx"
    # `--llm`: a provider:model id, or an Ollama model name, which
    # is what it always was. None: `llm_model`.
    llm: str | None = None
    # Which models run where (providers.py). Gitignored: it holds
    # keys. Missing is fine: everything then runs as it did before there was one.
    providers_path: str = "providers.json"

    # Web search (search.py). Keyless: ddgs talks to the search services directly, so
    # there is nothing to sign up for and no third party is told what was asked. The
    # caps are the feature, not a limit we ran into: measured 2026-09-20, Ollama gives
    # this model its whole 262144-token window, so five results of forty words are
    # what a 9B can actually attend to rather than what fits.
    search: bool = True
    # The handful of questions answered from a real data source instead of a search
    # engine (sources.py): weather, currency and headlines. All keyless. The general
    # search answered "weather in london tomorrow" with five descriptions of weather
    # websites and no temperature, which is what these exist to fix.
    search_sources: bool = True
    # What a weather question means when it names no place. None asks the search
    # instead of guessing at a city.
    search_home: str | None = "London"
    search_results: int = 5
    search_words: int = 40
    search_timeout_s: float = 4.0

    # Nothing the device says is kept on the Mac: a question is answered and
    # forgotten. `--debug-keep` turns the three stores back on for a debugging run -
    # history.jsonl (the exchange log, which is also what a follow-up question
    # replays), the utterance WAVs with their level metrics (capture.py), and the
    # copy of a recording sent for transcription. With it off a follow-up question
    # starts from nothing, which is the price of keeping no log.
    debug_keep: bool = False
    # Where the utterance WAVs go when they are kept. Relative to the cwd.
    capture_dir: str = "captures"
    # history.jsonl, the recordings, the generated pictures and, later, the device
    # backups. Relative to the cwd.
    data_dir: str = "data"

    def image(self, name: str | None = None, options: dict | None = None) -> ImageModel:
        """
        A local image model with its settings: a providers.json entry's own options
        (`steps`, `guidance`, `transform_steps`, `transform_keep`) first, then the
        command line's, then the model's row. `name` defaults to `--image-model`.
        """
        name = name or self.image_model
        o = options or {}
        base = IMAGE_MODELS.get(name)
        if base is None:   # a raw repo: assume Z-Image, which is what this server knows
            base = replace(IMAGE_MODELS["z-image"], repo=name,
                           note="given as a repo; assumed to be a Z-Image build")

        def pick(key: str, flag: Any, own: Any) -> Any:
            return o[key] if o.get(key) is not None else flag if flag is not None else own

        return replace(
            base,
            steps=int(pick("steps", self.image_steps, base.steps)),
            guidance=pick("guidance", self.image_guidance, base.guidance),
            transform_steps=int(pick("transform_steps", self.image_transform_steps, base.transform_steps)),
            transform_strength=float(pick("transform_keep", self.image_transform_strength,
                                          base.transform_strength)),
        )

    # Pictures: mflux with the local, pre-quantised Z-Image Turbo build. 768x1024
    # is the R1 screen's own 3:4 shape (2026-09-18).
    images: bool = True
    # A key in IMAGE_MODELS, or a raw HuggingFace repo (treated as Z-Image). Set to
    # whichever is under test, so a bare `uv run croissant-server` runs that one;
    # --image-model klein goes to the FLUX.2 build instead. It is the local picture
    # model both picture slots fall back to with no providers file.
    image_model: str = "z-image"
    # `--image-model` given: that local model goes first in both picture slots, ahead of
    # the providers file's entries, as `--llm` does for chat.
    image_pick: str | None = None
    image_width: int = 768
    image_height: int = 1024
    # None on any of these four means "whatever the chosen model asks for".
    image_steps: int | None = None
    image_guidance: float | None = None
    # Transforming a photo (img2img). Measured 2026-09-18 on the M5 Pro: `strength` is
    # the fraction of the steps SKIPPED, so a high one keeps
    # the photo and runs fewer steps. Turbo is distilled and runs at guidance 0, so a
    # style prompt only bites once enough steps actually run: 0.2 of 20 leaves 16, which
    # is a real restyle with the composition kept, in about 41 s. Higher strengths just
    # clean the photo up and ignore the prompt.
    image_transform_steps: int | None = None
    image_transform_strength: float | None = None
    # How long a picture nobody has collected is kept on the server.
    image_keep_hours: float = 24.0
    # How long a picture the device has already been handed is kept. The device
    # deletes it the moment the bytes are on its disk, so this only covers a DELETE
    # that never landed: long enough that a lost response can be fetched again,
    # short enough that a collected picture is not sitting here for a day.
    image_serve_grace_s: float = 300.0
    # The worker holds ~6 GB while it is loaded, so it is shut down after this long
    # with nothing to do. 0 keeps it loaded for good.
    image_idle_unload_s: float = 900.0
