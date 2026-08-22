#!/usr/bin/env python3
"""Builds secret cosmetic pixel art and gives trail icons a one-pixel scale lift."""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent / "src/assets/mgx/textures/item/cosmetic"
TRAILS = (
    "ember_trail",
    "blood_trail",
    "frost_trail",
    "cherry_blossom_trail",
    "drool_trail",
    "ender_trail",
    "prismatic_trail",
)


def icon() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    return image, ImageDraw.Draw(image)


def save(name: str, image: Image.Image) -> None:
    image.save(ROOT / f"{name}.png", optimize=True)


def build_reapers_verdict() -> None:
    image, draw = icon()
    draw.line((4, 14, 11, 3), fill="#7b5b91", width=2)
    draw.line((5, 14, 12, 3), fill="#d0b5df")
    draw.line((9, 3, 13, 3, 15, 5, 14, 7), fill="#321046", width=2)
    draw.line((10, 2, 14, 3, 15, 5), fill="#bb43ef", width=2)
    draw.point((4, 14), fill="#efe8f5")
    save("reapers_verdict", image)


def build_divine_rupture() -> None:
    image, draw = icon()
    draw.ellipse((3, 1, 12, 6), outline="#ffb51b", width=2)
    draw.line((9, 3, 6, 9, 9, 9, 5, 15), fill="#fff4a3", width=2)
    draw.line((10, 3, 7, 8, 10, 8, 6, 14), fill="#ffca2c", width=2)
    draw.point((8, 2), fill="#ffffff")
    save("divine_rupture", image)


def build_astral_sovereign() -> None:
    image, draw = icon()
    draw.polygon(((8, 1), (10, 6), (15, 8), (10, 10), (8, 15), (6, 10), (1, 8), (6, 6)),
                 fill="#5530b8")
    draw.polygon(((8, 3), (9, 7), (13, 8), (9, 9), (8, 13), (7, 9), (3, 8), (7, 7)),
                 fill="#45d8ef")
    draw.rectangle((7, 7, 9, 9), fill="#f1fbff")
    draw.point((2, 3), fill="#a680ff")
    draw.point((13, 13), fill="#a680ff")
    save("astral_sovereign", image)


def build_infernal_dominion() -> None:
    image, draw = icon()
    draw.polygon(((3, 13), (2, 9), (5, 4), (6, 8), (8, 1), (10, 8), (12, 4), (14, 10), (12, 14)),
                 fill="#8f160d")
    draw.polygon(((5, 13), (4, 10), (7, 6), (8, 10), (10, 6), (12, 11), (11, 14)),
                 fill="#ff5b0a")
    draw.polygon(((7, 14), (6, 11), (9, 8), (10, 13), (9, 15)), fill="#ffd43b")
    draw.line((3, 13, 12, 13), fill="#40100c", width=2)
    save("infernal_dominion", image)


def build_abyssal_seraph() -> None:
    image, draw = icon()
    draw.polygon(((7, 5), (4, 2), (1, 3), (5, 7), (1, 7), (4, 10), (1, 13), (7, 10)),
                 fill="#35105c")
    draw.polygon(((9, 5), (12, 2), (15, 3), (11, 7), (15, 7), (12, 10), (15, 13), (9, 10)),
                 fill="#35105c")
    draw.line((7, 5, 3, 3), fill="#9d45df", width=2)
    draw.line((9, 5, 13, 3), fill="#9d45df", width=2)
    draw.line((7, 10, 3, 12), fill="#6a22a5", width=2)
    draw.line((9, 10, 13, 12), fill="#6a22a5", width=2)
    draw.rectangle((7, 5, 9, 10), fill="#13051f")
    draw.point((8, 6), fill="#dfb8ff")
    save("abyssal_seraph", image)


def build_galaxy_wake() -> None:
    image, draw = icon()
    draw.line((2, 13, 10, 5), fill="#5529a6", width=3)
    draw.line((1, 10, 9, 4), fill="#30bdd8", width=2)
    draw.polygon(((11, 1), (12, 5), (15, 7), (12, 9), (10, 13), (9, 9), (6, 7), (9, 5)),
                 fill="#b14cff")
    draw.rectangle((9, 6, 12, 8), fill="#effcff")
    draw.point((3, 5), fill="#f1d8ff")
    draw.point((5, 13), fill="#f1d8ff")
    save("galaxy_wake", image)


def build_phantom_chains() -> None:
    image, draw = icon()
    for x, y in ((2, 9), (6, 6), (10, 3)):
        draw.rectangle((x, y, x + 4, y + 3), outline="#39c7d7", width=1)
        draw.rectangle((x + 1, y + 1, x + 3, y + 2), fill="#102f48")
    draw.line((5, 9, 7, 7), fill="#d1f8ff", width=2)
    draw.line((9, 6, 11, 4), fill="#d1f8ff", width=2)
    draw.point((2, 13), fill="#6ef0ff")
    draw.point((14, 2), fill="#6ef0ff")
    save("phantom_chains", image)


def build_reality_fracture() -> None:
    image, draw = icon()
    draw.line((8, 1, 6, 5, 9, 7, 5, 11, 7, 15), fill="#43105f", width=3)
    draw.line((8, 1, 7, 5, 10, 7, 6, 11, 7, 15), fill="#ff37d4", width=1)
    draw.line((7, 5, 3, 4, 1, 6), fill="#55eaff", width=2)
    draw.line((9, 7, 13, 6, 15, 3), fill="#55eaff", width=2)
    draw.line((5, 11, 2, 12), fill="#b428ff", width=2)
    draw.line((6, 12, 11, 14, 14, 12), fill="#b428ff", width=2)
    save("reality_fracture", image)


def zoom_trails() -> None:
    for name in TRAILS:
        path = ROOT / f"{name}.png"
        image = Image.open(path).convert("RGBA")
        bounds = image.getbbox()
        if bounds is None:
            continue
        cropped = image.crop(bounds)
        largest = max(cropped.size)
        if largest >= 15:
            continue
        scale = 15 / largest
        size = tuple(max(1, round(dimension * scale)) for dimension in cropped.size)
        enlarged = cropped.resize(size, Image.Resampling.NEAREST)
        output = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        output.alpha_composite(enlarged, ((16 - size[0]) // 2, (16 - size[1]) // 2))
        output.save(path, optimize=True)


def main() -> None:
    build_reapers_verdict()
    build_divine_rupture()
    build_astral_sovereign()
    build_infernal_dominion()
    build_abyssal_seraph()
    build_galaxy_wake()
    build_phantom_chains()
    build_reality_fracture()
    zoom_trails()


if __name__ == "__main__":
    main()
