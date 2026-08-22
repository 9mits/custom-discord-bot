#!/usr/bin/env python3
"""Build the purple tooltip background used by secret cosmetics."""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = (
    Path(__file__).resolve().parent
    / "src/assets/mgx/textures/gui/sprites/tooltip"
)


def build_background() -> None:
    image = Image.new("RGBA", (16, 16), (18, 5, 29, 255))
    draw = ImageDraw.Draw(image)
    draw.rectangle((1, 1, 14, 14), fill=(27, 8, 43, 255))
    draw.rectangle((2, 2, 13, 13), fill=(35, 10, 55, 255))
    image.save(ROOT / "secret_background.png", optimize=True)


def build_frame() -> None:
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    dark = (72, 24, 104, 255)
    purple = (154, 67, 214, 255)
    bright = (214, 139, 255, 255)

    draw.rectangle((0, 0, 15, 15), outline=dark)
    draw.rectangle((1, 1, 14, 14), outline=purple)
    draw.line((2, 1, 6, 1), fill=bright)
    draw.line((1, 2, 1, 6), fill=bright)
    draw.point((2, 2), fill=(242, 202, 255, 255))
    draw.line((9, 14, 13, 14), fill=(108, 34, 157, 255))
    draw.line((14, 9, 14, 13), fill=(108, 34, 157, 255))
    image.save(ROOT / "secret_frame.png", optimize=True)


def main() -> None:
    ROOT.mkdir(parents=True, exist_ok=True)
    build_background()
    build_frame()


if __name__ == "__main__":
    main()
