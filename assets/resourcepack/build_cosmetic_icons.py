#!/usr/bin/env python3
"""Builds the shared secret silhouette and gives trail icons a one-pixel scale lift."""

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


def build_secret_silhouette() -> None:
    image, draw = icon()

    # A single bevelled relic hides every secret's real shape. The hard outline,
    # stepped planes and tiny specular pixels match Minecraft's inventory art.
    outline = ((6, 1), (10, 2), (13, 4), (15, 8), (13, 12), (9, 15),
               (5, 14), (2, 12), (0, 8), (2, 4))
    draw.polygon(outline, fill="#030407")
    draw.polygon(((6, 2), (10, 3), (13, 5), (9, 8), (3, 5)), fill="#2b2f3b")
    draw.polygon(((2, 5), (8, 8), (8, 14), (5, 13), (2, 11), (1, 8)), fill="#0b0d12")
    draw.polygon(((9, 8), (14, 6), (14, 9), (12, 12), (9, 14)), fill="#181b24")
    draw.line(((3, 5), (8, 7), (12, 5)), fill="#414758", width=1)
    draw.line(((3, 6), (3, 10), (5, 12)), fill="#20232d", width=1)
    draw.line(((10, 9), (13, 8), (12, 11)), fill="#242834", width=1)
    draw.point((6, 3), fill="#596174")
    draw.point((4, 7), fill="#414758")
    save("secret_silhouette", image)


def build_reapers_verdict() -> None:
    image, draw = icon()
    draw.polygon(((2, 15), (1, 13), (9, 3), (12, 4), (5, 15)), fill="#160c1c")
    draw.polygon(((3, 14), (3, 12), (10, 4), (11, 5), (5, 14)), fill="#65506f")
    draw.line(((4, 12), (10, 5)), fill="#a28aad", width=1)
    draw.polygon(((8, 5), (10, 1), (13, 0), (15, 1), (15, 4), (13, 6), (10, 7)), fill="#160c1c")
    draw.polygon(((10, 5), (11, 2), (14, 1), (14, 3), (12, 5)), fill="#737b91")
    draw.line(((12, 2), (14, 2), (13, 4), (11, 5)), fill="#d5dbe8", width=1)
    draw.point((13, 1), fill="#f6f8ff")
    draw.point((1, 8), fill="#9b3bd1")
    draw.point((4, 4), fill="#9b3bd1")
    save("reapers_verdict", image)


def build_divine_rupture() -> None:
    image, draw = icon()
    bolt = ((8, 0), (14, 0), (10, 5), (13, 5), (5, 15), (6, 9), (2, 9))
    draw.polygon(bolt, fill="#3a1d08")
    draw.polygon(((9, 1), (12, 1), (8, 6), (11, 6), (6, 13), (7, 8), (4, 8)), fill="#e57808")
    draw.polygon(((9, 1), (11, 1), (7, 7), (9, 7), (6, 11), (7, 7), (5, 7)), fill="#ffc72c")
    draw.line(((10, 1), (7, 6), (8, 6)), fill="#fff1a6", width=1)
    draw.point((10, 1), fill="#ffffff")
    draw.point((1, 3), fill="#ffd85a")
    draw.point((14, 9), fill="#ffd85a")
    save("divine_rupture", image)


def build_astral_sovereign() -> None:
    image, draw = icon()
    draw.polygon(((1, 5), (4, 2), (10, 1), (14, 4), (15, 9), (12, 13), (6, 15), (2, 12), (0, 8)),
                 fill="#15102e")
    draw.line(((2, 6), (5, 3), (10, 2), (13, 4)), fill="#7353bd", width=2)
    draw.line(((14, 5), (14, 9), (11, 12), (7, 14), (3, 12)), fill="#3f2b83", width=2)
    draw.polygon(((8, 2), (10, 6), (14, 8), (10, 10), (8, 14), (6, 10), (2, 8), (6, 6)),
                 fill="#126c9c")
    draw.polygon(((8, 4), (9, 7), (12, 8), (9, 9), (8, 12), (7, 9), (4, 8), (7, 7)),
                 fill="#35cbe0")
    draw.rectangle((7, 7, 9, 9), fill="#dffcff")
    draw.point((8, 7), fill="#ffffff")
    save("astral_sovereign", image)


def build_infernal_dominion() -> None:
    image, draw = icon()
    draw.polygon(((1, 5), (4, 7), (5, 1), (8, 6), (11, 1), (12, 7), (15, 5),
                  (13, 14), (3, 14)), fill="#3a0a08")
    draw.polygon(((3, 7), (5, 9), (6, 4), (8, 8), (10, 4), (11, 9), (13, 7),
                  (12, 12), (4, 12)), fill="#a91c0d")
    draw.polygon(((5, 9), (7, 7), (8, 10), (10, 7), (11, 11), (5, 11)), fill="#ff5a0a")
    draw.rectangle((4, 12, 12, 14), fill="#5d1008")
    draw.line(((5, 12), (11, 12)), fill="#ff9e12", width=1)
    draw.rectangle((7, 10, 9, 12), fill="#ffd34c")
    draw.point((8, 10), fill="#fff1a0")
    save("infernal_dominion", image)


