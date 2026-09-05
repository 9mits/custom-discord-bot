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
GLYPHS = {
    1: ("010", "110", "010", "010", "111"),
    2: ("110", "001", "010", "100", "111"),
    3: ("110", "001", "010", "001", "110"),
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


def digit(draw, rank, x=23, y=21, colour=(255, 255, 255, 255)):
    glyph = GLYPHS[rank]
    for row, bits in enumerate(glyph):
        for col, bit in enumerate(bits):
            if bit == "1":
                draw.rectangle((x + col * 2 - 1, y + row * 2 - 1,
                                x + col * 2 + 2, y + row * 2 + 2), fill=(24, 13, 31, 255))
    for row, bits in enumerate(glyph):
        for col, bit in enumerate(bits):
            if bit == "1":
                draw.rectangle((x + col * 2, y + row * 2,
                                x + col * 2 + 1, y + row * 2 + 1), fill=colour)


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
    image = build("celestial_crown", palette=METAL[rank])
    draw = ImageDraw.Draw(image)
    # Amethyst inset ties every placement to this event without replacing the
    # detailed crown players already recognise as a leaderboard reward.
    draw.polygon([(12, 25), (16, 20), (20, 25), (16, 30)], fill=(56, 25, 80, 255))
    draw.line([(13, 25), (16, 21), (19, 25), (16, 29), (13, 25)],
              fill=(197, 121, 242, 255), width=1)
    digit(draw, rank, 23, 21, METAL[rank][3] + (255,))
    return image


def clan(rank):
    image = build("galactic_conquest", palette=METAL[rank])
    draw = ImageDraw.Draw(image)
    draw.polygon([(7, 7), (25, 7), (24, 20), (16, 29), (8, 20)], fill=(31, 16, 47, 210))
    draw.line([(7, 7), (25, 7), (24, 20), (16, 29), (8, 20), (7, 7)],
              fill=METAL[rank][2] + (255,), width=2)
    draw.polygon([(11, 15), (16, 9), (21, 15), (16, 23)], fill=(108, 51, 154, 255))
    draw.line([(12, 15), (16, 10), (20, 15), (16, 22), (12, 15)],
              fill=(225, 172, 255, 255), width=1)
    digit(draw, rank, 23, 21, METAL[rank][3] + (255,))
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
