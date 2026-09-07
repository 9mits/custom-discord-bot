"""Package existing generated badge pixels as Java and Bedrock text glyphs."""

import io
import json
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent
CATALOG = ROOT / "badges.json"
TEXTURES = ROOT / "src/assets/mgx/textures/badge"
CELL = 72


def badges():
    return json.loads(CATALOG.read_text(encoding="utf-8"))


def bedrock_files():
    # A dedicated private-use page avoids replacing vanilla controller symbols.
    sheet = Image.new("RGBA", (CELL * 16, CELL * 16))
    files = {}
    for name, code in badges().items():
        index = int(code, 16) - 0xE800
        if not 0 <= index < 256:
            raise ValueError(f"badge outside reserved E8 page: {name}")
        path = TEXTURES / f"{name}.png"
        with Image.open(path) as image:
            if image.size != (CELL, CELL) or image.mode != "RGBA":
                raise ValueError(f"invalid badge texture: {path}")
            sheet.paste(image, (index % 16 * CELL, index // 16 * CELL))
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
