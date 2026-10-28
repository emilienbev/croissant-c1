"""croissant atelier new and qr: the folder a new app gets, for every choice of features, the
glyph search, who owns it on GitHub, and its QR code, decoded as the C1 and as phones read it.

  uv run --project installer python -m unittest discover -s installer/tests

The decode checks need zxing-cpp (the dev group) and skip without it; the script checks need node.
"""
import argparse
import itertools
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import scratch  # noqa: F401  (the cache in a temporary directory, before croissant)
from croissant import atelier, lucide, qr, starter, ui

try:
    import zxingcpp
except ImportError:
    zxingcpp = None

ui.INTERACTIVE = False
ui.console.quiet = True


def args(name, **kw):
    a = dict(name=name, owner='', title=None, description=None, hue=None, glyph=None, uses=None, tool=None,
             tool_description=None, tool_example=None, no_git=True)
    a.update(kw)
    return argparse.Namespace(**a)


def root():
    return Path(tempfile.mkdtemp(prefix='atelier-new-'))


def read(png, invert):
    """What zxing-cpp reads in [png], trying the inverted image or not."""
    from PIL import Image
    img = Image.open(png).convert('RGB')
    return [r.text for r in zxingcpp.read_barcodes(img, try_invert=invert)]


class NewTest(unittest.TestCase):
    def test_a_new_app_is_a_good_app(self):
        r = root()
        atelier.new(args(str(r / 'Water_Log')))
        d = r / 'water-log'
        self.assertEqual(sorted(p.name for p in d.iterdir()), ['.gitignore', 'README.md', 'croissant.js', 'croissant.json'])
        m = atelier.check(d)
        self.assertEqual(m['name'], 'water log')
        self.assertEqual(m['croissant'], atelier.CROISSANT)
        self.assertEqual(m['uses'], [])
        self.assertNotIn('tools', m)
        self.assertNotIn('id', m)
        self.assertTrue(lucide.has(m['glyph']))
        self.assertIn(m['hue'], [h for _, h in atelier.HUES])
        text = (d / 'README.md').read_text()
        self.assertIn('./croissant atelier run path/to/water-log', text)
        self.assertIn('No QR code yet', text)
        self.assertNotIn('{{', text + (d / 'croissant.js').read_text())

    def test_every_choice_of_features_is_a_working_app(self):
        node = shutil.which('node')
        for n in range(5):
            for picked in itertools.combinations(['network', 'model', 'words', 'tool'], n):
                uses = [u for u in picked if u != 'tool']
                with self.subTest(picked=picked):
                    r = root()
                    atelier.new(args(str(r / 'app'), uses=uses, tool='add' if 'tool' in picked else None))
                    d = r / 'app'
                    m = atelier.check(d)
                    self.assertEqual(m['uses'], uses)
                    js = (d / 'croissant.js').read_text()
                    self.assertNotIn('{{', js)
                    for call, feature in (('fetch(', 'network'), ('ask(', 'model'), ('onWords(', 'words'),
                                          ('onTool("add"', 'tool')):
                        self.assertEqual(call in js, feature in picked, call)
                    self.assertEqual('tools' in m, 'tool' in picked)
                    for call in ('state(', 'render(', 'big(', 'pill(', 'row(', 'cue('):
                        self.assertIn(call, js)
                    if node:
                        p = subprocess.run([node, '--check', str(d / 'croissant.js')], capture_output=True, text=True)
                        self.assertEqual(p.returncode, 0, p.stderr)

    def test_the_flags_fill_every_answer(self):
        r = root()
        atelier.new(args(str(r / 'Water Log'), title='water', description='Glasses of water,\ncounted.',
                         hue='#1fa2ff', glyph='droplet', uses=['words', 'network'], tool='Add Glass',
                         tool_description='Add glasses of water drunk today', tool_example='I drank a glass'))
        d = r / 'water-log'
        m = atelier.check(d)
        self.assertEqual((m['name'], m['hue'], m['glyph'], m['uses']), ('water', '#1FA2FF', 'droplet', ['network', 'words']))
        t = m['tools'][0]
        self.assertEqual((t['name'], t['description'], t['examples']), ('add_glass', 'Add glasses of water drunk today', ['I drank a glass']))
        self.assertEqual(t['parameters']['type'], 'object')
        js, text = (d / 'croissant.js').read_text(), (d / 'README.md').read_text()
        self.assertIn('// water: Glasses of water, counted.', js)
        self.assertIn('onTool("add_glass"', js)
        self.assertIn('Glasses of water, counted.', text)

    def test_wrong_flags_are_refused_before_anything_is_made(self):
        for kw, said in [(dict(glyph='dropplet'), 'droplet'), (dict(uses=['camera']), 'camera'), (dict(hue='blue'), '#RRGGBB'),
                         (dict(title='x' * 25), '24'), (dict(tool='9lives'), 'lowercase'), (dict(owner='-me'), 'owner')]:
            with self.subTest(kw=kw):
                r = root()
                with self.assertRaises(ui.Abort) as e:
                    atelier.new(args(str(r / 'app'), **kw))
                self.assertIn(said, str(e.exception).replace('GitHub user or organisation', 'owner'))
                self.assertFalse((r / 'app').exists())

    def test_an_empty_folder_is_fine_and_a_full_one_refused(self):
        r = root()
        (r / 'empty').mkdir()
        atelier.new(args(str(r / 'empty')))
        self.assertTrue((r / 'empty' / 'croissant.json').is_file())
        with self.assertRaises(ui.Abort):
            atelier.new(args(str(r / 'empty')))
        (r / 'file').write_text('x')
        with self.assertRaises(ui.Abort):
            atelier.new(args(str(r / 'file')))

    def test_no_owner_asked_for_without_a_terminal_comes_from_git_not_gh(self):
        r = root()
        with mock.patch.object(atelier, 'github_owner', return_value='alice') as owner:
            atelier.new(args(str(r / 'app'), owner=None))
        owner.assert_called_once_with(use_gh=False)
        self.assertIn('alice/app', (r / 'app' / 'README.md').read_text())

    def test_with_an_owner_it_has_its_code_and_a_first_commit(self):
        r = root()
        cfg = r / 'gitconfig'
        cfg.write_text('')
        env = dict(GIT_CONFIG_GLOBAL=str(cfg), GIT_CONFIG_NOSYSTEM='1', GIT_AUTHOR_NAME='t', GIT_AUTHOR_EMAIL='t@example.com',
                   GIT_COMMITTER_NAME='t', GIT_COMMITTER_EMAIL='t@example.com')
        real = subprocess.run

        def run(argv, **kw):
            self.assertNotEqual(argv[0], 'gh')
            return real(argv, **kw)
        with mock.patch.dict(os.environ, env), mock.patch.object(atelier.subprocess, 'run', side_effect=run):
            atelier.new(args(str(r / 'Water-Log'), owner='you', hue='#1FA2FF', glyph='droplet', no_git=False))
        d = r / 'water-log'
        self.assertTrue((d / 'qr.svg').is_file() and (d / 'qr.png').is_file())
        text = (d / 'README.md').read_text()
        self.assertIn('<img src="qr.svg"', text)
        self.assertIn('<code>you/water-log</code>', text)
        if shutil.which('git'):
            log = subprocess.run(['git', '-C', str(d), 'log', '--format=%s', '--name-only'], capture_output=True, text=True,
                                 env={**os.environ, **env}).stdout
            for f in ('croissant.json', 'croissant.js', 'README.md', 'qr.svg', 'qr.png', '.gitignore'):
                self.assertIn(f, log)
            branch = subprocess.run(['git', '-C', str(d), 'branch', '--show-current'], capture_output=True, text=True).stdout
            self.assertEqual(branch.strip(), 'main')


