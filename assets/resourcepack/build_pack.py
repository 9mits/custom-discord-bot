#!/usr/bin/env python3
"""Rebuilds MysteriousSMPX.zip from src/, and prints the hash the server needs.

The zip was previously assembled by hand, which is how it drifted out of step with
src/ and how server.properties ended up declaring a SHA-1 no client could match.
A client that downloads a pack whose hash differs from the declared one rejects it,
and with require-resource-pack=true that is a disconnect rather than a silent skip.

Run:  python3 build_pack.py
Then: put the printed sha1 into server.properties as resource-pack-sha1, and give
      resource-pack-id a fresh UUID so clients do not reuse a cached copy.
"""
import hashlib
import pathlib
import uuid
import zipfile

PACK = pathlib.Path(__file__).resolve().parent
SOURCE = PACK / "src"
TARGET = PACK / "MysteriousSMPX.zip"


def main():
    files = sorted(p for p in SOURCE.rglob("*") if p.is_file())
    directories = sorted({
        str(p.relative_to(SOURCE)).replace("\\", "/") + "/"
        for f in files for p in f.parents if p != SOURCE and SOURCE in p.parents
    })
    with zipfile.ZipFile(TARGET, "w", zipfile.ZIP_DEFLATED) as zip_file:
        # Deterministic timestamps keep the hash stable across rebuilds that change
        # nothing, so a no-op rebuild does not force every client to re-download.
        for directory in directories:
            info = zipfile.ZipInfo(directory, date_time=(1980, 1, 1, 0, 0, 0))
            info.external_attr = (0o40755 << 16) | 0x10
            zip_file.writestr(info, b"")
        for file in files:
            relative = str(file.relative_to(SOURCE)).replace("\\", "/")
            info = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            zip_file.writestr(info, file.read_bytes())

    data = TARGET.read_bytes()
    print(f"{TARGET.name}: {len(files)} files, {len(data)} bytes")
    print(f"resource-pack-sha1={hashlib.sha1(data).hexdigest()}")
    print(f"resource-pack-id={uuid.uuid4()}")


if __name__ == "__main__":
    main()
