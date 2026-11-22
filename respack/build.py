#!/usr/bin/env python3
"""Сборка ресурспака HahGPS: стрелки, 3D-трекеры и локаторы (Minecraft 1.21.11+).

Что делает:
  * рисует 8 HUD-глифов 8×8 и шрифт `hahgps:arrow`;
  * собирает 3D-модели предмета-указателя и блока-знака (свои текстуры
    в namespace `hahgps`, ванильные блоки и их текстуры не переопределяются);
  * рисует 3D-трекеры iTrak (8 цветов × обычный/водонепроницаемый),
    оба локатора и терминал — «AirTag» с головой крипера, без блика
    зачарования и с честной моделью на земле;
  * добавляет item definitions с `custom_model_data` и откатом на ваниль:
    `oak_sign` (стрелки), 8 материалов трекеров, `recovery_compass`
    (локаторы, ванильный компас сохранён в fallback), `lodestone` (терминал);
  * рендерит превью и пакует `dist/hahgps.zip`.

Запуск: python3 respack/build.py   (нужны pillow и numpy)
"""
from __future__ import annotations

import json
import shutil
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

import arrow_art
import mc_render
import tracker_art

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

# CMD должны совпадать с ru.hahgps.item.ItemIds:
# TRACKER_BASE = 2001 (8 цветов × 2), ITRAK_LOCATOR = 2020,
# ITRAK_LOCATOR_LONG = 2021, ITRAK_TERMINAL = 2042.
TRACKER_BASE = 2001
LOCATOR_CMD = 2020
LOCATOR_LONG_CMD = 2021
TERMINAL_CMD = 2042

TRACKER_MATERIALS = {
    "blue": "lapis_lazuli",
    "cyan": "prismarine_crystals",
    "green": "emerald",
    "yellow": "gold_nugget",
    "orange": "magma_cream",
    "red": "redstone",
    "purple": "amethyst_shard",
    "white": "quartz",
}

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


DISPLAY_TRACKER = {
    "gui": {"rotation": [28, 225, 0], "translation": [0, 1, 0],
            "scale": [0.72, 0.72, 0.72]},
    # На земле трекер лежит плашмя и остаётся объёмным: это главное требование
    # к выброшенному предмету — никакой плоской иконки и блика зачарования.
    "ground": {"rotation": [0, 0, 0], "translation": [0, 0.5, 0],
               "scale": [0.4, 0.4, 0.4]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0],
              "scale": [0.5, 0.5, 0.5]},
    "thirdperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 2, 0],
                              "scale": [0.5, 0.5, 0.5]},
    "thirdperson_lefthand": {"rotation": [0, -45, 0], "translation": [0, 2, 0],
                             "scale": [0.5, 0.5, 0.5]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 2, 0],
                              "scale": [0.55, 0.55, 0.55]},
    "firstperson_lefthand": {"rotation": [0, -45, 0], "translation": [0, 2, 0],
                             "scale": [0.55, 0.55, 0.55]},
}

DISPLAY_LOCATOR = {
    "gui": {"rotation": [22, 205, 0], "translation": [0, 1, 0],
            "scale": [0.55, 0.55, 0.55]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0],
               "scale": [0.4, 0.4, 0.4]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0],
              "scale": [0.5, 0.5, 0.5]},
    "thirdperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 2, 0],
                              "scale": [0.42, 0.42, 0.42]},
    "thirdperson_lefthand": {"rotation": [0, -45, 0], "translation": [0, 2, 0],
                             "scale": [0.42, 0.42, 0.42]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 2, 0],
                              "scale": [0.46, 0.46, 0.46]},
    "firstperson_lefthand": {"rotation": [0, -45, 0], "translation": [0, 2, 0],
                             "scale": [0.46, 0.46, 0.46]},
}

DISPLAY_TERMINAL = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0],
            "scale": [0.65, 0.65, 0.65]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 4, 0],
               "scale": [0.3, 0.3, 0.3]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0],
              "scale": [0.5, 0.5, 0.5]},
    "thirdperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 3, 0],
                              "scale": [0.35, 0.35, 0.35]},
    "thirdperson_lefthand": {"rotation": [0, -45, 0], "translation": [0, 3, 0],
                             "scale": [0.35, 0.35, 0.35]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 2, 0],
                              "scale": [0.4, 0.4, 0.4]},
    "firstperson_lefthand": {"rotation": [0, -45, 0], "translation": [0, 2, 0],
                             "scale": [0.4, 0.4, 0.4]},
}


