"""The device's own name: its rule, its question, and --no-input.

  uv run --project installer python -m unittest discover -s installer/tests
"""
import unittest
from unittest import mock

import scratch  # noqa: F401  (the cache in a temporary directory, before croissant)
from croissant import devicename, plan, ui
from test_updates import flags


class Rule(unittest.TestCase):
    def test_takes_a_plain_name(self):
        for name in ('Kitchen', "Emi's R1", 'C1 2', 'a', 'x' * devicename.MAX, 'Café'):
            self.assertIsNone(devicename.problem(name), name)

    def test_refuses_what_the_app_refuses(self):
        for name in ('', 'x' * (devicename.MAX + 1), 'a/b', 'a"b', 'éé', '. -'):
            self.assertIsNotNone(devicename.problem(name), name)


class Question(unittest.TestCase):
    """ask() with only the device name left open, and unasked() for --no-input."""
    others = {'name', 'apk', 'offline_models', 'update_repo', 'sounds'}

    def setUp(self):
        for p in (mock.patch('croissant.updates.look'), mock.patch.object(ui, 'info')):
            p.start()
            self.addCleanup(p.stop)

    def ask(self, given=(), remembered=None, p=None):
        with mock.patch.object(ui, 'text', side_effect=lambda q, d, v: d) as t:
            p = plan.ask(p or plan.Plan(), self.others | set(given), False, remembered or {})
        return p, t

    def test_offers_the_default(self):
        p, t = self.ask()
        self.assertEqual(t.call_args.args[1], devicename.DEFAULT)
        self.assertEqual(p.device_name, devicename.DEFAULT)

    def test_the_remembered_answer_is_the_default(self):
        p, _ = self.ask(remembered={'device_name': 'Kitchen'})
        self.assertEqual(p.device_name, 'Kitchen')

    def test_the_flag_skips_the_question(self):
        p, given = plan.from_flags(flags(device_name='Desk'))
        p, t = self.ask(given=given, p=p)
        t.assert_not_called()
        self.assertEqual(p.device_name, 'Desk')

    def test_a_bad_flag_stops_before_anything(self):
        with self.assertRaises(ui.Abort):
            plan.from_flags(flags(device_name='a/b'))

    def test_no_input_takes_the_last_answer_else_the_default(self):
        self.assertEqual(plan.unasked(plan.Plan(), {'update_repo'}, {'device_name': 'Desk'}).device_name, 'Desk')
        self.assertEqual(plan.unasked(plan.Plan(), {'update_repo'}, {}).device_name, devicename.DEFAULT)


if __name__ == '__main__':
    unittest.main()
