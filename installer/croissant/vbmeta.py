"""A blank top-level vbmeta with verification and verity off.

The first R1 had its own stock vbmeta flashed back with the two flags set. With no
backup there is no stock vbmeta to hand, and fastbootd cannot read it (it does not
expose vbmeta). A vbmeta with no descriptors does the same job: with
VERIFICATION_DISABLED libavb skips every descriptor of the top-level image, which is
where the stock one keeps its chains to boot, vbmeta_system and vbmeta_vendor, so what
they held stops mattering. The same as
`avbtool make_vbmeta_image --flags 3 --padding_size 4096`.
"""
import struct

HASHTREE_DISABLED, VERIFICATION_DISABLED = 1, 2
SIZE = 4096


def blank(flags: int = HASHTREE_DISABLED | VERIFICATION_DISABLED) -> bytes:
    release = b'croissant blank vbmeta'
    header = (b'AVB0'
              + struct.pack('>II', 1, 0)            # libavb 1.0
              + struct.pack('>QQ', 0, 0)            # no auth block, no aux block
              + struct.pack('>I', 0)                # algorithm NONE
              + struct.pack('>10Q', *[0] * 10)      # hash, signature, key, key metadata, descriptors
              + struct.pack('>Q', 0)                # rollback index
              + struct.pack('>II', flags, 0)        # flags, rollback index location
              + release.ljust(48, b'\0')
              + bytes(80))
    assert len(header) == 256
    return header.ljust(SIZE, b'\0')
