#!/usr/bin/env python3
"""Сборка ресурспака HahGPS (Minecraft 1.21.11+).

Что делает:
  * рисует 2D-иконки меню (16x16) по docs/models.md;
  * собирает 3D-модели трекеров iTrak (8 цветов x обычный/водозащищённый);
  * переопределяет item model definitions по custom_model_data так, как их
    ставит плагин (ItemIds.java);
  * рендерит превью, упаковывает zip.

Запуск: python3 respack/build.py
"""
from __future__ import annotations

import json
import math
import shutil
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

import icons2d
import mc_render

ROOT = Path(__file__).resolve().parent
PACK = ROOT / "hahgps-pack"
DIST = ROOT / "dist"
PREVIEW = ROOT / "preview"
PACK_FORMAT = 75
MAX_FORMAT = 200

FALLBACKS = json.loads((ROOT / "vanilla_fallbacks.json").read_text(encoding="utf-8"))

COLORS = [
    ("blue", "LAPIS_LAZULI", (0x3F, 0x86, 0xFF)),
    ("cyan", "PRISMARINE_CRYSTALS", (0x4F, 0xD1, 0xE0)),
    ("green", "EMERALD", (0x55, 0xC3, 0x6B)),
    ("yellow", "GOLD_NUGGET", (0xF0, 0xC2, 0x4B)),
    ("orange", "MAGMA_CREAM", (0xF2, 0x99, 0x4A)),
    ("red", "REDSTONE", (0xEB, 0x57, 0x57)),
    ("purple", "AMETHYST_SHARD", (0x9B, 0x51, 0xE0)),
    ("white", "QUARTZ", (0xF2, 0xF2, 0xF2)),
]
TRACKER_BASE = 2001

# CMD -> (ключ модели, основы). Один номер может стоять на нескольких основах.
ICONS = [
    (1001, "nav-back", ["SPECTRAL_ARROW"]),
    (1002, "nav-forward", ["SPECTRAL_ARROW"]),
    (1003, "nav-info", ["LIME_DYE"]),
    (1004, "nav-close", ["BARRIER"]),
    (1005, "nav-page", ["BOOK"]),
    (1006, "brand", ["PAPER"]),
    (1007, "filler", ["BLUE_STAINED_GLASS_PANE"]),
    (1101, "main-points", ["COMPASS"]),
    (1102, "main-categories", ["BOOK"]),
    (1103, "main-map", ["MAP"]),
    (1104, "main-search", ["PAPER"]),
    (1105, "main-settings", ["REDSTONE"]),
    (1106, "main-itrak", ["RECOVERY_COMPASS"]),
    (1201, "poi-pin-dot", ["COMPASS"]),
    (1202, "poi-pin-flag", ["BEACON"]),
    (1203, "poi-pin-diamond", ["BOOK"]),
    (1204, "poi-pin-drop", ["ENDER_PEARL"]),
    (1205, "poi-pin-star", ["NETHER_STAR"]),
    (1206, "poi-pin-square", ["PAPER"]),
    (1301, "poi-info", ["PAPER"]),
    (1302, "poi-route", ["COMPASS"]),
    (1303, "poi-rate", ["NETHER_STAR"]),
    (1304, "poi-delete", ["LAVA_BUCKET"]),
    (1401, "category-row", ["BOOK"]),
    (1402, "category-create", ["EMERALD"]),
    (1403, "category-delete", ["LAVA_BUCKET"]),
    (1501, "search-input", ["PAPER"]),
    (1502, "search-quick", ["COMPASS"]),
    (1503, "search-result", ["COMPASS", "PAPER"]),
    (1504, "search-empty", ["BARRIER"]),
    (1505, "search-limit", ["REPEATER"]),
    (1601, "settings-language", ["BOOK"]),
    (1602, "settings-hints", ["PAPER"]),
    (1603, "settings-scale", ["MAP"]),
    (1701, "transport-foot", ["LEATHER_BOOTS"]),
    (1702, "transport-car", ["MINECART"]),
    (1703, "transport-transit", ["CLOCK"]),
    (1704, "transport-air", ["FEATHER"]),
    (1705, "transport-water", ["OAK_BOAT"]),
    (1801, "map", ["FILLED_MAP", "ARROW", "BOOK", "MAP", "PAPER",
                   "COMPASS", "SPYGLASS", "GLASS"]),
    (2020, "locator", ["RECOVERY_COMPASS"]),
    (2021, "locator-long", ["RECOVERY_COMPASS"]),
    (2032, "itrak-route", ["COMPASS"]),
    (2035, "itrak-remove", ["LAVA_BUCKET"]),
    (2036, "itrak-scan", ["COMPASS"]),
    (2037, "itrak-give", ["LAPIS_LAZULI"]),
    (2038, "itrak-refresh", ["SPYGLASS"]),
    (2039, "itrak-empty", ["BARRIER"]),
    (2042, "terminal", ["LODESTONE"]),
    (2044, "rename", ["NAME_TAG"]),
]
# одинаковый CMD, разные основы — разные картинки
ICON_BY_MATERIAL = {
    ("SPYGLASS", 2033): "itrak-watch",
    ("GLASS", 2033): "itrak-watch-off",
    ("CHEST", 2034): "itrak-take",
    ("BARRIER", 2034): "itrak-take-off",
}
# карточка трекера красится материалом цвета
CARD_CMD = 2031


