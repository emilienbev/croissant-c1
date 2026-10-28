"""croissant-server: the Croissant C1's voice backend."""

from importlib.metadata import PackageNotFoundError, version

try:
    # From pyproject.toml, which scripts/bump-version.sh keeps equal to VERSION at the repo root.
    __version__ = version("croissant-server")
except PackageNotFoundError:  # run from the source tree without being installed
    __version__ = "unknown"
