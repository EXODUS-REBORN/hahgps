#!/usr/bin/env python3
"""Иконки меню HahGPS: 32×32, нарисованы «как настоящие вещи».

Пак не переопределяет ванильные предметы целиком: на материал добавляется
только `custom_model_data`-диспетчер, а все чужие номера и значения выше
наших уходят в ванильную модель (`fallback`). Поэтому кнопки меню получают
свои картинки, а обычные предметы в инвентаре выглядят как раньше.

Номера — из `ru.hahgps.item.ItemIds`; материал-носитель берётся из GUI-кода.
"""
from __future__ import annotations

import json
import math
from pathlib import Path

from PIL import Image, ImageDraw

# Рисуем в 4× и уменьшаем: на 32 пикселях сглаженные края читаются лучше
# пиксельной сетки, а в игре иконка занимает ровно слот.
SS = 4
SIZE = 32

INK = (21, 24, 31, 255)
WHITE = (246, 249, 253, 255)

BLUE = (63, 134, 255, 255)
BLUE_DARK = (32, 78, 166, 255)
BLUE_LIGHT = (143, 198, 255, 255)
CYAN = (79, 209, 224, 255)
STEEL = (150, 158, 172, 255)
STEEL_DARK = (92, 100, 114, 255)
STEEL_LIGHT = (204, 212, 224, 255)
PAPER = (234, 238, 245, 255)
PAPER_DARK = (188, 196, 210, 255)
PARCHMENT = (233, 214, 168, 255)
PARCHMENT_DARK = (196, 172, 118, 255)
MANILA = (226, 174, 84, 255)
MANILA_DARK = (178, 128, 44, 255)
YELLOW = (245, 199, 66, 255)
GREEN = (85, 195, 107, 255)
GREEN_DARK = (44, 132, 66, 255)
RED = (235, 87, 87, 255)
RED_DARK = (150, 40, 40, 255)
LEATHER = (158, 108, 62, 255)
LEATHER_DARK = (104, 68, 36, 255)
OCEAN = (44, 104, 214, 255)
BRASS = (208, 166, 96, 255)
BRASS_DARK = (146, 106, 46, 255)
TEAL = (54, 178, 184, 255)
TEAL_DARK = (26, 104, 112, 255)
STONE = (54, 60, 74, 255)

# «Всё, что выше наших номеров, — не наше»: так чужие кнопки на том же
# материале (например iTrak на книге) не превращаются в нашу иконку.
TOP_THRESHOLD = 1_000_000


# ---------------------------------------------------------------------------
# примитивы
# ---------------------------------------------------------------------------

def _px(value: float) -> int:
    return int(round(value * SS))


def _new() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = Image.new("RGBA", (SIZE * SS, SIZE * SS), (0, 0, 0, 0))
    return image, ImageDraw.Draw(image)


def _finish(image: Image.Image) -> Image.Image:
    return image.resize((SIZE, SIZE), Image.LANCZOS)


def _rr(d, x0, y0, x1, y1, r, fill, outline=INK, width=1.1):
    d.rounded_rectangle([_px(x0), _px(y0), _px(x1), _px(y1)], radius=_px(r),
                        fill=fill, outline=outline, width=max(1, _px(width)))


def _circ(d, cx, cy, r, fill, outline=INK, width=1.1):
    d.ellipse([_px(cx - r), _px(cy - r), _px(cx + r), _px(cy + r)],
              fill=fill, outline=outline, width=max(1, _px(width)))


def _poly(d, points, fill, outline=INK, width=1.1):
    d.polygon([(_px(x), _px(y)) for x, y in points], fill=fill,
              outline=outline, width=max(1, _px(width)))


def _line(d, points, fill, width=1.2):
    d.line([(_px(x), _px(y)) for x, y in points], fill=fill,
           width=max(1, _px(width)), joint="curve")


def _glint(d, x, y, r):
    """Блик на стекле: короткая светлая дуга в левом верхнем углу."""
    d.arc([_px(x - r), _px(y - r), _px(x + r), _px(y + r)], 190, 250,
          fill=(255, 255, 255, 190), width=_px(0.8))


# ---------------------------------------------------------------------------
# Категории
# ---------------------------------------------------------------------------

def _folder(d, x0=4, y0=8, x1=28, y1=27, fill=MANILA, dark=MANILA_DARK,
            label=True):
    _poly(d, [(x0 + 1, y0 + 3), (x0 + 9, y0 + 3), (x0 + 11, y0 - 1),
              (x0 + 17, y0 - 1), (x0 + 17, y0 + 3), (x1, y0 + 3)],
          fill=dark)
    _rr(d, x0, y0, x1, y1, 2.6, fill)
    _line(d, [(x0 + 2, y1 - 1.2), (x1 - 2, y1 - 1.2)], dark, 1.4)
    if label:
        _rr(d, x0 + 2.6, y0 + 3.2, x1 - 8.5, y0 + 6.6, 1.2, WHITE,
            outline=(120, 84, 24, 255), width=0.7)


def icon_category() -> Image.Image:
    image, d = _new()
    _folder(d)
    _line(d, [(6, 11.4), (14, 11.4)], (255, 255, 255, 90), 0.6)
    return _finish(image)


def icon_categories() -> Image.Image:
    """Стопка папок: три листа со сдвигом, верхняя — с синей наклейкой."""
    image, _ = _new()
    for i, (box, fill, dark, angle) in enumerate((
            ((9.0, 2.6, 28.0, 14.4), (196, 150, 66, 255), (140, 98, 30, 255), -9),
            ((6.4, 6.0, 28.8, 18.6), MANILA, MANILA_DARK, -4),
    )):
        layer = Image.new("RGBA", (SIZE * SS, SIZE * SS), (0, 0, 0, 0))
        ld = ImageDraw.Draw(layer)
        _folder(ld, *box, fill=fill, dark=dark, label=False)
        layer = layer.rotate(angle, resample=Image.BICUBIC,
                             center=(_px(18), _px(18)))
        image.alpha_composite(layer)
    d = ImageDraw.Draw(image)
    _folder(d, 3.0, 11.0, 28.0, 28.2)
    _rr(d, 5.4, 14.2, 14.6, 17.4, 1.2, BLUE, outline=(26, 62, 132, 255), width=0.7)
    return _finish(image)