# ---------------------------------------------------------------------------
# 3D-трекер
# ---------------------------------------------------------------------------

def tex_body(color, wet: bool) -> Image.Image:
    """16x16: пластик корпуса-датчика; мокрый — капли и светлая кромка."""
    r, g, b = color
    img = Image.new("RGBA", (16, 16))
    d = ImageDraw.Draw(img)
    for y in range(16):
        for x in range(16):
            k = 1.04 - y / 52.0
            c = (int(r * k), int(g * k), int(b * k))
            if (x * 3 + y) % 23 == 0:
                c = tuple(max(0, v - 9) for v in c)
            if y in (0, 15):
                c = tuple(int(v * 0.55) for v in c)
            d.point((x, y), fill=c + (255,))
    for (x, y) in ((2, 3), (13, 3), (3, 12), (12, 12)):
        d.point((x, y), fill=(88, 94, 106, 255))
    if wet:
        for (x, y) in ((4, 5), (11, 7), (6, 12), (12, 13)):
            d.point((x, y), fill=(160, 228, 250, 255))
        d.rectangle([0, 0, 15, 0], fill=(150, 210, 235, 255))
    return img


def tex_panel() -> Image.Image:
    img = Image.new("RGBA", (16, 16), (20, 24, 32, 255))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 15, 15], fill=(20, 24, 32, 255))
    d.rectangle([1, 1, 14, 14], fill=(12, 16, 24, 255))
    d.line([1, 4, 6, 4], fill=(63, 134, 255, 255))     # линия «маршрута»
    d.line([6, 4, 9, 7], fill=(63, 134, 255, 255))
    d.line([9, 7, 14, 6], fill=(79, 209, 224, 255))
    d.rectangle([6, 8, 8, 10], fill=(79, 209, 224, 255))
    d.point((7, 9), fill=(234, 244, 255, 255))
    d.rectangle([2, 12, 5, 13], fill=(90, 100, 116, 255))
    d.rectangle([10, 12, 13, 13], fill=(90, 100, 116, 255))
    return img


def tex_metal() -> Image.Image:
    img = Image.new("RGBA", (16, 16))
    d = ImageDraw.Draw(img)
    for y in range(16):
        v = 150 if y % 4 == 0 else (96 if y % 4 == 2 else 122)
        d.line([0, y, 15, y], fill=(v, v + 2, v + 8, 255))
    return img


def disc_strips(radius, x0, x1, y0, y1, strip=2.0, overlap=0.02):
    out = []
    x = x0
    while x < x1 - 1e-9:
        xb = min(x + strip, x1)
        dx = (x + xb) / 2.0 - 8.0
        half = math.sqrt(max(radius * radius - dx * dx, 0.0))
        if half > 0.4:
            out.append((x, min(xb + overlap, x1), 8.0 - half, 8.0 + half))
        x = xb
    return out


