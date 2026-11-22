#!/usr/bin/env python3
"""Сборка ресурспака HahGPS: стрелки маршрута (Minecraft 1.21.11+).

Что делает:
  * рисует 8 HUD-глифов 8×8 и шрифт `hahgps:arrow`;
  * собирает 3D-модели предмета-указателя и блока-знака (свои текстуры
    в namespace `hahgps`, ванильные блоки и их текстуры не переопределяются);
  * добавляет item definitions для `minecraft:oak_sign`: CMD 1810–1817 —
    предмет-указатель, 1820–1827 — блок-знак, остальные CMD уходят в ваниль;
  * рендерит превью и пакует `dist/hahgps.zip`.

Запуск: python3 respack/build.py   (нужны pillow и numpy)
"""
from __future__ import annotations

import json
import shutil
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import arrow_art
import mc_render

ROOT = Path(__file__).resolve().parent
PACK = ROOT / "hahgps-pack"
DIST = ROOT / "dist"
PREVIEW = ROOT / "preview"

PACK_FORMAT = 75
MAX_FORMAT = 200
GLYPH_FIRST = 0xE000

# CMD должны совпадать с ru.hahgps.item.ItemIds.ROUTE_SIGN_BASE / ROUTE_BLOCK_BASE.
ROUTE_SIGN_BASE = 1810
ROUTE_BLOCK_BASE = 1820

OAK_SIGN_FALLBACK = {"type": "minecraft:model", "model": "minecraft:item/oak_sign"}

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(data, ensure_ascii=False, indent=1) + "\n"
    # Коды шрифта — …: в файле они должны быть экранированы, иначе
    # редакторы и диффы с приватными символами выглядят пустыми.
    for code in range(GLYPH_FIRST, GLYPH_FIRST + 8):
        text = text.replace(chr(code), f"\\u{code:04X}")
    path.write_text(text, encoding="utf-8")


def _faces(texture: str, uv=(0, 0, 16, 16)) -> dict:
    return {face: {"texture": texture, "uv": list(uv)}
            for face in ("north", "south", "west", "east", "up", "down")}


def _element(box, textures: dict[str, str]) -> dict:
    element = {"from": [round(v, 3) for v in box[:3]],
               "to": [round(v, 3) for v in box[3:]],
               "faces": {}}
    for face, texture in textures.items():
        element["faces"][face] = {"texture": texture, "uv": [0, 0, 16, 16]}
    return element


DISPLAY_ITEM = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0],
            "scale": [0.55, 0.55, 0.55]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 1.5, 0],
               "scale": [0.3, 0.3, 0.3]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0],
              "scale": [0.5, 0.5, 0.5]},
    "thirdperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 2, 0],
                              "scale": [0.4, 0.4, 0.4]},
    "thirdperson_lefthand": {"rotation": [0, -45, 0], "translation": [0, 2, 0],
                             "scale": [0.4, 0.4, 0.4]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 2, 0],
                              "scale": [0.45, 0.45, 0.45]},
    "firstperson_lefthand": {"rotation": [0, -45, 0], "translation": [0, 2, 0],
                             "scale": [0.45, 0.45, 0.45]},
}

DISPLAY_BLOCK_ITEM = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0],
            "scale": [0.625, 0.625, 0.625]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0],
               "scale": [0.25, 0.25, 0.25]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0],
              "scale": [0.5, 0.5, 0.5]},
    "on_shelf": {"rotation": [0, 180, 0], "translation": [0, 0, 0],
                 "scale": [1, 1, 1]},
    "thirdperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 3, 0],
                              "scale": [0.35, 0.35, 0.35]},
    "thirdperson_lefthand": {"rotation": [0, -45, 0], "translation": [0, 3, 0],
                             "scale": [0.35, 0.35, 0.35]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 2, 0],
                              "scale": [0.4, 0.4, 0.4]},
    "firstperson_lefthand": {"rotation": [0, -45, 0], "translation": [0, 2, 0],
                             "scale": [0.4, 0.4, 0.4]},
}


def model_sign(direction: str) -> dict:
    """3D-предмет: квадратный знак на короткой стойке."""
    face = f"hahgps:item/arrow_sign_{direction}"
    return {
        "parent": "minecraft:block/block",
        "textures": {
            "particle": face,
            "face": face,
            "back": "hahgps:item/arrow_sign_back",
            "edge": "hahgps:item/arrow_sign_edge",
            "post": "hahgps:item/arrow_sign_post",
        },
        "elements": [
            _element((7, 0, 7, 9, 5, 9), {
                "north": "#post", "south": "#post", "west": "#post",
                "east": "#post", "up": "#post", "down": "#post",
            }),
            _element((1, 2, 7.4, 15, 16, 8.6), {
                "north": "#face", "south": "#back", "west": "#edge",
                "east": "#edge", "up": "#edge", "down": "#edge",
            }),
        ],
        "display": DISPLAY_ITEM,
    }