def icon_category_add() -> Image.Image:
    image, d = _new()
    _folder(d, 3, 8, 24.5, 25)
    _circ(d, 23.5, 23.5, 7, GREEN, outline=GREEN_DARK)
    _line(d, [(19.8, 23.5), (27.2, 23.5)], WHITE, 1.7)
    _line(d, [(23.5, 19.8), (23.5, 27.2)], WHITE, 1.7)
    return _finish(image)


def icon_category_remove() -> Image.Image:
    image, d = _new()
    _folder(d, 3, 8, 24.5, 25)
    _circ(d, 23.5, 23.5, 7, RED, outline=RED_DARK)
    _line(d, [(20.4, 20.4), (26.6, 26.6)], WHITE, 1.7)
    _line(d, [(26.6, 20.4), (20.4, 26.6)], WHITE, 1.7)
    return _finish(image)


# ---------------------------------------------------------------------------
# Поиск
# ---------------------------------------------------------------------------

def _lens(d, cx, cy, r, r_inner=None, glass=(178, 214, 250, 240)):
    r_inner = r_inner if r_inner is not None else r - 1.1
    _circ(d, cx, cy, r, STEEL, outline=INK)
    _circ(d, cx, cy, r_inner, glass, outline=STEEL_LIGHT, width=0.6)
    _glint(d, cx, cy, r_inner)


def _handle(d, x, y, length=8.5, width=3.4):
    dx, dy = length * 0.72, length * 0.72
    _line(d, [(x, y), (x + dx, y + dy)], INK, width + 1.3)
    _line(d, [(x, y), (x + dx, y + dy)], STEEL, width)
    _line(d, [(x + 0.3, y + 0.3), (x + dx * 0.55, y + dy * 0.55)], STEEL_LIGHT, 0.9)


def icon_search() -> Image.Image:
    image, d = _new()
    _lens(d, 12.5, 12.5, 9.2)
    _handle(d, 19, 19, 8.2, 3.6)
    return _finish(image)


def icon_search_input() -> Image.Image:
    image, d = _new()
    _lens(d, 12, 12, 9)
    # Внутри лупы — строки текста: «искать по названию».
    _line(d, [(8, 10.2), (16, 10.2)], (36, 58, 104, 255), 1.4)
    _line(d, [(8, 13.4), (14.2, 13.4)], (36, 58, 104, 255), 1.4)
    _line(d, [(13.6, 13.4), (13.6, 17)], BLUE, 1.3)
    _handle(d, 18.5, 18.5, 8, 3.4)
    return _finish(image)


def icon_search_quick() -> Image.Image:
    image, d = _new()
    _lens(d, 12, 12, 9)
    _poly(d, [(13.4, 5.4), (8.2, 13.4), (11.6, 13.4), (9.6, 18.8),
              (16.4, 10.4), (12.8, 10.4)], YELLOW, outline=(150, 106, 16, 255))
    _handle(d, 18.5, 18.5, 8, 3.4)
    return _finish(image)


def icon_search_result() -> Image.Image:
    image, d = _new()
    # Сложенная карта, повёрнутая на 8 градусов.
    sheet = Image.new("RGBA", (SIZE * SS, SIZE * SS), (0, 0, 0, 0))
    sd = ImageDraw.Draw(sheet)
    _rr(sd, 3.5, 9, 22, 28, 2, PARCHMENT, outline=PARCHMENT_DARK)
    _line(sd, [(11.4, 9.6), (11.4, 27.4)], PARCHMENT_DARK, 0.9)
    _line(sd, [(5.4, 13), (9.6, 13)], (176, 152, 100, 255), 0.8)
    _line(sd, [(13.4, 16), (20, 16)], (176, 152, 100, 255), 0.8)
    _line(sd, [(13.4, 20), (18, 20)], (176, 152, 100, 255), 0.8)
    sheet = sheet.rotate(-8, resample=Image.BICUBIC, center=(_px(16), _px(16)))
    image.alpha_composite(sheet)
    d = ImageDraw.Draw(image)
    # Булавка: результат поиска — точка на карте.
    _circ(d, 21.4, 12.4, 6, BLUE, outline=BLUE_DARK, width=1.2)
    _poly(d, [(21.4, 22.4), (18.2, 14.6), (24.6, 14.6)], BLUE,
          outline=BLUE_DARK, width=1)
    _circ(d, 21.4, 12.4, 2.2, WHITE, outline=None)
    return _finish(image)


def icon_search_empty() -> Image.Image:
    image, d = _new()
    _lens(d, 12.5, 12.5, 9.2, glass=(214, 220, 230, 150))
    _handle(d, 19, 19, 8.2, 3.6)
    _line(d, [(6.4, 6.4), (18.6, 18.6)], RED, 2.2)
    _line(d, [(6.4, 6.4), (18.6, 18.6)], (255, 214, 214, 255), 0.8)
    return _finish(image)


def icon_search_limit() -> Image.Image:
    image, d = _new()
    _rr(d, 3, 6.5, 21.5, 26, 2.2, PAPER, outline=PAPER_DARK)
    for y in (11.5, 15.6, 19.7):
        _line(d, [(6, y), (18.5, y)], (150, 160, 178, 255), 1.3)
    _line(d, [(6, 11.5), (13, 11.5)], BLUE, 1.3)
    # Ползунок справа: сколько мест показывать.
    _line(d, [(26, 8), (26, 25)], (120, 130, 146, 255), 1.6)
    _circ(d, 26, 17.5, 3.2, BLUE, outline=BLUE_DARK)
    _circ(d, 26, 8, 1.6, STEEL, outline=STEEL_DARK, width=0.6)
    _circ(d, 26, 25, 1.6, STEEL, outline=STEEL_DARK, width=0.6)
    return _finish(image)


