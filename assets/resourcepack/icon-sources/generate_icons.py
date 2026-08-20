#!/usr/bin/env python3
"""Draws the 16x16 cosmetic icons.

Every cosmetic gets its own texture, so the wardrobe stops showing the same cyan
streak eighteen times. Each icon is an explicit pixel grid rather than a recolour:
a shared silhouette per category was tried first and read as eighteen coloured
sticks, which is the problem it was meant to solve.

Grid legend
    .  transparent      o  outline (darkest)
    m  body             c  core / highlight (brightest)
    a  accent           s  shadow (between outline and body)

Run:  python3 generate_icons.py [--out DIR]
"""
import argparse
import pathlib

from PIL import Image

SIZE = 16


def rgb(value):
    return tuple(int(value[i:i + 2], 16) for i in (1, 3, 5)) + (255,)


TRAIL = [
    "................",
    "............oo..",
    "...........occo.",
    "..........ocnco.",
    "..o.......ocnmo.",
    ".oco.....oommo..",
    "..o.....ocnmo...",
    "........ocmo....",
    "...o...ocmo.....",
    "..oco..ommo.....",
    "...o..ommo......",
    "......omo.......",
    ".....omo........",
    "....omo.........",
    "....oo..........",
    "................",
]

ORBIT = [
    "................",
    "................",
    "......oooo......",
    "....oommmmoo....",
    "...ommo..ommo...",
    "..omo......omo..",
    "..om........mo..",
    "..om........mo..",
    "..om........mo..",
    "..omo......omo..",
    "...ommo..ommo...",
    "....oommmmoo....",
    "......oooo......",
    "................",
    "................",
    "................",
]

SPLATTER = [
    "................",
    ".......o........",
    "...o..oco...o...",
    "..oco..o...oco..",
    "...o.ooooo..o...",
    "....ommcmmo.....",
    "...ommcccmmo....",
    "..ommcccccmmo...",
    "..omcccccccmo...",
    "...ommcccmmo....",
    "....ommmmmo.....",
    "...o..ooo..o....",
    "..oco..o..oco...",
    "...o..oco...o...",
    ".......o........",
    "................",
]

SHATTER = [
    "................",
    ".......oo.......",
    "......occo......",
    "..o...ocmo...o..",
    ".oco..ocmo..oco.",
    "..oooooommoooo..",
    "..ommccmmccmmo..",
    "...ommcccmmo....",
    "...ommcccmmo....",
    "..ommccmmccmmo..",
    "..oooooommoooo..",
    ".oco..ocmo..oco.",
    "..o...ocmo...o..",
    "......occo......",
    ".......oo.......",
    "................",
]

RAYS = [
    "................",
    ".......oo.......",
    "..o....oo....o..",
    ".oco...cc...oco.",
    "..o..oocmcoo..o.",
    ".....ocmcmco....",
    "..oo.ocmcmco.oo.",
    "oococmmcccmmcooo",
    "oococmmcccmmcooo",
    "..oo.ocmcmco.oo.",
    ".....ocmcmco....",
    "..o..oocmcoo..o.",
    ".oco...cc...oco.",
    "..o....oo....o..",
    ".......oo.......",
    "................",
]

PORTAL = [
    "................",
    "......oooo......",
    "....oommmmoo....",
    "...ommccccmmo...",
    "..ommcoooocmmo..",
    "..omcoo..oocmo..",
    ".omco......ocmo.",
    ".omco......ocmo.",
    ".omco......ocmo.",
    ".omco......ocmo.",
    "..omcoo..oocmo..",
    "..ommcoooocmmo..",
    "...ommccccmmo...",
    "....oommmmoo....",
    "......oooo......",
    "................",
]

SOUL = [
    "................",
    ".......oo.......",
    "......ocmo......",
    "......ocmo......",
    ".....ocmmco.....",
    "....ocmccmco....",
    "...ocmcccmco....",
    "...ocmcccmco....",
    "....ocmcmco.....",
    ".....ommmo......",
    "......ooo.......",
    "..o.........o...",
    ".oco.......oco..",
    "..o.........o...",
    "................",
    "................",
]