def build_abyssal_seraph() -> None:
    image, draw = icon()
    outline = "#10091d"
    shadow = "#2a1151"
    mid = "#57278c"
    light = "#9a5bd0"
    for points in (
        ((7, 5), (4, 1), (1, 1), (4, 6), (0, 5), (4, 9), (1, 11), (7, 10)),
        ((9, 5), (12, 1), (15, 1), (12, 6), (16, 5), (12, 9), (15, 11), (9, 10)),
    ):
        draw.polygon(points, fill=outline)
    draw.polygon(((6, 5), (4, 2), (2, 2), (5, 6), (1, 6), (5, 9), (2, 10), (7, 9)), fill=shadow)
    draw.polygon(((10, 5), (12, 2), (14, 2), (11, 6), (15, 6), (11, 9), (14, 10), (9, 9)), fill=mid)
    draw.line(((4, 3), (6, 6), (3, 6)), fill=light, width=1)
    draw.line(((12, 3), (10, 6), (13, 6)), fill=light, width=1)
    draw.polygon(((8, 3), (10, 6), (9, 12), (8, 15), (7, 12), (6, 6)), fill="#090611")
    draw.rectangle((7, 6, 9, 8), fill="#71d4e2")
    draw.point((8, 6), fill="#e9fdff")
    save("abyssal_seraph", image)


def build_galaxy_wake() -> None:
    image, draw = icon()
    draw.polygon(((0, 14), (2, 9), (6, 7), (8, 3), (12, 2), (15, 5), (14, 9),
                  (10, 11), (7, 10), (4, 14)), fill="#171037")
    draw.polygon(((1, 12), (4, 8), (7, 7), (9, 4), (12, 3), (14, 5), (13, 8),
                  (10, 10), (7, 9), (4, 12)), fill="#4d2a9a")
    draw.polygon(((2, 10), (6, 7), (8, 8), (5, 11)), fill="#168bb2")
    draw.polygon(((9, 4), (12, 3), (14, 5), (13, 8), (10, 9), (8, 7)), fill="#8a3fe0")
    draw.polygon(((10, 4), (12, 4), (13, 6), (12, 8), (10, 8), (9, 6)), fill="#44d6e7")
    draw.rectangle((10, 5, 11, 6), fill="#e6fdff")
    draw.point((2, 5), fill="#d9b8ff")
    save("galaxy_wake", image)


def build_phantom_chains() -> None:
    image, draw = icon()
    outline = "#071b2c"
    shadow = "#12536a"
    mid = "#1c9cb1"
    light = "#70e9ef"
    shine = "#e5ffff"
    links = ((1, 10), (5, 6), (9, 2))
    for x, y in links:
        draw.polygon(((x + 1, y), (x + 4, y), (x + 5, y + 1), (x + 5, y + 3),
                      (x + 4, y + 4), (x + 1, y + 4), (x, y + 3), (x, y + 1)), fill=outline)
        draw.line(((x + 1, y + 1), (x + 4, y + 1), (x + 4, y + 3)), fill=mid, width=1)
        draw.line(((x + 1, y + 3), (x + 3, y + 3)), fill=shadow, width=1)
        draw.point((x + 2, y + 1), fill=light)
    draw.line(((5, 10), (7, 8)), fill=shine, width=1)
    draw.line(((9, 6), (11, 4)), fill=shine, width=1)
    draw.point((1, 5), fill="#765ac4")
    draw.point((14, 12), fill="#765ac4")
    save("phantom_chains", image)


def build_reality_fracture() -> None:
    image, draw = icon()
    draw.polygon(((6, 0), (12, 2), (15, 6), (13, 12), (8, 15), (3, 13), (0, 8), (2, 3)),
                 fill="#10091f")
    draw.polygon(((6, 1), (11, 3), (8, 7), (3, 4)), fill="#38205f")
    draw.polygon(((2, 4), (7, 8), (7, 14), (4, 12), (1, 8)), fill="#20113f")
    draw.polygon(((8, 8), (14, 6), (12, 11), (8, 14)), fill="#4d236f")
    draw.line(((8, 1), (7, 5), (9, 7), (6, 10), (8, 14)), fill="#ff36ce", width=1)
    draw.line(((7, 5), (3, 6), (1, 8)), fill="#4de8f0", width=1)
    draw.line(((9, 7), (12, 5), (14, 5)), fill="#4de8f0", width=1)
    draw.point((7, 5), fill="#f2fdff")
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
    build_secret_silhouette()
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