def model_block(direction: str) -> dict:
    """Блок-знак: бетонная опора, стойка и квадратная плита со стрелкой."""
    face = f"hahgps:block/arrow_block_{direction}"
    return {
        "parent": "minecraft:block/block",
        "textures": {
            "particle": face,
            "face": face,
            "back": "hahgps:block/arrow_block_back",
            "edge": "hahgps:block/arrow_block_edge",
            "post": "hahgps:block/arrow_block_post",
            "side": "hahgps:block/arrow_block_side",
            "bottom": "hahgps:block/arrow_block_bottom",
        },
        "elements": [
            _element((0, 0, 0, 16, 2, 16), {
                "north": "#side", "south": "#side", "west": "#side",
                "east": "#side", "up": "#side", "down": "#bottom",
            }),
            _element((7, 2, 7, 9, 7, 9), {
                "north": "#post", "south": "#post", "west": "#post",
                "east": "#post", "up": "#post", "down": "#post",
            }),
            _element((3, 6, 7.4, 13, 16, 8.6), {
                "north": "#face", "south": "#back", "west": "#edge",
                "east": "#edge", "up": "#edge", "down": "#edge",
            }),
        ],
    }


def build_pack() -> None:
    if PACK.exists():
        shutil.rmtree(PACK)
    tex_item = PACK / "assets/hahgps/textures/item"
    tex_block = PACK / "assets/hahgps/textures/block"
    tex_font = PACK / "assets/hahgps/textures/font"
    models_item = PACK / "assets/hahgps/models/item"
    models_block = PACK / "assets/hahgps/models/block"
    font_dir = PACK / "assets/hahgps/font"
    items_dir = PACK / "assets/minecraft/items"
    for path in (tex_item, tex_block, tex_font, models_item, models_block,
                 font_dir, items_dir):
        path.mkdir(parents=True, exist_ok=True)

    # --- HUD-глифы ---------------------------------------------------------
    arrow_art.font_atlas().save(tex_font / "arrow.png")
    chars = "".join(chr(GLYPH_FIRST + i) for i in range(len(arrow_art.DIRECTIONS)))
    write_json(font_dir / "arrow.json", {
        "providers": [{
            "type": "bitmap",
            "file": "hahgps:font/arrow.png",
            "ascent": 8,
            "height": 8,
            "chars": [chars],
        }],
    })

    # --- текстуры предмета и блока ----------------------------------------
    arrow_art.sign_back().save(tex_item / "arrow_sign_back.png")
    arrow_art.steel("edge").save(tex_item / "arrow_sign_edge.png")
    arrow_art.steel("post").save(tex_item / "arrow_sign_post.png")
    arrow_art.sign_back().save(tex_block / "arrow_block_back.png")
    arrow_art.steel("edge").save(tex_block / "arrow_block_edge.png")
    arrow_art.steel("post").save(tex_block / "arrow_block_post.png")
    arrow_art.block_side().save(tex_block / "arrow_block_side.png")
    arrow_art.block_bottom().save(tex_block / "arrow_block_bottom.png")
    for direction in arrow_art.DIRECTIONS:
        arrow_art.sign_face(direction).save(tex_item / f"arrow_sign_{direction}.png")
        arrow_art.sign_face(direction).save(tex_block / f"arrow_block_{direction}.png")

    # --- модели -----------------------------------------------------------
    entries: list[tuple[int, str]] = []
    for i, direction in enumerate(arrow_art.DIRECTIONS):
        sign_name = f"arrow_sign_{direction}"
        block_name = f"arrow_block_{direction}"
        write_json(models_item / f"{sign_name}.json", model_sign(direction))
        write_json(models_block / f"{block_name}.json", model_block(direction))
        write_json(models_item / f"{block_name}.json", {
            "parent": f"hahgps:block/{block_name}",
            "display": DISPLAY_BLOCK_ITEM,
        })
        entries.append((ROUTE_SIGN_BASE + i, f"hahgps:item/{sign_name}"))
        entries.append((ROUTE_BLOCK_BASE + i, f"hahgps:item/{block_name}"))

    entries.sort(key=lambda item: item[0])
    write_json(items_dir / "oak_sign.json", {
        "model": {
            "type": "minecraft:range_dispatch",
            "property": "minecraft:custom_model_data",
            "entries": [{"threshold": float(cmd),
                         "model": {"type": "minecraft:model", "model": model}}
                        for cmd, model in entries],
            "fallback": OAK_SIGN_FALLBACK,
        },
    })

    write_json(PACK / "pack.mcmeta", {
        "pack": {
            "description": "HahGPS: стрелки маршрута, знак-указатель",
            "min_format": PACK_FORMAT,
            "max_format": MAX_FORMAT,
        },
    })
    arrow_art.pack_icon().save(PACK / "pack.png")


