#!/usr/bin/env python3
"""Build the Dragon wardrobe set as independent vanilla-style 16px sprites."""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent / "src/assets/mgx/textures/item/cosmetic"

INK = (29, 10, 39, 255)
DEEP = (53, 18, 75, 255)
SHADOW = (87, 30, 121, 255)
VIOLET = (137, 52, 187, 255)
AMETHYST = (181, 84, 226, 255)
LILAC = (215, 139, 247, 255)
SHINE = (243, 210, 255, 255)
WHITE = (255, 247, 255, 255)
CYAN = (130, 222, 242, 255)

METALS = {
    "gold": ((61, 31, 4, 255), (125, 69, 7, 255), (201, 124, 12, 255),
             (246, 184, 36, 255), (255, 229, 112, 255), (255, 248, 190, 255)),
    "silver": ((28, 36, 51, 255), (55, 73, 96, 255), (91, 118, 148, 255),
               (145, 174, 201, 255), (201, 222, 238, 255), (245, 252, 255, 255)),
    "bronze": ((54, 24, 16, 255), (96, 40, 24, 255), (143, 61, 32, 255),
               (194, 91, 45, 255), (235, 139, 76, 255), (255, 202, 139, 255)),
}


def canvas() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    return image, ImageDraw.Draw(image)


def finish(image: Image.Image) -> Image.Image:
    return image.resize((32, 32), Image.Resampling.NEAREST)


def gem(draw: ImageDraw.ImageDraw, points, fill=AMETHYST, edge=DEEP, bright=SHINE):
    draw.polygon(points, fill=edge)
    if len(points) >= 4:
        xs = [point[0] for point in points]
        ys = [point[1] for point in points]
        cx, cy = round(sum(xs) / len(xs)), round(sum(ys) / len(ys))
        draw.line((points[0], (cx, cy), points[2]), fill=fill)
        draw.point((cx, min(ys) + 1), fill=bright)


def dragonheart_rupture() -> Image.Image:
    image, d = canvas()
    d.polygon([(3, 4), (5, 2), (8, 4), (11, 2), (13, 4), (13, 8),
               (8, 14), (3, 8)], fill=INK)
    d.polygon([(4, 4), (6, 3), (8, 5), (10, 3), (12, 4), (12, 7),
               (8, 12), (4, 7)], fill=VIOLET)
    d.polygon([(5, 4), (7, 4), (8, 6), (8, 10), (5, 7)], fill=AMETHYST)
    d.polygon([(9, 5), (11, 4), (11, 7), (9, 10)], fill=SHADOW)
    d.line([(8, 4), (7, 7), (9, 8), (7, 11)], fill=WHITE)
    d.point((5, 4), fill=SHINE)
    for point, colour in [((1, 3), LILAC), ((14, 2), AMETHYST), ((14, 9), SHINE),
                          ((2, 11), VIOLET), ((11, 14), LILAC), ((5, 14), SHADOW)]:
        d.rectangle((point[0], point[1], point[0] + (point[0] % 2), point[1]), fill=colour)
    return finish(image)


def crystal_wingfall() -> Image.Image:
    image, d = canvas()
    d.polygon([(1, 2), (6, 5), (7, 12), (4, 9), (2, 10), (3, 7), (0, 6)], fill=INK)
    d.polygon([(15, 2), (10, 5), (9, 12), (12, 9), (14, 10), (13, 7), (15, 6)], fill=INK)
    d.polygon([(2, 3), (6, 6), (6, 9), (4, 7), (2, 8), (3, 6), (1, 5)], fill=AMETHYST)
    d.polygon([(14, 3), (10, 6), (10, 9), (12, 7), (14, 8), (13, 6), (15, 5)], fill=LILAC)
    d.line([(2, 3), (6, 7), (4, 7), (6, 10)], fill=SHINE)
    d.line([(14, 3), (10, 7), (12, 7), (10, 10)], fill=VIOLET)
    gem(d, [(7, 1), (10, 5), (8, 14), (6, 5)], VIOLET, INK, WHITE)
    d.line([(8, 3), (8, 11)], fill=LILAC)
    return finish(image)


def endscale_cataclysm() -> Image.Image:
    image, d = canvas()
    scales = [(7, 1), (10, 2), (12, 5), (12, 9), (9, 12), (5, 13),
              (2, 10), (2, 6), (4, 3), (7, 5), (9, 7), (7, 9), (5, 7)]
    for index, (x, y) in enumerate(scales):
        colour = (VIOLET, AMETHYST, LILAC)[index % 3]
        d.polygon([(x, y - 1), (x + 2, y), (x + 1, y + 2), (x - 1, y + 1)], fill=INK)
        d.polygon([(x, y), (x + 1, y), (x + 1, y + 1), (x, y + 1)], fill=colour)
    d.rectangle((7, 6, 9, 8), fill=DEEP)
    d.point((8, 7), fill=WHITE)
    for x, y in ((0, 3), (14, 4), (14, 12), (1, 14), (11, 15)):
        d.point((x, y), fill=SHINE)
    return finish(image)