# ---------------------------------------------------------------------------
# Настройки
# ---------------------------------------------------------------------------

def icon_settings() -> Image.Image:
    """Шестерня: восемь зубьев, металл, отверстие с синей подсветкой."""
    import math

    image, d = _new()
    cx = cy = 15.6
    for i in range(8):
        a = math.radians(i * 45 + 22.5)
        inner, outer, half = 7.6, 13.4, 0.30
        points = []
        for da in (-half, half):
            points.append((cx + math.cos(a + da) * inner,
                           cy + math.sin(a + da) * inner))
        for da in (half * 0.62, -half * 0.62):
            points.append((cx + math.cos(a + da) * outer,
                           cy + math.sin(a + da) * outer))
        _poly(d, points, STEEL, outline=STEEL_DARK, width=0.9)
    _circ(d, cx, cy, 10.2, STEEL, outline=STEEL_DARK, width=1.2)
    _circ(d, cx, cy, 8.6, (176, 184, 198, 255), outline=STEEL_LIGHT, width=0.7)
    _circ(d, cx, cy, 4.2, (58, 66, 80, 255), outline=BLUE, width=0.9)
    _circ(d, cx, cy, 2, BLUE_LIGHT, outline=(70, 110, 180, 255), width=0.5)
    d.arc([_px(cx - 9.4), _px(cy - 9.4), _px(cx + 9.4), _px(cy + 9.4)], 196, 262,
          fill=(255, 255, 255, 180), width=_px(1))
    return _finish(image)


def icon_language() -> Image.Image:
    image, d = _new()
    _circ(d, 15.5, 15.5, 11.4, OCEAN, outline=(20, 48, 108, 255), width=1.3)
    green = (74, 174, 96, 255)
    _poly(d, [(6.5, 10), (11, 8.4), (13.4, 11.6), (10.2, 15), (6.2, 13.6)], green,
          outline=(34, 110, 56, 255), width=0.8)
    _poly(d, [(17.6, 12.4), (23, 11), (25.4, 15), (21, 18.4), (18, 16)], green,
          outline=(34, 110, 56, 255), width=0.8)
    _poly(d, [(10.4, 19.4), (15.6, 18.8), (17.4, 22.6), (12.6, 24.2)], green,
          outline=(34, 110, 56, 255), width=0.8)
    _circ(d, 15.5, 15.5, 11.4, None, outline=(20, 48, 108, 255), width=1.3)
    d.ellipse([_px(10.4), _px(4.2), _px(20.6), _px(26.8)],
              outline=(226, 240, 255, 150), width=_px(0.8))
    _line(d, [(4.4, 15.5), (26.6, 15.5)], (226, 240, 255, 150), 0.8)
    d.arc([_px(15.5 - 11.4), _px(15.5 - 11.4), _px(15.5 + 11.4), _px(15.5 + 11.4)],
          200, 260, fill=(255, 255, 255, 190), width=_px(1))
    return _finish(image)


def icon_logging() -> Image.Image:
    image, d = _new()
    # Планшет-зажим с листом: журнал действий.
    _rr(d, 5, 5, 21.5, 28, 2.4, STEEL, outline=STEEL_DARK, width=1.2)
    _rr(d, 7, 8, 19.5, 26, 1.6, WHITE, outline=PAPER_DARK, width=0.7)
    for y in (11.6, 14.8, 18, 21.2):
        _line(d, [(9, y), (17.6, y)], (150, 160, 178, 255), 1.1)
    _line(d, [(9, 11.6), (14, 11.6)], BLUE, 1.1)
    _rr(d, 9.6, 2.6, 16.9, 6.4, 1.2, STEEL_LIGHT, outline=STEEL_DARK, width=0.9)
    # Карандаш поверх листа.
    _poly(d, [(20.4, 27.6), (27.6, 20.4), (24.4, 17.2), (17.2, 24.4)], YELLOW,
          outline=(148, 104, 16, 255), width=1)
    _poly(d, [(17.2, 24.4), (16.4, 28.4), (20.4, 27.6)], (238, 240, 246, 255),
          outline=(120, 128, 142, 255), width=0.9)
    _line(d, [(25.6, 21.6), (24.4, 20.4)], (255, 240, 190, 255), 0.9)
    return _finish(image)


# ---------------------------------------------------------------------------
# Транспорт
# ---------------------------------------------------------------------------

def icon_transport_foot() -> Image.Image:
    image, d = _new()
    # Голенище.
    _poly(d, [(8.6, 27.6), (8.6, 11.4), (10.2, 9.4), (15.4, 9.4), (16.8, 12.4),
              (16.8, 19.8), (23.0, 20.8), (25.8, 22.8), (26.6, 26.2),
              (26.6, 27.6)], LEATHER, outline=LEATHER_DARK, width=1.2)
    # Кант голенища и светлый носок.
    _line(d, [(10.4, 10.6), (15.2, 10.6)], (206, 154, 100, 255), 1.1)
    _poly(d, [(22.4, 21.2), (25.6, 23.0), (26.2, 26.0), (22.6, 26.0)],
          (186, 134, 84, 255), outline=LEATHER_DARK, width=0.7)
    # Подошва с каблуком.
    _poly(d, [(7.6, 27.6), (27.0, 27.6), (26.4, 30.2), (8.2, 30.2)],
          (52, 46, 42, 255), outline=(30, 26, 24, 255), width=0.9)
    _line(d, [(8.2, 28.6), (26.6, 28.6)], (86, 78, 70, 255), 0.8)
    # Шнуровка.
    for y in (13.2, 15.2, 17.2):
        _line(d, [(10.8, y), (16.2, y + 1.4)], YELLOW, 0.9)
    _line(d, [(9.4, 20.2), (16.2, 20.2)], (196, 146, 96, 255), 0.8)
    return _finish(image)


