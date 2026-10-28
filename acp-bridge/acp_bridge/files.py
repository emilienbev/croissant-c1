"""A session's folder, for the R1 to look through: its entries, and a file read as text or bytes.

Only inside the folder the R1 names (a session's cwd): a path that leads out of it, through
`..` or a link, is refused. Read only: nothing here writes.
"""

import base64
import os
from pathlib import Path

from .acp import Json

# Left out of a listing: git's own folder and the Mac's folder notes.
HIDDEN = {".git", ".DS_Store"}

# The most read as text, and as bytes (a picture), at once.
TEXT_MAX = 1024 * 1024
BYTES_MAX = 12 * 1024 * 1024


class Outside(Exception):
    """A path the R1 cannot have."""


def inside(cwd: str, path: str) -> Path:
    root = Path(cwd)
    if not root.is_absolute() or not root.is_dir():
        raise Outside(f"no folder {cwd}")
    root = root.resolve()
    target = (root / path.lstrip("/")).resolve()
    if target != root and root not in target.parents:
        raise Outside("that is outside the session's folder")
    return target


def listing(cwd: str, path: str) -> Json:
    """The entries of a folder in [cwd], folders first, each by name: {"name", "dir", "size", "mtime"}."""
    folder = inside(cwd, path)
    if not folder.is_dir():
        raise Outside(f"no folder {path}")
    entries = []
    with os.scandir(folder) as found:
        for e in found:
            if e.name in HIDDEN:
                continue
            try:
                st = e.stat()
                d = e.is_dir()
            except OSError:
                continue
            entries.append({"name": e.name, "dir": d, "size": 0 if d else st.st_size, "mtime": int(st.st_mtime * 1000)})
    entries.sort(key=lambda x: (not x["dir"], x["name"].lower()))
    return {"entries": entries}


def read(cwd: str, path: str, as_bytes: bool) -> Json:
    """A file in [cwd]: its text ({"text"}, or {"binary": true} when it is not text), or its bytes ({"base64"})."""
    f = inside(cwd, path)
    if not f.is_file():
        raise Outside(f"no file {path}")
    size = f.stat().st_size
    if as_bytes:
        if size > BYTES_MAX:
            raise Outside("too big to open on the R1")
        return {"size": size, "base64": base64.b64encode(f.read_bytes()).decode()}
    if size > TEXT_MAX:
        return {"size": size, "binary": True, "tooBig": True}
    data = f.read_bytes()
    try:
        return {"size": size, "text": data.decode("utf-8")}
    except UnicodeDecodeError:
        return {"size": size, "binary": True}
