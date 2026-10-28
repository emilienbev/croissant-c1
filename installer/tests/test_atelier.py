"""croissant atelier: an app folder checked as the C1 checks it, its id, and what the push runs (new and qr:
test_atelier_new.py).

  uv run --project installer python -m unittest discover -s installer/tests

No R1 needed: a stand-in plays adb.
"""
import json
import tempfile
import unittest
from pathlib import Path

import scratch  # noqa: F401  (the cache in a temporary directory, before croissant)
from croissant import atelier, paths, ui


def folder(manifest, script='render(s => [])'):
    d = Path(tempfile.mkdtemp(prefix='atelier-'))
    (d / 'croissant.json').write_text(manifest if isinstance(manifest, str) else json.dumps(manifest))
    if script is not None:
        (d / 'croissant.js').write_text(script)
    return d


GOOD = {'croissant': 1, 'name': 'water', 'hue': '#1FA2FF', 'glyph': 'droplet', 'uses': ['words']}


class CheckTest(unittest.TestCase):
    def test_a_good_app(self):
        self.assertEqual(atelier.check(folder(GOOD))['name'], 'water')

    def test_an_id_in_it_is_ignored(self):
        atelier.check(folder({**GOOD, 'id': 'Not An Id!'}))

    def test_what_is_wrong_is_said_all_at_once(self):
        with self.assertRaises(ui.Abort) as e:
            atelier.check(folder({'croissant': 2, 'hue': 'blue', 'uses': ['camera']}))
        msg = str(e.exception)
        for part in ('croissant: 2 needs a newer Croissant', 'name:', 'hue:', 'uses: camera'):
            self.assertIn(part, msg)

    def test_the_croissant_version_is_needed(self):
        for m in ({k: v for k, v in GOOD.items() if k != 'croissant'}, {**GOOD, 'croissant': '1'},
                  {**GOOD, 'croissant': True}, {**GOOD, 'croissant': 0}):
            with self.assertRaises(ui.Abort) as e:
                atelier.check(folder(m))
            self.assertIn('croissant:', str(e.exception))
        with self.assertRaises(ui.Abort) as e:
            atelier.check(folder({**{k: v for k, v in GOOD.items() if k != 'croissant'}, 'kit': 1}))
        self.assertIn('croissant: missing', str(e.exception))

    def test_tools_are_checked(self):
        ok = {**GOOD, 'tools': [{'name': 'add_glass', 'description': 'Add a glass', 'examples': ['I drank a glass']}]}
        self.assertEqual(atelier.check(folder(ok))['tools'][0]['name'], 'add_glass')
        with self.assertRaises(ui.Abort) as e:
            atelier.check(folder({**GOOD, 'tools': [{'name': 'Add'}, {'name': 'x', 'description': 'y', 'parameters': []}]}))
        msg = str(e.exception)
        for part in ('tools[0].name', 'tools[0].description', 'tools[1].parameters'):
            self.assertIn(part, msg)

    def test_an_example_is_a_sentence_or_says_its_args(self):
        ok = {**GOOD, 'tools': [{'name': 'add', 'description': 'Add', 'examples': [
            'add one', {'say': 'add two', 'args': {'n': 2}}]}]}
        atelier.check(folder(ok))
        with self.assertRaises(ui.Abort) as e:
            atelier.check(folder({**GOOD, 'tools': [{'name': 'add', 'description': 'Add', 'examples': [
                {'args': {'n': 2}}, {'say': 'x', 'args': 'n'}, 3]}]}))
        msg = str(e.exception)
        for j in (0, 1, 2):
            self.assertIn(f'tools[0].examples[{j}]', msg)

    def test_no_script_or_no_json(self):
        with self.assertRaises(ui.Abort) as e:
            atelier.check(folder(GOOD, script=None))
        self.assertIn('no croissant.js', str(e.exception))
        with self.assertRaises(ui.Abort) as e:
            atelier.check(folder('{'))
        self.assertIn('not JSON', str(e.exception))

    def test_a_link_is_refused(self):
        for name in ('croissant.json', 'croissant.js'):
            d = folder(GOOD)
            real = d / f'real-{name}'
            (d / name).rename(real)
            (d / name).symlink_to(real)
            with self.assertRaises(ui.Abort) as e:
                atelier.check(d)
            self.assertIn('is a link', str(e.exception))

    def test_the_old_names_are_not_an_app(self):
        d = Path(tempfile.mkdtemp(prefix='atelier-'))
        (d / 'app.json').write_text(json.dumps(GOOD))
        (d / 'app.js').write_text('render(s => [])')
        with self.assertRaises(ui.Abort) as e:
            atelier.check(d)
        self.assertIn('no croissant.json', str(e.exception))


class AppIdTest(unittest.TestCase):
    # The same cases as AtelierTest.anIdIsTheNameMadeSafe: the CLI and the C1 agree.
    def test_a_name_made_safe(self):
        for name, want in [('water-log', 'water-log'), ('Water_Log', 'water-log'), ('croissant.water', 'croissant-water'),
                           ('--a...b--', 'a-b'), ('Café', 'caf'), ('2048', '2048'), ('...', 'app'), ('', 'app'),
                           ('a' * 50, 'a' * 40), ('a' * 39 + '-b', 'a' * 39), ('My App', 'my-app')]:
            self.assertEqual(atelier.app_id(name), want, name)


class FakeAdb:
    def __init__(self):
        self.pushed, self.scripts, self.shells = [], [], []

    def sh(self, cmd, **kw):
        self.shells.append(cmd)
        return ''

    def push(self, local, remote):
        self.pushed.append((Path(local).name, remote))

    def script(self, text, name, **kw):
        self.scripts.append((name, text))
        return ''


class PushTest(unittest.TestCase):
    def test_both_files_land_as_the_apps_own_and_the_reload_comes_last(self):
        adb = FakeAdb()
        atelier.push(adb, folder(GOOD), 'water')
        self.assertEqual([n for n, _ in adb.pushed], ['croissant.json', 'croissant.js'])
        name, text = adb.scripts[0]
        self.assertEqual(name, 'atelier')
        self.assertIn('D=$A/apps/water', text)
        self.assertIn('mv -f /data/local/tmp/croissant-atelier/croissant.json $D/croissant.json', text)
        self.assertIn('mv -f /data/local/tmp/croissant-atelier/croissant.js $D/croissant.js', text)
        self.assertIn("printf '%s\\n' water > $A/.reload", text)
        self.assertIn(f'APPDIR=/data/user_de/0/{paths.PACKAGE}', text)
        self.assertIn('chcon -hR $C $A', text)
        self.assertIn('chown -h $U:$U', text)
        self.assertNotIn('chmod 0600 $D/*', text)
        self.assertNotIn('state.json', text)
        self.assertTrue(text.rstrip().endswith('mv -f $A/.reload $A/reload'))

    def test_fresh_drops_the_saved_state(self):
        adb = FakeAdb()
        atelier.push(adb, folder(GOOD), 'water', fresh=True)
        self.assertIn('rm -f $D/state.json', adb.scripts[0][1])


if __name__ == '__main__':
    unittest.main()