def icon_transport_car() -> Image.Image:
    image, d = _new()
    _poly(d, [(3.4, 21.4), (3.4, 18.4), (6.4, 17.4), (9.4, 12.2), (20.6, 12.2),
              (24.4, 17.6), (28.6, 18.6), (28.6, 22.4), (3.4, 22.4)], BLUE,
          outline=BLUE_DARK, width=1.2)
    # Окна.
    _poly(d, [(10.6, 13.6), (19.4, 13.6), (22, 17.2), (8.4, 17.2)],
          (206, 232, 255, 255), outline=(120, 160, 210, 255), width=0.8)
    _line(d, [(15.8, 13.6), (15.8, 17.2)], (120, 160, 210, 255), 0.8)
    _line(d, [(4.4, 19.4), (27.4, 19.4)], (196, 226, 255, 120), 0.8)
    for cx in (10.2, 22.2):
        _circ(d, cx, 22.4, 3.4, (46, 50, 58, 255), outline=(24, 26, 32, 255))
        _circ(d, cx, 22.4, 1.5, STEEL, outline=STEEL_DARK, width=0.6)
    _rr(d, 25.6, 18.8, 28.6, 20.4, 0.7, (255, 244, 180, 255),
        outline=(170, 150, 70, 255), width=0.6)
    return _finish(image)


def icon_transport_transit() -> Image.Image:
    image, d = _new()
    _rr(d, 7, 4.4, 25, 29, 5, BLUE, outline=BLUE_DARK, width=1.3)
    _rr(d, 9, 7.4, 23, 16.4, 2.6, (206, 232, 255, 255),
        outline=(120, 160, 210, 255), width=0.9)
    _line(d, [(16, 7.6), (16, 16.2)], (120, 160, 210, 255), 0.8)
    _rr(d, 9, 19, 23, 20.6, 0.7, (196, 226, 255, 150),
        outline=(120, 160, 210, 255), width=0.5)
    for cx in (11.4, 20.6):
        _circ(d, cx, 24.6, 2.2, (255, 244, 190, 255), outline=(168, 146, 60, 255),
              width=0.7)
    _line(d, [(9, 28), (23, 28)], BLUE_DARK, 1.4)
    return _finish(image)


def icon_transport_air() -> Image.Image:
    image, d = _new()
    _poly(d, [(15.6, 2.6), (18.4, 9.2), (18.4, 15.4), (28.6, 20.4), (28.6, 23.2),
              (18.4, 20.6), (18.4, 25.4), (21.6, 28), (21.6, 29.6),
              (16, 28.4), (10.4, 29.6), (10.4, 28), (13.6, 25.4), (13.6, 20.6),
              (3.4, 23.2), (3.4, 20.4), (13.6, 15.4), (13.6, 9.2)], WHITE,
          outline=(128, 138, 154, 255), width=1.2)
    _poly(d, [(15.6, 2.6), (18.4, 9.2), (16, 10.4), (13.6, 9.2)], BLUE,
          outline=BLUE_DARK, width=0.7)
    _line(d, [(13.6, 15.6), (18.4, 15.6)], (196, 210, 230, 255), 0.8)
    _circ(d, 16, 13.6, 1.2, (206, 232, 255, 255), outline=(128, 160, 200, 255),
          width=0.5)
    return _finish(image)


def icon_transport_water() -> Image.Image:
    image, d = _new()
    # Корпус катера.
    _poly(d, [(2.6, 18.6), (29.4, 18.6), (25.4, 26.4), (7.4, 26.4)],
          (238, 242, 248, 255), outline=(120, 130, 148, 255), width=1.2)
    _line(d, [(4.6, 22.2), (27.2, 22.2)], BLUE, 1.4)
    # Рубка.
    _rr(d, 10, 9.6, 21.4, 18.6, 1.8, (238, 242, 248, 255),
        outline=(120, 130, 148, 255), width=1.1)
    _rr(d, 11.4, 11.4, 20, 15.4, 1.2, (206, 232, 255, 255),
        outline=(120, 160, 210, 255), width=0.8)
    # Флаг на корме.
    _line(d, [(23.4, 18.4), (23.4, 6.4)], (108, 116, 132, 255), 0.9)
    _poly(d, [(23.6, 6.6), (28.6, 8.4), (23.6, 10.6)], YELLOW,
          outline=(150, 106, 16, 255), width=0.8)
    # Вода.
    _line(d, [(2.4, 28.4), (29.6, 28.4)], CYAN, 1.1)
    return _finish(image)


# ---------------------------------------------------------------------------
# Навигация меню: назад, вперёд, инфо, закрыть, страница, бренд, заполнитель
# ---------------------------------------------------------------------------

def _mirror_x(points):
    """Отразить точки относительно центра 32×32: «вперёд» — зеркало «назад»."""
    return [(32.0 - x, y) for x, y in points]


def _nav_arrow(forward: bool) -> Image.Image:
    image, d = _new()
    _rr(d, 3.2, 3.2, 28.8, 28.8, 7.2, BLUE_DARK, outline=INK, width=1.3)
    _rr(d, 4.8, 4.8, 27.2, 27.2, 5.8, BLUE, outline=BLUE_LIGHT, width=0.7)
    d.arc([_px(6.6), _px(6.6), _px(25.4), _px(25.4)], 197, 250,
          fill=(255, 255, 255, 115), width=_px(0.9))
    arrow = [(23.4, 12.4), (14.6, 12.4), (14.6, 7.4),
             (6.0, 16.0), (14.6, 24.6), (14.6, 19.6), (23.4, 19.6)]
    shine = [(16.0, 13.6), (22.2, 13.6)]
    if forward:
        arrow = _mirror_x(arrow)
        shine = _mirror_x(shine)
    _poly(d, arrow, WHITE, outline=INK, width=1.2)
    _line(d, shine, (205, 226, 252, 210), 0.7)
    return _finish(image)


