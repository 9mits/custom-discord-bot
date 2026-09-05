#!/usr/bin/env python3
"""Build Dragon cosmetics from the same detailed 32px icon language as the wardrobe."""

from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent / "src/assets/mgx/textures/item/cosmetic"
PURPLE = ((31, 17, 51), (92, 42, 137), (174, 101, 231), (244, 214, 255))
METAL = {
    1: ((45, 27, 5), (151, 88, 8), (240, 181, 38), (255, 244, 154)),
    2: ((29, 38, 55), (88, 111, 143), (176, 202, 226), (245, 252, 255)),
    3: ((53, 25, 17), (130, 61, 35), (206, 112, 62), (255, 200, 142)),
}


def source(name):
    return Image.open(ROOT / f"{name}.png").convert("RGBA")


def tint(image, palette=PURPLE):
    result = Image.new("RGBA", image.size)
    pixels = []
    for r, g, b, a in image.getdata():
        if not a:
            pixels.append((0, 0, 0, 0))
            continue
        light = (r * 54 + g * 183 + b * 19) // 256
        segment = min(2, light * 3 // 256)
        local = (light * 3 % 256) / 255
        lo, hi = palette[segment], palette[segment + 1]
        pixels.append(tuple(round(lo[i] + (hi[i] - lo[i]) * local) for i in range(3)) + (a,))
    result.putdata(pixels)
    return result


def bright(draw, points, colour=(231, 181, 255, 255)):
    draw.line(points, fill=(42, 20, 67, 255), width=3)
    draw.line(points, fill=colour, width=1)


def wings(draw, colour=(185, 105, 234, 255)):
    left = [(15, 13), (11, 7), (3, 4), (7, 11), (1, 13), (10, 17), (4, 21), (14, 20)]
    right = [(17, 13), (21, 7), (29, 4), (25, 11), (31, 13), (22, 17), (28, 21), (18, 20)]
    for points in (left, right):
        draw.polygon(points, fill=(37, 17, 59, 255))
        inset = [(round((x + 16) * .5), round((y + 15) * .5)) for x, y in points]
        draw.line(points + [points[0]], fill=colour, width=1)
        draw.line(inset, fill=(230, 177, 255, 255), width=1)


def build(base, decoration=None, palette=PURPLE):
    image = tint(source(base), palette)
    if decoration:
        decoration(ImageDraw.Draw(image))
    return image


def heart(draw):
    draw.polygon([(11, 12), (14, 9), (16, 12), (18, 9), (21, 12),
                  (20, 17), (16, 22), (12, 17)], fill=(40, 16, 57, 255))
    draw.line([(12, 12), (14, 10), (16, 13), (18, 10), (20, 12),
               (19, 17), (16, 20), (13, 17), (12, 12)], fill=(243, 188, 255, 255), width=1)
    draw.line([(16, 12), (15, 15), (17, 17), (16, 20)], fill=(255, 255, 255, 255), width=1)


def scales(draw):
    for x, y in ((9, 10), (15, 7), (20, 12), (12, 17), (18, 20)):
        draw.polygon([(x, y), (x + 3, y - 2), (x + 6, y), (x + 3, y + 4)],
                     fill=(44, 20, 67, 255))
        draw.line([(x + 1, y), (x + 3, y - 1), (x + 5, y), (x + 3, y + 3), (x + 1, y)],
                  fill=(213, 142, 255, 255), width=1)


def crown(draw):
    draw.line([(8, 17), (7, 10), (12, 14), (16, 6), (20, 14), (25, 10), (24, 17)],
              fill=(45, 20, 65, 255), width=3)
    draw.line([(8, 17), (7, 10), (12, 14), (16, 6), (20, 14), (25, 10), (24, 17)],
              fill=(223, 161, 255, 255), width=1)
    draw.line([(9, 19), (23, 19)], fill=(249, 222, 255, 255), width=2)


def wyrms(draw):
    bright(draw, [(3, 18), (6, 11), (12, 8), (17, 9)], (205, 127, 255, 255))
    bright(draw, [(29, 14), (26, 21), (20, 24), (15, 23)], (143, 76, 214, 255))
    draw.rectangle((3, 16, 5, 18), fill=(245, 222, 255, 255))
    draw.rectangle((27, 14, 29, 16), fill=(245, 222, 255, 255))


def fire(draw):
    draw.polygon([(13, 28), (9, 23), (12, 17), (16, 7), (18, 17), (23, 13),
                  (22, 23), (18, 28)], fill=(43, 18, 62, 255))
    draw.polygon([(14, 26), (12, 22), (15, 17), (16, 12), (18, 21), (20, 18),
                  (20, 24), (18, 27)], fill=(180, 87, 231, 255))
    draw.polygon([(15, 25), (16, 19), (18, 24), (17, 26)], fill=(248, 211, 255, 255))


def podium(rank):
    # These are ordinary wardrobe illustrations whose colour communicates the
    # placement. The reward name carries the rank; the artwork never draws a
    # number or turns the cosmetic into a podium badge.
    base = {1: "celestial_crown", 2: "reapers_verdict", 3: "resonant_shatter"}[rank]
    image = build(base, palette=METAL[rank])
    draw = ImageDraw.Draw(image)
    if rank == 1:
        crown(draw)
    elif rank == 2:
        # A pale crystal fang cuts through the silver effect.
        draw.polygon([(18, 7), (23, 10), (17, 27), (14, 18)], fill=(40, 24, 58, 255))
        draw.line([(19, 8), (22, 10), (17, 25), (15, 18)],
                  fill=(225, 181, 255, 255), width=1)
    else:
        scales(draw)
    return image


def clan(rank):
    # Clan rewards use three different full cosmetic silhouettes. They remain
    # recognisable beside the existing wardrobe icons instead of reading as
    # medal shields with placement digits.
    base = {1: "galactic_conquest", 2: "amethyst_ascension", 3: "geode_cathedral"}[rank]
    image = build(base, palette=METAL[rank])
    draw = ImageDraw.Draw(image)
    if rank == 1:
        wings(draw, METAL[rank][2] + (255,))
    elif rank == 2:
        bright(draw, [(7, 24), (13, 15), (16, 6)], (223, 171, 255, 255))
        bright(draw, [(25, 24), (19, 15), (16, 6)], (190, 219, 245, 255))
    else:
        wyrms(draw)
    return image


BUILDERS = {
    "dragonheart_rupture": lambda: build("violet_detonation", heart),
    "crystal_wingfall": lambda: build("amethyst_ascension", wings),
    "endscale_cataclysm": lambda: build("shattered_continuum", scales),
    "amethyst_dragon_crown": lambda: build("celestial_crown", crown),
    "violet_wyrm_orbit": lambda: build("amethyst_orbit", wyrms),
    "geode_sovereignty": lambda: build("iridescent_imperium", crown),
    "dragonflight_wake": lambda: build("moonlit_procession", wings),
    "shardwing_procession": lambda: build("shardstorm_wake", wings),
    "crystalfire_trail": lambda: build("geode_bloom", fire),
    "amethyst_dragon_ascendant": lambda: build("astral_sovereign", lambda d: (wings(d), crown(d))),
    **{f"dragon_podium_{rank}": (lambda rank=rank: podium(rank)) for rank in (1, 2, 3)},
    **{f"dragon_clan_{rank}": (lambda rank=rank: clan(rank)) for rank in (1, 2, 3)},
}


def main():
    for name, builder in BUILDERS.items():
        image = builder()
        image = image.resize((16, 16), Image.Resampling.NEAREST).resize(
            (32, 32), Image.Resampling.NEAREST
        )
        alpha = image.getchannel("A").point(lambda value: 255 if value else 0)
        rgb = image.convert("RGB").quantize(colors=24, method=Image.Quantize.MEDIANCUT).convert("RGB")
        polished = Image.merge("RGBA", (*rgb.split(), alpha))
        polished.save(ROOT / f"{name}.png")
    print(f"Built {len(BUILDERS)} detailed Dragon icons in {ROOT}")


if __name__ == "__main__":
    main()
