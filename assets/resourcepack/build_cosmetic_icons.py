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
    zoom_trails()


if __name__ == "__main__":
    main()