def icon_nav_back() -> Image.Image:
    return _nav_arrow(False)


def icon_nav_forward() -> Image.Image:
    return _nav_arrow(True)


def icon_nav_info() -> Image.Image:
    image, d = _new()
    _circ(d, 16, 16, 12.4, BLUE_DARK, outline=INK, width=1.4)
    _circ(d, 16, 16, 10.9, BLUE, outline=BLUE_LIGHT, width=0.7)
    d.arc([_px(7.2), _px(7.2), _px(24.8), _px(24.8)], 198, 250,
          fill=(255, 255, 255, 125), width=_px(0.9))
    _circ(d, 16.0, 10.0, 1.9, WHITE, outline=None)
    _rr(d, 14.4, 13.0, 17.6, 23.2, 1.6, WHITE, outline=None)
    return _finish(image)


def icon_nav_close() -> Image.Image:
    image, d = _new()
    _circ(d, 16, 16, 12.4, RED_DARK, outline=INK, width=1.4)
    _circ(d, 16, 16, 10.8, RED, outline=(255, 178, 178, 255), width=0.8)
    # Крест: толстая тёмная подложка + белый штрих, концы скруглены.
    for x0, y0, x1, y1 in ((10.6, 10.6, 21.4, 21.4),
                           (21.4, 10.6, 10.6, 21.4)):
        _line(d, [(x0, y0), (x1, y1)], INK, 4.8)
        _circ(d, x0, y0, 2.4, INK, outline=None)
        _circ(d, x1, y1, 2.4, INK, outline=None)
        _line(d, [(x0, y0), (x1, y1)], WHITE, 3.0)
        _circ(d, x0, y0, 1.5, WHITE, outline=None)
        _circ(d, x1, y1, 1.5, WHITE, outline=None)
    d.arc([_px(7.8), _px(7.8), _px(24.2), _px(24.2)], 200, 246,
          fill=(255, 255, 255, 110), width=_px(0.8))
    return _finish(image)


def icon_page_info() -> Image.Image:
    image, d = _new()
    # Нижняя страница стопки.
    _rr(d, 8.4, 7.8, 24.2, 27.8, 2.4, PAPER_DARK, outline=INK, width=1.2)
    # Верхняя страница.
    _rr(d, 5.6, 4.8, 21.4, 25.4, 2.4, PAPER, outline=INK, width=1.2)
    for y, x1 in ((10.8, 15.4), (14.4, 15.4), (18.0, 15.4), (21.6, 12.4)):
        _line(d, [(8.6, y), (x1, y)], (150, 160, 178, 255), 1.1)
    # Загнутый угол.
    _poly(d, [(16.0, 4.8), (21.4, 4.8), (21.4, 10.2)],
          PAPER_DARK, outline=INK, width=0.9)
    _line(d, [(16.6, 5.4), (20.6, 9.6)], (255, 255, 255, 210), 0.7)
    # Синяя закладка — «где мы в списке».
    _poly(d, [(9.0, 4.4), (12.0, 4.4), (12.0, 10.8),
              (10.5, 9.2), (9.0, 10.8)], BLUE, outline=BLUE_DARK, width=0.8)
    return _finish(image)


def icon_brand() -> Image.Image:
    image, d = _new()
    # Хвост булавки.
    _poly(d, [(10.2, 16.4), (21.8, 16.4), (16.0, 29.2)],
          BLUE, outline=INK, width=1.2)
    # Голова-циферблат.
    _circ(d, 16, 13.4, 9.8, BLUE, outline=INK, width=1.4)
    _circ(d, 16, 13.4, 8.2, None, outline=BLUE_LIGHT, width=0.8)
    _circ(d, 16, 13.4, 6.3, (240, 246, 253, 255),
          outline=(120, 150, 200, 255), width=0.8)
    # Север/юг — как у компаса.
    _poly(d, [(16, 8.0), (18.6, 13.2), (13.4, 13.2)],
          BLUE, outline=BLUE_DARK, width=0.6)
    _poly(d, [(16, 18.6), (13.4, 13.6), (18.6, 13.6)],
          STEEL_DARK, outline=(60, 66, 78, 255), width=0.6)
    _circ(d, 16, 13.3, 1.6, BLUE_DARK, outline=INK, width=0.5)
    d.arc([_px(8.2), _px(5.6), _px(23.8), _px(21.2)], 196, 246,
          fill=(255, 255, 255, 135), width=_px(0.9))
    return _finish(image)


def icon_filler() -> Image.Image:
    """Пустой слот: одна цельная плитка на весь слот.

    Раньше это было «окошко» 2.8…29.2 с уголками-заклёпками: в игре пустые
    слоты читались как мелкие квадратики в дырке. Теперь заливка идёт от края
    до края (0…16), остаётся только тонкая кромка, чтобы сетка слотов была
    видна.
    """
    image, d = _new()
    # Канва 32×32 единицы (иконка 16 единиц = слот), поэтому края — 0…32.
    d.rectangle([_px(0), _px(0), _px(SIZE) - 1, _px(SIZE) - 1],
                fill=(18, 31, 56, 240))
    d.rectangle([_px(0), _px(0), _px(SIZE) - 1, _px(SIZE) - 1],
                outline=(44, 70, 116, 255), width=_px(1))
    # Едва заметный светлый верх — плитка не выглядит плоской заливкой.
    d.line([(_px(1.5), _px(1.2)), (_px(SIZE - 1.5), _px(1.2))],
           fill=(66, 98, 152, 120), width=_px(1))
    return _finish(image)