def validate() -> list[str]:
    problems: list[str] = []
    known = {p.relative_to(PACK).as_posix() for p in PACK.rglob("*") if p.is_file()}

    def exists(kind: str, ref: str) -> bool:
        namespace, _, path = ref.partition(":")
        if not path:
            namespace, path = "minecraft", ref
        if namespace == "minecraft":
            return True
        return (f"assets/{namespace}/{kind}/{path}.png" in known
                or f"assets/{namespace}/{kind}/{path}.json" in known)

    for path in PACK.rglob("*.json"):
        data = json.loads(path.read_text(encoding="utf-8"))
        relative = path.relative_to(PACK).as_posix()

        def walk(node) -> None:
            if isinstance(node, dict):
                for key, value in node.items():
                    if key == "texture" and isinstance(value, str) \
                            and not value.startswith("#"):
                        if not exists("textures", value):
                            problems.append(f"{relative}: нет текстуры {value}")
                    elif key == "model" and isinstance(value, str):
                        if not exists("models", value):
                            problems.append(f"{relative}: нет модели {value}")
                    elif key == "parent" and isinstance(value, str):
                        if not exists("models", value):
                            problems.append(f"{relative}: нет parent {value}")
                    else:
                        walk(value)
            elif isinstance(node, list):
                for item in node:
                    walk(item)

        walk(data)

    atlas = Image.open(PACK / "assets/hahgps/textures/font/arrow.png")
    if atlas.size != (8 * len(arrow_art.DIRECTIONS), 8):
        problems.append(f"font/arrow.png: {atlas.size}, ожидалось 64×8")
    return problems


def zip_pack(name: str = "hahgps.zip") -> Path:
    DIST.mkdir(parents=True, exist_ok=True)
    out = DIST / name
    if out.exists():
        out.unlink()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(PACK.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(PACK).as_posix())
    return out


# ---------------------------------------------------------------------------
# превью
# ---------------------------------------------------------------------------

def label(draw, text, xy, size=18, fill=(214, 220, 232)) -> None:
    draw.text(xy, text, font=ImageFont.truetype(FONT, size), fill=fill)


def load_images() -> dict[str, Image.Image]:
    images = {}
    for folder in ("item", "block"):
        for path in (PACK / "assets/hahgps/textures" / folder).glob("*.png"):
            images[f"hahgps:{folder}/{path.stem}"] = Image.open(path).convert("RGBA")
    return images


def render_model(model: dict, images, px=220, yaw=-38.0, pitch=-24.0,
                 view=1.45) -> Image.Image:
    scene = mc_render.Scene(px, px, bg=(20, 23, 29, 255), bg2=(13, 15, 19, 255))
    matrix = (mc_render.translate((0, 0, 0))
              @ mc_render.scale_m((1 / 16, 1 / 16, 1 / 16))
              @ mc_render.translate((-8, -8, -8)))
    scene.add_model(model, matrix, images)
    return scene.raster(mc_render.Cam(yaw, pitch, px / view, px, px))


