#!/usr/bin/env python3
"""Read or set a key in LK's environment, in the R1's `para` partition, over adb as root.

  lk_env.py [-s SERIAL] show
  lk_env.py [-s SERIAL] set off-mode-charge 0

Layout verified on this unit: one block at para+0x20000,
16 KiB: "ENV_v1" (8 B), data to +0x3ff4 (NUL-separated key=value), "ENV_v1" (8 B), then a
u32 checksum that is the byte sum of the data. Only a same-length value change is supported,
which is all `off-mode-charge` needs; only the changed bytes and the checksum are written.
`fastboot oem off-mode-charge` does the same but this LK refuses it ("not support on security").
"""
import struct, subprocess, sys

PART = '/dev/block/by-name/para'
BASE, SIZE = 0x20000, 0x4000
DATA, TAIL, CSUM = 8, 0x3ff4, 0x3ffc


def adb(serial, cmd, data=None):
    args = ['adb'] + (['-s', serial] if serial else []) + ['exec-out', f"su -c '{cmd}'"]
    return subprocess.run(args, input=data, capture_output=True, check=True).stdout


def read_block(serial):
    b = adb(serial, f'dd if={PART} bs=4096 skip={BASE // 4096} count={SIZE // 4096} 2>/dev/null')
    assert len(b) == SIZE and b[:6] == b'ENV_v1' and b[TAIL:TAIL + 6] == b'ENV_v1', 'no LK env block'
    assert sum(b[DATA:TAIL]) == struct.unpack_from('<I', b, CSUM)[0], 'checksum does not match'
    return b


def entries(b):
    return [e.decode() for e in b[DATA:TAIL].split(b'\0') if e]


def write_byte(serial, offset, value):
    adb(serial, f'dd of={PART} bs=1 seek={offset} conv=notrunc 2>/dev/null', bytes([value]))


def main(argv):
    serial = None
    if argv[:1] == ['-s']:
        serial, argv = argv[1], argv[2:]
    b = read_block(serial)
    if argv == ['show']:
        print('\n'.join(entries(b)))
        return
    if len(argv) != 3 or argv[0] != 'set':
        sys.exit(__doc__)
    key, value = argv[1], argv[2].encode()
    needle = key.encode() + b'='
    at = b.find(b'\0' + needle, DATA - 1, TAIL) + 1
    assert at > 0, f'{key} not in the environment'
    start = at + len(needle)
    old = b[start:b.index(b'\0', start)]
    assert len(old) == len(value), f'{key}={old.decode()}: only same-length values are supported'
    if old == value:
        print(f'{key} is already {value.decode()}')
        return
    new = bytearray(b)
    new[start:start + len(value)] = value
    csum = struct.pack('<I', sum(new[DATA:TAIL]))
    new[CSUM:CSUM + 4] = csum
    for i in range(SIZE):
        if new[i] != b[i]:
            write_byte(serial, BASE + i, new[i])
    adb(serial, 'sync; echo 3 > /proc/sys/vm/drop_caches')
    assert read_block(serial) == bytes(new), 'read-back differs'
    print(f'{key}: {old.decode()} -> {value.decode()}, checksum {csum.hex()}; LK reads it on the next boot')


if __name__ == '__main__':
    main(sys.argv[1:])
