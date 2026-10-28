#!/usr/bin/env python3
"""Unpack and repack the R1's MTK `logo` partition.

  logo.py unpack  logo.bin  outdir/          # every image as PNG (full-screen ones) or .raw
  logo.py replace logo.bin  new.bin  IDX=file.png [IDX=file.png ...]

Layout verified on this unit: 0x200-byte MTK header
(content size at +4), then `u32 count, u32 total, u32 offset[count]` and zlib streams.
Full-screen images are 480x640 BGRA8888, alpha 0xFF. Index 0 and 38 are the boot logo.
Run with: uv run --with pillow device/logo/logo.py ...
"""
import struct, sys, zlib
from PIL import Image

W, H = 480, 640
HDR = 0x200


def load(path):
    d = open(path, 'rb').read()
    assert struct.unpack_from('<I', d, 0)[0] == 0x58881688, 'not an MTK image'
    size = struct.unpack_from('<I', d, 4)[0]
    body = d[HDR:HDR + size]
    n, total = struct.unpack_from('<II', body, 0)
    offs = [struct.unpack_from('<I', body, 8 + 4 * i)[0] for i in range(n)]
    blobs = [body[offs[i]:(offs[i + 1] if i + 1 < n else total)] for i in range(n)]
    return d[:HDR], blobs


def save(header, blobs, path):
    n = len(blobs)
    table = 8 + 4 * n
    offs, cur = [], table
    for b in blobs:
        offs.append(cur)
        cur += len(b)
    body = struct.pack('<II', n, cur) + b''.join(struct.pack('<I', o) for o in offs) + b''.join(blobs)
    header = bytearray(header)
    struct.pack_into('<I', header, 4, len(body))
    open(path, 'wb').write(bytes(header) + body)


def image_to_blob(im):
    """A full-screen entry from an image: BGRA, alpha forced to 0xFF, zlib."""
    assert im.size == (W, H), f'need {W}x{H}, got {im.size}'
    rgb = im.convert('RGBA').convert('RGB')          # drops alpha, does not composite
    opaque = Image.merge('RGBA', (*rgb.split(), Image.new('L', (W, H), 255)))
    return zlib.compress(opaque.tobytes('raw', 'BGRA'), 9)


def png_to_blob(path):
    return image_to_blob(Image.open(path))


def main(argv):
    cmd = argv[1]
    if cmd == 'unpack':
        _, blobs = load(argv[2])
        import os
        os.makedirs(argv[3], exist_ok=True)
        for i, b in enumerate(blobs):
            raw = zlib.decompress(b)
            if len(raw) == W * H * 4:
                Image.frombytes('RGBA', (W, H), raw, 'raw', 'BGRA').save(f'{argv[3]}/{i:02d}.png')
            else:
                open(f'{argv[3]}/{i:02d}.raw', 'wb').write(raw)
            print(i, len(raw))
    elif cmd == 'replace':
        header, blobs = load(argv[2])
        for spec in argv[4:]:
            idx, png = spec.split('=', 1)
            blobs[int(idx)] = png_to_blob(png)
            print('replaced', idx, 'with', png)
        save(header, blobs, argv[3])
        print('wrote', argv[3])
    else:
        sys.exit(__doc__)


if __name__ == '__main__':
    main(sys.argv)
