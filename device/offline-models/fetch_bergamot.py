"""Fetch the Firefox Translations (Bergamot) models for the Translator's pairs.

Reads Foxlet's registry.json (github.com/yinvoke/foxlet-translate, v0.5.0), which lists
Mozilla's own CDN URLs and SHA-256 for each file, downloads the ten directions between
English and fr, de, it, es, ja, checks every hash, and writes one directory a pair with
the model, vocab(s), shortlist and a config.yml for Foxlet's `smoke` CLI whose paths
point at DEVICE_DIR.

    uv run python device/offline-models/fetch_bergamot.py REGISTRY OUT [--device-dir DIR]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import urllib.request
from pathlib import Path

LANGS = ["fr", "de", "it", "es", "ja"]


def config(files: dict[str, str], d: str) -> str:
    vocabs = [files[k] for k in ("srcvocab", "trgvocab") if k in files] or [files["vocab"]] * 2
    return "\n".join([
        f"models:\n  - {d}/{files['model']}",
        "vocabs:", *[f"  - {d}/{v}" for v in vocabs],
        f"shortlist:\n  - {d}/{files['lex']}\n  - false",
        "beam-size: 1", "normalize: 1.0", "word-penalty: 0", "max-length-break: 128",
        "mini-batch-words: 512", "max-length-factor: 2.0", "skip-cost: true", "cpu-threads: 0",
        "quiet: true", "quiet-translation: true", "gemm-precision: int8shiftAlphaAll",
        "alignment: soft", "",
    ])


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("registry", type=Path)
    p.add_argument("out", type=Path)
    p.add_argument("--device-dir", default="/data/local/tmp/ov/m/bergamot")
    a = p.parse_args()
    models = json.load(open(a.registry))["models"]
    want = {("en", l) for l in LANGS} | {(l, "en") for l in LANGS}
    for m in models:
        pair = (m["from"], m["to"])
        if pair not in want:
            continue
        name = f"{pair[0]}{pair[1]}"
        d = a.out / name
        d.mkdir(parents=True, exist_ok=True)
        files = {}
        for f in m["files"]:
            path = d / f["name"]
            if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != f["sha256"]:
                urllib.request.urlretrieve(f["url"], path)
            got = hashlib.sha256(path.read_bytes()).hexdigest()
            if got != f["sha256"]:
                raise SystemExit(f"{path}: sha256 {got} != {f['sha256']}")
            files[f["type"]] = f["name"]
        (d / "config.yml").write_text(config(files, f"{a.device_dir}/{name}"))
        size = sum(x.stat().st_size for x in d.iterdir()) / 1e6
        print(f"{name}  v{m['version']}  {size:.1f} MB  {sorted(files)}")
        want.discard(pair)
    if want:
        print("not in the registry:", sorted(want))


if __name__ == "__main__":
    main()