# ---------------------------------------------------------------------------
# Иконки точек по категориям: категория выбирает номер, номер — предмет
# ---------------------------------------------------------------------------

def icon_poi_compass() -> Image.Image:
    """Компас: латунный корпус, синяя/красная стрелка."""
    image, d = _new()
    _circ(d, 16, 16, 12.6, BRASS, outline=INK, width=1.4)
    _circ(d, 16, 16, 11.1, None, outline=BRASS_DARK, width=0.8)
    _circ(d, 16, 16, 9.9, (238, 244, 251, 255),
          outline=(126, 100, 58, 255), width=0.8)
    for i in range(12):
        angle = math.radians(i * 30)
        inner, outer = (8.0, 9.4) if i % 3 == 0 else (8.6, 9.4)
        _line(d, [(16 + math.cos(angle) * inner, 16 + math.sin(angle) * inner),
                  (16 + math.cos(angle) * outer, 16 + math.sin(angle) * outer)],
              (150, 128, 86, 255), 1.0 if i % 3 == 0 else 0.7)
    _poly(d, [(16, 6.8), (18.7, 16.3), (13.3, 16.3)],
          RED, outline=RED_DARK, width=0.7)
    _poly(d, [(16, 25.2), (13.3, 15.7), (18.7, 15.7)],
          BLUE, outline=BLUE_DARK, width=0.7)
    _circ(d, 16, 16, 1.9, BRASS_DARK, outline=INK, width=0.6)
    d.arc([_px(8.0), _px(8.0), _px(24.0), _px(24.0)], 200, 248,
          fill=(255, 255, 255, 150), width=_px(0.8))
    return _finish(image)


def icon_poi_beacon() -> Image.Image:
    """Маяк: стеклянная пирамида на обсидиановом основании, луч вверх."""
    image, d = _new()
    # Луч: мягкая внешняя струя и яркое ядро.
    _poly(d, [(13.2, 9.4), (18.8, 9.4), (17.4, 0.8), (14.6, 0.8)],
          (170, 235, 255, 70), outline=None)
    _poly(d, [(14.8, 9.4), (17.2, 9.4), (16.8, 0.8), (15.2, 0.8)],
          (220, 248, 255, 155), outline=None)
    # Обсидиановое основание.
    _poly(d, [(4.5, 24.6), (27.5, 24.6), (26.0, 29.2), (6.0, 29.2)],
          (46, 52, 66, 255), outline=INK, width=1.1)
    _line(d, [(6.0, 26.6), (26.0, 26.6)], (74, 84, 104, 255), 0.7)
    # Стеклянная пирамида: четыре сужающихся яруса.
    tiers = ((7.0, 20.8, 25.0, 24.8), (9.4, 17.4, 22.6, 21.1),
             (11.6, 14.2, 20.4, 17.7), (13.4, 11.4, 18.6, 14.5))
    for x0, y0, x1, y1 in tiers:
        _rr(d, x0, y0, x1, y1, 0.9, (92, 200, 214, 232),
            outline=INK, width=1.0)
        _line(d, [(x0 + 0.9, y0 + 0.8), (x1 - 0.9, y0 + 0.8)],
              (186, 240, 246, 190), 0.7)
        _line(d, [(x0 + 0.9, y1 - 0.8), (x1 - 0.9, y1 - 0.8)],
              (52, 140, 158, 220), 0.7)
    _line(d, [(16, 11.6), (16, 24.6)], (150, 224, 234, 120), 0.8)
    # Светящаяся верхушка.
    _rr(d, 13.9, 9.6, 18.1, 11.6, 0.8, (206, 246, 255, 255),
        outline=(110, 194, 214, 255), width=0.7)
    _circ(d, 16.0, 10.6, 1.3, WHITE, outline=None)
    return _finish(image)


def icon_poi_book() -> Image.Image:
    """Книга: синяя обложка, золотые полосы и застёжка."""
    image, d = _new()
    _rr(d, 9.2, 6.6, 26.6, 26.8, 1.6, PAPER,
        outline=PAPER_DARK, width=0.9)
    for y in (10.2, 13.4, 16.6, 19.8, 23.0):
        _line(d, [(24.6, y), (26.0, y)], (150, 158, 172, 255), 0.8)
    _rr(d, 5.0, 4.6, 23.4, 28.2, 2.4, BLUE, outline=INK, width=1.3)
    _rr(d, 5.0, 4.6, 9.0, 28.2, 2.2, BLUE_DARK, outline=INK, width=1.1)
    _line(d, [(7.8, 6.4), (7.8, 26.4)], (102, 156, 236, 255), 0.8)
    for y0, y1 in ((9.6, 11.4), (21.4, 23.2)):
        _rr(d, 9.6, y0, 22.4, y1, 0.7, YELLOW,
            outline=(148, 104, 16, 255), width=0.6)
    _rr(d, 20.4, 14.6, 24.8, 18.6, 1.0, YELLOW,
        outline=(148, 104, 16, 255), width=0.7)
    _line(d, [(22.6, 15.4), (22.6, 17.8)], (255, 244, 190, 255), 0.7)
    return _finish(image)


