#!/usr/bin/env python3
"""Карточки и 3D-иконки главного меню HahGPS.

Модуль дополняет `gui_art.py` (плоские иконки навигации, категорий, поиска,
настроек и транспорта): здесь то, чего там нет, —

* **карточка точки** (CMD 1301–1304) и **карточка трекера / каталог**
  (CMD 2030, 2032–2039, 2044) — плоские 2D-иконки действий 32×32;
* **главное меню** (CMD 1101, 1106, 1107) — объёмные 3D-иконки: карта с
  компасом, витрина с трекером и жетон на держателе.

Важно: диспетчеры `custom_model_data` не пересоздаются, а дополняются.
`gui_art.build()` уже положил в `items/<материал>.json` свои пороги (и
«чужие» записи, ведущие на ваниль); мы читаем этот файл, убираем пороги,
которые теперь наши, вставляем свои модели и оставляем fallback как был.
Поэтому ванильный предмет без CMD и чужие кнопки на том же материале
работают как раньше.
"""
from __future__ import annotations

import json
from pathlib import Path

from PIL import Image

import ui_art
import ui_models

# Порог «выше этого — не наше»: то же соглашение, что в gui_art.py.
TOP_THRESHOLD = 1_000_000

# 2D-иконки карточек: CMD -> имя иконки в ui_art.ICONS.
CARD_ICONS: dict[int, str] = {
    1301: "poi_info",
    1302: "poi_goto",
    1303: "poi_rate",
    1304: "poi_delete",
    2030: "itrak_catalog",
    2032: "itrak_route",
    1107: "itrak_give",
    2033: "itrak_watch",
    2035: "itrak_remove",
    2036: "itrak_scan",
    2038: "itrak_refresh",
    2039: "itrak_empty",
    2044: "itrak_rename",
}

# Варианты для тех же CMD на втором материале (состояние кнопки).
CARD_VARIANTS: dict[str, tuple[int, str]] = {
    "glass": (2033, "itrak_watch_off"),
    "barrier": (2034, "itrak_locked"),
    "chest": (2034, "itrak_take"),
}

# 3D-иконки главного меню: CMD -> модель из ui_models.MODELS.
MAIN_ICONS: dict[int, str] = {
    1101: "main_points",     # «Мои точки»
    1106: "main_trackers",   # «Мои трекеры»
    1107: "main_catalog",    # «Каталог трекеров»
}

# Материалы-носители карточек: CMD на материал.
CARD_CARRIERS: dict[str, list[int]] = {
    "compass": [1302, 2032, 2036],
    "paper": [1301],
    "nether_star": [1303],
    "lava_bucket": [1304, 2035],
    "book": [2030],
    "spyglass": [2033, 2038],
    "glass": [2033],
    "barrier": [2034, 2039],
    "chest": [2034],
    "name_tag": [2044],
    # В «Моих трекерах» кнопка «выдать» — лазурит-блок с CMD 1107 (а не 2037).
    "lapis_block": [1107],
}

MAIN_CARRIERS: dict[str, list[int]] = {
    "compass": [1101],
    "recovery_compass": [1106, 1107],
}

# Ванильные откаты (модели, у которых нельзя подставить просто item/<id>).
SPYGLASS_FALLBACK = {
    "type": "minecraft:select",
    "property": "minecraft:display_context",
    "cases": [{
        "when": ["gui", "ground", "fixed", "on_shelf"],
        "model": {"type": "minecraft:model", "model": "minecraft:item/spyglass"},
    }],
    "fallback": {"type": "minecraft:model",
                 "model": "minecraft:item/spyglass_in_hand"},
}

CHEST_FALLBACK = {
    "type": "minecraft:select",
    "property": "minecraft:local_time",
    "pattern": "MM-dd",
    "cases": [{
        "when": ["12-24", "12-25", "12-26"],
        "model": {"type": "minecraft:special", "base": "minecraft:item/chest",
                  "model": {"type": "minecraft:chest",
                            "texture": "minecraft:christmas"}},
    }],
    "fallback": {"type": "minecraft:special", "base": "minecraft:item/chest",
                 "model": {"type": "minecraft:chest",
                           "texture": "minecraft:normal"}},
}

VANILLA_RECOVERY_COMPASS = None  # строит build.py; при отсутствии — оставляем как есть


def _fallback(material: str) -> dict:
    if material == "spyglass":
        return SPYGLASS_FALLBACK
    if material == "chest":
        return CHEST_FALLBACK
    if material in ("lapis_block", "beacon", "glass"):
        return {"type": "minecraft:model", "model": f"minecraft:block/{material}"}
    return {"type": "minecraft:model", "model": f"minecraft:item/{material}"}


