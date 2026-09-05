#!/usr/bin/env python3
"""Build Amethyst Dragon cosmetics on the pack's vanilla 16-pixel grid."""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent / "src/assets/mgx/textures/item/cosmetic"
O, D, S, A, B, L, W = (
    "#24143b", "#452064", "#64308a", "#8f45bf", "#c779ed", "#edb9ff", "#fff0ff"
)
METALS = {
    1: ("#8a4c00", "#e5a51d", "#ffe36b"),
    2: ("#596675", "#aebdcc", "#ecf7ff"),
    3: ("#63351d", "#b96432", "#efab68"),
}


def canvas():
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    return image, ImageDraw.Draw(image)


def save(name, image):
    ROOT.mkdir(parents=True, exist_ok=True)
    image.resize((32, 32), Image.Resampling.NEAREST).save(ROOT / f"{name}.png")


def crystal(draw, x, y, color=A, light=L):
    draw.polygon([(x, y), (x + 2, y - 2), (x + 4, y), (x + 3, y + 4), (x + 1, y + 4)], fill=O)
    draw.polygon([(x + 1, y), (x + 2, y - 1), (x + 3, y), (x + 2, y + 3), (x + 1, y + 2)], fill=color)
    draw.point((x + 2, y), fill=light)


def wing(draw, points, inset):
    draw.polygon(points, fill=O)
    draw.polygon(inset, fill=A)


def numeral(draw, digit, x, y, color):
    glyphs = {
        1: ("010", "110", "010", "010", "111"),
        2: ("110", "001", "010", "100", "111"),
        3: ("110", "001", "010", "001", "110"),
    }
    for row, bits in enumerate(glyphs[digit]):
        for column, bit in enumerate(bits):
            if bit == "1":
                draw.point((x + column, y + row), fill=color)


def dragonheart_rupture():
    image, draw = canvas()
    draw.polygon([(3, 4), (5, 2), (8, 4), (11, 2), (13, 4), (12, 8), (8, 13), (4, 8)], fill=O)
    draw.polygon([(4, 4), (5, 3), (8, 6), (11, 3), (12, 4), (11, 8), (8, 11), (5, 8)], fill=A)
    draw.polygon([(5, 4), (6, 4), (8, 7), (8, 10), (6, 8)], fill=B)
    draw.line([(8, 5), (7, 7), (9, 8), (8, 11)], fill=L)
    draw.point((11, 5), fill=W)
    return image


def crystal_wingfall():
    image, draw = canvas()
    wing(draw, [(1, 4), (4, 2), (7, 5), (7, 10), (4, 8), (2, 6)], [(2, 4), (4, 3), (6, 5), (6, 8), (4, 7), (3, 5)])
    wing(draw, [(15, 4), (12, 2), (9, 5), (9, 10), (12, 8), (14, 6)], [(14, 4), (12, 3), (10, 5), (10, 8), (12, 7), (13, 5)])
    draw.line([(3, 5), (6, 7)], fill=L)
    draw.line([(13, 5), (10, 7)], fill=L)
    crystal(draw, 6, 9, B, W)
    return image


def endscale_cataclysm():
    image, draw = canvas()
    for x, y in [(3, 4), (8, 2), (9, 8)]:
        draw.polygon([(x, y), (x + 2, y - 2), (x + 4, y), (x + 2, y + 4)], fill=O)
        draw.polygon([(x + 1, y), (x + 2, y - 1), (x + 3, y), (x + 2, y + 2)], fill=A)
        draw.point((x + 2, y), fill=L)
    draw.line([(3, 12), (12, 3)], fill=D)
    draw.point((2, 13), fill=B)
    draw.point((13, 12), fill=L)
    return image


def amethyst_dragon_crown():
    image, draw = canvas()
    draw.polygon([(2, 5), (4, 8), (6, 3), (8, 8), (11, 2), (12, 8), (14, 5), (13, 13), (3, 13)], fill=O)
    draw.polygon([(3, 6), (5, 9), (6, 5), (8, 10), (11, 4), (11, 9), (13, 7), (12, 12), (4, 12)], fill=A)
    draw.rectangle((5, 10, 11, 11), fill=S)
    draw.point((6, 6), fill=L)
    draw.point((11, 5), fill=W)
    return image


def violet_wyrm_orbit():
    image, draw = canvas()
    draw.ellipse((3, 3, 12, 12), fill=O)
    draw.ellipse((4, 4, 11, 11), fill=D)
    draw.rectangle((7, 6, 9, 9), fill=A)
    draw.point((8, 6), fill=L)
    draw.line([(1, 7), (2, 4), (5, 2), (8, 2)], fill=B)
    draw.line([(15, 8), (13, 11), (10, 13), (7, 13)], fill=A)
    draw.point((1, 6), fill=W)
    draw.point((14, 10), fill=L)
    return image


def geode_sovereignty():
    image, draw = canvas()
    draw.polygon([(2, 13), (3, 6), (5, 3), (7, 6), (9, 2), (11, 6), (13, 4), (14, 13)], fill=O)
    draw.polygon([(3, 12), (4, 7), (5, 5), (7, 9), (9, 4), (10, 8), (12, 6), (13, 12)], fill=A)
    draw.rectangle((5, 10, 11, 13), fill=D)
    draw.rectangle((6, 9, 10, 11), fill=S)
    draw.point((5, 6), fill=L)
    draw.point((9, 5), fill=W)
    draw.point((12, 7), fill=B)
    return image