def build_previews() -> list[Path]:
    PREVIEW.mkdir(parents=True, exist_ok=True)
    images = load_images()
    out: list[Path] = []

    # 1. HUD-стрелки: 8×8 в масштабе и строка «как в боссбаре».
    tile, pad = 112, 10
    cols = 4
    width = cols * (tile + pad) + pad
    height = 2 * (tile + 36) + pad + 96
    sheet = Image.new("RGB", (width, height), (17, 19, 24))
    draw = ImageDraw.Draw(sheet)
    label(draw, "HUD-стрелки 8×8: 8 направлений", (14, 10), 20)
    for i, direction in enumerate(arrow_art.DIRECTIONS):
        glyph = arrow_art.glyph(direction).resize((tile, tile), Image.NEAREST)
        x = pad + (i % cols) * (tile + pad)
        y = 40 + (i // cols) * (tile + 36)
        draw.rounded_rectangle([x, y, x + tile, y + tile], radius=6,
                               fill=(31, 34, 41), outline=(48, 53, 63))
        sheet.paste(glyph, (x, y), glyph.split()[3])
        label(draw, direction, (x, y + tile + 2), 14, (198, 204, 216))

    # Мок строки боссбара: глиф в нативном 8 px рядом с подписью.
    y = 40 + 2 * (tile + 36) + 8
    draw.rounded_rectangle([pad, y, width - pad, y + 46], radius=8,
                           fill=(38, 11, 11))
    mock = Image.new("RGB", (width - 2 * pad, 46), (38, 11, 11))
    md = ImageDraw.Draw(mock)
    arrow = arrow_art.glyph("slight-left").resize((16, 16), Image.NEAREST)
    mock.paste(arrow, (18, 15), arrow.split()[3])
    md.text((38, 12), "Памятник  120 м", font=ImageFont.truetype(FONT, 16),
            fill=(255, 255, 255))
    sheet.paste(mock, (pad, y))
    label(draw, "Строка BossBar: стрелка + название + расстояние", (pad + 4, y + 52), 14,
          (188, 194, 206))
    path = PREVIEW / "01-hud.png"
    sheet.save(path)
    out.append(path)

    # 2. 3D-предмет, 8 направлений.
    tile = 210
    cols = 4
    width = cols * (tile + 10) + 10
    height = 2 * (tile + 44) + 52
    sheet = Image.new("RGB", (width, height), (17, 19, 24))
    draw = ImageDraw.Draw(sheet)
    label(draw, "Предмет «знак-указатель» (3D, CMD 1810–1817)", (14, 10), 20)
    for i, direction in enumerate(arrow_art.DIRECTIONS):
        model = json.loads((PACK / "assets/hahgps/models/item"
                            / f"arrow_sign_{direction}.json").read_text(encoding="utf-8"))
        image = render_model(model, images, tile)
        x = 10 + (i % cols) * (tile + 10)
        y = 44 + (i // cols) * (tile + 44)
        sheet.paste(image, (x, y))
        label(draw, f"{direction} · {ROUTE_SIGN_BASE + i}", (x + 4, y + tile + 2), 14,
              (198, 204, 216))
    path = PREVIEW / "02-item.png"
    sheet.save(path)
    out.append(path)

    # 3. Блок-знак: спереди, сбоку, сверху.
    tile = 260
    sheet = Image.new("RGB", (3 * (tile + 12) + 12, tile + 52), (17, 19, 24))
    draw = ImageDraw.Draw(sheet)
    label(draw, "Блок-знак (3D, CMD 1820–1827; свои текстуры, ванильные не тронуты)",
          (14, 10), 20)
    model = json.loads((PACK / "assets/hahgps/models/block"
                        / "arrow_block_left.json").read_text(encoding="utf-8"))
    for i, (yaw, pitch, caption) in enumerate((
            (0, -10, "спереди"), (-42, -24, "три четверти"), (-90, -50, "сверху"))):
        image = render_model(model, images, tile, yaw=yaw, pitch=pitch, view=1.35)
        x = 12 + i * (tile + 12)
        sheet.paste(image, (x, 44))
        label(draw, caption, (x + 4, tile + 46), 14, (198, 204, 216))
    path = PREVIEW / "03-block.png"
    sheet.save(path)
    out.append(path)

    # 4. Таблица CMD.
    height = 40 + len(arrow_art.DIRECTIONS) * 24 + 70
    sheet = Image.new("RGB", (760, height), (17, 19, 24))
    draw = ImageDraw.Draw(sheet)
    label(draw, "Номера CustomModelData", (14, 10), 20)
    label(draw, "направление", (16, 42), 15, (150, 158, 172))
    label(draw, "глиф HUD", (320, 42), 15, (150, 158, 172))
    label(draw, "предмет", (470, 42), 15, (150, 158, 172))
    label(draw, "блок", (620, 42), 15, (150, 158, 172))
    for i, direction in enumerate(arrow_art.DIRECTIONS):
        y = 66 + i * 24
        label(draw, direction, (16, y), 15)
        label(draw, f"\\u{GLYPH_FIRST + i:04X}", (320, y), 15, (222, 190, 96))
        label(draw, str(ROUTE_SIGN_BASE + i), (470, y), 15, (222, 190, 96))
        label(draw, str(ROUTE_BLOCK_BASE + i), (620, y), 15, (222, 190, 96))
    path = PREVIEW / "04-cmd.png"
    sheet.save(path)
    out.append(path)
    return out


def main() -> None:
    build_pack()
    problems = validate()
    previews = build_previews()
    archive = zip_pack()
    print(f"пак: {PACK}")
    print(f"zip: {archive} ({archive.stat().st_size / 1024:.1f} КБ)")
    print(f"item definitions: {len(list((PACK / 'assets/minecraft/items').glob('*.json')))}")
    for preview in previews:
        print("превью:", preview)
    if problems:
        print("ПРОБЛЕМЫ:")
        for problem in problems:
            print("  -", problem)
        raise SystemExit(1)
    print("валидация: ок")


if __name__ == "__main__":
    main()
