#!/usr/bin/env python3
"""Carve logical partitions out of a dumped `super` image.

The R1 uses dynamic partitions, so `system` / `vendor` / `product` do not exist as
GPT partitions -- they are extents inside `super`, described by liblp metadata.
Raw-writing `super` with mtkclient corrupts that metadata, so the only safe way to
restore one of them is `fastboot flash <name> <image>` from fastbootd. This script
produces exactly those flashable images from the stock backup.

Output is bit-exact: each logical partition is carved at full allocated size, so the
AVB hashtree that lives in the tail of the image comes along with it.

Usage:
    scripts/carve-super.py <super.bin> <outdir> [--slot b]
    scripts/carve-super.py <super.bin> --list
"""
import argparse
import os
import struct
import sys

LP_PARTITION_RESERVED_BYTES = 4096
LP_METADATA_GEOMETRY_MAGIC = 0x616C4467
LP_METADATA_HEADER_MAGIC = 0x414C5030
SECTOR = 512


def read_geometry(f):
    f.seek(LP_PARTITION_RESERVED_BYTES)
    geo = f.read(4096)
    magic, struct_size = struct.unpack_from("<II", geo, 0)
    if magic != LP_METADATA_GEOMETRY_MAGIC:
        sys.exit(f"not a super image: geometry magic {magic:#x}")
    metadata_max_size, slot_count, logical_block_size = struct.unpack_from("<III", geo, 40)
    return metadata_max_size, slot_count, logical_block_size


def read_metadata(f, metadata_max_size, slot):
    off = LP_PARTITION_RESERVED_BYTES * 3 + slot * metadata_max_size
    f.seek(off)
    hdr = f.read(256)
    magic, major, minor, header_size = struct.unpack_from("<IHHI", hdr, 0)
    if magic != LP_METADATA_HEADER_MAGIC:
        return None
    tables_size, = struct.unpack_from("<I", hdr, 44)
    desc = {}
    p = 80
    for name in ("partitions", "extents", "groups", "block_devices"):
        desc[name] = struct.unpack_from("<III", hdr, p)
        p += 12
    f.seek(off + header_size)
    tables = f.read(tables_size)

    po, pn, pe = desc["partitions"]
    eo, _, ee = desc["extents"]
    parts = {}
    for i in range(pn):
        d = tables[po + i * pe:po + (i + 1) * pe]
        name = d[0:36].rstrip(b"\0").decode()
        _attrs, first_extent, num_extents, _group = struct.unpack_from("<IIII", d, 36)
        extents = []
        for j in range(first_extent, first_extent + num_extents):
            ed = tables[eo + j * ee:eo + (j + 1) * ee]
            num_sectors, _target_type, target_data, _source = struct.unpack_from("<QIQI", ed, 0)
            extents.append((num_sectors * SECTOR, target_data * SECTOR))
        parts[name] = extents
    return {"version": f"{major}.{minor}", "partitions": parts}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("super_img")
    ap.add_argument("outdir", nargs="?")
    ap.add_argument("--slot", default="b", help="slot suffix to carve (default: b)")
    ap.add_argument("--list", action="store_true", help="print the layout and exit")
    args = ap.parse_args()

    with open(args.super_img, "rb") as f:
        metadata_max_size, slot_count, _lbs = read_geometry(f)

        if args.list:
            for slot in range(slot_count):
                md = read_metadata(f, metadata_max_size, slot)
                if md is None:
                    print(f"metadata slot {slot}: invalid")
                    continue
                print(f"metadata slot {slot} (liblp {md['version']}):")
                for name, extents in md["partitions"].items():
                    total = sum(size for size, _ in extents)
                    print(f"  {name:20s} {total:>12d} B  extents={extents}")
            return

        if not args.outdir:
            ap.error("outdir is required unless --list is given")

        # Metadata slot N corresponds to boot slot N (a=0, b=1).
        slot_index = {"a": 0, "b": 1}[args.slot]
        md = read_metadata(f, metadata_max_size, slot_index)
        if md is None:
            sys.exit(f"metadata slot {slot_index} is invalid")

        os.makedirs(args.outdir, exist_ok=True)
        suffix = "_" + args.slot
        for name, extents in md["partitions"].items():
            # Skip the virtual-A/B copy-on-write scratch partitions.
            if name.endswith("-cow") or not name.endswith(suffix):
                continue
            base = name[: -len(suffix)]
            out = os.path.join(args.outdir, f"{base}.img")
            total = sum(size for size, _ in extents)
            with open(out, "wb") as o:
                for size, offset in extents:
                    f.seek(offset)
                    remaining = size
                    while remaining:
                        chunk = f.read(min(remaining, 1 << 22))
                        if not chunk:
                            sys.exit(f"short read carving {name}")
                        o.write(chunk)
                        remaining -= len(chunk)
            magic = open(out, "rb").read(0x43A)[0x438:0x43A]
            ok = "ext4" if magic == b"\x53\xef" else f"UNKNOWN({magic.hex()})"
            print(f"{out}  {total} B  superblock={ok}")


if __name__ == "__main__":
    main()
