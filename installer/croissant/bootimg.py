"""The R1's boot image: legacy header v2, not GKI.

Enough to trim a partition read down to the image and to prove what
Magisk changed: the kernel and the dtb must come back identical, since the side button
and the camera motor live there; only the ramdisk may differ.
"""
import gzip
import hashlib
import lzma
import re
import struct
import zlib

import lz4.block
import lz4.frame

MAGIC = b'ANDROID!'


def _sizes(d: bytes) -> dict:
    if d[:8] != MAGIC:
        raise ValueError('not an Android boot image')
    ver, = struct.unpack_from('<I', d, 40)
    if ver != 2:
        raise ValueError(f'boot header v{ver}, the R1 has v2')
    s = {k: struct.unpack_from('<I', d, o)[0]
         for k, o in (('kernel', 8), ('ramdisk', 16), ('second', 24), ('page', 36),
                      ('recovery_dtbo', 1632), ('dtb', 1648))}
    return s


def sections(d: bytes) -> dict[str, bytes]:
    s = _sizes(d)
    page = s['page']
    o, out = page, {}
    for name in ('kernel', 'ramdisk', 'second', 'recovery_dtbo', 'dtb'):
        out[name] = d[o:o + s[name]]
        o += (s[name] + page - 1) // page * page
    out['_end'] = o
    return out


def trim(d: bytes) -> bytes:
    """The image without the partition's tail (an AVB0 blob and AVBf footer on this unit)."""
    return d[:sections(d)['_end']]


def same_except_ramdisk(stock: bytes, patched: bytes) -> list[str]:
    """Section names that differ other than the ramdisk; empty means the patch is clean."""
    a, b = sections(stock), sections(patched)
    return [k for k in ('kernel', 'second', 'recovery_dtbo', 'dtb')
            if hashlib.sha256(a[k]).digest() != hashlib.sha256(b[k]).digest()]


def _decompress(r: bytes) -> bytes:
    if r[:2] == b'\x1f\x8b':
        return gzip.decompress(r)
    if r[:6] == b'\xfd7zXZ\x00':
        return lzma.decompress(r)
    if r[:4] == bytes.fromhex('02214c18'):          # lz4 legacy, what this unit uses
        out, p = bytearray(), 4
        while p < len(r):
            n, = struct.unpack_from('<I', r, p)
            p += 4
            if n == 0x184c2102:                     # a concatenated stream's magic
                continue
            out += lz4.block.decompress(r[p:p + n], uncompressed_size=8 << 20)
            p += n
        return bytes(out)
    return r


def ramdisk_files(d: bytes) -> dict[str, bytes]:
    """The ramdisk's files (newc cpio), by path."""
    c, p, files = _decompress(sections(d)['ramdisk']), 0, {}
    while True:
        h = c[p:p + 110]
        if h[:6] != b'070701':
            raise ValueError('ramdisk is not a newc cpio')
        fsz, nsz = int(h[54:62], 16), int(h[94:102], 16)
        name = c[p + 110:p + 110 + nsz - 1].decode()
        p = (p + 110 + nsz + 3) & ~3
        if name == 'TRAILER!!!':
            return files
        files[name] = c[p:p + fsz]
        p = (p + fsz + 3) & ~3


def magisk_config(d: bytes) -> dict[str, str]:
    """Magisk's install flags as it wrote them into a patched ramdisk; {} if unpatched."""
    raw = ramdisk_files(d).get('.backup/.magisk')
    if raw is None:
        return {}
    return dict(l.split('=', 1) for l in raw.decode().splitlines() if '=' in l)


# Top-level ramdisk entries that are not rabbit's: Magisk's backup of the stock init and its
# config (.backup), and its overlay (overlay.d), where the off-mode hook goes too.
FOREIGN = ('.backup', 'overlay.d')


def foreign(d: bytes) -> list[str]:
    """Ramdisk paths a stock boot image has not; empty means the ramdisk is rabbit's."""
    return sorted(n for n in ramdisk_files(d) if n.split('/', 1)[0] in FOREIGN)


# What Magisk's boot_patch.sh adds to the ramdisk (v30.7): its tmpfs files, the backup of the
# stock init and its config; it replaces init with magiskinit. Anything else must be ours.
MAGISK_DIRS = ('.backup', 'overlay.d', 'overlay.d/sbin')


def _magisks(name: str) -> bool:
    return (name in MAGISK_DIRS or name.startswith('.backup/')
            or (name.startswith('overlay.d/sbin/') and name.endswith('.xz') and name.count('/') == 2))


def patch_problems(stock: bytes, patched: bytes, ours: dict[str, bytes]) -> list[str]:
    """Why [patched] is not [stock] with Magisk's patch plus exactly [ours] (ramdisk path:
    contents) in its ramdisk; empty means it may be flashed. The other sections are
    same_except_ramdisk's."""
    a, b = ramdisk_files(stock), ramdisk_files(patched)
    out = [f'{n} removed' for n in sorted(a.keys() - b.keys())]
    out += [f'{n} changed' for n in sorted(a.keys() & b.keys()) if a[n] != b[n] and n != 'init']
    out += [f'{n} added' for n in sorted(b.keys() - a.keys()) if not _magisks(n) and n not in ours]
    for n, data in ours.items():
        if n in a:
            out.append(f'{n} was in the stock ramdisk already')
        elif n not in b:
            out.append(f'{n} missing')
        elif b[n] != data:
            out.append(f'{n} is not the file this installer adds')
    return out


def _kernel_text(k: bytes) -> bytes:
    """The kernel, decompressed when it is stored compressed (gzip on the R1)."""
    try:
        if k[:2] == b'\x1f\x8b':
            return zlib.decompressobj(31).decompress(k)          # tolerates an appended dtb
        if k[:4] == bytes.fromhex('04224d18'):
            return lz4.frame.decompress(k)
        if k[:4] == bytes.fromhex('02214c18') or k[:6] == b'\xfd7zXZ\x00':
            return _decompress(k)
    except (zlib.error, lzma.LZMAError, RuntimeError, ValueError):
        return b''
    return k


BANNER = re.compile(rb'Linux version (\S+) \(.*\) (#\d+ [^\n\0]*)')


def kernel_id(text: str | bytes) -> str | None:
    """The kernel's release and build ("4.19.191-g... #6 SMP PREEMPT <date>") from a
    "Linux version" banner, as the kernel image carries it and /proc/version prints it."""
    if isinstance(text, str):
        text = text.encode()
    m = BANNER.search(text)
    return f'{m[1].decode()} {m[2].decode().strip()}' if m else None


def kernel_version(d: bytes) -> str | None:
    """kernel_id of the boot image's kernel: which firmware the image belongs to."""
    k = sections(d)['kernel']
    return kernel_id(k) or kernel_id(_kernel_text(k))
