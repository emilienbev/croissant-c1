"""device.locate takes a lone device as the R1 only if it does not answer as something else:
someone's own phone, alone on USB, is refused before the plan."""
import unittest
from unittest import mock

import scratch  # noqa: F401  (the cache in a temporary directory, before croissant)
from croissant import device, ui

R1_VENDOR = 'Rabbit/vnd_k65v1_64_bsp/k65v1_64_bsp:12/SP1A.210812.016/20260910192544:user/release-keys'
PHONE_VENDOR = 'google/husky/husky:14/AP1A.240305.019/11365389:user/release-keys'


class FakeAdb:
    def __init__(self, devices=(), vendor=''):
        self.listed, self.vendor, self.serial = list(devices), vendor, None

    def devices(self):
        return [(s, 'device') for s in self.listed]

    def getprop(self, name):
        return self.vendor if name == 'ro.vendor.build.fingerprint' else ''


class FakeFastboot:
    def __init__(self, devices=(), product='', userspace=False):
        self.listed, self.product, self.user, self.serial = list(devices), product, userspace, None

    def devices(self):
        return self.listed

    def getvar(self, name):
        return self.product if name == 'product' else ''

    def userspace(self):
        return self.user


def locate(adb, fb, serial=None):
    with mock.patch.object(device, '_usb_ids', return_value=set()):
        return device.locate(adb, fb, serial)


class LocateTest(unittest.TestCase):
    def test_an_r1_over_adb(self):
        where = locate(FakeAdb(['A'], R1_VENDOR), FakeFastboot())
        self.assertEqual((where.mode, where.serial), ('android', 'A'))

    def test_a_phone_over_adb_is_refused(self):
        with self.assertRaises(ui.Abort) as e:
            locate(FakeAdb(['P'], PHONE_VENDOR), FakeFastboot())
        self.assertIn('P does not look like a Rabbit R1', str(e.exception))
        self.assertIn('Leave only the R1 plugged in', str(e.exception))

    def test_a_phone_picked_with_serial_is_refused(self):
        with self.assertRaises(ui.Abort):
            locate(FakeAdb(['P'], PHONE_VENDOR), FakeFastboot(), serial='P')

    def test_an_r1_in_lk_and_in_fastbootd(self):
        self.assertEqual(locate(FakeAdb(), FakeFastboot(['F'], 'k65v1_64_bsp')).mode, 'lk')
        self.assertEqual(locate(FakeAdb(), FakeFastboot(['F'], 'k65v1_64_bsp', userspace=True)).mode,
                         'fastbootd')

    def test_another_product_in_fastboot_is_refused(self):
        with self.assertRaises(ui.Abort) as e:
            locate(FakeAdb(), FakeFastboot(['F'], 'husky'))
        self.assertIn("product 'husky'", str(e.exception))

    def test_an_empty_answer_passes(self):
        self.assertEqual(locate(FakeAdb(['A'], ''), FakeFastboot()).mode, 'android')
        self.assertEqual(locate(FakeAdb(), FakeFastboot(['F'], '')).mode, 'lk')

    def test_more_than_one_device_asks_for_only_the_r1(self):
        with self.assertRaises(ui.Abort) as e:
            locate(FakeAdb(['A'], R1_VENDOR), FakeFastboot(['F'], 'k65v1_64_bsp'))
        self.assertIn('Leave only the R1 plugged in, or pick it with --serial', str(e.exception))

    def test_nothing_on_usb(self):
        self.assertEqual(locate(FakeAdb(), FakeFastboot()).mode, 'none')


if __name__ == '__main__':
    unittest.main()