CROWN = [
    "................",
    "................",
    "...c.......c....",
    "..ooo.....ooo...",
    "...c..ooo..c....",
    "......oco.......",
    ".o.....c.....o..",
    "ooo...ooo...ooo.",
    ".o...ommmo...o..",
    "..ommmmcmmmmo...",
    "..omcmmcmmcmo...",
    "..ommmmmmmmmo...",
    "..oooooooooo....",
    "..ommmmmmmmo....",
    "..oooooooooo....",
    "................",
]

HORIZON = [
    "................",
    "......aaaa......",
    "....aacccaaa....",
    "...acoooooca....",
    "..acoo....ooca..",
    "..aco......oca..",
    ".aco........oca.",
    ".aco........oca.",
    ".aco........oca.",
    ".aco........oca.",
    "..aco......oca..",
    "..acoo....ooca..",
    "...acoooooca....",
    "....aaacccaa....",
    "......aaaa......",
    "................",
]

# Each aura follows its own description: three lights, two rings, a helix of
# shards, a small sun. A single ring for all four was the first attempt and read
# as four recolours of one icon.
ORBIT_THREE = [
    "................",
    "................",
    "........ooo.....",
    ".......occco....",
    "...oooo.occo.o..",
    "..oo...o.oo.ooo.",
    ".oo.........o.o.",
    "occo.........oo.",
    "occo............",
    ".oo......ooo....",
    "..oo....occco...",
    "...ooo..occco...",
    "......ooo.ooo...",
    "................",
    "................",
    "................",
]

ORBIT_DOUBLE = [
    "................",
    "................",
    ".....oooooo.....",
    "...oommmmmmoo...",
    "..omo......omo..",
    ".omo..oooo..omo.",
    ".om..ommmmo..mo.",
    ".om..omccmo..mo.",
    ".om..omccmo..mo.",
    ".om..ommmmo..mo.",
    ".omo..oooo..omo.",
    "..omo......omo..",
    "...oommmmmmoo...",
    ".....oooooo.....",
    "................",
    "................",
]

HELIX = [
    "................",
    "...........oo...",
    "..........occo..",
    "..........occo..",
    "...........oo...",
    "........oo......",
    ".......occo.....",
    ".......occo.....",
    "........oo......",
    "....oo..........",
    "...occo.........",
    "...occo.........",
    "....oo..........",
    ".o..............",
    "oco.............",
    ".o..............",
]

SUN = [
    "................",
    ".......oo.......",
    "...o...cc...o...",
    "..oco..cc..oco..",
    "...o..oooo..o...",
    "....oommmmoo....",
    "..oommccccmmoo..",
    "occomcccccccmoco",
    "occomcccccccmoco",
    "..oommccccmmoo..",
    "....oommmmoo....",
    "...o..oooo..o...",
    "..oco..cc..oco..",
    "...o...cc...o...",
    ".......oo.......",
    "................",
]

VORTEX = [
    "................",
    "......oooo......",
    "....oomccmoo....",
    "...omccooccmo...",
    "..omco....ocmo..",
    "..mco..oo..ocm..",
    ".omc..oooo..cmo.",
    ".omc..oooo..cmo.",
    ".omc...oo...cmo.",
    "..mco......ocm..",
    "..omcoo..oocmo..",
    "...omcccccmo....",
    "....oommmoo.....",
    "......oooo......",
    "................",
    "................",
]

# One bright arc on a dark disc: a plain ring is what void_collapse already is.
HORIZON2 = [
    "................",
    ".......aa.......",
    ".....aacccaa....",
    "....acoooooca...",
    "...aco.....oca..",
    "..aco.......oco.",
    "..ac.........oo.",
    "..ac.........oo.",
    "..aco........oo.",
    "..aco.......ooo.",
    "...aco.....ooo..",
    "....acoooooco...",
    ".....aaccooo....",
    ".......aa.......",
    "................",
    "................",
]

