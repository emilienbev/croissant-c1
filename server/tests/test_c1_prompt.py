"""Ask's prompt the C1's way (tools/c1_prompt.py) against the C1's own. Run: uv run python -m unittest discover tests"""

import json
import sys
import unittest
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))

import c1_prompt  # noqa: E402

GOLDEN = Path(__file__).resolve().parents[2] / "android/app/src/test/resources/ask/prompts.golden.json"
CATALOG = c1_prompt.load_catalog()


class GoldenTest(unittest.TestCase):
    """The holds the C1's AskPromptTest builds: a change goes there first, then here."""

    def test_every_hold_as_the_c1_builds_it(self):
        holds = json.loads(GOLDEN.read_text(encoding="utf-8"))
        self.assertGreaterEqual(len(holds), 5)
        for h in holds:
            given = h["in"]
            with self.subTest(h.get("name")):
                got = c1_prompt.hold(
                    CATALOG, entry=given.get("entry"), now=datetime.fromisoformat(given["now"]),
                    photo=given.get("photo", False), server_tools=given.get("server_tools"),
                    apps=given.get("apps", []), variant=given.get("variant"), text=given.get("text"))
                want = h["out"]
                self.assertEqual(got["variant"], want["variant"])
                self.assertEqual(got["system"].encode(), want["system"].encode())
                self.assertEqual(got["tools"], want["tools"])


class AppToolsTest(unittest.TestCase):
    """An app's tool as Atelier offers it: never under a reserved name, even one not on offer."""

    def test_reserved_names_are_left_out(self):
        tool = {"description": "d", "parameters": {"type": "object", "properties": {}}}
        apps = [{"id": "tape", "name": "Tape", "tool": {**tool, "name": "read"}},
                {"id": "web", "name": "Web", "tool": {**tool, "name": "search"}},
                {"id": "shop", "name": "Shop", "tool": {**tool, "name": "add"}}]
        got = c1_prompt.hold(CATALOG, entry=None, now=datetime.fromisoformat("2026-10-16T09:00:00+01:00"),
                             photo=False, server_tools=[], apps=apps)
        names = [t["name"] for t in got["tools"]]
        self.assertEqual(names[-1], "shop_add")
        self.assertNotIn("tape_read", names)
        self.assertNotIn("web_search", names)
        self.assertNotIn("web_search", got["system"])


class SplitTest(unittest.TestCase):
    """The old tool rules split: each tool's own rules in its description, the variants general."""

    MOVED = ["is agenda_add", "No day or time said", "For a timer", "for a timer", "What you can do, with your tools",
             "You have tools for", "- Agenda:", "- Timers:", "- Web:", "- Pictures:", "- Tapes:"]

    def tool(self, name):
        return next(t for t in CATALOG["tools"] if t["name"] == name)

    def test_the_seeds(self):
        self.assertEqual(list(CATALOG["prompts"]), ["default", "gemma"])
        self.assertEqual(list(CATALOG["photo"]), ["default", "gemma"])
        self.assertEqual(c1_prompt.suggested(CATALOG, "ollama:gemma4:26b-nvfp4"), "gemma")
        self.assertEqual(c1_prompt.suggested(CATALOG, "gemini:Gemma-4-26b-it"), "gemma")
        self.assertEqual(c1_prompt.suggested(CATALOG, "ollama:qwen3.5:9b-mlx"), "default")
        self.assertEqual(c1_prompt.suggested(CATALOG, None), "default")

    def test_no_tool_rule_is_left_in_a_variant(self):
        for name, text in CATALOG["prompts"].items():
            for s in self.MOVED:
                self.assertNotIn(s, text, name)
            self.assertTrue(text.startswith("You are the voice assistant on a small handheld device."), name)
            self.assertIn("Never refuse", text, name)

    def test_the_tools_own_rules_are_in_their_descriptions(self):
        add = self.tool("agenda_add")["description"]
        self.assertIn("is agenda_add", add)
        self.assertIn("No day or time said: no `at`.", add)
        self.assertIn("name only the duration that was asked for, never a finish time",
                      self.tool("timers_add")["description"])

    def test_the_servers_two_then_tape_ask(self):
        # tape_ask is the C1's since 2026-10-16: offered whatever the server runs.
        self.assertEqual([t["name"] for t in CATALOG["tools"] if t.get("runs") == "server"],
                         ["image_generate", "web_search"])
        self.assertEqual([t["name"] for t in CATALOG["tools"]][-3:], ["image_generate", "web_search", "tape_ask"])
        got = c1_prompt.hold(CATALOG, entry=None, now=datetime.fromisoformat("2026-10-16T09:00:00+01:00"),
                             photo=False, server_tools=[], apps=[])
        self.assertEqual([t["name"] for t in got["tools"]][-1], "tape_ask")


if __name__ == "__main__":
    unittest.main()