def model_tracker(color_key: str, wet: bool) -> dict:
    suffix = "_wp" if wet else ""
    texts = {
        "particle": f"hahgps:item/tracker_{color_key}{suffix}",
        "body": f"hahgps:item/tracker_{color_key}{suffix}",
        "panel": "hahgps:item/tracker_panel",
        "metal": "hahgps:item/tracker_metal",
    }

    def box(b, tex):
        el = {"from": [round(v, 3) for v in b[:3]], "to": [round(v, 3) for v in b[3:]],
              "faces": {}}
        for face in ("north", "south", "west", "east", "up", "down"):
            el["faces"][face] = {"texture": tex, "uv": _auto_uv(face, b)}
        return el

    els = []
    for (x0, x1, z0, z1) in disc_strips(6.0, 2.0, 14.0, 4.0, 9.5, 2.0):
        els.append(box((x0, 4.0, z0, x1, 9.5, z1), "#body"))
    for (x0, x1, z0, z1) in disc_strips(6.4, 1.6, 14.4, 9.5, 11.0, 2.0):
        els.append(box((x0, 9.5, z0, x1, 11.0, z1), "#body"))
    for (x0, x1, z0, z1) in disc_strips(5.2, 2.8, 13.2, 11.0, 12.0, 2.0):
        els.append(box((x0, 11.0, z0, x1, 12.0, z1), "#metal"))
    for dx in (-3.0, -1.0, 1.0, 3.0):
        els.append(box((8.0 + dx, 12.0, 5.6, 8.6 + dx, 12.4, 10.4), "#panel"))
    els.append(box((7.0, 11.6, 6.4, 9.0, 12.6, 8.0), "#panel"))
    els.append(box((9.6, 9.8, 2.2, 11.2, 11.0, 3.8), "#metal"))
    els.append(box((7.0, 2.2, 6.6, 9.0, 4.0, 9.4), "#metal"))
    return {
        "parent": "minecraft:block/block",
        "textures": texts,
        "elements": els,
        "display": {
            "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
            "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
            "on_shelf": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
            "thirdperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 3, 0], "scale": [0.4, 0.4, 0.4]},
            "thirdperson_lefthand": {"rotation": [0, -45, 0], "translation": [0, 3, 0], "scale": [0.4, 0.4, 0.4]},
            "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 2, 0], "scale": [0.45, 0.45, 0.45]},
            "firstperson_lefthand": {"rotation": [0, -45, 0], "translation": [0, 2, 0], "scale": [0.45, 0.45, 0.45]},
        },
    }


def _auto_uv(face, b):
    x0, y0, z0, x1, y1, z1 = b
    if face == "north":
        return [x0, 16 - y1, x1, 16 - y0]
    if face == "south":
        return [16 - x1, 16 - y1, 16 - x0, 16 - y0]
    if face == "west":
        return [z0, 16 - y1, z1, 16 - y0]
    if face == "east":
        return [16 - z1, 16 - y1, 16 - z0, 16 - y0]
    if face == "up":
        return [x0, z0, x1, z1]
    return [x0, 16 - z1, x1, 16 - z0]


# ---------------------------------------------------------------------------
# сборка пака
# ---------------------------------------------------------------------------

def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def build_pack() -> None:
    if PACK.exists():
        shutil.rmtree(PACK)
    tex = PACK / "assets" / "hahgps" / "textures" / "item"
    models = PACK / "assets" / "hahgps" / "models" / "item"
    items = PACK / "assets" / "minecraft" / "items"
    tex.mkdir(parents=True, exist_ok=True)
    models.mkdir(parents=True, exist_ok=True)

    # 2D-иконки
    for cmd, key, mats in ICONS:
        icons2d.render(icons2d.DRAW[key]).save(tex / f"{key}.png")
    for (mat, cmd), key in ICON_BY_MATERIAL.items():
        icons2d.render(icons2d.DRAW[key]).save(tex / f"{key}.png")
    icons2d.render(icons2d.DRAW["itrak-card"]).save(tex / "itrak-card.png")

    # модели плоских иконок: parent item/generated + layer0
    flat = {key for _cmd, key, _mats in ICONS}
    flat |= set(ICON_BY_MATERIAL.values())
    flat.add("itrak-card")
    for key in sorted(flat):
        write_json(models / f"{key}.json", {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"hahgps:item/{key}"},
        })

    # 3D-трекеры
    tex_panel().save(tex / "tracker_panel.png")
    tex_metal().save(tex / "tracker_metal.png")
    for key, mat, rgb in COLORS:
        for wet in (False, True):
            name = f"tracker_{key}{'_wp' if wet else ''}"
            tex_body(rgb, wet).save(tex / f"{name}.png")
            write_json(models / f"{name}.json", model_tracker(key, wet))

    # item definitions: материал -> [(cmd, model)]
    per_material: dict[str, list[tuple[int, str]]] = {}
    for cmd, key, mats in ICONS:
        for mat in mats:
            model_key = ICON_BY_MATERIAL.get((mat, cmd), key)
            per_material.setdefault(mat, []).append((cmd, f"hahgps:item/{model_key}"))
    for idx, (key, mat, _rgb) in enumerate(COLORS):
        normal = TRACKER_BASE + idx * 2
        wet = normal + 1
        per_material.setdefault(mat, []).append((normal, f"hahgps:item/tracker_{key}"))
        per_material.setdefault(mat, []).append((wet, f"hahgps:item/tracker_{key}_wp"))
        per_material[mat].append((CARD_CMD, "hahgps:item/itrak-card"))
    for (mat, cmd), key in ICON_BY_MATERIAL.items():
        per_material.setdefault(mat, []).append((cmd, f"hahgps:item/{key}"))

    for mat, entries in sorted(per_material.items()):
        entries.sort(key=lambda e: e[0])
        write_json(items / f"{mat.lower()}.json", {
            "model": {
                "type": "minecraft:range_dispatch",
                "property": "minecraft:custom_model_data",
                "entries": [{"threshold": float(cmd),
                             "model": {"type": "minecraft:model", "model": model}}
                            for cmd, model in entries],
                "fallback": FALLBACKS[mat],
            }
        })

    write_json(PACK / "pack.mcmeta", {
        "pack": {
            "description": "HahGPS: iTrak, локаторы, терминал и меню",
            "min_format": PACK_FORMAT,
            "max_format": MAX_FORMAT,
        }
    })