class FillTest(unittest.TestCase):
    def test_blocks_values_and_lines(self):
        t = 'a{{#x}} {{v}}{{/x}}{{^x}} none{{/x}}\n{{#y}}\nline {{v}}\n{{#x}}\nboth\n{{/x}}\n{{/y}}\nend\n'
        self.assertEqual(starter.fill(t, {'v': '1'}, {'x': True, 'y': True}), 'a 1\nline 1\nboth\nend\n')
        self.assertEqual(starter.fill(t, {'v': '1'}, {'x': False, 'y': False}), 'a none\nend\n')

    def test_a_typed_value_is_not_a_block(self):
        a = starter.Answers(repo='r', name='{{#x}}', hue='#1FA2FF', glyph=None, description='two\nlines */ {{v}}')
        js = starter.script(a)
        self.assertIn('// {{#x}}: two lines */ {{v}}', js)

    def test_the_readme_code_is_made_again(self):
        text = starter.readme(starter.Answers(repo='r', name='r', hue='#1FA2FF', glyph=None))
        self.assertNotIn('<img', text)
        again = starter.set_qr(text, 'you/r')
        self.assertIn('<code>you/r</code>', again)
        self.assertEqual(starter.set_qr(again, 'you/r'), again)
        put = starter.set_qr('# r\n\nA line.\n', 'you/r')
        self.assertTrue(put.startswith('# r\n\n' + starter.QR_START))
        self.assertTrue(put.endswith(starter.QR_END + '\n\nA line.\n'))
        self.assertEqual(starter.set_qr(put, 'you/r'), put)

    def test_an_apps_own_readme(self):
        m = {'croissant': 1, 'name': 'water', 'hue': '#1FA2FF', 'glyph': 'droplet', 'uses': ['network'],
             'tools': [{'name': 'add', 'description': 'd', 'examples': [{'say': 'add a glass'}]}]}
        text = starter.readme(starter.from_manifest(m, 'atelier-water', 'you', 'Glasses today.'))
        self.assertIn('# water\n\nGlasses today.\n', text)
        self.assertNotIn('a count the wheel', text)
        self.assertIn('with a tool Ask calls ("add a glass")', text)
        self.assertIn('**the network**', text)
        self.assertIn('<code>you/atelier-water</code>', text)
        self.assertEqual(starter.about('# water\n\n<p>x</p>\n\nThe glasses\nof today.\n\n## Install\n'), 'The glasses of today.')


