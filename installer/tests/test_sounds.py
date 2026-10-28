"""The sound packs: the installer's list is the app's, every pack is whole, every demo is there, and
the pick lands as the app reads it.

  uv run --project installer python -m unittest discover -s installer/tests
"""
import re
import unittest
from pathlib import Path
from unittest import mock

import scratch  # noqa: F401  (the cache in a temporary directory, before croissant)
from croissant import paths, plan, sounds, stages, ui
from test_updates import flags

APP = paths.REPO / 'android' / 'app' / 'src' / 'main'
SILENT = {'zen': {'listen', 'heard', 'arrived'}}
AUDIO = APP / 'java' / 'com' / 'bleural' / 'croissant' / 'audio'


class SoundsTest(unittest.TestCase):
    def test_the_installer_offers_the_packs_the_app_carries(self):
        carried = sorted(d.name for d in (APP / 'assets' / 'sounds').iterdir() if d.is_dir())
        self.assertEqual(sorted(sounds.PACKS), carried)

    def test_every_pack_has_every_cue(self):
        """Every cue, but those a pack leaves silent on purpose (Zen had nothing for Ask), and nothing else."""
        cues = sorted(re.findall(r'^\s+\w+\("(\w+)"\),', (AUDIO / 'Cue.kt').read_text(), re.M))
        self.assertEqual(sorted(cues), sorted(f.stem for f in (APP / 'assets' / 'sounds' / sounds.DEFAULT).glob('*.ogg')))
        for p in sounds.PACKS:
            self.assertEqual(sorted(f.stem for f in (APP / 'assets' / 'sounds' / p).glob('*.ogg')),
                             sorted(set(cues) - SILENT.get(p, set())), p)

    def test_every_pack_has_a_demo(self):
        for p in sounds.PACKS:
            self.assertGreater((sounds.DEMOS / f'{p}.m4a').stat().st_size, 10_000, p)

    def test_the_names_are_the_apps(self):
        cues = (AUDIO / 'Cues.kt').read_text()
        self.assertIn(f'DEFAULT_PACK = "{sounds.DEFAULT}"', cues)
        self.assertIn(f'PACK_FILE = "{sounds.FILE}"', cues)

    def test_refuses_a_pack_it_does_not_have(self):
        self.assertEqual(sounds.check('grove'), 'grove')
        with self.assertRaises(ui.Abort):
            sounds.check('glass')

    def test_the_pick_lands_in_the_apps_files(self):
        adb = mock.Mock()
        ctx = stages.Ctx(adb, mock.Mock())
        ctx.plan.sounds = 'shrine'
        pushed = {}
        adb.push.side_effect = lambda src, dst: pushed.setdefault(dst, Path(src).read_text())
        stages.sound_pack(ctx)
        self.assertEqual(pushed, {f'{paths.STAGE}/croissant-sound-pack': 'shrine\n'})
        script = adb.script.call_args.args[0]
        self.assertIn(f'APPDIR=/data/user_de/0/{paths.PACKAGE}\n', script)
        self.assertIn(f'mv -f {paths.STAGE}/croissant-sound-pack $APPDIR/files/sound-pack', script)
        self.assertIn('chcon', script)
        self.assertTrue(ctx.restart_needed)



class Question(unittest.TestCase):
    """ask() with only the sounds left open, and unasked() for --no-input."""
    others = {'name', 'apk', 'offline_models', 'update_repo'}

    def setUp(self):
        for p in (mock.patch('croissant.updates.look'), mock.patch.object(ui, 'info')):
            p.start()
            self.addCleanup(p.stop)

    def ask(self, given=(), remembered=None, p=None):
        with mock.patch.object(sounds, 'pick', side_effect=lambda d: d) as pick:
            p = plan.ask(p or plan.Plan(), self.others | set(given), False, remembered or {})
        return p, pick

    def test_asks_with_shrine_first(self):
        p, pick = self.ask()
        pick.assert_called_once_with('shrine')
        self.assertEqual(p.sounds, 'shrine')

    def test_the_remembered_answer_is_the_default(self):
        p, pick = self.ask(remembered={'sounds': 'bubble'})
        pick.assert_called_once_with('bubble')
        self.assertEqual(p.sounds, 'bubble')

    def test_the_flag_skips_the_question(self):
        p, given = plan.from_flags(flags(sounds='kalimba'))
        p, pick = self.ask(given=given, p=p)
        pick.assert_not_called()
        self.assertEqual(p.sounds, 'kalimba')

    def test_a_bad_flag_stops_before_anything(self):
        with self.assertRaises(ui.Abort):
            plan.from_flags(flags(sounds='glass'))

    def test_no_input_takes_the_last_answer_else_shrine(self):
        self.assertEqual(plan.unasked(plan.Plan(), {'update_repo'}, {'sounds': 'grove'}).sounds, 'grove')
        self.assertEqual(plan.unasked(plan.Plan(), {'update_repo'}, {}).sounds, 'shrine')


if __name__ == '__main__':
    unittest.main()