def icon_poi_pearl() -> Image.Image:
    """Жемчужина: бирюзовая сфера с бликом и искрами."""
    image, d = _new()
    _circ(d, 16, 16, 14.0, (92, 224, 224, 45), outline=None)
    _circ(d, 16, 16, 10.8, TEAL_DARK, outline=INK, width=1.3)
    _circ(d, 15.6, 15.2, 9.2, TEAL, outline=None)
    _circ(d, 14.6, 13.8, 6.9, (104, 222, 226, 255), outline=None)
    _circ(d, 13.4, 12.2, 4.4, (168, 244, 244, 255), outline=None)
    _circ(d, 12.2, 11.0, 2.0, WHITE, outline=None)
    d.arc([_px(8.4), _px(8.4), _px(23.6), _px(23.6)], 28, 150,
          fill=(210, 255, 255, 160), width=_px(0.9))
    _poly(d, [(24.6, 7.4), (25.4, 9.4), (27.4, 10.2), (25.4, 11.0),
              (24.6, 13.0), (23.8, 11.0), (21.8, 10.2), (23.8, 9.4)],
          (235, 255, 255, 230), outline=None)
    _poly(d, [(8.2, 21.4), (8.8, 22.8), (10.2, 23.4), (8.8, 24.0),
              (8.2, 25.4), (7.6, 24.0), (6.2, 23.4), (7.6, 22.8)],
          (235, 255, 255, 200), outline=None)
    return _finish(image)


def icon_poi_star() -> Image.Image:
    """Звезда: золотая четырёхлучевая с белым ядром и ореолом."""
    image, d = _new()
    _circ(d, 16, 16, 14.2, (255, 236, 160, 50), outline=None)
    _poly(d, [(16, 2.6), (19.4, 12.6), (29.4, 16.0), (19.4, 19.4),
              (16, 29.4), (12.6, 19.4), (2.6, 16.0), (12.6, 12.6)],
          YELLOW, outline=(150, 106, 16, 255), width=1.2)
    _poly(d, [(16, 7.6), (17.6, 14.4), (24.4, 16.0), (17.6, 17.6),
              (16, 24.4), (14.4, 17.6), (7.6, 16.0), (14.4, 14.4)],
          (255, 250, 214, 255), outline=None)
    _circ(d, 16, 16, 2.2, WHITE, outline=None)
    _circ(d, 16, 16, 1.1, YELLOW, outline=None)
    return _finish(image)


