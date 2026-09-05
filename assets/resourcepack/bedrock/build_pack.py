#!/usr/bin/env python3
"""Build the Bedrock pack and Geyser v2 item mappings from the Java assets."""

from __future__ import annotations

import hashlib
import json
import pathlib
import zipfile


BEDROCK = pathlib.Path(__file__).resolve().parent
RESOURCE_PACK = BEDROCK.parent
JAVA_SOURCE = RESOURCE_PACK / "src"
CATALOG = BEDROCK / "catalog.json"
MAPPINGS = BEDROCK / "mgx_items.json"
TARGET = BEDROCK / "MysteriousSMPX-Bedrock.mcpack"
MUSIC_FILES = {
    "iridescent_imperium": JAVA_SOURCE / "assets" / "mgx" / "sounds" / "music" / "iridescent_imperium.ogg",
    "amethyst_dragon_ascendant": JAVA_SOURCE / "assets" / "mgx" / "sounds" / "music" / "amethyst_dragon_ascendant.ogg",
}
JAVA_SOUNDS = JAVA_SOURCE / "assets" / "mgx" / "sounds.json"

HEADER_UUID = "33d7b953-728f-43b3-a6a9-675e70370582"
MODULE_UUID = "d3b48e32-87e7-4f60-8504-17ebcc9dafcd"


def read_json(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def texture_path(texture_key: str) -> pathlib.Path:
    namespace, name = texture_key.split(":", 1)
    return JAVA_SOURCE / "assets" / namespace / "textures" / f"{name}.png"


def model_texture(model_key: str) -> pathlib.Path:
    namespace, name = model_key.split(":", 1)
    item = read_json(JAVA_SOURCE / "assets" / namespace / "items" / f"{name}.json")
    item_model = item["model"]
    while item_model["type"] == "minecraft:condition":
        item_model = item_model["on_false"]
    model_key = item_model["model"]
    namespace, name = model_key.split(":", 1)
    model = read_json(JAVA_SOURCE / "assets" / namespace / "models" / f"{name}.json")
    textures = model["textures"]
    texture_key = textures.get("layer0", textures.get("0"))
    if texture_key is None:
        raise ValueError(f"model {model_key} has no inventory texture")
    return texture_path(texture_key)


def icon_texture(item: dict) -> pathlib.Path:
    explicit = item.get("icon_texture")
    return texture_path(explicit) if explicit else model_texture(item["model"])


def source_version(items: list[dict]) -> list[int]:
    digest = hashlib.sha256(CATALOG.read_bytes())
    for item in items:
        digest.update(icon_texture(item).read_bytes())
    for music in MUSIC_FILES.values():
        digest.update(music.read_bytes())
    digest.update(JAVA_SOUNDS.read_bytes())
    raw = digest.digest()
    return [int.from_bytes(raw[index:index + 2], "big") or 1 for index in (0, 2, 4)]


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def zip_files(target: pathlib.Path, files: dict[str, bytes]) -> None:
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)


def main() -> None:
    items = read_json(CATALOG)["items"]
    models: set[str] = set()
    identifiers: set[str] = set()
    mappings: dict[str, list[dict]] = {}
    texture_data: dict[str, dict] = {}
    pack_files: dict[str, bytes] = {}

    for item in items:
        model = item["model"]
        identifier = item["bedrock_identifier"]
        if model in models:
            raise ValueError(f"duplicate Java item model: {model}")
        if identifier in identifiers:
            raise ValueError(f"duplicate Bedrock item identifier: {identifier}")
        models.add(model)
        identifiers.add(identifier)

        mappings.setdefault(item["java_item"], []).append({
            "type": "definition",
            "model": model,
            "bedrock_identifier": identifier,
            "display_name": item["display_name"],
            "bedrock_options": {"icon": identifier},
        })
        texture_name = identifier.split(":", 1)[1]
        texture_path = f"textures/items/{texture_name}"
        texture_data[identifier] = {"textures": [texture_path]}
        pack_files[f"{texture_path}.png"] = icon_texture(item).read_bytes()

    version = source_version(items)
    manifest = {
        "format_version": 2,
        "header": {
            "name": "Mysterious SMP X",
            "description": "Mysterious SMP X — custom items, cosmetics, sounds, and UI",
            "uuid": HEADER_UUID,
            "version": version,
            "min_engine_version": [1, 21, 0],
        },
        "modules": [{
            "type": "resources",
            "uuid": MODULE_UUID,
            "version": version,
        }],
    }
    atlas = {
        "resource_pack_name": "mysterious_smp_x",
        "texture_name": "atlas.items",
        "texture_data": texture_data,
    }
    mapping_document = {"format_version": 2, "items": mappings}
    MAPPINGS.write_bytes(json_bytes(mapping_document))
    pack_files["manifest.json"] = json_bytes(manifest)
    pack_files["textures/item_texture.json"] = json_bytes(atlas)
    for name, music in MUSIC_FILES.items():
        pack_files[f"sounds/music/{name}.ogg"] = music.read_bytes()
    pack_files["sounds/sound_definitions.json"] = json_bytes({
        "format_version": "1.14.0",
        "sound_definitions": {
            f"mgx:{name}": {
                # The server exposes its own /settings volume. UI bypasses Bedrock's
                # Music slider while remaining under the client's master volume.
                "category": "ui",
                "sounds": [{
                    "name": f"sounds/music/{name}",
                    "stream": True,
                    "is3D": False,
                    "volume": 0.72,
                }],
            }
            for name in MUSIC_FILES
        },
    })
    zip_files(TARGET, pack_files)

    print(f"{TARGET.name}: {len(items)} custom items, {TARGET.stat().st_size} bytes")
    print(f"pack-version={'.'.join(map(str, version))}")
    print(f"pack-sha256={hashlib.sha256(TARGET.read_bytes()).hexdigest()}")
    print(f"mapping-sha256={hashlib.sha256(MAPPINGS.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
