"""Every module of the server and every tool imports: nothing left calling what went. Run: uv run python -m unittest discover tests"""

import importlib
import pkgutil
import sys
import unittest
from pathlib import Path

import croissant_server

TOOLS = Path(__file__).resolve().parents[1] / "tools"
sys.path.insert(0, str(TOOLS))


class ImportsTest(unittest.TestCase):
    def test_the_package(self):
        for m in pkgutil.iter_modules(croissant_server.__path__):
            with self.subTest(m.name):
                importlib.import_module(f"croissant_server.{m.name}")

    def test_the_tools(self):
        for path in sorted(TOOLS.glob("*.py")):
            with self.subTest(path.stem):
                importlib.import_module(path.stem)


if __name__ == "__main__":
    unittest.main()