def icon_poi_note() -> Image.Image:
    """Ярлык категории: манильская бирка с синей наклейкой и верёвочкой."""
    image, _ = _new()
    layer = Image.new("RGBA", (SIZE * SS, SIZE * SS), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    _rr(ld, 5.4, 9.2, 26.4, 27.2, 2.6, MANILA,
        outline=INK, width=1.2)
    _line(ld, [(7.4, 25.6), (24.4, 25.6)], MANILA_DARK, 1.2)
    _rr(ld, 8.0, 13.6, 16.4, 17.2, 1.0, BLUE,
        outline=BLUE_DARK, width=0.6)
    for y, x1 in ((19.4, 23.4), (22.2, 20.6)):
        _line(ld, [(8.0, y), (x1, y)], (128, 92, 34, 255), 1.1)
    # Отверстие с металлическим ободком.
    _circ(ld, 22.8, 12.4, 2.6, (176, 184, 198, 255),
          outline=INK, width=0.7)
    _circ(ld, 22.8, 12.4, 1.6, (20, 24, 34, 255), outline=None)
    # Верёвочка выходит из отверстия вверх-вправо.
    _line(ld, [(23.6, 11.0), (26.0, 7.0), (27.9, 4.2)],
          (98, 106, 122, 255), 2.2)
    _line(ld, [(23.6, 11.0), (26.0, 7.0), (27.9, 4.2)],
          (176, 184, 198, 255), 1.1)
    _circ(ld, 27.8, 4.3, 1.2, (98, 106, 122, 255), outline=None)
    layer = layer.rotate(-9, resample=Image.BICUBIC,
                         center=(_px(16), _px(16)))
    image.alpha_composite(layer)
    return _finish(image)


# ---------------------------------------------------------------------------
# состав пака
# ---------------------------------------------------------------------------

# Номер CMD → функция рисования. Номера — ru.hahgps.item.ItemIds.
ICONS = {
    1001: ("nav_back", icon_nav_back),
    1002: ("nav_forward", icon_nav_forward),
    1003: ("nav_info", icon_nav_info),
    1004: ("nav_close", icon_nav_close),
    1005: ("page_info", icon_page_info),
    1006: ("brand", icon_brand),
    1007: ("filler", icon_filler),
    1102: ("categories", icon_categories),
    1104: ("search", icon_search),
    1105: ("settings", icon_settings),
    1201: ("poi_compass", icon_poi_compass),
    1202: ("poi_beacon", icon_poi_beacon),
    1203: ("poi_book", icon_poi_book),
    1204: ("poi_pearl", icon_poi_pearl),
    1205: ("poi_star", icon_poi_star),
    1206: ("poi_note", icon_poi_note),
    1401: ("category", icon_category),
    1402: ("category_add", icon_category_add),
    1403: ("category_remove", icon_category_remove),
    1501: ("search_input", icon_search_input),
    1502: ("search_quick", icon_search_quick),
    1503: ("search_result", icon_search_result),
    1504: ("search_empty", icon_search_empty),
    1505: ("search_limit", icon_search_limit),
    1601: ("language", icon_language),
    1602: ("logging", icon_logging),
    1701: ("transport_foot", icon_transport_foot),
    1702: ("transport_car", icon_transport_car),
    1703: ("transport_transit", icon_transport_transit),
    1704: ("transport_air", icon_transport_air),
    1705: ("transport_water", icon_transport_water),
}

# Группы для превью: заголовок и номера по порядку CMD. Превью показывает
# иконки именно так, как их группирует ItemIds, — по разделам меню.
GROUPS = [
    ("Навигация", [1001, 1002, 1003, 1004, 1005, 1006, 1007]),
    ("Главное меню", [1102, 1104, 1105]),
    ("Иконки точек по категориям", [1201, 1202, 1203, 1204, 1205, 1206]),
    ("Категории", [1401, 1402, 1403]),
    ("Поиск", [1501, 1502, 1503, 1504, 1505]),
    ("Настройки", [1601, 1602]),
    ("Транспорт", [1701, 1702, 1703, 1704, 1705]),
]

# Материал-носитель → номера, которые он рисует. Материалы взяты из GUI-кода:
# spectral_arrow — навигация, lime_dye — инфо, barrier — закрытие, book —
# страницы/категории/точки, paper — бренд/поиск/записки, стекло — заполнитель,
# compass/beacon/ender_pearl/nether_star — иконки категорий точек,
# leather/minecart/rail/feather/oak_boat — транспорт.
CARRIERS = {
    "spectral_arrow": [1001, 1002],
    "lime_dye": [1003],
    "barrier": [1004, 1504],
    "book": [1005, 1102, 1203, 1401, 1601],
    "paper": [1006, 1104, 1206, 1501, 1502, 1503, 1602],
    "blue_stained_glass_pane": [1007],
    "compass": [1201],
    "beacon": [1202],
    "ender_pearl": [1204],
    "nether_star": [1205],
    "redstone": [1105],
    "emerald": [1402],
    "lava_bucket": [1403],
    "repeater": [1505],
    "leather": [1701],
    "minecart": [1702],
    "rail": [1703],
    "feather": [1704],
    "oak_boat": [1705],
}

# Чужие кнопки на тех же материалах: им нужна ванильная модель, иначе
# диапазон «последний порог ≤ значения» отдал бы им нашу иконку.
FOREIGN = {
    "book": [2030],
    "paper": [1301],
    "barrier": [2034, 2039],
    "lava_bucket": [1304, 2035],
    "spyglass": [2033, 2038],
    # Компас в GUI носят несколько чужих кнопок; у них должна остаться
    # ванильная модель со стрелкой (см. items/compass.json).
    "compass": [1302],
    "nether_star": [1303],
}


def _model(name: str) -> dict:
    return {"parent": "minecraft:item/generated",
            "textures": {"layer0": f"hahgps:item/gui_{name}"}}


def vanilla_compass_model() -> dict:
    """Ванильный `items/compass.json` 1.21.11 целиком.

    Простой `minecraft:item/compass` тут не годится: у компаса кадры модели
    выбираются по углу (и отдельно — при привязке к магнетиту). Если подменить
    fallback картинкой, обычный компас в игре замрёт.
    """
    names = list(range(16, 32)) + list(range(0, 17))
    entries = [{"threshold": 0.0 if index == 0 else index - 0.5,
                "model": {"type": "minecraft:model",
                          "model": f"minecraft:item/compass_{number:02d}"}}
               for index, number in enumerate(names)]

    def branch(target: str) -> dict:
        return {"type": "minecraft:range_dispatch",
                "property": "minecraft:compass", "scale": 32.0,
                "target": target, "entries": entries}

    return {"type": "minecraft:condition",
            "property": "minecraft:has_component",
            "component": "minecraft:lodestone_tracker",
            "on_true": branch("lodestone"),
            "on_false": branch("spawn")}


def _vanilla(material: str) -> dict:
    if material == "compass":
        return vanilla_compass_model()
    if material == "beacon":
        # Блок-предмет ссылается на модель блока, а не на item/beacon.
        return {"type": "minecraft:model", "model": "minecraft:block/beacon"}
    return {"type": "minecraft:model", "model": f"minecraft:item/{material}"}


def _dispatch(entries: list[tuple[float, dict]], fallback: dict) -> dict:
    entries = sorted(entries, key=lambda item: item[0])
    return {
        "model": {
            "type": "minecraft:range_dispatch",
            "property": "minecraft:custom_model_data",
            "entries": [{"threshold": float(threshold), "model": model}
                        for threshold, model in entries],
            "fallback": fallback,
        }
    }


def build(pack: Path) -> list[str]:
    """Нарисовать иконки, модели и диспетчеры CMD. Возвращает имена иконок."""
    # Текстуры иконок лежат в textures/item: у Minecraft атлас предметов
    # собирается из каталога textures/item (атлас items.json), и всё, что
    # лежит в textures/gui, в него не попадает — модель остаётся «без
    # текстуры» и рисуется фиолетово-чёрной клеткой.
    tex = pack / "assets/hahgps/textures/item"
    models = pack / "assets/hahgps/models/item"
    items = pack / "assets/minecraft/items"
    for path in (tex, models, items):
        path.mkdir(parents=True, exist_ok=True)

    for cmd, (name, draw) in ICONS.items():
        draw().save(tex / f"gui_{name}.png")
        (models / f"gui_{name}.json").write_text(
            json.dumps(_model(name), ensure_ascii=False, indent=1) + "\n",
            encoding="utf-8")

    for material, cmds in CARRIERS.items():
        vanilla = _vanilla(material)
        entries: list[tuple[float, dict]] = []
        # Уже записанные диспетчеры (трекеры) сохраняем как есть.
        path = items / f"{material}.json"
        if path.is_file():
            existing = json.loads(path.read_text(encoding="utf-8"))
            for entry in _entries(existing):
                entries.append((entry["threshold"], entry["model"]))
        for cmd in cmds:
            entries.append((cmd, {"type": "minecraft:model",
                                  "model": f"hahgps:item/gui_{ICONS[cmd][0]}"}))
        for cmd in FOREIGN.get(material, ()):
            entries.append((cmd, vanilla))
        entries.append((TOP_THRESHOLD, vanilla))
        path.write_text(json.dumps(_dispatch(entries, vanilla),
                                   ensure_ascii=False, indent=1) + "\n",
                        encoding="utf-8")
    return [name for name, _ in ICONS.values()]


def _entries(node) -> list[dict]:
    """Все пороги range_dispatch из готового item definition."""
    found: list[dict] = []
    if isinstance(node, dict):
        if "threshold" in node and isinstance(node.get("model"), dict):
            found.append(node)
        for value in node.values():
            found.extend(_entries(value))
    elif isinstance(node, list):
        for value in node:
            found.extend(_entries(value))
    return found
