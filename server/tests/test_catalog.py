"""Gemini's free-tier probe. Run: uv run python -m unittest discover tests"""

import asyncio
import unittest
from types import SimpleNamespace
from unittest import mock

from google.genai import errors

from croissant_server import catalog


def _client(answers: dict[str, object]):
    """A Gemini client whose models answer as `answers` says: "ok", "hang" or an APIError."""
    asked: list[str] = []

    async def generate_content(model, contents, config):
        asked.append(model)
        a = answers.get(model, "ok")
        if a == "hang":
            await asyncio.sleep(60)
        elif isinstance(a, Exception):
            raise a
        return SimpleNamespace(text="hi")

    return SimpleNamespace(aio=SimpleNamespace(models=SimpleNamespace(generate_content=generate_content))), asked


def _error(code: int, message: str = "") -> errors.APIError:
    cls = errors.ServerError if code >= 500 else errors.ClientError
    return cls(code, {"error": {"code": code, "message": message, "status": ""}})


FREE = _error(429, "Quota exceeded for metric: generate_content_free_tier_requests, limit: 0")
CHAT = ["gemini-3-flash", "gemini-3.1-pro-preview", "gemini-3.1-pro-preview-customtools", "gemini-2.5-pro"]


class GeminiFreeTest(unittest.IsolatedAsyncioTestCase):
    async def test_plain_name_before_its_variants_newest_first(self):
        client, asked = _client({})
        self.assertFalse(await catalog._gemini_free(client, CHAT))
        self.assertEqual(asked, ["gemini-3.1-pro-preview"])

    async def test_free_tier_refusal(self):
        client, _ = _client({"gemini-3.1-pro-preview": FREE})
        self.assertTrue(await catalog._gemini_free(client, CHAT))

    async def test_closed_and_failing_pass_to_the_next(self):
        client, asked = _client({"gemini-3.1-pro-preview": _error(404), "gemini-2.5-pro": _error(504)})
        self.assertFalse(await catalog._gemini_free(client, CHAT))
        self.assertEqual(asked, ["gemini-3.1-pro-preview", "gemini-2.5-pro", "gemini-3.1-pro-preview-customtools"])

    async def test_a_failing_one_then_the_free_tier(self):
        client, _ = _client({"gemini-3.1-pro-preview": _error(503), "gemini-2.5-pro": FREE})
        self.assertTrue(await catalog._gemini_free(client, CHAT))

    async def test_slow_cannot_tell_within_the_budget(self):
        client, asked = _client({"gemini-3.1-pro-preview": "hang", "gemini-2.5-pro": FREE})
        with mock.patch.object(catalog, "PROBE_S", 0.05):
            self.assertFalse(await asyncio.wait_for(catalog._gemini_free(client, CHAT), 1))
        self.assertEqual(asked, ["gemini-3.1-pro-preview"])

    async def test_other_refusals_say_paid(self):
        client, asked = _client({"gemini-3.1-pro-preview": _error(429, "Resource exhausted")})
        self.assertFalse(await catalog._gemini_free(client, CHAT))
        self.assertEqual(asked, ["gemini-3.1-pro-preview"])

    async def test_no_pro_model(self):
        client, asked = _client({})
        self.assertFalse(await catalog._gemini_free(client, ["gemini-3-flash"]))
        self.assertEqual(asked, [])


class OpenAiSlotsTest(unittest.TestCase):
    def test_mistral_by_its_capabilities_though_it_has_a_type(self):
        d = {"id": "mistral-large-latest", "type": "base",
             "capabilities": {"completion_chat": True, "function_calling": True}}
        self.assertEqual(catalog._openai(d["id"], d), {"chat"})

    def test_together_by_its_type(self):
        self.assertEqual(catalog._openai("black-forest-labs/FLUX.1-schnell", {"type": "image"}), {"text_to_image"})

    def test_an_instruct_chat_model_is_chat_and_turbo_instruct_is_not(self):
        self.assertEqual(catalog._openai("qwen2.5-7b-instruct", {"id": "qwen2.5-7b-instruct"}), {"chat"})
        self.assertEqual(catalog._openai("gpt-3.5-turbo-instruct", {"id": "gpt-3.5-turbo-instruct"}), set())


if __name__ == "__main__":
    unittest.main()
