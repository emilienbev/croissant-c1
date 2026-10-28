"""Imported by every test before croissant: the installer's cache in a fresh temporary
directory, so no test writes into the checkout's cache/ (or wherever CROISSANT_CACHE
points). paths.CACHE is read once, when croissant is first imported."""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
os.environ['CROISSANT_CACHE'] = CACHE = tempfile.mkdtemp(prefix='croissant-test-')
os.environ.pop('CROISSANT_FONTS', None)

from croissant import paths  # noqa: E402

if paths.CACHE != Path(CACHE):
    raise RuntimeError(f'croissant was imported before the test cache was set: {paths.CACHE}')
