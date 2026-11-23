#!/usr/bin/env python3
"""3D-иконки главного меню HahGPS.

Соглашение: в слотах главного меню стоят не плоские картинки, а объёмные
предметы («вещи на столе»), как локаторы и трекеры. Поэтому у каждой иконки
есть геометрия и свои текстуры из `ui_art.py`:

* «Мои точки» — складная карта с компасом и пином;
* «Категории» — книга с закладками;
* «Каталог трекеров» — витрина с трекером под стеклом и бирюзовой лентой;
* «Поиск» — лупа с линзой и ручкой;
* «Настройки» — шестерёнка с втулкой;
* «Мои трекеры» — жетон трекера на стальном держателе.

Всё в namespace `hahgps`, ванильные предметы переопределяются только
через `custom_model_data` с откатом (см. `build.py`).
"""
from __future__ import annotations

# Ракурсы: gui-поворот подобран так, чтобы в слоте было видно объём, но
# силуэт оставался узнаваемым (как у ванильных блоков и наших трекеров).
DISPLAY_MAIN = {
    "gui": {"rotation": [28, 205, 0], "translation": [0, 1.2, 0],
            "scale": [0.62, 0.62, 0.62]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 1.2, 0],
               "scale": [0.34, 0.34, 0.34]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0],
              "scale": [0.5, 0.5, 0.5]},
    "thirdperson_righthand": {"rotation": [0, 45, 0],
                              "translation": [0, 2, 0],
                              "scale": [0.42, 0.42, 0.42]},
    "thirdperson_lefthand": {"rotation": [0, -45, 0],
                             "translation": [0, 2, 0],
                             "scale": [0.42, 0.42, 0.42]},
    "firstperson_righthand": {"rotation": [0, 45, 0],
                              "translation": [0, 2, 0],
                              "scale": [0.46, 0.46, 0.46]},
    "firstperson_lefthand": {"rotation": [0, -45, 0],
                             "translation": [0, 2, 0],
                             "scale": [0.46, 0.46, 0.46]},
}


def _display(gui_rotation: list[float], scale: float) -> dict:
    display = {key: dict(value) for key, value in DISPLAY_MAIN.items()}
    display["gui"] = {"rotation": gui_rotation, "translation": [0, 1.2, 0],
                      "scale": [scale, scale, scale]}
    return display


def _faces(texture: str, side: str | None = None,
           top: str | None = None, bottom: str | None = None) -> dict:
    side = side or texture
    top = top or texture
    bottom = bottom or texture
    return {
        "north": {"texture": side, "uv": [0, 0, 16, 16]},
        "south": {"texture": side, "uv": [0, 0, 16, 16]},
        "west": {"texture": side, "uv": [0, 0, 16, 16]},
        "east": {"texture": side, "uv": [0, 0, 16, 16]},
        "up": {"texture": top, "uv": [0, 0, 16, 16]},
        "down": {"texture": bottom, "uv": [0, 0, 16, 16]},
    }


# Иконки — низкие «вещи на столе»: их геометрия лежит у земли (y ≈ 0–8),
# а в слоте предмет должен стоять по центру. Поднимаем геометрию модели, а
# display.gui оставляем компактным — иначе в слоте иконка уезжает вниз.
LIFT = 4.5


def _box(box, texture="hahgps:item/gui_plain", side=None, top=None,
         bottom=None, rotation=None) -> dict:
    element = {
        "from": [round(value, 3) for value in box[:3]],
        "to": [round(value, 3) for value in box[3:]],
        "faces": _faces(texture, side, top, bottom),
    }
    element["from"][1] += LIFT
    element["to"][1] += LIFT
    if rotation:
        element["rotation"] = rotation
    return element


