"""Z-Image in a worker process, for generated pictures.

Same reason as stt_worker.py: MLX streams are thread-local, so a thread is out, and
a generation takes tens of seconds - long enough to freeze every hold if it ran on
the event loop. One worker, one picture at a time.

The weights are a pre-quantised 4-bit build, so no `quantize=` argument: the repo is
already quantised and asking for it again would be wrong. The model is built on the
first call and kept; the pool is what gets shut down when the Mac has been idle
(images.py), and that takes its memory with it.

Which model runs is a key in `config.IMAGE_MODELS`, and the family decides the mflux
class: Z-Image, FLUX.2 Klein or FLUX.1. Their `generate_image` signatures agree on
everything passed here, so only the construction differs.

The same model does img2img, from a photo the device sent. `strength` is how much
of the photo is kept: mflux turns it into the number of steps *skipped* at the
start, so a high strength runs fewer steps and stays closer to the photo. Turbo is
distilled and runs at guidance 0, so a style prompt only bites once enough steps
actually run.
"""

from __future__ import annotations

import json
import logging
import struct
import time
from pathlib import Path

log = logging.getLogger(__name__)

_model = None
_model_path: str = ""
_family: str = "z-image"
_variant: str = "z_image_turbo"


def init(model_path: str, family: str = "z-image", variant: str = "z_image_turbo") -> None:
    """The pool's initializer: which weights and which family, no loading (see `load`)."""
    global _model_path, _family, _variant
    _model_path, _family, _variant = model_path, family, variant
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)-5s image: %(message)s", datefmt="%H:%M:%S")


def _short_by(path: Path) -> int:
    """
    How many bytes a safetensors file is missing, 0 when it looks whole.

    A safetensors file states its own length: eight bytes of header length, the JSON
    header, then the tensor data, which ends at the largest offset the header names. An
    interrupted download leaves a file short by exactly the bytes that never arrived,
    and a loader reports that as "Unable to load weights from checkpoint file" with no
    hint that the file is simply incomplete (one checkpoint was 61 MB short and every
    generation failed with nothing else to go on). Reading one
    header is cheap next to loading several gigabytes, so it is read first and the real
    problem is named.

    Bigger than expected is not an error: nothing here writes these files, and a
    checkpoint with trailing padding still loads.
    """
    size = path.stat().st_size
    with path.open("rb") as f:
        raw = f.read(8)
        if len(raw) < 8:
            return 8 - size
        header_len = struct.unpack("<Q", raw)[0]
        if header_len <= 0 or 8 + header_len > size:
            return 8 + header_len - size
        header = json.loads(f.read(header_len))
    end = max((v["data_offsets"][1] for k, v in header.items()
               if k != "__metadata__" and isinstance(v, dict) and "data_offsets" in v),
              default=0)
    return max(0, 8 + header_len + end - size)


def _verify(model_path: str) -> None:
    """
    Refuse to build a model whose weights did not finish downloading.

    Every safetensors file under the model directory, in whatever layout the family
    uses. A file that cannot be parsed at all is left to mflux, which will say so
    itself; only a short one is caught here, because that is the failure that otherwise
    arrives as a shrug. Under 10 ms across a 6 GB checkpoint.
    """
    for path in sorted(Path(model_path).rglob("*.safetensors")):
        try:
            short = _short_by(path)
        except (OSError, ValueError, KeyError, struct.error):
            continue  # unreadable or not safetensors: mflux's to report
        if short > 0:
            raise RuntimeError(
                f"{path} is {short / 1e6:.1f} MB short of its "
                f"{path.stat().st_size + short} bytes: the download did not finish. "
                f"Re-run the hf download for this model (server/models/README.md)."
            )


def _build(family: str, variant: str, model_path: str):
    """
    The mflux class for a family, built on the named ModelConfig.

    Three families, one call each; they take the same arguments and their
    `generate_image` signatures agree on everything this server passes.
    """
    _verify(model_path)

    from mflux.models.common.config import ModelConfig

    config = getattr(ModelConfig, variant)()
    if family == "z-image":
        from mflux.models.z_image import ZImage
        return ZImage(model_config=config, model_path=model_path)
    if family == "flux2-klein":
        from mflux.models.flux2 import Flux2Klein
        return Flux2Klein(model_config=config, model_path=model_path)
    if family == "flux1":
        from mflux.models.flux.variants.txt2img.flux import Flux1
        return Flux1(model_config=config, model_path=model_path)
    raise ValueError(f"unknown image model family {family!r}")


def load() -> float:
    """Build the model if it is not built yet. Returns the seconds it took."""
    global _model
    if _model is not None:
        return 0.0
    t0 = time.monotonic()
    _model = _build(_family, _variant, _model_path)
    took = time.monotonic() - t0
    log.info("loaded %s (%s) in %.1fs", _model_path, _family, took)
    return took


def generate(
    prompt: str, seed: int, out: str, steps: int, width: int, height: int,
    source: str | None = None, strength: float | None = None,
    guidance: float | None = None,
) -> dict:
    """
    One picture, written to `out` as a JPEG.

    With `source`, it is img2img from that file and `strength` is how much of it to
    keep. The PIL image is saved directly rather than through `GeneratedImage.save`,
    which renames the file when one already exists and embeds EXIF metadata; the
    prompt is kept in our own sidecar instead (images.py).
    """
    loaded = load()
    assert _model is not None
    t0 = time.monotonic()
    kwargs = {}
    if guidance is not None:
        kwargs["guidance"] = guidance
    image = _model.generate_image(
        seed=seed,
        prompt=prompt,
        num_inference_steps=steps,
        width=width,
        height=height,
        image_path=source,
        image_strength=strength if source else None,
        **kwargs,
    )
    image.image.convert("RGB").save(out, "JPEG", quality=92)
    took = time.monotonic() - t0
    log.info("%s %dx%d in %.1fs (seed %d, %d steps%s)",
             "transformed" if source else "generated", width, height, took, seed, steps,
             f", keeping {strength}" if source else "")
    row = {"seconds": round(took, 1), "load_seconds": round(loaded, 1),
           "width": width, "height": height, "seed": seed, "steps": steps,
           "model": _model_path}
    if guidance is not None:
        row["guidance"] = guidance
    if source:
        row["strength"] = strength
    return row