class GlyphTest(unittest.TestCase):
    def test_a_name_comes_first(self):
        self.assertEqual(lucide.search('droplet')[0], 'droplet')
        self.assertEqual(lucide.search('Shopping Cart')[0], 'shopping-cart')

    def test_tags_find_what_names_do_not(self):
        self.assertIn('droplet', lucide.search('water'))
        self.assertIn('shopping-cart', lucide.search('trolley'))

    def test_a_typo_is_forgiven(self):
        self.assertTrue(all(n.startswith('shopping') for n in lucide.search('shoping')[:3]))
        self.assertEqual(lucide.search('calandar')[0], 'calendar')

    def test_only_icons_the_c1_has(self):
        for q in ('water', 'zzzz', 'music note', 'x'):
            self.assertTrue(all(lucide.has(n) for n in lucide.search(q)))
        self.assertEqual(lucide.search(''), [])
        self.assertTrue(set(lucide.tags()) <= set(lucide.icons()))
        self.assertTrue(lucide.has(atelier.default_glyph('water log')))
        self.assertEqual(atelier.default_glyph('qqqq'), atelier.FALLBACK_GLYPH)
        self.assertTrue(lucide.has(atelier.FALLBACK_GLYPH))

    def test_every_icon_is_drawn(self):
        from PIL import Image
        img = Image.new('RGB', (48, 48))
        for name in lucide.icons():
            lucide.draw(img, name, 0, 0, 48, 'white')


class OwnerTest(unittest.TestCase):
    def run_with(self, answers, gh=True, **kw):
        calls = []

        def run(argv, **_):
            calls.append(argv[0])
            out = answers.get(argv[0])
            return subprocess.CompletedProcess(argv, 0 if out else 1, out or '', '')
        with mock.patch.object(atelier.shutil, 'which', return_value='/bin/gh' if gh else None), \
                mock.patch.object(atelier.subprocess, 'run', side_effect=run):
            return atelier.github_owner(**kw), calls

    def test_gh_first(self):
        self.assertEqual(self.run_with({'gh': 'alice\n', 'git': 'bob'}), ('alice', ['gh']))

    def test_then_git(self):
        self.assertEqual(self.run_with({'git': 'bob\n'}), ('bob', ['gh', 'git']))
        self.assertEqual(self.run_with({'gh': 'alice', 'git': 'bob'}, gh=False), ('bob', ['git']))

    def test_gh_never_when_told_not_to(self):
        self.assertEqual(self.run_with({'gh': 'alice', 'git': 'bob'}, use_gh=False), ('bob', ['git']))

    def test_none_and_nonsense(self):
        self.assertEqual(self.run_with({})[0], None)
        self.assertEqual(self.run_with({'gh': 'not a login', 'git': '-x'})[0], None)

    def test_gh_missing_or_stuck(self):
        def boom(argv, **_):
            raise subprocess.TimeoutExpired(argv, 8) if argv[0] == 'gh' else OSError()
        with mock.patch.object(atelier.shutil, 'which', return_value='/bin/gh'), \
                mock.patch.object(atelier.subprocess, 'run', side_effect=boom):
            self.assertIsNone(atelier.github_owner())


