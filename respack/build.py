#!/usr/bin/env python3
"""Сборка ресурспака HahGPS: HUD-стрелки, иконки меню, 3D-трекеры и локаторы.

Что делает:
  * рисует 8 HUD-глифов 8×8 и шрифт `hahgps:arrow`;
  * рисует 32×32 иконки меню (категории, поиск, настройки, транспорт) и
    диспетчеры `custom_model_data` на их материалы-носители;
  * рисует 3D-трекеры iTrak (8 цветов × обычный/водонепроницаемый) и оба
    локатора — «AirTag» с головой крипера, без блика зачарования и с честной
    моделью на земле;
  * оставляет ванильные предметы работоспособными: у материалов добавлен
    только диспетчер CMD, откат — ванильная модель (`recovery_compass` —
    полная ванильная модель со стрелкой на точку смерти);
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
import gui_art
import mc_render
import ui_kit
import tracker_art

ROOT = Path(__file__).resolve().parent
PACK = ROOT / "hahgps-pack"
DIST = ROOT / "dist"
PREVIEW = ROOT / "preview"

PACK_FORMAT = 75
MAX_FORMAT = 200
GLYPH_FIRST = 0xE000

# CMD должны совпадать с ru.hahgps.item.ItemIds:
# TRACKER_BASE = 2001 (8 цветов × 2), ITRAK_LOCATOR = 2020,
# ITRAK_LOCATOR_LONG = 2021.
TRACKER_BASE = 2001
LOCATOR_CMD = 2020
LOCATOR_LONG_CMD = 2021

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

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(data, ensure_ascii=False, indent=1) + "\n"
    # Коды шрифта — …: в файле они должны быть экранированы, иначе
    # редакторы и диффы с приватными символами выглядят пустыми.
    for code in range(GLYPH_FIRST, GLYPH_FIRST + 8):
        text = text.replace(chr(code), f"\\u{code:04X}")
    path.write_text(text, encoding="utf-8")


def _element(box, textures: dict[str, str]) -> dict:
    element = {"from": [round(v, 3) for v in box[:3]],
               "to": [round(v, 3) for v in box[3:]],
               "faces": {}}
    for face, texture in textures.items():
        element["faces"][face] = {"texture": texture, "uv": [0, 0, 16, 16]}
    return element


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
            # Нижний диск — тонкий, чтобы силуэт читался круглым.
            _element((3.5, 0, 3.5, 12.5, 0.05, 12.5), {
                "north": "#edge", "south": "#edge", "west": "#edge",
                "east": "#edge", "up": "#back", "down": "#back",
            }),
            # Узкий корпус-«таблетка» под крышкой: он спрятан в тени диска.
            # Низ тоже задняя крышка: иначе сквозь прозрачные углы снизу
            # просвечивала цветная кромка прямоугольником.
            _element((3.2, 0.05, 3.2, 12.8, 1.95, 12.8), {
                "north": "#edge", "south": "#edge", "west": "#edge",
                "east": "#edge", "up": "#back", "down": "#back",
            }),
            # Крышка-диск шире корпуса: углы прозрачны, силуэт круглый,
            # боковая кромка всего 0.05 — квадратной «рамки» почти не видно.
            # Низ — круглая задняя крышка: с кромкой на нижней грани трекер
            # сзади выглядел прямоугольной пластиной, а не шайбой.
            _element((0.9, 1.95, 0.9, 15.1, 2.0, 15.1), {
                "north": "#edge", "south": "#edge", "west": "#edge",
                "east": "#edge", "up": "#top", "down": "#back",
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


def build_pack() -> None:
    if PACK.exists():
        shutil.rmtree(PACK)
    tex_item = PACK / "assets/hahgps/textures/item"
    tex_font = PACK / "assets/hahgps/textures/font"
    models_item = PACK / "assets/hahgps/models/item"
    font_dir = PACK / "assets/hahgps/font"
    items_dir = PACK / "assets/minecraft/items"
    for path in (tex_item, tex_font, models_item, font_dir, items_dir):
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

    # --- iTrak: трекеры 8 цветов × 2 версии ------------------------------
    tracker_art.tracker_back().save(tex_item / "tracker_back.png")
    for color_key in tracker_art.COLORS:
        tracker_art.tracker_top(color_key).save(tex_item / f"tracker_{color_key}.png")
        tracker_art.tracker_top(color_key, True).save(tex_item / f"tracker_{color_key}_wp.png")
        tracker_art.tracker_edge(color_key).save(tex_item / f"tracker_{color_key}_edge.png")
        tracker_art.tracker_edge(color_key, True).save(
            tex_item / f"tracker_{color_key}_edge_wp.png")

    # --- локаторы ----------------------------------------------------------
    tracker_art.locator_front().save(tex_item / "locator_front.png")
    tracker_art.locator_front(True).save(tex_item / "locator_front_long.png")
    tracker_art.locator_side().save(tex_item / "locator_side.png")
    tracker_art.locator_back().save(tex_item / "locator_back.png")
    tracker_art.locator_top().save(tex_item / "locator_top.png")
    tracker_art.locator_tip().save(tex_item / "locator_tip.png")
    tracker_art.locator_button().save(tex_item / "locator_button.png")
    tracker_art.locator_band().save(tex_item / "locator_band.png")

    # --- модели -----------------------------------------------------------
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

    # --- иконки меню ------------------------------------------------------
    # gui_art — плоские иконки навигации, категорий, поиска и транспорта;
    # ui_kit — карточки точки/трекера и 3D-иконки главного меню. Второй шаг
    # читает уже готовые диспетчеры и дополняет их, не затирая чужие пороги.
    gui_art.build(PACK)
    ui_kit.build(PACK)

    write_json(PACK / "pack.mcmeta", {
        "pack": {
            "description": "HahGPS: стрелки, иконки меню, 3D-трекеры и локаторы",
            "min_format": PACK_FORMAT,
            "max_format": MAX_FORMAT,
        },
    })
    tracker_art.pack_icon().save(PACK / "pack.png")


def _in_item_atlas(ref: str) -> bool:
    """Текстура попадёт в атлас предметов, только если лежит в textures/item
    или textures/block: атлас `minecraft:items` (атласы `blocks.json` и
    `items.json`) собирается источниками directory по этим каталогам, а
    остальные файлы в него не попадают."""
    _, _, path = ref.partition(":")
    return path.startswith(("item/", "block/"))


def _face_refs(node) -> list[str]:
    """Все ссылки граней на переменные текстуры («#map_side»)."""
    refs: list[str] = []
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "texture" and isinstance(value, str) \
                    and value.startswith("#"):
                refs.append(value)
            else:
                refs.extend(_face_refs(value))
    elif isinstance(node, list):
        for value in node:
            refs.extend(_face_refs(value))
    return refs


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

        # Карта textures модели: сюда смотрят грани через «#имя». Раньше этот
        # блок не проверялся вовсе, и модель с «#map_side» уезжала в пак без
        # файла текстуры — клиент рисовал фиолетово-чёрную клетку.
        declared: set[str] = set()
        texture_map = data.get("textures")
        if isinstance(texture_map, dict):
            for key, value in texture_map.items():
                declared.add(key)
                if isinstance(value, str) and not value.startswith("#"):
                    if not exists("textures", value):
                        problems.append(
                            f"{relative}: нет текстуры {value} (textures.{key})")
                    elif "/models/" in relative and not _in_item_atlas(value):
                        problems.append(
                            f"{relative}: текстура {value} вне атласа предметов — "
                            f"клиент соберёт модель без неё (фиолетово-чёрная клетка)")
        if "/models/" in relative:
            for ref in _face_refs(data):
                if ref[1:] not in declared:
                    problems.append(
                        f"{relative}: грань ссылается на {ref}, "
                        f"а такой переменной нет в textures")

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
                    elif key == "layer0" and isinstance(value, str):
                        if not exists("textures", value):
                            problems.append(f"{relative}: нет текстуры {value}")
                    elif key == "textures":
                        continue
                    else:
                        walk(value)
            elif isinstance(node, list):
                for item in node:
                    walk(item)

        walk(data)

    atlas = Image.open(PACK / "assets/hahgps/textures/font/arrow.png")
    if atlas.size != (8 * len(arrow_art.DIRECTIONS), 8):
        problems.append(f"font/arrow.png: {atlas.size}, ожидалось 64×8")

    # Иконки меню: все номера из ItemIds должны получить модель.
    declared = {cmd for cmds in gui_art.CARRIERS.values() for cmd in cmds}
    drawn = set(gui_art.ICONS)
    for cmd in sorted(declared - drawn):
        problems.append(f"gui: нет иконки для CMD {cmd}")
    for name in gui_art.ICONS.values():
        icon = Image.open(PACK / f"assets/hahgps/textures/item/gui_{name[0]}.png")
        if icon.size != (gui_art.SIZE, gui_art.SIZE):
            problems.append(f"gui/{name[0]}.png: {icon.size}, ожидалось 32×32")

    # Карточки и 3D-иконки меню: текстуры 32×32 и пороги CMD на месте.
    for name in sorted(set(ui_kit.CARD_ICONS.values())
                       | {variant[1] for variant in ui_kit.CARD_VARIANTS.values()}):
        icon = Image.open(PACK / f"assets/hahgps/textures/item/gui_ui_{name}.png")
        if icon.size != (32, 32):
            problems.append(f"gui/ui_{name}.png: {icon.size}, ожидалось 32×32")
    for material, cmds in ui_kit.CARD_CARRIERS.items():
        data = json.loads((PACK / f"assets/minecraft/items/{material}.json")
                          .read_text(encoding="utf-8"))
        thresholds = {int(round(entry["threshold"]))
                      for entry in _range_entries(data)}
        for cmd in cmds:
            if cmd not in thresholds:
                problems.append(f"items/{material}.json: нет порога {cmd}")
    for material, cmds in ui_kit.MAIN_CARRIERS.items():
        data = json.loads((PACK / f"assets/minecraft/items/{material}.json")
                          .read_text(encoding="utf-8"))
        thresholds = {int(round(entry["threshold"]))
                      for entry in _range_entries(data)}
        for cmd in cmds:
            if cmd not in thresholds:
                problems.append(f"items/{material}.json: нет порога {cmd}")
    for name in set(ui_kit.MAIN_ICONS.values()):
        if not (PACK / f"assets/hahgps/models/item/{name}.json").is_file():
            problems.append(f"нет 3D-модели {name}")
    return problems


def _range_entries(node):
    """Все пороги custom_model_data-диспетчера (для проверки CMD)."""
    found = []
    if isinstance(node, dict):
        if (node.get("type") == "minecraft:range_dispatch"
                and node.get("property") == "minecraft:custom_model_data"):
            for entry in node.get("entries", []):
                if isinstance(entry, dict) and "threshold" in entry:
                    found.append(entry)
        for value in node.values():
            found.extend(_range_entries(value))
    elif isinstance(node, list):
        for value in node:
            found.extend(_range_entries(value))
    return found


def zip_pack(name: str = "hahgps.zip") -> Path:
    """Собрать zip байт-в-байт воспроизводимо.

    SHA-1 этого файла уходит клиенту Minecraft как хэш пака, и клиент
    отказывается ставить пак, если скачанный файл не совпал: mtime файлов
    (разный на CI и локально) раньше давал другой sha1 при том же содержимом.
    Поэтому дату фиксируем, права — 0644.
    """
    DIST.mkdir(parents=True, exist_ok=True)
    out = DIST / name
    if out.exists():
        out.unlink()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(PACK.rglob("*")):
            if not path.is_file():
                continue
            # фиксированная дата (1980-01-01 — минимум формата zip)
            info = zipfile.ZipInfo(path.relative_to(PACK).as_posix(), (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, path.read_bytes())
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
    for path in (PACK / "assets/hahgps/textures/item").glob("*.png"):
        images[f"hahgps:item/{path.stem}"] = Image.open(path).convert("RGBA")
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
    label(draw, "Стрелки HUD — ванильные, перекрашены в жёлтый", (14, 10), 20)
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

    # 2. Иконки меню: по группам ItemIds, с номерами CMD.
    tile, pad, caption = 96, 10, 26
    cols = 7
    head, group_gap = 24, 8
    groups = gui_art.GROUPS
    rows_total = sum((len(cmds) + cols - 1) // cols for _, cmds in groups)
    width = cols * (tile + pad) + pad
    height = 44 + len(groups) * (head + group_gap) + rows_total * (tile + caption)
    sheet = Image.new("RGB", (width, height), (17, 19, 24))
    draw = ImageDraw.Draw(sheet)
    label(draw, "Иконки меню HahGPS (32×32, CMD 1001–1705)", (14, 12), 20)
    y = 44
    for title, cmds in groups:
        label(draw, title, (pad + 2, y), 16, (143, 198, 255))
        draw.line([pad + 2, y + 19, width - pad - 2, y + 19],
                  fill=(40, 46, 58), width=1)
        y += head
        for index, cmd in enumerate(cmds):
            name, _ = gui_art.ICONS[cmd]
            icon = Image.open(PACK / f"assets/hahgps/textures/item/gui_{name}.png").convert("RGBA")
            icon = icon.resize((tile, tile), Image.NEAREST)
            x = pad + (index % cols) * (tile + pad)
            ty = y + (index // cols) * (tile + caption)
            draw.rounded_rectangle([x, ty, x + tile, ty + tile], radius=4,
                                   fill=(40, 43, 51), outline=(58, 63, 72))
            sheet.paste(icon, (x, ty), icon)
            label(draw, f"{name}", (x + 3, ty + tile + 2), 12, (198, 204, 216))
            label(draw, str(cmd), (x + 3, ty + tile + 14), 11, (150, 158, 172))
        y += ((len(cmds) + cols - 1) // cols) * (tile + caption) + group_gap
    path = PREVIEW / "02-icons.png"
    sheet.save(path)
    out.append(path)

    # 3. Трекеры: 8 цветов × обычный/водонепроницаемый.
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
    path = PREVIEW / "03-trackers.png"
    sheet.save(path)
    out.append(path)

    # 4. Локаторы.
    tile = 250
    sheet = Image.new("RGB", (2 * (tile + 12) + 12, tile + 58), (17, 19, 24))
    draw = ImageDraw.Draw(sheet)
    label(draw, "Локаторы iTrak (CMD 2020/2021)", (14, 10), 20)
    models = (
        ("locator", "локатор · 20", LOCATOR_CMD),
        ("locator_long", "дальний · 30", LOCATOR_LONG_CMD),
    )
    for i, (name, caption, cmd) in enumerate(models):
        model = json.loads((PACK / "assets/hahgps/models/item" / f"{name}.json")
                           .read_text(encoding="utf-8"))
        image = render_model(model, images, tile, yaw=-28, pitch=-22, view=1.3)
        x = 12 + i * (tile + 12)
        sheet.paste(image, (x, 42))
        label(draw, f"{caption} · {cmd}", (x + 4, tile + 46), 14, (198, 204, 216))
    path = PREVIEW / "04-locators.png"
    sheet.save(path)
    out.append(path)

    # 5. Выброшенные предметы: 3D на земле, без блика зачарования.
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
    path = PREVIEW / "05-dropped.png"
    sheet.save(path)
    out.append(path)

    # 6. Карточки точки и трекера: плоские иконки действий.
    cards: list[tuple[int, str]] = [(cmd, name) for cmd, name in
                                    sorted(ui_kit.CARD_ICONS.items())]
    for material in sorted(ui_kit.CARD_VARIANTS):
        cmd, name = ui_kit.CARD_VARIANTS[material]
        cards.append((cmd, name))
    tile, pad = 96, 10
    cols = 8
    rows = (len(cards) + cols - 1) // cols
    width = cols * (tile + pad) + pad
    height = 60 + rows * (tile + 30)
    sheet = Image.new("RGB", (width, height), (17, 19, 24))
    draw = ImageDraw.Draw(sheet)
    label(draw, "Карточки точки и iTrak (2D, CMD 1301–2044)", (14, 12), 20)
    for index, (cmd, name) in enumerate(cards):
        icon = Image.open(PACK / f"assets/hahgps/textures/item/gui_ui_{name}.png")
        icon = icon.convert("RGBA").resize((tile, tile), Image.NEAREST)
        x = pad + (index % cols) * (tile + pad)
        y = 46 + (index // cols) * (tile + 30)
        draw.rounded_rectangle([x, y, x + tile, y + tile], radius=4,
                               fill=(198, 198, 198), outline=(120, 122, 126))
        sheet.paste(icon, (x, y), icon)
        label(draw, name, (x + 3, y + tile + 2), 12, (198, 204, 216))
        label(draw, str(cmd), (x + 3, y + tile + 14), 11, (150, 158, 172))
    path = PREVIEW / "08-cards.png"
    sheet.save(path)
    out.append(path)

    # 7. Главное меню: объёмные иконки на сером слоте.
    tiles = [
        ("main_points", "Мои точки · 1101"),
        ("main_catalog", "Каталог трекеров · 1107"),
        ("main_trackers", "Мои трекеры · 1106"),
    ]
    tile = 220
    width = len(tiles) * (tile + 12) + 12
    height = tile + 66
    sheet = Image.new("RGB", (width, height), (198, 198, 198))
    draw = ImageDraw.Draw(sheet)
    label(draw, "Главное меню: 3D-иконки", (14, 10), 20, (40, 40, 40))
    for index, (name, caption) in enumerate(tiles):
        model = json.loads((PACK / "assets/hahgps/models/item"
                            / f"{name}.json").read_text(encoding="utf-8"))
        image = render_model(model, images, tile, yaw=-32, pitch=-28,
                             view=1.16, transparent=True)
        x = 12 + index * (tile + 12)
        draw.rounded_rectangle([x, 40, x + tile, 40 + tile], radius=6,
                               fill=(178, 178, 178), outline=(128, 128, 128))
        sheet.paste(image, (x, 40), image)
        label(draw, caption, (x + 6, tile + 44), 14, (52, 52, 52))
    path = PREVIEW / "09-main-3d.png"
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
