"""The plain-HTTP routes (app.py `http_routes`). Run: uv run python -m unittest discover tests"""

import json
import unittest

from websockets.datastructures import Headers
from websockets.http11 import Request

from croissant_server.app import http_routes


class FakeSearch:
    def __init__(self):
        self.asked = []

    async def digest(self, query):
        self.asked.append(query)
        return f'Web results for "{query}":\n1. example.com: twelve.'


class FakeCosts:
    def public(self):
        return {"chat": {"default": None, "entries": []}}


def get(process, path):
    return process(None, Request(path, Headers()))


class SearchTest(unittest.IsolatedAsyncioTestCase):
    async def test_the_digest_as_text(self):
        search = FakeSearch()
        r = await get(http_routes(None, FakeCosts(), search), "/search?q=f1%20spanish%20grand+prix%20%20result")
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.headers["Content-Type"], "text/plain; charset=utf-8")
        self.assertEqual(r.body.decode(), 'Web results for "f1 spanish grand prix result":\n1. example.com: twelve.')
        self.assertEqual(search.asked, ["f1 spanish grand prix result"])

    def test_no_words_is_a_400(self):
        search = FakeSearch()
        for path in ("/search", "/search?q=", "/search?q=%20%20"):
            r = get(http_routes(None, FakeCosts(), search), path)
            self.assertEqual((r.status_code, json.loads(r.body)), (400, {"error": "query is required"}), path)
        self.assertEqual(search.asked, [])

    def test_the_search_off_is_a_404(self):
        r = get(http_routes(None, FakeCosts()), "/search?q=bitcoin")
        self.assertEqual(r.status_code, 404)


class ProvidersTest(unittest.TestCase):
    def test_the_tools_it_runs_for_the_c1(self):
        r = get(http_routes(None, FakeCosts(), FakeSearch(), tools=("image_generate", "web_search")), "/providers")
        body = json.loads(r.body)
        self.assertEqual(body["tools"], ["image_generate", "web_search"])
        self.assertIn("chat", body)

    def test_the_mailbox_is_gone(self):
        self.assertIsNone(get(http_routes(None, FakeCosts()), "/turns/t_0123456789abcdef"))


if __name__ == "__main__":
    unittest.main()