def validate() -> list[str]:
    problems = []
    known = {p.relative_to(PACK).as_posix() for p in PACK.rglob("*") if p.is_file()}

    def exists(kind: str, ref: str) -> bool:
        ns, _, path = ref.partition(":")
        if not path:
            ns, path = "minecraft", ref
        if ns == "minecraft":
            return True
        return f"assets/{ns}/{kind}/{path}.png" in known or f"assets/{ns}/{kind}/{path}.json" in known

    for path in PACK.rglob("*.json"):
        data = json.loads(path.read_text(encoding="utf-8"))
        rel = path.relative_to(PACK).as_posix()

        def walk(node):
            if isinstance(node, dict):
                for key, val in node.items():
                    if key == "texture" and isinstance(val, str) and not val.startswith("#"):
                        if not exists("textures", val):
                            problems.append(f"{rel}: нет текстуры {val}")
                    elif key == "model" and isinstance(val, str):
                        if not exists("models", val):
                            problems.append(f"{rel}: нет модели {val}")
                    elif key == "parent" and isinstance(val, str):
                        if not exists("models", val):
                            problems.append(f"{rel}: нет parent {val}")
                    else:
                        walk(val)
            elif isinstance(node, list):
                for x in node:
                    walk(x)

        walk(data)
    return problems


def zip_pack(name: str = "hahgps.zip") -> Path:
    DIST.mkdir(parents=True, exist_ok=True)
    out = DIST / name
    if out.exists():
        out.unlink()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for path in sorted(PACK.rglob("*")):
            if path.is_file():
                z.write(path, path.relative_to(PACK).as_posix())
    return out


# ---------------------------------------------------------------------------
# превью
# ---------------------------------------------------------------------------

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"


def label(img, text, xy, size=20, fill=(214, 220, 232)):
    d = ImageDraw.Draw(img)
    d.text(xy, text, font=ImageFont.truetype(FONT, size), fill=fill)


def load_images() -> dict[str, Image.Image]:
    base = PACK / "assets" / "hahgps" / "textures" / "item"
    return {f"hahgps:item/{p.stem}": Image.open(p).convert("RGBA") for p in base.glob("*.png")}


def render_item_tile(model: dict, images, px=128, yaw=-45.0, pitch=-22.0, view=1.15):
    sc = mc_render.Scene(px, px)
    mat = (mc_render.translate((0, 0, 0)) @ mc_render.scale_m((1 / 16, 1 / 16, 1 / 16))
           @ mc_render.translate((-8, -8, -8)))
    sc.add_model(model, mat, images)
    cam = mc_render.Cam(yaw, pitch, px / view, px, px)
    return sc.raster(cam)