def amethyst_dragon_crown() -> Image.Image:
    image, d = canvas()
    d.polygon([(1, 5), (4, 7), (5, 11), (2, 9), (0, 10), (2, 7)], fill=INK)
    d.polygon([(15, 5), (12, 7), (11, 11), (14, 9), (15, 10), (14, 7)], fill=INK)
    d.polygon([(2, 6), (5, 8), (5, 10), (3, 8), (1, 9), (3, 7)], fill=AMETHYST)
    d.polygon([(14, 6), (11, 8), (11, 10), (13, 8), (15, 9), (13, 7)], fill=VIOLET)
    d.polygon([(3, 6), (4, 2), (7, 6), (8, 1), (9, 6), (12, 2), (13, 10), (3, 10)], fill=INK)
    d.polygon([(4, 7), (5, 4), (7, 7), (8, 3), (9, 7), (11, 4), (12, 9), (4, 9)], fill=VIOLET)
    d.rectangle((5, 8, 11, 10), fill=AMETHYST)
    d.rectangle((6, 8, 9, 8), fill=LILAC)
    d.point((8, 8), fill=WHITE)
    d.point((2, 5), fill=SHINE); d.point((14, 5), fill=SHINE)
    return finish(image)


def violet_wyrm_orbit() -> Image.Image:
    image, d = canvas()
    coil = [(4, 2), (9, 1), (13, 4), (14, 8), (12, 12), (8, 14),
            (4, 13), (1, 10), (1, 6), (3, 4), (6, 5), (5, 8), (7, 10),
            (10, 9), (11, 6), (9, 4)]
    d.line(coil, fill=INK, width=3, joint="curve")
    d.line(coil, fill=VIOLET, width=1, joint="curve")
    d.polygon([(3, 3), (2, 0), (5, 2), (7, 1), (7, 4)], fill=INK)
    d.polygon([(4, 3), (3, 1), (5, 3), (6, 2), (6, 4)], fill=AMETHYST)
    d.point((4, 3), fill=CYAN)
    d.line([(12, 5), (13, 8), (11, 11)], fill=LILAC)
    d.point((0, 12), fill=SHINE); d.point((14, 13), fill=AMETHYST)
    return finish(image)


def geode_sovereignty() -> Image.Image:
    image, d = canvas()
    d.polygon([(2, 14), (1, 8), (3, 3), (6, 1), (10, 1), (13, 3),
               (15, 8), (14, 14)], fill=INK)
    d.polygon([(3, 13), (2, 8), (4, 4), (7, 2), (10, 3), (13, 5),
               (14, 9), (13, 13)], fill=SHADOW)
    d.polygon([(5, 13), (4, 7), (6, 4), (8, 3), (11, 5), (12, 8), (11, 13)], fill=VIOLET)
    d.polygon([(6, 11), (6, 7), (8, 5), (10, 7), (10, 11)], fill=LILAC)
    d.rectangle((4, 11, 12, 14), fill=DEEP)
    d.rectangle((6, 10, 10, 12), fill=AMETHYST)
    d.point((7, 6), fill=WHITE); d.point((11, 4), fill=SHINE); d.point((3, 7), fill=LILAC)
    return finish(image)


def dragonflight_wake() -> Image.Image:
    image, d = canvas()
    d.polygon([(2, 11), (4, 7), (7, 6), (8, 3), (10, 5), (15, 3),
               (12, 8), (15, 10), (10, 10), (8, 14), (6, 10)], fill=INK)
    d.polygon([(3, 10), (5, 8), (8, 7), (9, 4), (10, 6), (13, 5),
               (11, 8), (13, 9), (9, 9), (8, 12), (7, 9)], fill=VIOLET)
    d.polygon([(5, 8), (8, 7), (10, 6), (9, 9)], fill=LILAC)
    d.point((10, 6), fill=CYAN)
    d.line([(0, 3), (4, 5)], fill=AMETHYST); d.line([(0, 6), (3, 7)], fill=SHINE)
    d.line([(1, 14), (5, 12)], fill=SHADOW)
    return finish(image)