class RepoTest(unittest.TestCase):
    # What Source.parse takes (update/Releases.kt), and a git remote's forms.
    def test_what_is_read(self):
        for text, want in [('alice/water', ('alice', 'water')), ('https://github.com/alice/water', ('alice', 'water')),
                           ('github.com/alice/water/tree/main', ('alice', 'water')), ('www.github.com/a/b.git', ('a', 'b')),
                           ('git@github.com:alice/water.git', ('alice', 'water')), ('ssh://git@github.com/a/b', ('a', 'b')),
                           ('a/b/c', None), ('-a/b', None), ('alice', None), ('https://gitlab.com/a/b', None), ('a/..', None)]:
            self.assertEqual(atelier.parse_repo(text), want, text)


class QrTest(unittest.TestCase):
    SAMPLES = [('you/water-log', '#1FA2FF', 'droplet'), ('you/sunny', '#FFE01A', 'sun'),
               ('alice/night-owl', '#3B1E6E', 'moon'), ('alice/' + 'x' * 100, '#FF4F00', 'tally-5'),
               ('bob/plain', '#62F23A', None)]

    def test_it_holds_the_repository_link(self):
        d = qr.Design(qr.link('you/water-log'), '#1FA2FF', 'droplet')
        self.assertEqual(d.text, 'https://github.com/you/water-log')
        self.assertEqual(atelier.parse_repo(d.text), ('you', 'water-log'))

    def test_beams_hue(self):
        for n, h in atelier.HUES:
            self.assertEqual(qr.lift(h), h, n)
        self.assertEqual(qr.lift('#800000'), '#FF0000')
        navy = int(qr.lift('#000080')[1:], 16)
        self.assertEqual(navy & 0xFF, 0xFF)
        self.assertLess((navy >> 16) & 0xFF, 0xC0)
        for h in ('#000000', '#202020', '#4B0082', '#3B1E6E'):
            v = int(qr.lift(h)[1:], 16)
            self.assertGreaterEqual(qr._luminance((v >> 16) & 255, (v >> 8) & 255, v & 255), 0.175, h)

    def test_the_svg_has_the_glyph_and_no_text(self):
        svg = qr.Design(qr.link('you/water-log'), '#1FA2FF', 'droplet').svg()
        self.assertTrue(svg.startswith('<svg xmlns="http://www.w3.org/2000/svg"'))
        self.assertIn('stroke-linecap="round"', svg)
        self.assertIn(lucide.icons()['droplet'][0], svg)
        self.assertNotIn('<text', svg)

    @unittest.skipUnless(zxingcpp, 'zxing-cpp is not installed (the dev group)')
    def test_read_as_the_c1_and_as_a_reader_that_never_inverts(self):
        for slug, hue, glyph in self.SAMPLES:
            with self.subTest(slug=slug[:20]):
                d = Path(tempfile.mkdtemp(prefix='qr-'))
                _, png = qr.write(d, slug, hue, glyph)
                self.assertEqual(read(png, invert=True), [qr.link(slug)])
                self.assertEqual(read(png, invert=False), [qr.link(slug)])

    @unittest.skipUnless(zxingcpp, 'zxing-cpp is not installed (the dev group)')
    def test_read_small_as_a_readme_on_a_screen(self):
        from PIL import Image
        d = Path(tempfile.mkdtemp(prefix='qr-'))
        _, png = qr.write(d, 'you/water-log', '#1FA2FF', 'droplet')
        small = d / 'small.png'
        Image.open(png).resize((240, 240), Image.LANCZOS).save(small)
        self.assertEqual(read(small, invert=False), [qr.link('you/water-log')])

    @unittest.skipUnless(zxingcpp, 'zxing-cpp is not installed (the dev group)')
    def test_light_on_black_is_lost_on_a_reader_that_never_inverts(self):
        # Why the code is dark on the hue: its negative reads only where inversion is tried.
        from PIL import Image, ImageOps
        d = Path(tempfile.mkdtemp(prefix='qr-'))
        _, png = qr.write(d, 'you/water-log', '#FFFFFF', None)
        img = Image.open(png)
        flat = Image.new('RGB', img.size, 'white')
        flat.paste(img, (0, 0), img)
        neg = d / 'neg.png'
        ImageOps.invert(flat).save(neg)
        self.assertEqual(read(neg, invert=True), [qr.link('you/water-log')])
        self.assertEqual(read(neg, invert=False), [])