def dragonflight_wake():
    image, draw = canvas()
    wing(draw, [(3, 3), (8, 4), (13, 2), (11, 7), (8, 9), (5, 8), (3, 12)], [(4, 4), (8, 5), (11, 4), (10, 6), (7, 8), (5, 7)])
    draw.line([(5, 5), (8, 6), (10, 5)], fill=L)
    draw.point((2, 13), fill=L)
    draw.point((5, 12), fill=B)
    draw.point((8, 11), fill=A)
    return image


def shardwing_procession():
    image, draw = canvas()
    for x, y in [(2, 3), (8, 2), (5, 8)]:
        draw.polygon([(x, y + 1), (x + 2, y), (x + 3, y + 2), (x + 2, y + 3)], fill=O)
        draw.polygon([(x + 1, y + 1), (x + 2, y + 1), (x + 2, y + 2)], fill=B)
        draw.line([(x + 3, y + 2), (x + 5, y + 1)], fill=A)
    draw.point((11, 11), fill=L)
    draw.point((8, 13), fill=B)
    draw.point((4, 14), fill=A)
    return image


def crystalfire_trail():
    image, draw = canvas()
    draw.polygon([(7, 14), (4, 11), (5, 7), (8, 2), (9, 7), (12, 5), (11, 11)], fill=O)
    draw.polygon([(7, 12), (6, 10), (7, 7), (8, 5), (9, 9), (10, 8), (10, 11)], fill=A)
    draw.polygon([(7, 11), (8, 8), (9, 11)], fill=L)
    draw.point((3, 13), fill=A)
    draw.point((1, 14), fill=B)
    draw.point((12, 3), fill=L)
    return image


def amethyst_dragon_ascendant():
    image, draw = canvas()
    draw.polygon([(2, 5), (5, 4), (6, 1), (8, 4), (10, 1), (11, 4), (14, 5), (12, 12), (9, 14), (7, 14), (4, 12)], fill=O)
    draw.polygon([(4, 6), (6, 5), (7, 3), (8, 6), (10, 3), (10, 5), (12, 6), (11, 11), (9, 12), (7, 12), (5, 11)], fill=A)
    draw.rectangle((5, 7, 6, 8), fill=W)
    draw.rectangle((10, 7, 11, 8), fill=W)
    draw.point((6, 8), fill=D)
    draw.point((10, 8), fill=D)
    draw.rectangle((7, 10, 9, 11), fill=D)
    draw.point((8, 5), fill=L)
    return image


def podium(rank):
    image, draw = canvas()
    dark, mid, light = METALS[rank]
    draw.polygon([(2, 4), (5, 6), (7, 2), (9, 6), (13, 3), (12, 12), (3, 12)], fill=O)
    draw.polygon([(3, 5), (5, 7), (7, 4), (9, 8), (12, 5), (11, 11), (4, 11)], fill=mid)
    draw.rectangle((5, 7, 11, 14), fill=O)
    draw.rectangle((6, 8, 10, 13), fill=dark)
    numeral(draw, rank, 7, 8, light)
    draw.point((7, 4), fill=L)
    return image


def clan(rank):
    image, draw = canvas()
    dark, mid, light = METALS[rank]
    draw.polygon([(3, 2), (13, 2), (13, 9), (11, 13), (8, 15), (5, 13), (3, 9)], fill=O)
    draw.polygon([(4, 3), (12, 3), (12, 9), (10, 12), (8, 13), (6, 12), (4, 9)], fill=mid)
    draw.rectangle((5, 4, 10, 11), fill=dark)
    numeral(draw, rank, 6, 5, light)
    draw.polygon([(10, 3), (12, 5), (11, 7), (9, 5)], fill=A)
    draw.point((10, 4), fill=L)
    return image


BUILDERS = {
    "dragonheart_rupture": dragonheart_rupture,
    "crystal_wingfall": crystal_wingfall,
    "endscale_cataclysm": endscale_cataclysm,
    "amethyst_dragon_crown": amethyst_dragon_crown,
    "violet_wyrm_orbit": violet_wyrm_orbit,
    "geode_sovereignty": geode_sovereignty,
    "dragonflight_wake": dragonflight_wake,
    "shardwing_procession": shardwing_procession,
    "crystalfire_trail": crystalfire_trail,
    "amethyst_dragon_ascendant": amethyst_dragon_ascendant,
    "dragon_podium_1": lambda: podium(1),
    "dragon_podium_2": lambda: podium(2),
    "dragon_podium_3": lambda: podium(3),
    "dragon_clan_1": lambda: clan(1),
    "dragon_clan_2": lambda: clan(2),
    "dragon_clan_3": lambda: clan(3),
}


def main():
    for name, builder in BUILDERS.items():
        save(name, builder())
    print(f"Built {len(BUILDERS)} vanilla-style Amethyst Dragon icons in {ROOT}")


if __name__ == "__main__":
    main()
