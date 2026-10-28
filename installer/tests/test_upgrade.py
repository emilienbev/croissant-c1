"""croissant upgrade: which release it goes to, and what it hands that release's install.sh.

The install itself (download, check, switch, the PATH) is install.sh's, tried end to end by
the CLI job in ci.yml.
"""
import types
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

import scratch  # noqa: F401  (the cache in a temporary directory, before croissant)
from croissant import paths, ui, upgrade


def redirected(url):
    return mock.MagicMock(**{'__enter__.return_value.geturl.return_value': url, 'geturl.return_value': url})


class Latest(unittest.TestCase):
    def test_the_tag_github_redirects_to(self):
        with mock.patch.object(upgrade.urllib.request, 'urlopen',
                               return_value=redirected('https://github.com/me/mine/releases/tag/v0.12.3')):
            self.assertEqual(upgrade.latest('me/mine'), '0.12.3')

    def test_no_release_yet(self):           # GitHub then redirects to the releases page itself
        with mock.patch.object(upgrade.urllib.request, 'urlopen',
                               return_value=redirected('https://github.com/me/mine/releases')), \
                self.assertRaises(ui.Abort) as e:
            upgrade.latest('me/mine')
        self.assertIn('no release yet', str(e.exception))

    def test_a_tag_that_is_no_version(self):
        with mock.patch.object(upgrade.urllib.request, 'urlopen',
                               return_value=redirected('https://github.com/me/mine/releases/tag/nightly')), \
                self.assertRaises(ui.Abort):
            upgrade.latest('me/mine')

    def test_private_or_missing(self):
        err = urllib.error.HTTPError('u', 404, 'Not Found', {}, None)
        with mock.patch.object(upgrade.urllib.request, 'urlopen', side_effect=err), \
                self.assertRaises(ui.Abort) as e:
            upgrade.latest('me/mine')
        self.assertIn('HTTP 404', str(e.exception))


class Run(unittest.TestCase):
    def setUp(self):
        for name, value in (('INSTALLED', True), ('HOME', Path('/opt/me/.croissant'))):
            p = mock.patch.object(paths, name, value)
            p.start()
            self.addCleanup(p.stop)
        for p in (mock.patch.object(paths, 'install', return_value={'repo': 'me/mine', 'version': '0.2.0'}),
                  mock.patch.object(upgrade, '__version__', '0.2.0'),
                  mock.patch.object(upgrade, '_install_sh', return_value=Path('/tmp/install.sh'))):
            p.start()
            self.addCleanup(p.stop)
        self.sh = mock.patch.object(upgrade.subprocess, 'run', return_value=types.SimpleNamespace(returncode=0))
        self.ran = self.sh.start()
        self.addCleanup(self.sh.stop)

    def go(self, to=None, latest='0.2.0'):
        with mock.patch.object(upgrade, 'latest', return_value=latest), mock.patch.object(ui, 'ok'):
            upgrade.run(types.SimpleNamespace(to=to))

    def test_the_latest_already(self):
        self.go(latest='0.2.0')
        self.ran.assert_not_called()

    def test_newer_than_the_latest(self):
        self.go(latest='0.1.9')
        self.ran.assert_not_called()

    def test_a_newer_release_runs_its_install_sh(self):
        self.go(latest='0.10.0')                  # by number, not by text
        env = self.ran.call_args.kwargs['env']
        self.assertEqual((env['CROISSANT_VERSION'], env['CROISSANT_UPGRADE'], env['CROISSANT_HOME']),
                         ('0.10.0', '1', '/opt/me/.croissant'))
        self.assertEqual(self.ran.call_args.args[0], ['sh', '/tmp/install.sh'])

    def test_to_any_release_even_older(self):
        self.go(to='v0.1.4')
        self.assertEqual(self.ran.call_args.kwargs['env']['CROISSANT_VERSION'], '0.1.4')

    def test_to_something_else(self):
        with self.assertRaises(ui.Abort):
            self.go(to='latest')
        self.ran.assert_not_called()

    def test_a_failed_install_says_so(self):
        self.ran.return_value = types.SimpleNamespace(returncode=1)
        with self.assertRaises(ui.Abort) as e:
            self.go(latest='0.3.0')
        self.assertIn('0.2.0 is still the one in use', str(e.exception))

    def test_a_checkout_pulls(self):
        with mock.patch.object(paths, 'INSTALLED', False), self.assertRaises(ui.Abort) as e:
            self.go(latest='0.3.0')
        self.assertIn('git pull', str(e.exception))
        self.ran.assert_not_called()


if __name__ == '__main__':
    unittest.main()