def shardwing_procession() -> Image.Image:
    image, d = canvas()
    for offset, colour in ((0, SHADOW), (4, VIOLET), (8, AMETHYST)):
        y = 3 + offset // 4 * 4
        d.polygon([(0, y), (3, y - 2), (6, y), (4, y + 3), (3, y + 1), (1, y + 2)], fill=INK)
        d.polygon([(1, y), (3, y - 1), (5, y), (4, y + 2), (3, y)], fill=colour)
        d.polygon([(10, y), (13, y - 2), (15, y), (15, y + 2), (13, y + 1), (12, y + 3)], fill=INK)
        d.polygon([(11, y), (13, y - 1), (14, y), (14, y + 1), (13, y)], fill=LILAC if offset == 8 else colour)
    d.line([(7, 1), (8, 14)], fill=DEEP, width=2)
    d.point((8, 2), fill=SHINE); d.point((7, 13), fill=CYAN)
    return finish(image)


def crystalfire_trail() -> Image.Image:
    image, d = canvas()
    d.polygon([(5, 15), (2, 12), (4, 8), (3, 5), (7, 7), (8, 1),
               (11, 6), (14, 5), (12, 10), (13, 13), (10, 15)], fill=INK)
    d.polygon([(6, 14), (4, 12), (6, 9), (5, 7), (8, 9), (8, 3),
               (10, 8), (12, 7), (10, 11), (11, 13), (9, 14)], fill=VIOLET)
    d.polygon([(7, 13), (6, 11), (8, 9), (8, 6), (10, 10), (9, 13)], fill=LILAC)
    d.point((8, 7), fill=WHITE)
    gem(d, [(2, 13), (4, 11), (6, 14), (4, 15)], AMETHYST, DEEP, SHINE)
    d.point((13, 2), fill=SHINE); d.point((1, 7), fill=AMETHYST)
    return finish(image)


def amethyst_dragon_ascendant() -> Image.Image:
    image, d = canvas()
    d.line([(3, 10), (2, 6), (4, 2), (8, 1), (12, 3), (14, 7),
            (13, 12), (10, 14), (6, 14), (3, 12), (5, 9), (8, 8),
            (11, 9), (11, 6), (9, 4), (6, 4)], fill=INK, width=3, joint="curve")
    d.line([(3, 10), (3, 6), (5, 3), (8, 2), (11, 4), (13, 7),
            (12, 11), (10, 13), (6, 13), (4, 11)], fill=AMETHYST, width=1)
    d.polygon([(4, 3), (4, 0), (7, 2), (8, 0), (9, 2), (12, 0), (11, 4)], fill=INK)
    d.polygon([(5, 3), (5, 1), (7, 3), (8, 1), (9, 3), (11, 1), (10, 4)], fill=LILAC)
    gem(d, [(6, 7), (8, 5), (10, 7), (9, 11), (7, 11)], LILAC, DEEP, WHITE)
    d.point((6, 4), fill=CYAN)
    d.point((1, 2), fill=CYAN); d.point((14, 3), fill=SHINE); d.point((15, 11), fill=LILAC)
    return finish(image)


def dragon_podium_1() -> Image.Image:
    image, d = canvas(); dark, shadow, mid, gold, light, white = METALS["gold"]
    d.polygon([(1, 5), (4, 7), (5, 12), (2, 10), (0, 11), (2, 7),
               (4, 1), (7, 5), (8, 0), (10, 5), (13, 1), (14, 7),
               (16, 11), (13, 10), (11, 12), (12, 7), (15, 5)], fill=dark)
    d.polygon([(3, 7), (5, 3), (7, 7), (8, 2), (10, 7), (12, 3),
               (13, 11), (3, 11)], fill=gold)
    d.rectangle((4, 9, 12, 12), fill=mid); d.rectangle((5, 9, 11, 10), fill=light)
    d.polygon([(7, 7), (8, 6), (9, 7), (8, 9)], fill=VIOLET)
    d.point((8, 7), fill=SHINE); d.point((5, 4), fill=white); d.point((11, 4), fill=light)
    return finish(image)


def dragon_podium_2() -> Image.Image:
    image, d = canvas(); dark, shadow, mid, silver, light, white = METALS["silver"]
    d.polygon([(2, 1), (7, 3), (10, 7), (9, 14), (6, 11), (4, 6), (0, 4)], fill=dark)
    d.polygon([(3, 2), (7, 4), (9, 7), (8, 12), (6, 10), (5, 6), (2, 4)], fill=silver)
    d.line([(3, 3), (7, 5), (8, 8)], fill=white)
    d.polygon([(11, 2), (15, 4), (12, 6), (15, 8), (11, 9), (9, 6)], fill=dark)
    d.polygon([(11, 3), (14, 4), (11, 6), (14, 7), (11, 8), (10, 6)], fill=mid)
    d.polygon([(8, 5), (10, 7), (8, 15), (6, 10)], fill=INK)
    d.polygon([(8, 6), (9, 7), (8, 13), (7, 10)], fill=LILAC)
    d.point((12, 4), fill=CYAN)
    return finish(image)


