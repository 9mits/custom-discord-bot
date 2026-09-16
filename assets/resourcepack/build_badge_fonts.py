"""Package existing generated badge pixels as Java and Bedrock text glyphs."""

import io
import json
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent
CATALOG = ROOT / "badges.json"
TEXTURES = ROOT / "src/assets/mgx/textures/badge"
CELL = 72
# Bedrock has no per-glyph metrics: it scales one cell of a glyph page to the line height,
# so how big a badge looks is decided entirely by how much of its cell the artwork fills.
# Filling the cell edge to edge — which this sheet used to do — drew every badge about
# twice the height of the letters beside it. Vanilla's own glyphs use a little over half
# their cell, which is what these two numbers reproduce. Java is unaffected: its font
# providers read the individual badge PNGs and size them with `height`/`ascent`.
BEDROCK_CELL = 64
BEDROCK_GLYPH = 36
BEDROCK_TOP = 8


def badges():
    return json.loads(CATALOG.read_text(encoding="utf-8"))


def bedrock_files():
    # A dedicated private-use page avoids replacing vanilla controller symbols.
    sheet = Image.new("RGBA", (BEDROCK_CELL * 16, BEDROCK_CELL * 16))
    files = {}
    for name, code in badges().items():
        index = int(code, 16) - 0xE800
        if not 0 <= index < 256:
            raise ValueError(f"badge outside reserved E8 page: {name}")
        path = TEXTURES / f"{name}.png"
        with Image.open(path) as image:
            if image.size != (CELL, CELL) or image.mode != "RGBA":
                raise ValueError(f"invalid badge texture: {path}")
            # An exact halving, so every logical pixel stays a crisp 2x2 block.
            glyph = image.resize((BEDROCK_GLYPH, BEDROCK_GLYPH), Image.NEAREST)
            sheet.paste(glyph, (index % 16 * BEDROCK_CELL,
                                index // 16 * BEDROCK_CELL + BEDROCK_TOP))
        files[f"textures/mgx/badges/{name}.png"] = path.read_bytes()
    output = io.BytesIO()
    sheet.save(output, format="PNG", optimize=True)
    files["font/glyph_E8.png"] = output.getvalue()
    return files


def main():
    font = {"providers": [
        {"type": "bitmap", "file": f"mgx:badge/{name}.png", "ascent": 8,
         "height": 9, "chars": [chr(int(code, 16))]}
        for name, code in badges().items()
    ]}
    target = ROOT / "src/assets/mgx/font/badges.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(font, indent=2) + "\n", encoding="utf-8")
    # Font providers compose with vanilla's providers; only our PUA glyphs are added.
    default = ROOT / "src/assets/minecraft/font/default.json"
    default.write_text(json.dumps({"providers": [
        {"type": "reference", "id": "mgx:badges"}
    ]}, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