def model_main_points() -> dict:
    """Складная карта с компасом и пином — «Мои точки»."""
    return {
        "parent": "minecraft:block/block",
        "textures": {
            "particle": "hahgps:item/main_map_top",
            "map_top": "hahgps:item/main_map_top",
            "map_side": "hahgps:item/main_map_side",
            "map_back": "hahgps:item/main_map_back",
            "compass": "hahgps:item/main_compass_face",
            "compass_side": "hahgps:item/main_compass_side",
            "pin": "hahgps:item/main_pin",
            "pin_top": "hahgps:item/main_pin_top",
        },
        "elements": [
            _box((1.4, 0.2, 2.2, 14.6, 0.8, 12.8),
                 texture="#map_side", top="#map_top", bottom="#map_back"),
            # Компас лежит на карте.
            _box((5.2, 0.8, 3.6, 11.8, 1.7, 10.2),
                 texture="#compass_side", top="#compass", bottom="#compass_side"),
            # Пин: стойка и головка.
            _box((12.4, 0.8, 6.2, 13.4, 2.9, 7.2),
                 texture="#pin", top="#pin", bottom="#pin"),
            _box((11.9, 2.9, 5.7, 13.9, 4.5, 7.7),
                 texture="#pin_top", top="#pin_top", bottom="#pin"),
        ],
        "display": _display([30, 205, 0], 0.62),
    }


def model_main_book() -> dict:
    """Книга-каталог с закладками — «Категории»."""
    elements = [
        _box((1.8, 0.6, 1.8, 14.2, 1.2, 14.2),
             texture="#cover", top="#pages", bottom="#cover"),
        _box((2.4, 1.2, 2.4, 13.6, 3.4, 13.6),
             texture="#pages", top="#pages", bottom="#pages"),
        _box((1.8, 3.4, 1.8, 14.2, 4.2, 14.2),
             texture="#side", top="#cover", bottom="#pages"),
        _box((1.8, 1.8, 1.8, 2.6, 4.2, 14.2),
             texture="#spine", top="#cover", bottom="#cover"),
    ]
    for index, z0 in enumerate((3.8, 6.8, 9.8)):
        elements.append(_box((13.6, 3.3, z0, 15.0, 4.1, z0 + 2.0),
                             texture="#tab", top="#tab", bottom="#tab"))
    return {
        "parent": "minecraft:block/block",
        "textures": {
            "particle": "hahgps:item/main_book_cover",
            "cover": "hahgps:item/main_book_cover",
            "pages": "hahgps:item/main_book_pages",
            "tab": "hahgps:item/main_book_tabs",
            "side": "hahgps:item/main_book_spine",
            "spine": "hahgps:item/main_book_spine",
        },
        "elements": elements,
        "display": _display([30, 225, 0], 0.6),
    }


def model_main_catalog() -> dict:
    """Витрина с трекером под стеклом — «Каталог трекеров»."""
    return {
        "parent": "minecraft:block/block",
        "textures": {
            "particle": "hahgps:item/main_catalog_glass",
            "glass": "hahgps:item/main_catalog_glass",
            "base": "hahgps:item/main_catalog_base",
            "band": "hahgps:item/main_catalog_band",
            "plate": "hahgps:item/main_catalog_plate",
            "tracker_top": "hahgps:item/tracker_blue",
            "tracker_edge": "hahgps:item/tracker_blue_edge",
            "tracker_back": "hahgps:item/tracker_back",
        },
        "elements": [
            _box((1.4, 0.0, 1.4, 14.6, 0.8, 14.6), texture="#base",
                 top="#base", bottom="#base"),
            _box((2.4, 0.8, 2.4, 13.6, 1.3, 13.6), texture="#plate",
                 top="#plate", bottom="#glass"),
            _box((4.2, 1.3, 4.2, 11.8, 1.9, 11.8), texture="#tracker_edge",
                 top="#tracker_top", bottom="#tracker_back"),
            _box((2.0, 0.8, 2.0, 14.0, 7.0, 14.0), texture="#glass",
                 top="#glass", bottom="#glass"),
            # Лента крест-накрест поверх стекла.
            _box((2.0, 7.0, 6.8, 14.0, 7.12, 8.4), texture="#band",
                 top="#band", bottom="#band"),
            _box((6.8, 7.0, 2.0, 8.4, 7.12, 14.0), texture="#band",
                 top="#band", bottom="#band"),
            _box((2.0, 6.4, 13.5, 14.0, 7.2, 13.62), texture="#band",
                 top="#band", bottom="#band"),
        ],
        "display": _display([24, 210, 0], 0.56),
    }


