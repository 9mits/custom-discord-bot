#!/usr/bin/env python3
"""Rebuilds the Amethyst Golem skin from the mod art and the vanilla iron golem.

The Amethyst Golem is a retextured vanilla iron golem, because Minecraft has no
iron golem variant registry — a pack holds exactly one iron_golem.png, so there is
no way to give one golem a different texture from another.

The mod ships its golem as an Animated Java rig: a 128x128 atlas laid out for a
custom 12-bone model, not a mob skin. It cannot be dropped into the iron golem
slot the way the amethyst zombie and skeleton skins were. So instead of inventing
art, this derives the skin:

  * every vanilla iron golem pixel is ranked by luminance among its own texture
  * the mod's opaque pixels are ranked the same way
  * each vanilla pixel takes the mod colour at the same rank

Vanilla's shading structure and UV layout survive exactly; the palette becomes
entirely the mod's, keeping the mod's own proportion of dark basalt to amethyst
rather than brightening it back towards iron.

Inputs are not in the repo. Pass them explicitly:

    python3 import_amethyst_golem.py \
        --mod path/to/amethystgolem.jar \
        --client ~/Library/Application\\ Support/minecraft/versions/1.21.11/1.21.11.jar
"""
import argparse
import io
import pathlib
import zipfile

import numpy as np
from PIL import Image

LUMINANCE = np.array([0.2126, 0.7152, 0.0722])


def luminance(rgb):
    # An explicit weighted sum rather than a matmul: the BLAS path warns on the
    # non-contiguous views these masks produce, for the same answer.
    return (rgb * LUMINANCE).sum(axis=-1)
MOD_TEXTURE = "assets/minecraft/textures/blockbench/amgolem.png"
VANILLA_TEXTURE = "assets/minecraft/textures/entity/iron_golem/iron_golem.png"
TARGET = pathlib.Path(__file__).resolve().parent / "src" / VANILLA_TEXTURE


def read_png(archive, name):
    with zipfile.ZipFile(archive) as zip_file:
        return Image.open(io.BytesIO(zip_file.read(name))).convert("RGBA")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mod", required=True, help="the Amethyst Golem jar")
    parser.add_argument("--client", required=True, help="a 1.21.11 client jar")
    args = parser.parse_args()

    mod = np.asarray(read_png(args.mod, MOD_TEXTURE)).astype(float)
    opaque = mod[mod[..., 3] > 200][:, :3]
    ramp = opaque[np.argsort(luminance(opaque))]

    source = np.asarray(read_png(args.client, VANILLA_TEXTURE)).astype(float)
    drawn = source[..., 3] > 0
    rank = np.argsort(np.argsort(luminance(source[..., :3][drawn])))
    rank = rank / max(1, len(rank) - 1)

    result = source.copy()
    result[drawn, :3] = ramp[np.clip(
        (rank * (len(ramp) - 1)).astype(int), 0, len(ramp) - 1
    )]

    TARGET.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(result.astype("uint8")).save(TARGET)
    print(f"{TARGET}: {len(ramp)} mod colours over {int(drawn.sum())} vanilla pixels")


if __name__ == "__main__":
    main()