def _write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n",
                    encoding="utf-8")


def _existing_entries(path: Path) -> tuple[dict | None, list[dict]]:
    """Верхний custom_model_data-диспетчер файла и его записи (если есть)."""
    if not path.is_file():
        return None, []
    data = json.loads(path.read_text(encoding="utf-8"))
    node = data.get("model")
    if not isinstance(node, dict):
        return None, []
    if (node.get("type") == "minecraft:range_dispatch"
            and node.get("property") == "minecraft:custom_model_data"):
        entries = [entry for entry in node.get("entries", [])
                   if isinstance(entry, dict)]
        return node, entries
    return None, []


def _merge_dispatch(path: Path, entries: list[tuple[int, str]],
                    fallback: dict) -> None:
    """Дополнить существующий диспетчер нашими порогами (без дублей)."""
    mine = {cmd for cmd, _ in entries}
    node, old = _existing_entries(path)
    kept: list[dict] = []
    for entry in old:
        threshold = entry.get("threshold")
        if isinstance(threshold, (int, float)) \
                and int(round(threshold)) in mine:
            continue
        kept.append(entry)
    for cmd, model in entries:
        kept.append({"threshold": float(cmd),
                     "model": {"type": "minecraft:model", "model": model}})
    kept.sort(key=lambda entry: float(entry.get("threshold", 0.0)))
    if node is not None:
        node["entries"] = kept
        payload = {"model": node}
    else:
        payload = {"model": {
            "type": "minecraft:range_dispatch",
            "property": "minecraft:custom_model_data",
            "entries": kept,
            "fallback": fallback,
        }}
    _write_json(path, payload)


def build(pack: Path) -> list[str]:
    """Нарисовать карточки и 3D-иконки меню, дополнить диспетчеры CMD."""
    tex_item = pack / "assets/hahgps/textures/item"
    models = pack / "assets/hahgps/models/item"
    items = pack / "assets/minecraft/items"
    for path in (tex_item, models, items):
        path.mkdir(parents=True, exist_ok=True)

    # --- 2D-иконки карточек -------------------------------------------------
    for cmd, name in CARD_ICONS.items():
        ui_art.ICONS[name]().save(tex_item / f"gui_ui_{name}.png")
        _write_json(models / f"gui_ui_{name}.json", {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"hahgps:item/gui_ui_{name}"},
        })
    for material, (cmd, name) in CARD_VARIANTS.items():
        ui_art.ICONS[name]().save(tex_item / f"gui_ui_{name}.png")
        _write_json(models / f"gui_ui_{name}.json", {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"hahgps:item/gui_ui_{name}"},
        })

    # --- 3D-иконки главного меню -------------------------------------------
    # Текстуры берём из карты самой модели, а не из граней: наши элементы
    # ссылаются на переменные («#map_side»), поэтому нужны цели, а не имена.
    # Раньше в пак попадали только текстуры, прописанные полным именем в
    # `particle`, а остальные клиент рисовал фиолетово-чёрными.
    used_textures: set[str] = set()
    for name in set(MAIN_ICONS.values()):
        model = ui_models.MODELS[name]()
        _write_json(models / f"{name}.json", model)
        used_textures.update(
            ref for ref in (model.get("textures") or {}).values()
            if isinstance(ref, str) and ref.startswith("hahgps:item/"))
    for ref in sorted(used_textures):
        short = ref.split("/", 1)[1]
        if short.startswith("tracker") or short in ("locator", "locator_long"):
            continue  # уже нарисованы build.py
        painter = ui_art.MAIN_TEXTURES.get(short)
        if painter is None:
            raise SystemExit(f"ui_kit: нет художника для текстуры {ref}")
        painter().save(tex_item / f"{short}.png")

    # --- диспетчеры CMD -----------------------------------------------------
    for material, cmds in CARD_CARRIERS.items():
        entries = []
        for cmd in cmds:
            variant = CARD_VARIANTS.get(material)
            if variant is not None and variant[0] == cmd:
                # Второй материал того же CMD: «включено/выключено», «есть/нет».
                name = variant[1]
            else:
                name = CARD_ICONS[cmd]
            entries.append((cmd, f"hahgps:item/gui_ui_{name}"))
        _merge_dispatch(items / f"{material}.json", entries,
                        _fallback(material))

    for material, cmds in MAIN_CARRIERS.items():
        entries = [(cmd, f"hahgps:item/{MAIN_ICONS[cmd]}") for cmd in cmds]
        _merge_dispatch(items / f"{material}.json", entries,
                        _fallback(material))

    return sorted(name for name in CARD_ICONS.values()) + \
        sorted(set(MAIN_ICONS.values()))