def model_main_search() -> dict:
    """Лупа: обод из восьми сегментов, линза, ручка — «Поиск»."""
    center = (8.0, 7.7, 8.0)
    radius = 4.2
    segment_length = 3.6
    elements = []
    # Обод: восемь коротких сегментов по окружности.
    for index in range(8):
        elements.append(_box(
            (center[0] - segment_length / 2, 7.0, center[2] - radius - 0.7,
             center[0] + segment_length / 2, 8.4, center[2] - radius + 0.7),
            texture="#ring", top="#ring", bottom="#ring",
            rotation={"axis": "y", "angle": index * 45.0,
                      "origin": list(center)},
        ))
    # Линза внутри обода.
    elements.append(_box((5.4, 7.35, 5.4, 10.6, 7.85, 10.6),
                         texture="#lens", top="#lens", bottom="#lens"))
    # Ручка: удлинённый сегмент к северо-востоку — с ракурса GUI её видно.
    handle = _box((7.4, 7.0, center[2] - radius - 4.4,
                   8.6, 8.4, center[2] - radius + 0.4),
                  texture="#handle", top="#handle", bottom="#handle",
                  rotation={"axis": "y", "angle": -135.0, "origin": list(center)})
    elements.append(handle)
    return {
        "parent": "minecraft:block/block",
        "textures": {
            "particle": "hahgps:item/main_search_ring",
            "ring": "hahgps:item/main_search_ring",
            "handle": "hahgps:item/main_search_handle",
            "lens": "hahgps:item/main_search_lens",
        },
        "elements": elements,
        "display": _display([24, 215, 0], 0.66),
    }


def model_main_settings() -> dict:
    """Шестерёнка с восемью зубьями — «Настройки»."""
    elements = [
        # Корпус: два крест-накрест лежащих короба дают восьмиугольник.
        _box((3.2, 1.2, 4.6, 12.8, 3.6, 11.4), texture="#side",
             top="#face", bottom="#side"),
        _box((4.6, 1.2, 3.2, 11.4, 3.6, 12.8), texture="#side",
             top="#face", bottom="#side"),
    ]
    # Зубья: один базовый короб, повёрнутый вокруг центра на 45° шагом.
    for index in range(8):
        elements.append(_box(
            (7.2, 1.2, 0.8, 8.8, 3.6, 3.2),
            texture="#side", top="#face", bottom="#side",
            rotation={"axis": "y", "angle": index * 45.0,
                      "origin": [8, 8, 8]},
        ))
    # Втулка.
    elements.append(_box((6.6, 3.6, 6.6, 9.4, 4.6, 9.4),
                         texture="#side", top="#hub", bottom="#side"))
    return {
        "parent": "minecraft:block/block",
        "textures": {
            "particle": "hahgps:item/main_gear_face",
            "face": "hahgps:item/main_gear_face",
            "side": "hahgps:item/main_gear_side",
            "hub": "hahgps:item/main_gear_hub",
        },
        "elements": elements,
        "display": _display([26, 205, 0], 0.6),
    }


def model_main_trackers() -> dict:
    """Жетон трекера на стальном держателе — «Мои трекеры»."""
    return {
        "parent": "minecraft:block/block",
        "textures": {
            "particle": "hahgps:item/tracker_blue",
            "tracker_top": "hahgps:item/tracker_blue",
            "tracker_edge": "hahgps:item/tracker_blue_edge",
            "tracker_back": "hahgps:item/tracker_back",
            "ring": "hahgps:item/main_tag_ring",
        },
        "elements": [
            _box((2.0, 1.0, 2.0, 14.0, 1.9, 14.0), texture="#tracker_edge",
                 top="#tracker_top", bottom="#tracker_back"),
            _box((7.1, 1.9, 7.1, 8.9, 3.9, 8.9), texture="#ring",
                 top="#ring", bottom="#ring"),
            _box((6.0, 3.9, 7.2, 7.0, 6.4, 8.8), texture="#ring",
                 top="#ring", bottom="#ring"),
            _box((9.0, 3.9, 7.2, 10.0, 6.4, 8.8), texture="#ring",
                 top="#ring", bottom="#ring"),
            _box((6.0, 6.4, 7.2, 10.0, 7.1, 8.8), texture="#ring",
                 top="#ring", bottom="#ring"),
        ],
        "display": _display([24, 200, 0], 0.62),
    }


MODELS = {
    "main_points": model_main_points,
    "main_book": model_main_book,
    "main_catalog": model_main_catalog,
    "main_search": model_main_search,
    "main_settings": model_main_settings,
    "main_trackers": model_main_trackers,
}