RAYS2 = [
    "................",
    ".......cc.......",
    "...c...cc...c...",
    "....c..mm..c....",
    ".....c.mm.c.....",
    "..c...ommo...c..",
    "...c.ommccmmo.c.",
    "cmmommcccccmmomc",
    "cmmommcccccmmomc",
    "...c.ommccmmo.c.",
    "..c...ommo...c..",
    ".....c.mm.c.....",
    "....c..mm..c....",
    "...c...cc...c...",
    ".......cc.......",
    "................",
]

# outline, shadow, body, core, accent
SPECS = {
    "ember_trail": (TRAIL, "#3d1000", "#8f2f07", "#f07a12", "#ffd772", "#ffd772"),
    "blood_trail": (TRAIL, "#2b0206", "#6b0d14", "#b81a26", "#ff6f78", "#ff6f78"),
    "frost_trail": (TRAIL, "#0e2841", "#3a6fa8", "#8fc9ef", "#ffffff", "#ffffff"),
    "cherry_blossom_trail": (TRAIL, "#4d0f2d", "#b8397e", "#f584bb", "#ffe1ef", "#ffe1ef"),
    "drool_trail": (TRAIL, "#073c40", "#128f96", "#3fd8dd", "#d7fdff", "#d7fdff"),
    "ender_trail": (TRAIL, "#150528", "#4b1d80", "#9b4ede", "#e9c9ff", "#e9c9ff"),
    "prismatic_trail": (TRAIL, "#1e1030", "#2f8fd6", "#66d96b", "#ffe14d", "#ff7ad9"),
    "solar_orbit": (SUN, "#4a2900", "#c47b06", "#ffc328", "#fff2b0", "#fff2b0"),
    "crimson_orbit": (ORBIT_THREE, "#33030d", "#8c101f", "#e02d3c", "#ff9a9e", "#ff9a9e"),
    "emerald_orbit": (ORBIT_DOUBLE, "#052613", "#12793c", "#2fd15f", "#c2ffd6", "#c2ffd6"),
    "amethyst_orbit": (HELIX, "#230939", "#6a24a8", "#b558ee", "#f0cfff", "#f0cfff"),
    "celestial_crown": (CROWN, "#1a2447", "#3f5bab", "#8fb6ff", "#ffffff", "#ffffff"),
    "blood_burst": (SPLATTER, "#2b0206", "#7d0f19", "#cc1f2d", "#ff8a90", "#ff8a90"),
    "frozen_shatter": (SHATTER, "#0d2642", "#2f6ba3", "#79c4ef", "#ffffff", "#ffffff"),
    "shining_light": (RAYS2, "#4d3904", "#c99a0c", "#ffe14d", "#fffbe0", "#fffbe0"),
    "void_collapse": (VORTEX, "#100520", "#3b1566", "#7a2fc4", "#dbaaff", "#dbaaff"),
    "soul_requiem": (SOUL, "#04262d", "#0f6d80", "#31c4d8", "#ddfcff", "#ddfcff"),
    "event_horizon": (HORIZON2, "#05030d", "#1a0b33", "#3d1470", "#ffca4d", "#ff8a1f"),
}


def build(name):
    grid, outline, shadow, body, core, accent = SPECS[name]
    palette = {"o": rgb(outline), "s": rgb(shadow), "m": rgb(body),
               "c": rgb(core), "a": rgb(accent), "n": rgb(core)}
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    for y, row in enumerate(grid):
        for x, char in enumerate(row):
            if char != ".":
                img.putpixel((x, y), palette[char])
    return img


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="../src/assets/mgx/textures/item")
    arguments = parser.parse_args()
    out = pathlib.Path(arguments.out)
    out.mkdir(parents=True, exist_ok=True)
    for name in SPECS:
        build(name).save(out / f"{name}.png")
    print(f"wrote {len(SPECS)} icons to {out}")


if __name__ == "__main__":
    main()
