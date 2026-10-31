"""The pairing code's payload. Run: uv run python -m unittest discover tests"""

import stat
import tempfile
import unittest
from pathlib import Path
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

    def test_a_lan_address_with_its_token(self):
        text = pairing.payload(["127.0.0.1", "100.100.100.103", "192.168.1.5"], 8765, "Mac", "t0k-en_")
        self.assertEqual(text, "croissant://server?host=192.168.1.5&port=8765&name=Mac&token=t0k-en_")

    def test_no_token_no_lan_code(self):
        text = pairing.payload(["127.0.0.1", "100.100.100.103", "192.168.1.5"], 8765, "Mac")
        self.assertEqual(text, "croissant://server?host=100.100.100.103&port=8765&name=Mac")

    def test_a_token_and_no_lan_address_gives_the_tailnet_code(self):
        text = pairing.payload(["127.0.0.1", "100.100.100.103", "0.0.0.0"], 8765, "Mac", "t0k-en_")
        self.assertEqual(text, "croissant://server?host=100.100.100.103&port=8765&name=Mac")

    def test_render_is_a_compact_block(self):
        text = pairing.payload(["100.100.100.103"], 8765, "Mac")
        lines = pairing.render(text, color=False).splitlines()
        self.assertEqual(len({len(line) for line in lines}), 1)
        self.assertLess(len(lines), 30)



class LanTokenTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = Path(self.dir.name) / "home" / "lan-token"

    def tearDown(self):
        self.dir.cleanup()

    def test_made_once_kept_private_and_read_back(self):
        first = pairing.lan_token(self.path)
        self.assertGreaterEqual(len(first), 32)
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.path.parent.stat().st_mode), 0o700)
        self.assertEqual(pairing.lan_token(self.path), first)

    def test_deleting_the_file_makes_a_new_one(self):
        first = pairing.lan_token(self.path)
        self.path.unlink()
        self.assertNotEqual(pairing.lan_token(self.path), first)


if __name__ == "__main__":
    unittest.main()