def dragon_podium_3() -> Image.Image:
    image, d = canvas(); dark, shadow, mid, bronze, light, white = METALS["bronze"]
    plates = [(7, 2), (4, 4), (10, 4), (2, 7), (7, 7), (12, 7), (4, 10), (9, 10), (7, 13)]
    for index, (x, y) in enumerate(plates):
        d.polygon([(x, y - 2), (x + 3, y), (x + 1, y + 3), (x - 2, y + 1)], fill=dark)
        d.polygon([(x, y - 1), (x + 2, y), (x + 1, y + 2), (x - 1, y + 1)],
                  fill=(mid, bronze, light)[index % 3])
        d.point((x, y), fill=white if index in (0, 4) else shadow)
    d.line([(1, 2), (3, 3)], fill=VIOLET); d.line([(13, 12), (15, 13)], fill=LILAC)
    return finish(image)


def dragon_clan_1() -> Image.Image:
    image, d = canvas(); dark, shadow, mid, gold, light, white = METALS["gold"]
    d.polygon([(1, 4), (5, 6), (6, 12), (3, 9), (0, 10), (2, 6)], fill=dark)
    d.polygon([(15, 4), (11, 6), (10, 12), (13, 9), (15, 10), (14, 6)], fill=dark)
    d.polygon([(2, 5), (5, 7), (5, 10), (3, 8), (1, 9), (3, 6)], fill=gold)
    d.polygon([(14, 5), (11, 7), (11, 10), (13, 8), (15, 9), (13, 6)], fill=light)
    d.polygon([(5, 6), (8, 2), (11, 6), (10, 13), (8, 15), (6, 13)], fill=dark)
    d.polygon([(6, 6), (8, 3), (10, 6), (9, 12), (8, 14), (7, 12)], fill=gold)
    d.polygon([(6, 3), (6, 0), (8, 2), (10, 0), (10, 4)], fill=dark)
    d.line([(7, 3), (7, 1), (8, 3), (9, 1), (9, 4)], fill=white)
    d.point((8, 5), fill=VIOLET)
    return finish(image)


def dragon_clan_2() -> Image.Image:
    image, d = canvas(); dark, shadow, mid, silver, light, white = METALS["silver"]
    d.polygon([(8, 0), (14, 3), (13, 10), (8, 15), (3, 10), (2, 3)], fill=dark)
    d.polygon([(8, 2), (12, 4), (11, 9), (8, 13), (5, 9), (4, 4)], fill=silver)
    d.polygon([(8, 3), (10, 6), (8, 11), (6, 6)], fill=DEEP)
    d.polygon([(8, 4), (9, 6), (8, 9), (7, 6)], fill=LILAC)
    d.line([(0, 13), (5, 5)], fill=dark, width=2); d.line([(1, 12), (5, 5)], fill=white)
    d.line([(16, 13), (11, 5)], fill=dark, width=2); d.line([(15, 12), (11, 5)], fill=light)
    d.point((8, 5), fill=WHITE); d.point((2, 2), fill=CYAN); d.point((14, 2), fill=CYAN)
    return finish(image)


def dragon_clan_3() -> Image.Image:
    image, d = canvas(); dark, shadow, mid, bronze, light, white = METALS["bronze"]
    centres = [(5, 5), (11, 5), (8, 11)]
    for index, (x, y) in enumerate(centres):
        d.polygon([(x, y - 4), (x + 4, y - 1), (x + 3, y + 3),
                   (x, y + 4), (x - 3, y + 3), (x - 4, y - 1)], fill=dark)
        d.polygon([(x, y - 2), (x + 2, y), (x + 1, y + 2),
                   (x, y + 3), (x - 2, y + 1), (x - 2, y)],
                  fill=(mid, bronze, light)[index])
        d.point((x, y - 1), fill=LILAC)
    d.line([(5, 5), (11, 5), (8, 11), (5, 5)], fill=SHINE)
    d.point((8, 6), fill=WHITE); d.point((1, 13), fill=bronze); d.point((14, 13), fill=VIOLET)
    return finish(image)


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
    "dragon_podium_1": dragon_podium_1,
    "dragon_podium_2": dragon_podium_2,
    "dragon_podium_3": dragon_podium_3,
    "dragon_clan_1": dragon_clan_1,
    "dragon_clan_2": dragon_clan_2,
    "dragon_clan_3": dragon_clan_3,
}


def main() -> None:
    ROOT.mkdir(parents=True, exist_ok=True)
    for name, builder in BUILDERS.items():
        builder().save(ROOT / f"{name}.png", optimize=True)
    print(f"Built {len(BUILDERS)} independent vanilla-style Dragon icons in {ROOT}")


if __name__ == "__main__":
    main()