def build_previews() -> list[Path]:
    PREVIEW.mkdir(parents=True, exist_ok=True)
    images = load_images()
    out = []

    # 1. трекеры 3D: 8 цветов x (обычный, водозащищённый) = 16 плиток
    cols, rows = 8, 2
    tile, pad, top = 150, 10, 46
    W = cols * (tile + pad) + pad
    H = top + rows * (tile + 34) + pad
    sheet = Image.new("RGB", (W, H), (18, 24, 38))
    label(sheet, "iTrak: 8 цветов, обычный и водозащищённый (3D, CMD 2001–2016)", (14, 12), 22)
    for idx, (key, mat, rgb) in enumerate(COLORS):
        for row, wet in enumerate((False, True)):
            name = f"tracker_{key}{'_wp' if wet else ''}"
            model = json.loads((PACK / "assets" / "hahgps" / "models" / "item" / f"{name}.json")
                               .read_text(encoding="utf-8"))
            icon = render_item_tile(model, images, 128, yaw=-38, pitch=-26, view=1.32)
            icon = icon.resize((tile, tile), Image.NEAREST)
            x = pad + idx * (tile + pad)
            y = top + row * (tile + 34)
            sheet.paste(icon, (x, y))
            cmd = TRACKER_BASE + idx * 2 + (1 if wet else 0)
            label(sheet, f"{key}{' wp' if wet else ''} · {cmd}", (x, y + tile + 4), 16,
                  (200 - row * 30, 210 - row * 30, 226))
    p = PREVIEW / "01-trackers.png"
    sheet.save(p)
    out.append(p)

    # 2. контактный лист 2D-иконок
    entries = [(cmd, key) for cmd, key, _m in ICONS]
    entries += [(2033, "itrak-watch"), (2033, "itrak-watch-off"),
                (2034, "itrak-take"), (2034, "itrak-take-off"),
                (CARD_CMD, "itrak-card")]
    entries.sort(key=lambda e: e[0])
    cols = 7
    cell, pad = 112, 8
    rows = (len(entries) + cols - 1) // cols
    W = cols * (cell + pad) + pad
    H = 46 + rows * (cell + 34) + pad
    sheet = Image.new("RGB", (W, H), (18, 24, 38))
    label(sheet, f"Иконки меню (2D, 16×16): {len(entries)} шт.", (14, 12), 22)
    for i, (cmd, key) in enumerate(entries):
        img = images[f'hahgps:item/{key}'].resize((cell, cell), Image.NEAREST)
        x = pad + (i % cols) * (cell + pad)
        y = 46 + (i // cols) * (cell + 34)
        sheet.paste(img, (x, y), img.split()[3])
        label(sheet, str(cmd), (x, y + cell + 1), 13, (150, 200, 255))
        label(sheet, key, (x, y + cell + 15), 11, (170, 178, 196))
    p = PREVIEW / "02-icons.png"
    sheet.save(p)
    out.append(p)

    # 3. как это в слотах
    slots = [("tracker_blue", 2001), ("tracker_blue_wp", 2002),
             ("tracker_red", 2011), ("tracker_red_wp", 2012)]
    W, H = 1080, 260
    sheet = Image.new("RGB", (W, H), (18, 24, 38))
    d = ImageDraw.Draw(sheet)
    label(sheet, "В руке и в меню: слот 64 px и 32 px", (14, 10), 20)
    x = 20
    for key, cmd in slots:
        model = json.loads((PACK / "assets" / "hahgps" / "models" / "item" / f"{key}.json")
                           .read_text(encoding="utf-8"))
        for slot in (64, 32):
            icon = render_item_tile(model, images, slot * 2, yaw=-38, pitch=-26, view=1.32).resize((slot, slot), Image.NEAREST)
            d.rounded_rectangle([x, 44, x + slot, 44 + slot], radius=5,
                                fill=(40, 48, 64), outline=(60, 70, 92))
            sheet.paste(icon, (x, 44))
            x += slot + 10
        x += 16
    for key, cmd in (("itrak-card", 2031), ("locator", 2020), ("terminal", 2042), ("map", 1801)):
        img = images[f'hahgps:item/{key}']
        for slot in (64, 32):
            d.rounded_rectangle([x, 44, x + slot, 44 + slot], radius=5,
                                fill=(40, 48, 64), outline=(60, 70, 92))
            icon2 = img.resize((slot, slot), Image.NEAREST)
            sheet.paste(icon2, (x, 44), icon2.split()[3])
            x += slot + 10
        x += 10
    p = PREVIEW / "03-in-menu.png"
    sheet.save(p)
    out.append(p)

    # иконка пака
    brand = images["hahgps:item/brand"].resize((128, 128), Image.NEAREST)
    brand.save(PACK / "pack.png")
    return out


def main() -> None:
    build_pack()
    problems = validate()
    previews = build_previews()
    out = zip_pack()
    print(f"пак: {PACK}")
    print(f"zip: {out} ({out.stat().st_size / 1024:.1f} КБ)")
    print(f"item definitions: {len(list((PACK / 'assets' / 'minecraft' / 'items').glob('*.json')))}")
    for p in previews:
        print("превью:", p)
    if problems:
        print("ПРОБЛЕМЫ:")
        for p in problems:
            print("  -", p)
    else:
        print("валидация: ок")


if __name__ == "__main__":
    main()
