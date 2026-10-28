"""The pairing code's payload. Run: uv run python -m unittest discover tests"""

import unittest
from urllib.parse import parse_qs, urlsplit

from croissant_server import pairing


class PayloadTest(unittest.TestCase):
    def test_tailnet_host_port_and_name(self):
        text = pairing.payload(["127.0.0.1", "100.100.100.103"], 8765, "Mac")
        self.assertEqual(text, "croissant://server?host=100.100.100.103&port=8765&name=Mac")

    def test_name_with_spaces_and_symbols_is_encoded(self):
        text = pairing.payload(["100.100.100.103"], 9000, "Kim's Mac & more")
        u = urlsplit(text)
        self.assertEqual((u.scheme, u.netloc), ("croissant", "server"))
        self.assertNotIn(" ", text)
        q = parse_qs(u.query)
        self.assertEqual(q["name"], ["Kim's Mac & more"])
        self.assertEqual(q["port"], ["9000"])

    def test_no_tailnet_address(self):
        self.assertIsNone(pairing.payload(["127.0.0.1"], 8765, "Mac"))
        self.assertIsNone(pairing.payload(["192.168.1.5", "0.0.0.0"], 8765, "Mac"))
        self.assertIsNone(pairing.payload([], 8765, "Mac"))

    def test_range_edges(self):
        self.assertEqual(pairing.tailnet_host(["100.63.255.255"]), None)
        self.assertEqual(pairing.tailnet_host(["100.64.0.1"]), "100.64.0.1")
        self.assertEqual(pairing.tailnet_host(["100.127.255.255"]), "100.127.255.255")
        self.assertEqual(pairing.tailnet_host(["100.128.0.0"]), None)

    def test_render_is_a_compact_block(self):
        text = pairing.payload(["100.100.100.103"], 8765, "Mac")
        lines = pairing.render(text, color=False).splitlines()
        self.assertEqual(len({len(line) for line in lines}), 1)
        self.assertLess(len(lines), 30)


if __name__ == "__main__":
    unittest.main()