def model_tracker(color_key: str, waterproof: bool = False) -> dict:
    """3D-«AirTag»: один тонкий диск с прозрачными углами.

    Слои-«пирамидка» из нескольких коробок читались как стопка плит. Один
    короб с круглой текстурой лица и скруглёнными боковыми кромками даёт
    честный силуэт жетона, а голова крипера остаётся объёмной за счёт толщины.
    """
    suffix = "_wp" if waterproof else ""
    top = f"hahgps:item/tracker_{color_key}{suffix}"
    edge = f"hahgps:item/tracker_{color_key}_edge{suffix}"
    back = "hahgps:item/tracker_back"
    return {
        "parent": "minecraft:block/block",
        "textures": {
            "particle": top,
            "top": top,
            "edge": edge,
            "back": back,
        },
        "elements": [
            # Узкий корпус-«таблетка» под крышкой: он спрятан в тени диска.
            _element((3.2, 0, 3.2, 12.8, 1.8, 12.8), {
                "north": "#edge", "south": "#edge", "west": "#edge",
                "east": "#edge", "up": "#edge", "down": "#back",
            }),
            # Тонкая крышка-диск шире корпуса: углы прозрачны, силуэт круглый.
            _element((1.6, 1.8, 1.6, 14.4, 2.0, 14.4), {
                "north": "#edge", "south": "#edge", "west": "#edge",
                "east": "#edge", "up": "#top", "down": "#edge",
            }),
        ],
        "display": DISPLAY_TRACKER,
    }


def model_locator(long: bool = False) -> dict:
    """Ручной сканер: корпус, экран с радаром, кнопки и антенна."""
    suffix = "_long" if long else ""
    front = f"hahgps:item/locator_front{suffix}"
    antenna_top = 16.4 if long else 15.6
    elements = [
        _element((4.5, 2, 7, 11.5, 13, 9), {
            "north": "#front", "south": "#back", "west": "#side",
            "east": "#side", "up": "#top", "down": "#side",
        }),
        # Антенна.
        _element((7.4, 13, 7.4, 8.6, antenna_top, 8.6), {
            "north": "#side", "south": "#side", "west": "#side",
            "east": "#side", "up": "#top", "down": "#side",
        }),
        _element((7.1, antenna_top, 7.1, 8.9, antenna_top + 0.5, 8.9), {
            "north": "#tip", "south": "#tip", "west": "#tip",
            "east": "#tip", "up": "#tip", "down": "#tip",
        }),
        # Боковая кнопка питания.
        _element((11.4, 5, 7.4, 11.9, 8, 8.6), {
            "north": "#button", "south": "#button", "west": "#button",
            "east": "#button", "up": "#button", "down": "#button",
        }),
    ]
    textures = {
        "particle": front,
        "front": front,
        "back": "hahgps:item/locator_back",
        "side": "hahgps:item/locator_side",
        "top": "hahgps:item/locator_top",
        "tip": "hahgps:item/locator_tip",
        "button": "hahgps:item/locator_button",
    }
    if long:
        # Жёлтый кант дальней версии.
        elements.append(_element((4.7, 3, 6.9, 11.3, 4.2, 7.1), {
            "north": "#band", "south": "#band", "west": "#band",
            "east": "#band", "up": "#band", "down": "#band",
        }))
        textures["band"] = "hahgps:item/locator_band"
    return {
        "parent": "minecraft:block/block",
        "textures": textures,
        "elements": elements,
        "display": DISPLAY_LOCATOR,
    }


def model_terminal() -> dict:
    """Терминал: плита с экраном и антенной, как маленькая база iTrak."""
    return {
        "parent": "minecraft:block/block",
        "textures": {
            "particle": "hahgps:item/terminal_front",
            "front": "hahgps:item/terminal_front",
            "side": "hahgps:item/terminal_side",
            "top": "hahgps:item/terminal_top",
            "back": "hahgps:item/terminal_back",
        },
        "elements": [
            _element((1, 0, 1, 15, 1, 15), {
                "north": "#side", "south": "#side", "west": "#side",
                "east": "#side", "up": "#side", "down": "#side",
            }),
            _element((2, 1, 2, 14, 5, 14), {
                "north": "#front", "south": "#back", "west": "#side",
                "east": "#side", "up": "#top", "down": "#side",
            }),
            _element((7.4, 5, 7.4, 8.6, 9, 8.6), {
                "north": "#side", "south": "#side", "west": "#side",
                "east": "#side", "up": "#top", "down": "#side",
            }),
            _element((7.1, 9, 7.1, 8.9, 9.5, 8.9), {
                "north": "#top", "south": "#top", "west": "#top",
                "east": "#top", "up": "#top", "down": "#top",
            }),
        ],
        "display": DISPLAY_TERMINAL,
    }


