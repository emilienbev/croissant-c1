"""The font step: what it takes, what it refuses, and that --no-input never guesses.

  uv run --project installer python -m unittest discover -s installer/tests

No real font needed: the pinned sums are swapped for stand-in files'.
"""
import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

import scratch  # noqa: F401  (the cache in a temporary directory, before croissant)
from croissant import font, paths, ui

FAKE = {n: f'stand-in {n}'.encode() for n in font.FILES}
SUMS = {n: hashlib.sha256(b).hexdigest() for n, b in FAKE.items()}


class FontTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        fonts = self.tmp / 'kept'
        for p in (mock.patch.dict(font.FILES, SUMS), mock.patch.object(font, 'FONTS', fonts),
                  mock.patch.object(ui, 'INTERACTIVE', False)):
            p.start()
            self.addCleanup(p.stop)

    def zip(self, files: dict[str, bytes]) -> Path:
        z = self.tmp / 'power-grotesk-font.zip'
        with zipfile.ZipFile(z, 'w') as w:
            for n, b in files.items():
                w.writestr(f'Power Grotesk/{n}', b)
        return z

    def test_fonts_live_in_the_cache(self):
        self.assertTrue(str(paths.CACHE).startswith(tempfile.gettempdir()))

    def test_takes_the_zip(self):
        font.ensure(str(self.zip(FAKE)))
        self.assertTrue(font.have())

    def test_kept_is_enough(self):
        font.take(self.zip(FAKE))
        font.ensure()                       # no --font, nothing asked

    def test_no_input_needs_the_font(self):
        with self.assertRaises(ui.Abort) as e:
            font.ensure()
        self.assertIn('--font PATH', str(e.exception))
        self.assertIn(font.PAGE, str(e.exception))

    def test_refuses_a_changed_file(self):
        with self.assertRaises(ui.Abort) as e:
            font.ensure(str(self.zip(FAKE | {'PowerGrotesk-Light.ttf': b'other'})))
        self.assertIn('sha256 differs', str(e.exception))
        self.assertFalse(font.have())

    def test_refuses_a_missing_file(self):
        with self.assertRaises(ui.Abort) as e:
            font.take(self.zip({'PowerGrotesk-Light.ttf': FAKE['PowerGrotesk-Light.ttf']}))
        self.assertIn('has no PowerGrotesk-Regular.ttf', str(e.exception))
        with self.assertRaises(ui.Abort) as e:
            font.take(self.tmp / 'nowhere.zip')
        self.assertIn('no such file', str(e.exception))

    def test_asks_again_after_a_bad_answer(self):
        good = self.zip(FAKE)
        bad = self.tmp / 'notes.txt'
        bad.write_text('not a font')
        answers = iter([str(bad), '', str(good)])
        with mock.patch.object(ui, 'INTERACTIVE', True), \
                mock.patch.object(ui, 'path', side_effect=lambda m, d='': next(answers)) as asked, \
                mock.patch.object(font, 'downloads', return_value=[good]):
            font.ensure()
        self.assertEqual(asked.call_count, 3)
        self.assertEqual(asked.call_args_list[0].args[1], str(good))   # the newest download offered
        self.assertTrue(font.have())


if __name__ == '__main__':
    unittest.main()
