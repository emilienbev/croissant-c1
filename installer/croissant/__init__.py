"""croissant: put Croissant on a Rabbit R1."""

from importlib.metadata import PackageNotFoundError, version

try:
    # From pyproject.toml, which scripts/bump-version.sh keeps equal to VERSION at the repo root.
    __version__ = version('croissant')
except PackageNotFoundError:  # run from the source tree without being installed
    __version__ = 'unknown'