class MakeQrTest(unittest.TestCase):
    def qr_args(self, target, **kw):
        a = dict(target=target, owner=None, hue=None, glyph=None, out=None, readme=False, description=None)
        a.update(kw)
        return argparse.Namespace(**a)

    def test_a_folder_from_its_manifest_and_readme(self):
        r = root()
        atelier.new(args(str(r / 'water'), hue='#1FA2FF', glyph='droplet'))
        d = r / 'water'
        self.assertFalse((d / 'qr.svg').exists())
        with mock.patch.object(atelier, 'remote_repo', return_value=None):
            atelier.make_qr(self.qr_args(str(d), owner='you'))
        self.assertTrue((d / 'qr.svg').is_file() and (d / 'qr.png').is_file())
        self.assertIn('<code>you/water</code>', (d / 'README.md').read_text())
        self.assertIn('#1FA2FF', (d / 'qr.svg').read_text())

    def test_a_readme_without_markers_and_one_made_again(self):
        r = root()
        atelier.new(args(str(r / 'water'), hue='#1FA2FF', glyph='droplet'))
        d = r / 'water'
        (d / 'README.md').write_text('# water\n\nGlasses of water, counted.\n\n## Install it\n')
        with mock.patch.object(atelier, 'remote_repo', return_value=('you', 'atelier-water')):
            atelier.make_qr(self.qr_args(str(d)))
            text = (d / 'README.md').read_text()
            self.assertTrue(text.startswith('# water\n\n' + starter.QR_START))
            self.assertIn('## Install it', text)
            atelier.make_qr(self.qr_args(str(d), readme=True))
        text = (d / 'README.md').read_text()
        self.assertIn('Glasses of water, counted.', text)
        self.assertNotIn('## Install it', text)
        self.assertIn('<code>you/atelier-water</code>', text)
        with self.assertRaises(ui.Abort):
            atelier.make_qr(self.qr_args('you/water', hue='#1FA2FF', readme=True, out=str(r)))

    def test_the_remote_names_the_repository(self):
        r = root()
        atelier.new(args(str(r / 'water'), hue='#1FA2FF', glyph='droplet'))
        with mock.patch.object(atelier, 'remote_repo', return_value=('alice', 'Water-App')):
            atelier.make_qr(self.qr_args(str(r / 'water')))
        self.assertIn('https://github.com/alice/Water-App', (r / 'water' / 'qr.svg').read_text())

    def test_an_owner_is_needed(self):
        r = root()
        atelier.new(args(str(r / 'water')))
        with mock.patch.object(atelier, 'remote_repo', return_value=None), \
                mock.patch.object(atelier, 'github_owner', return_value=None), self.assertRaises(ui.Abort):
            atelier.make_qr(self.qr_args(str(r / 'water')))

    def test_a_repository_needs_its_hue(self):
        r = root()
        with self.assertRaises(ui.Abort) as e:
            atelier.make_qr(self.qr_args('alice/water', out=str(r)))
        self.assertIn('--hue', str(e.exception))
        atelier.make_qr(self.qr_args('https://github.com/alice/water', hue='#1FA2FF', glyph='droplet', out=str(r)))
        self.assertIn('https://github.com/alice/water<', (r / 'qr.svg').read_text())
        with self.assertRaises(ui.Abort):
            atelier.make_qr(self.qr_args('not a repo', hue='#1FA2FF', out=str(r)))


if __name__ == '__main__':
    unittest.main()