def vanilla_recovery_compass_model() -> dict:
    """Ванильная модель recovery_compass — fallback для CMD 0..2019.

    Её нельзя терять: обычный компас восстановления должен по-прежнему
    показывать направление на точку смерти, а не превращаться в картинку.
    """
    entries = []
    names = list(range(16, 32)) + list(range(0, 17))
    for index, number in enumerate(names):
        threshold = 0.0 if index == 0 else index - 0.5
        entries.append({
            "threshold": threshold,
            "model": {"type": "minecraft:model",
                      "model": f"minecraft:item/recovery_compass_{number:02d}"},
        })
    return {
        "type": "minecraft:range_dispatch",
        "property": "minecraft:compass",
        "scale": 32.0,
        "target": "recovery",
        "entries": entries,
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
    blockstates_dir = PACK / "assets/minecraft/blockstates"
    for path in (tex_item, tex_block, tex_font, models_item, models_block,
                 font_dir, items_dir, blockstates_dir):
        path.mkdir(parents=True, exist_ok=True)

    # --- HUD-глифы ---------------------------------------------------------
    arrow_art.font_atlas().save(tex_font / "arrow.png")
    chars = "".join(chr(GLYPH_FIRST + i) for i in range(len(arrow_art.DIRECTIONS)))
    write_json(font_dir / "arrow.json", {
        "providers": [{
            "type": "bitmap",
            "file": "hahgps:font/arrow.png",
            "ascent": 7,
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

    # --- iTrak: трекеры 8 цветов × 2 версии ------------------------------
    tracker_art.tracker_back().save(tex_item / "tracker_back.png")
    for color_key in tracker_art.COLORS:
        tracker_art.tracker_top(color_key).save(tex_item / f"tracker_{color_key}.png")
        tracker_art.tracker_top(color_key, True).save(tex_item / f"tracker_{color_key}_wp.png")
        tracker_art.tracker_side(color_key).save(tex_item / f"tracker_{color_key}_side.png")
        tracker_art.tracker_side(color_key, True).save(
            tex_item / f"tracker_{color_key}_side_wp.png")
        tracker_art.tracker_edge(color_key).save(tex_item / f"tracker_{color_key}_edge.png")
        tracker_art.tracker_edge(color_key, True).save(
            tex_item / f"tracker_{color_key}_edge_wp.png")
        tracker_art.tracker_rim(color_key).save(tex_item / f"tracker_{color_key}_rim.png")
        tracker_art.tracker_rim(color_key, True).save(
            tex_item / f"tracker_{color_key}_rim_wp.png")

    # --- локаторы и терминал ----------------------------------------------
    tracker_art.locator_front().save(tex_item / "locator_front.png")
    tracker_art.locator_front(True).save(tex_item / "locator_front_long.png")
    tracker_art.locator_side().save(tex_item / "locator_side.png")
    tracker_art.locator_back().save(tex_item / "locator_back.png")
    tracker_art.locator_top().save(tex_item / "locator_top.png")
    tracker_art.locator_tip().save(tex_item / "locator_tip.png")
    tracker_art.locator_button().save(tex_item / "locator_button.png")
    tracker_art.locator_band().save(tex_item / "locator_band.png")
    tracker_art.terminal_front().save(tex_item / "terminal_front.png")
    tracker_art.terminal_side().save(tex_item / "terminal_side.png")
    tracker_art.terminal_top().save(tex_item / "terminal_top.png")
    tracker_art.terminal_back().save(tex_item / "terminal_back.png")

    # --- модели -----------------------------------------------------------
    sign_entries: list[tuple[int, str]] = []
    for i, direction in enumerate(arrow_art.DIRECTIONS):
        sign_name = f"arrow_sign_{direction}"
        block_name = f"arrow_block_{direction}"
        write_json(models_item / f"{sign_name}.json", model_sign(direction))
        write_json(models_block / f"{block_name}.json", model_block(direction))
        write_json(models_item / f"{block_name}.json", {
            "parent": f"hahgps:block/{block_name}",
            "display": DISPLAY_BLOCK_ITEM,
        })
        sign_entries.append((ROUTE_SIGN_BASE + i, f"hahgps:item/{sign_name}"))
        sign_entries.append((ROUTE_BLOCK_BASE + i, f"hahgps:item/{block_name}"))

    sign_entries.sort(key=lambda item: item[0])
    write_json(items_dir / "oak_sign.json", {
        "model": {
            "type": "minecraft:range_dispatch",
            "property": "minecraft:custom_model_data",
            "entries": [{"threshold": float(cmd),
                         "model": {"type": "minecraft:model", "model": model}}
                        for cmd, model in sign_entries],
            "fallback": OAK_SIGN_FALLBACK,
        },
    })

    # --- диспетчеры CMD для трекеров, локаторов и терминала ---------------
    for index, color_key in enumerate(tracker_art.COLORS):
        regular_cmd = TRACKER_BASE + index * 2
        waterproof_cmd = regular_cmd + 1
        for suffix, cmd in (("", regular_cmd), ("_wp", waterproof_cmd)):
            model_name = f"tracker_{color_key}{suffix}"
            write_json(models_item / f"{model_name}.json",
                       model_tracker(color_key, suffix == "_wp"))
        material = TRACKER_MATERIALS[color_key]
        write_json(items_dir / f"{material}.json", {
            "model": {
                "type": "minecraft:range_dispatch",
                "property": "minecraft:custom_model_data",
                "entries": [
                    {"threshold": float(regular_cmd),
                     "model": {"type": "minecraft:model",
                               "model": f"hahgps:item/tracker_{color_key}"}},
                    {"threshold": float(waterproof_cmd),
                     "model": {"type": "minecraft:model",
                               "model": f"hahgps:item/tracker_{color_key}_wp"}},
                ],
                "fallback": {"type": "minecraft:model",
                             "model": f"minecraft:item/{material}"},
            },
        })

    write_json(models_item / "locator.json", model_locator(False))
    write_json(models_item / "locator_long.json", model_locator(True))
    write_json(models_item / "terminal.json", model_terminal())
    # Терминал — это блок в мире. Без blockstate на лодестоуне стоял бы
    # огромный ванильный куб; теперь и предмет, и поставленный блок выглядят
    # одинаково — маленькая база с экраном и антенной.
    terminal_block = model_terminal()
    terminal_block.pop("display", None)
    write_json(models_block / "terminal.json", terminal_block)
    write_json(blockstates_dir / "lodestone.json", {
        "variants": {"": {"model": "hahgps:block/terminal"}},
    })
    write_json(items_dir / "recovery_compass.json", {
        "model": {
            "type": "minecraft:range_dispatch",
            "property": "minecraft:custom_model_data",
            "entries": [
                {"threshold": float(LOCATOR_CMD),
                 "model": {"type": "minecraft:model",
                           "model": "hahgps:item/locator"}},
                {"threshold": float(LOCATOR_LONG_CMD),
                 "model": {"type": "minecraft:model",
                           "model": "hahgps:item/locator_long"}},
            ],
            # Обычный компас восстановления остаётся ванильным: направление на
            # точку смерти, а не наша картинка.
            "fallback": vanilla_recovery_compass_model(),
        },
    })
    write_json(items_dir / "lodestone.json", {
        "model": {
            "type": "minecraft:range_dispatch",
            "property": "minecraft:custom_model_data",
            "entries": [
                {"threshold": float(TERMINAL_CMD),
                 "model": {"type": "minecraft:model",
                           "model": "hahgps:item/terminal"}},
            ],
            "fallback": {"type": "minecraft:model",
                         "model": "minecraft:item/lodestone"},
        },
    })

    write_json(PACK / "pack.mcmeta", {
        "pack": {
            "description": "HahGPS: стрелки, 3D-трекеры и локаторы",
            "min_format": PACK_FORMAT,
            "max_format": MAX_FORMAT,
        },
    })
    tracker_art.pack_icon().save(PACK / "pack.png")


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

def _font(size: int):
    """DejaVu есть не на каждом билд-хосте — превью не должны ронять сборку."""
    try:
        return ImageFont.truetype(FONT, size)
    except OSError:
        return ImageFont.load_default()


def label(draw, text, xy, size=18, fill=(214, 220, 232)) -> None:
    draw.text(xy, text, font=_font(size), fill=fill)


def load_images() -> dict[str, Image.Image]:
    images = {}
    for folder in ("item", "block"):
        for path in (PACK / "assets/hahgps/textures" / folder).glob("*.png"):
            images[f"hahgps:{folder}/{path.stem}"] = Image.open(path).convert("RGBA")
    return images


def render_model(model: dict, images, px=220, yaw=-38.0, pitch=-24.0,
                 view=1.45, transparent=False) -> Image.Image:
    scene = mc_render.Scene(px, px, bg=(20, 23, 29, 255), bg2=(13, 15, 19, 255))
    matrix = (mc_render.translate((0, 0, 0))
              @ mc_render.scale_m((1 / 16, 1 / 16, 1 / 16))
              @ mc_render.translate((-8, -8, -8)))
    scene.add_model(model, matrix, images)
    camera = mc_render.Cam(yaw, pitch, px / view, px, px)
    return scene.raster_rgba(camera) if transparent else scene.raster(camera)


def build_previews() -> list[Path]:
    PREVIEW.mkdir(parents=True, exist_ok=True)
    images = load_images()
    out: list[Path] = []

    # 1. Стрелки: ванильные формы, перекрашенные в жёлтый + карта направлений.
    unique = [("straight", "прямо"), ("left", "влево"), ("right", "вправо"),
              ("back", "разворот")]
    tile, pad = 128, 12
    cols = 4
    width = cols * (tile + pad) + pad
    height = tile + 96
    sheet = Image.new("RGB", (width, height), (17, 19, 24))
    draw = ImageDraw.Draw(sheet)
    label(draw, "Стрелки — ванильные, перекрашены в жёлтый", (14, 10), 20)
    for i, (direction, caption) in enumerate(unique):
        glyph = arrow_art.glyph(direction).resize((tile, tile), Image.NEAREST)
        x = pad + i * (tile + pad)
        y = 40
        draw.rounded_rectangle([x, y, x + tile, y + tile], radius=6,
                               fill=(31, 34, 41), outline=(48, 53, 63))
        sheet.paste(glyph, (x, y), glyph.split()[3])
        label(draw, caption, (x + 4, y + tile + 4), 16, (198, 204, 216))
    label(draw, "прямо ↑   чуть/влево/резко ←   чуть/вправо/резко →   разворот ↓",
          (14, tile + 70), 15, (150, 158, 172))
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

    # 5. Трекеры: 8 цветов × обычный/водонепроницаемый.
    tile = 188
    cols = 8
    width = cols * (tile + 8) + 8
    height = 2 * (tile + 42) + 58
    sheet = Image.new("RGB", (width, height), (17, 19, 24))
    draw = ImageDraw.Draw(sheet)
    label(draw, "iTrak: 8 цветов × обычный / водонепроницаемый (3D)", (14, 10), 20)
    for row, waterproof in enumerate((False, True)):
        for col, color_key in enumerate(tracker_art.COLORS):
            suffix = "_wp" if waterproof else ""
            model = json.loads((PACK / "assets/hahgps/models/item"
                                / f"tracker_{color_key}{suffix}.json").read_text(encoding="utf-8"))
            image = render_model(model, images, tile, yaw=-32, pitch=-32, view=1.35)
            x = 8 + col * (tile + 8)
            y = 42 + row * (tile + 42)
            sheet.paste(image, (x, y))
            label(draw, f"{color_key}{' · wp' if waterproof else ''} · "
                        f"{TRACKER_BASE + col * 2 + (1 if waterproof else 0)}",
                  (x + 4, y + tile + 2), 13, (198, 204, 216))
    path = PREVIEW / "05-trackers.png"
    sheet.save(path)
    out.append(path)

    # 6. Локаторы и терминал.
    tile = 250
    sheet = Image.new("RGB", (3 * (tile + 12) + 12, tile + 58), (17, 19, 24))
    draw = ImageDraw.Draw(sheet)
    label(draw, "Локаторы iTrak (CMD 2020/2021) и терминал (CMD 2042)", (14, 10), 20)
    models = (
        ("locator", "локатор · 20", LOCATOR_CMD),
        ("locator_long", "дальний · 30", LOCATOR_LONG_CMD),
        ("terminal", "терминал", TERMINAL_CMD),
    )
    for i, (name, caption, cmd) in enumerate(models):
        model = json.loads((PACK / "assets/hahgps/models/item" / f"{name}.json")
                           .read_text(encoding="utf-8"))
        image = render_model(model, images, tile, yaw=-28, pitch=-22, view=1.3)
        x = 12 + i * (tile + 12)
        sheet.paste(image, (x, 42))
        label(draw, f"{caption} · {cmd}", (x + 4, tile + 46), 14, (198, 204, 216))
    path = PREVIEW / "06-locators.png"
    sheet.save(path)
    out.append(path)

    # 7. Выброшенные предметы: 3D на земле, без блика зачарования.
    width, height = 1400, 430
    sheet = Image.new("RGB", (width, height), (18, 21, 27))
    draw = ImageDraw.Draw(sheet)
    # Небо и земля.
    for y in range(height):
        t = y / (height - 1)
        color = (int(18 + 22 * t), int(21 + 24 * t), int(27 + 22 * t))
        draw.line([0, y, width, y], fill=color)
    draw.rectangle([0, 300, width, height], fill=(48, 57, 42))
    draw.rectangle([0, 300, width, 312], fill=(72, 104, 56))
    for x in range(0, width, 16):
        draw.line([x, 301, x, 312], fill=(58, 86, 47))
    label(draw, "Брошенные трекеры и локатор: 3D-модель на земле, без зачарования",
          (20, 16), 20, (220, 226, 236))
    items = [
        ("tracker_blue", -30, -58, 0.52),
        ("tracker_green", -30, -58, 0.52),
        ("tracker_yellow", -30, -58, 0.52),
        ("tracker_red", -30, -58, 0.52),
        ("tracker_white_wp", -30, -58, 0.52),
        ("locator", -18, -18, 0.62),
        ("locator_long", -18, -18, 0.62),
    ]
    x = 34
    for index, (name, pitch, yaw, scale) in enumerate(items):
        model = json.loads((PACK / "assets/hahgps/models/item" / f"{name}.json")
                           .read_text(encoding="utf-8"))
        image = render_model(model, images, 220, yaw=yaw, pitch=pitch,
                             view=1.5, transparent=True)
        size = int((160 if name.startswith("locator") else 180) * scale / 0.52)
        image = image.resize((size, size), Image.LANCZOS)
        # Мягкая тень под предметом.
        shadow = Image.new("RGBA", (size, 34), (0, 0, 0, 0))
        sd = ImageDraw.Draw(shadow)
        sd.ellipse([6, 4, size - 6, 30], fill=(0, 0, 0, 105))
        shadow = shadow.filter(ImageFilter.GaussianBlur(5))
        sheet.paste(shadow, (x, 314), shadow)
        sheet.paste(image, (x, 318 - size // 2), image)
        x += size + 6
    path = PREVIEW / "07-dropped.png"
    sheet.save(path)
    out.append(path)

    # 8. Терминал в мире: blockstate лодестоуна ведёт на нашу модель.
    tile = 430
    sheet = Image.new("RGB", (tile + 28, tile + 58), (17, 19, 24))
    draw = ImageDraw.Draw(sheet)
    label(draw, "Терминал в мире: blockstate lodestone → hahgps:block/terminal",
          (14, 10), 18)
    model = json.loads((PACK / "assets/hahgps/models/block/terminal.json")
                       .read_text(encoding="utf-8"))
    image = render_model(model, images, tile, yaw=-32, pitch=-24, view=1.25)
    sheet.paste(image, (14, 40))
    path = PREVIEW / "10-terminal-block.png"
    sheet.save(path)
    out.append(path)
    return out


def main() -> None:
    build_pack()
    problems = validate()
    previews = build_previews()
    archive = zip_pack()
    # Копия для jar: /gps pack раздаёт именно этот файл, поэтому он должен
    # попадать в сборку вместе с плагином.
    jar_pack = ROOT.parent / "src/main/resources/hahgps.zip"
    if jar_pack.parent.is_dir():
        shutil.copyfile(archive, jar_pack)
        print(f"в jar: {jar_pack}")
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
