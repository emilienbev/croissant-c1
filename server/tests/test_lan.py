"""The LAN's gate (app.py `gated`). Run: uv run python -m unittest discover tests"""

import json
import unittest

from websockets.datastructures import Headers
from websockets.http11 import Request

from croissant_server.app import REFUSED, gated

TOKEN = "s3cret-token_0123456789abcdefghij"


class FakeConnection:
    def __init__(self, local, remote="192.168.1.20"):
        self.local_address = (local, 8765)
        self.remote_address = (remote, 50123)


def passed(connection, request):
    return "passed"


def ask(local, path, **headers):
    return gated(passed, TOKEN)(FakeConnection(local), Request(path, Headers(headers)))


class GateTest(unittest.TestCase):
    def test_loopback_and_the_tailnet_pass_as_before(self):
        self.assertEqual(ask("127.0.0.1", "/providers"), "passed")
        self.assertEqual(ask("100.100.100.103", "/session", Upgrade="websocket"), "passed")

    def test_the_lan_with_the_token_passes(self):
        self.assertEqual(ask("192.168.1.5", "/providers", Authorization=f"Bearer {TOKEN}"), "passed")
        self.assertEqual(ask("192.168.1.5", "/session", Upgrade="websocket", Authorization=f"Bearer {TOKEN}"),
                         "passed")

    def test_the_lan_without_the_token_is_refused(self):
        for headers in ({}, {"Authorization": "Bearer wrong"}, {"Authorization": TOKEN}):
            r = ask("192.168.1.5", "/usage", **headers)
            self.assertEqual((r.status_code, json.loads(r.body)), (401, {"error": REFUSED}), headers)

    def test_a_websocket_upgrade_without_the_token_is_refused(self):
        r = ask("10.0.0.7", "/session", Upgrade="websocket")
        self.assertEqual(r.status_code, 401)

    def test_the_liveness_check_always_answers(self):
        self.assertEqual(ask("192.168.1.5", "/"), "passed")
        self.assertEqual(ask("192.168.1.5", "/?x=1"), "passed")

    def test_no_token_checks_nothing(self):
        self.assertIs(gated(passed, None), passed)


if __name__ == "__main__":
    unittest.main()
