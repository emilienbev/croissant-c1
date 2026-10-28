"""Ask's loop in Python for eval_tools (tools/c1_loop.py), on a model that answers as scripted. Run: uv run python -m unittest discover tests"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))

from c1_chat import Call, Reply  # noqa: E402
from c1_loop import Llm, Tool, as_messages  # noqa: E402
from croissant_server.providers import Entry, Provider  # noqa: E402

P = {"type": "object", "properties": {}}


class Scripted:
    """One round per item: its text deltas, then its calls."""

    def __init__(self, *rounds):
        self.rounds = list(rounds)
        self.seen = []

    async def stream(self, messages, tools):
        self.seen.append((list(messages), tools))
        deltas, calls = self.rounds.pop(0)
        for d in deltas:
            yield d
        yield Reply(calls)


def llm(*rounds):
    m = Llm(Entry("ollama:qwen", "qwen", Provider("ollama", "ollama", None, None, "Ollama"), "qwen"))
    m.backend = Scripted(*rounds)
    return m


class LoopTest(unittest.IsolatedAsyncioTestCase):
    async def test_a_tool_round_then_the_answer_held_back_until_it_ends(self):
        m = llm(([], [Call("web_search", {"query": "f1 result"}, "c1")]),
                (["Let me check."], [Call("web_search", {"query": "again"}, "c2")]),
                (["Verstappen ", "won."], []))
        calls = []

        async def execute(tool, args):
            calls.append((tool.name, args))
            return 'Web results for "f1 result":\n1. example.com: Verstappen.'

        out = [d async for d in m.answer("who won", system="S", prior=[], execute=execute,
                                         tools=[Tool("web_search", "look up", P)])]
        self.assertEqual(out, ["Verstappen won."])
        self.assertEqual(calls, [("web_search", {"query": "f1 result"}), ("web_search", {"query": "again"})])
        # A text result goes to the model as it is, not quoted.
        tool_msg = m.backend.seen[1][0][-1]
        self.assertEqual((tool_msg.role, tool_msg.text), ("tool", 'Web results for "f1 result":\n1. example.com: Verstappen.'))
        self.assertEqual(m.backend.seen[0][1], [Tool("web_search", "look up", P).spec()])

    async def test_the_first_round_is_live(self):
        m = llm((["Par", "is."], []))
        out = [d async for d in m.answer("capital of france", system="S", prior=as_messages([{"q": "hi", "a": "hello"}]),
                                         execute=None, tools=[])]
        self.assertEqual(out, ["Par", "is."])
        self.assertEqual([(x.role, x.text) for x in m.backend.seen[0][0]],
                         [("system", "S"), ("user", "hi"), ("assistant", "hello"), ("user", "capital of france")])
        self.assertIsNone(m.backend.seen[0][1])


if __name__ == "__main__":
    unittest.main()
