#!/usr/bin/env python3
"""Рисование 3D-трекеров и локаторов iTrak для HahGPS.

Трекер — «AirTag» с головой крипера вместо логотипа: круглый белый корпус в
цветном бампере, металлическая задняя крышка, светодиод. Восемь цветов и две
версии (обычная и водонепроницаемая: бирюзовый кант и капля).

Локатор — ручной сканер: тёмный корпус, экран с радаром, кнопки и антенна.
Дальняя версия отличается жёлтыми кольцами радара и длинной антенной.

Все текстуры — свои, namespace `hahgps`; ванильные предметы не переопределяются,
кроме item-диска с `custom_model_data` и откатом на ванильную модель.
"""
from __future__ import annotations

import math

from PIL import Image, ImageDraw, ImageFilter

# Порядок и цвета совпадают с ru.hahgps.model.TrackerColor.
COLORS = {
    "blue": "#3f86ff",
    "cyan": "#4fd1e0",
    "green": "#55c36b",
    "yellow": "#f0c24b",
    "orange": "#f2994a",
    "red": "#eb5757",
    "purple": "#9b51e0",
    "white": "#f2f2f2",
}

TRACKER_SIZE = 64
LOCATOR_SIZE = 64
SIDE = 32
SS = 4  # суперсэмплинг для кругов и скруглений

_GREEN = (85, 195, 107)
_GREEN_LIGHT = (118, 218, 128)
_GREEN_DARK = (37, 92, 46)
_FACE = (10, 24, 12)
_WHITE = (246, 247, 244)
_WHITE_DARK = (218, 221, 218)
_STEEL = (176, 181, 187)
_STEEL_DARK = (103, 109, 117)
_STEEL_LIGHT = (224, 228, 232)
_CYAN = (79, 209, 224)
_CYAN_DARK = (28, 121, 138)
_GRAPHITE = (37, 41, 48)
_GRAPHITE_LIGHT = (60, 66, 76)
_GRAPHITE_DARK = (20, 23, 28)
_SCREEN = (7, 22, 30)


def rgb(hex_color: str) -> tuple[int, int, int]:
    value = hex_color.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))


def mix(a, b, t: float):
    t = max(0.0, min(1.0, t))
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


def shade(color, factor: float):
    return tuple(max(0, min(255, int(c * factor))) for c in color)


def _supersampled(size: int):
    image = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    return image, ImageDraw.Draw(image)


def _down(image: Image.Image, size: int) -> Image.Image:
    return image.resize((size, size), Image.LANCZOS)


# ---------------------------------------------------------------------------
# Пиксельный шрифт 3×5: мелкие подписи на корпусе, как на настоящей технике.
# ---------------------------------------------------------------------------
_PIXEL_FONT = {
    "A": ("010", "101", "111", "101", "101"),
    "C": ("011", "100", "100", "100", "011"),
    "D": ("110", "101", "101", "101", "110"),
    "E": ("111", "100", "110", "100", "111"),
    "I": ("111", "010", "010", "010", "111"),
    "K": ("101", "101", "110", "101", "101"),
    "L": ("100", "100", "100", "100", "111"),
    "M": ("101", "111", "111", "101", "101"),
    "N": ("101", "111", "111", "111", "101"),
    "O": ("010", "101", "101", "101", "010"),
    "R": ("110", "101", "110", "101", "101"),
    "S": ("011", "100", "010", "001", "110"),
    "T": ("111", "010", "010", "010", "010"),
    "V": ("101", "101", "101", "101", "010"),
    "Y": ("101", "101", "010", "010", "010"),
    "0": ("111", "101", "101", "101", "111"),
    "1": ("010", "110", "010", "010", "111"),
    "2": ("110", "001", "010", "100", "111"),
    "3": ("110", "001", "010", "001", "110"),
    "DEG": ("010", "101", "010", "000", "000"),
}


def pixel_text(draw: ImageDraw.ImageDraw, text: str, xy, color, scale=1,
               spacing=1) -> int:
    """Нарисовать строку пиксельным шрифтом; вернуть ширину."""
    x, y = xy
    for char in text.upper():
        glyph = _PIXEL_FONT.get(char, _PIXEL_FONT.get("O"))
        for row, bits in enumerate(glyph):
            for col, bit in enumerate(bits):
                if bit == "1":
                    draw.rectangle([
                        x + col * scale, y + row * scale,
                        x + (col + 1) * scale - 1, y + (row + 1) * scale - 1,
                    ], fill=tuple(color) + (255,))
        x += (3 + spacing) * scale
    return x


def pixel_text_width(text: str, scale=1, spacing=1) -> int:
    return max(0, len(text) * (3 + spacing) * scale - spacing * scale)


# ---------------------------------------------------------------------------
# Трекер
# ---------------------------------------------------------------------------

def tracker_top(color_key: str, waterproof: bool = False,
                size: int = TRACKER_SIZE) -> Image.Image:
    """Лицо трекера: белый кружок в цветном бампере, голова крипера, LED."""
    color = rgb(COLORS[color_key])
    big, draw = _supersampled(size)
    edge = size * SS

    # Цветной бампер-кольцо.
    draw.ellipse([2 * SS, 2 * SS, edge - 2 * SS, edge - 2 * SS], fill=color + (255,))
    draw.ellipse([1 * SS, 1 * SS, edge - 1 * SS, edge - 1 * SS],
                 outline=shade(color, 0.55) + (255,), width=1 * SS)
    # Верхний блик бампера.
    draw.arc([4 * SS, 4 * SS, edge - 4 * SS, edge - 4 * SS], 200, 315,
             fill=mix(color, (255, 255, 255), 0.55) + (255,), width=2 * SS)

    # Белая керамическая площадка.
    inner = [5 * SS, 5 * SS, edge - 5 * SS, edge - 5 * SS]
    draw.ellipse(inner, fill=_WHITE + (255,))
    draw.ellipse(inner, outline=_WHITE_DARK + (255,), width=1 * SS)
    # Лёгкая тень по нижнему краю площадки.
    draw.arc(inner, 20, 160, fill=(196, 200, 198, 255), width=2 * SS)

    if waterproof:
        # Водозащита: бирюзовый кант и капля.
        draw.ellipse([3 * SS, 3 * SS, edge - 3 * SS, edge - 3 * SS],
                     outline=_CYAN + (255,), width=2 * SS)
        draw.arc([4 * SS, 4 * SS, edge - 4 * SS, edge - 4 * SS], 210, 320,
                 fill=(177, 240, 250, 255), width=1 * SS)

    image = _down(big, size)
    draw = ImageDraw.Draw(image)

    # Голова крипера вместо яблока — компактный логотип, а не пол-лица.
    head_box = [21, 20, 43, 42]
    draw.rounded_rectangle(head_box, radius=4, fill=_GREEN + (255,),
                           outline=_GREEN_DARK + (255,), width=2)
    draw.line([24, 23, 40, 23], fill=_GREEN_LIGHT + (255,), width=1)
    draw.line([23, 24, 23, 38], fill=_GREEN_LIGHT + (255,), width=1)
    draw.line([24, 39, 40, 39], fill=_GREEN_DARK + (255,), width=1)

    # Глаза.
    draw.rectangle([24, 26, 28, 30], fill=_FACE + (255,))
    draw.rectangle([36, 26, 40, 30], fill=_FACE + (255,))
    # Рот: центральный блок и две «ножки».
    draw.rectangle([30, 30, 34, 35], fill=_FACE + (255,))
    draw.rectangle([25, 35, 30, 39], fill=_FACE + (255,))
    draw.rectangle([34, 35, 39, 39], fill=_FACE + (255,))

    # Светодиод сверху: у водонепроницаемого — холодный белый.
    led = (196, 250, 255) if waterproof else mix(color, (255, 255, 255), 0.6)
    draw.ellipse([30, 6, 34, 10], fill=led + (255,))
    draw.ellipse([31, 7, 33, 9], fill=(255, 255, 255, 255))

    # Мелкая гравировка на нижнем крае.
    text = "ITRAK"
    width = pixel_text_width(text)
    pixel_text(draw, text, ((size - width) // 2, 52), (150, 154, 152))

    if waterproof:
        # Капля в правом верхнем углу площадки.
        draw.polygon([(48, 12), (52, 19), (50, 23), (46, 23), (44, 19)],
                     fill=_CYAN + (255,))
        draw.ellipse([46, 18, 50, 22], fill=(206, 248, 255, 255))

    return image
def tracker_edge(color_key: str, waterproof: bool = False,
                 size: int = SIDE) -> Image.Image:
    """Боковая кромка диска: скруглённые концы, а не квадратная плита."""
    color = rgb(COLORS[color_key])
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    # Кромка не доходит до углов квадрата: иначе на 3/4 сверху из-за
    # прозрачных углов круглого лица торчат четыре цветных «крыла».
    box = [6, 0, size - 7, size - 1]
    draw.rounded_rectangle(box, radius=5, fill=color + (255,))
    draw.rounded_rectangle(box, radius=5, outline=shade(color, 0.5) + (255,), width=1)
    for y in range(size):
        t = y / max(1, size - 1)
        shade_color = mix(shade(color, 1.22), shade(color, 0.55), t)
        draw.line([8, y, size - 9, y], fill=shade_color + (255,))
    # Блик и шов не доводим до скруглённых концов: иначе на углах лица
    # появляются белые «крылья», которых у AirTag нет.
    draw.line([8, 2, size - 9, 2], fill=mix(color, (255, 255, 255), 0.32) + (255,))
    draw.line([8, size - 3, size - 9, size - 3], fill=shade(color, 0.45) + (255,))
    seam = _CYAN if waterproof else mix(color, (235, 238, 240), 0.6)
    draw.line([8, size // 2, size - 9, size // 2], fill=seam + (255,))
    if waterproof:
        draw.ellipse([size - 7, 4, size - 3, 8], fill=_CYAN + (255,))
    return image
def tracker_back(size: int = SIDE) -> Image.Image:
    """Задняя крышка: шлифованный металл, кольцо, винты и серийник."""
    big, draw = _supersampled(size)
    edge = size * SS
    draw.ellipse([1 * SS, 1 * SS, edge - 1 * SS, edge - 1 * SS],
                 fill=_STEEL_DARK + (255,))
    draw.ellipse([3 * SS, 3 * SS, edge - 3 * SS, edge - 3 * SS],
                 fill=_STEEL + (255,))
    # Радиальный блик.
    draw.ellipse([6 * SS, 6 * SS, edge - 7 * SS, edge - 7 * SS],
                 fill=mix(_STEEL, _STEEL_LIGHT, 0.55) + (255,))
    draw.ellipse([10 * SS, 10 * SS, edge - 11 * SS, edge - 11 * SS],
                 outline=mix(_STEEL, _STEEL_LIGHT, 0.7) + (255,), width=1 * SS)
    image = _down(big, size)
    draw = ImageDraw.Draw(image)

    # Шлифовка: короткие горизонтальные штрихи.
    for y in range(5, size - 4, 3):
        draw.line([5, y, size - 6, y], fill=shade(_STEEL, 0.88) + (120,))
    # Винты по четырём сторонам.
    for cx, cy in ((6, 6), (size - 7, 6), (6, size - 7), (size - 7, size - 7)):
        draw.ellipse([cx - 2, cy - 2, cx + 2, cy + 2], fill=(92, 98, 106, 255))
        draw.line([cx - 1, cy, cx + 1, cy], fill=(190, 196, 202, 255))
    text = "ITRAK"
    width = pixel_text_width(text)
    pixel_text(draw, text, ((size - width) // 2, size // 2 - 2), (116, 122, 130))
    return image


# ---------------------------------------------------------------------------
# Локатор
# ---------------------------------------------------------------------------

def locator_front(long: bool = False, size: int = LOCATOR_SIZE) -> Image.Image:
    """Передняя панель: корпус, экран с радаром, кнопки, подписи."""
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    accent = rgb("#f0c24b") if long else _CYAN

    # Корпус с фаской.
    draw.rounded_rectangle([3, 3, size - 4, size - 4], radius=8,
                           fill=_GRAPHITE + (255,), outline=_GRAPHITE_DARK + (255,), width=2)
    draw.line([6, 5, size - 7, 5], fill=_GRAPHITE_LIGHT + (255,), width=1)
    draw.line([5, 6, 5, size - 8], fill=_GRAPHITE_LIGHT + (255,), width=1)
    draw.line([6, size - 6, size - 7, size - 6], fill=_GRAPHITE_DARK + (255,), width=1)

    # Экран.
    screen = [8, 9, size - 9, 38]
    draw.rounded_rectangle(screen, radius=3, fill=_SCREEN + (255,),
                           outline=(12, 44, 52, 255), width=1)
    draw.rectangle([10, 11, size - 11, 36], fill=(5, 17, 23, 255))

    # Радар: кольца, крест, сектор и отметки.
    cx, cy = size // 2, 24
    for radius in (6, 11, 16):
        draw.ellipse([cx - radius, cy - radius, cx + radius, cy + radius],
                     outline=accent + (120,), width=1)
    draw.line([cx - 17, cy, cx + 17, cy], fill=accent + (60,))
    draw.line([cx, cy - 15, cx, cy + 15], fill=accent + (60,))
    # Сектор развёртки.
    draw.pieslice([cx - 16, cy - 16, cx + 16, cy + 16], 300, 350,
                  fill=accent + (26,))
    draw.ellipse([cx - 1, cy - 1, cx + 1, cy + 1], fill=(225, 255, 255, 255))
    for bx, by in ((-9, -5), (7, 6), (10, -8), (-4, 9)):
        draw.ellipse([cx + bx - 1, cy + by - 1, cx + bx + 1, cy + by + 1],
                     fill=accent + (255,))

    # Подпись и модель.
    pixel_text(draw, "ITRAK", (11, 40), (150, 205, 212))
    label = "30M" if long else "20M"
    pixel_text(draw, label, (size - 11 - pixel_text_width(label), 40),
               accent)

    # Кнопки.
    for i, (bx, color) in enumerate((
            (18, (90, 98, 108)), (32, accent), (46, (90, 98, 108)))):
        draw.ellipse([bx - 3, 49, bx + 3, 55], fill=color + (255,),
                     outline=_GRAPHITE_DARK + (255,))
        draw.ellipse([bx - 2, 50, bx + 2, 54], fill=mix(color, (255, 255, 255), 0.25) + (255,))
    # Решётка динамика.
    for x in range(18, 47, 3):
        draw.point((x, 59), fill=(100, 108, 118, 255))

    if long:
        # Дальний локатор: жёлтая полоса и второй индикатор.
        draw.rectangle([6, 58, size - 7, 60], fill=accent + (255,))
    return image


def locator_side(size: int = SIDE) -> Image.Image:
    image = Image.new("RGBA", (size, size), _GRAPHITE + (255,))
    draw = ImageDraw.Draw(image)
    for y in range(size):
        t = y / max(1, size - 1)
        draw.line([0, y, size - 1, y],
                  fill=mix(_GRAPHITE_LIGHT, _GRAPHITE_DARK, t) + (255,))
    for y in range(4, size - 3, 4):
        draw.line([3, y, size - 4, y], fill=shade(_GRAPHITE, 0.78) + (255,))
    draw.rectangle([0, 0, 2, size - 1], fill=_GRAPHITE_DARK + (255,))
    draw.line([0, 1, size - 1, 1], fill=_GRAPHITE_LIGHT + (255,))
    # Кнопка питания сбоку.
    draw.rounded_rectangle([size - 8, 5, size - 5, 15], radius=1,
                           fill=(120, 128, 138, 255))
    return image


def locator_back(size: int = SIDE) -> Image.Image:
    image = Image.new("RGBA", (size, size), _GRAPHITE_DARK + (255,))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle([1, 1, size - 2, size - 2], radius=4,
                           fill=(30, 34, 40, 255), outline=(14, 17, 21, 255))
    draw.rounded_rectangle([5, 5, size - 6, size - 7], radius=2,
                           fill=(42, 47, 55, 255), outline=(70, 77, 88, 255))
    # Крышка батарейного отсека и защёлка.
    draw.rectangle([7, 18, size - 8, 24], fill=(34, 39, 46, 255))
    draw.line([7, 21, size - 8, 21], fill=(84, 92, 104, 255))
    pixel_text(draw, "ITRAK", (7, 8), (120, 160, 168))
    for x in (5, size - 6):
        draw.ellipse([x - 1, 27, x + 1, 29], fill=(97, 104, 114, 255))
    return image


def locator_tip(size: int = 8) -> Image.Image:
    """Торец антенны: тёмный резиновый колпачок."""
    image = Image.new("RGBA", (size, size), (24, 27, 32, 255))
    draw = ImageDraw.Draw(image)
    draw.rectangle([0, 0, size - 1, size - 1], outline=(10, 12, 15, 255))
    draw.line([1, 1, size - 2, 1], fill=(58, 64, 74, 255))
    return image


def locator_button(size: int = 8) -> Image.Image:
    image = Image.new("RGBA", (size, size), (96, 104, 115, 255))
    draw = ImageDraw.Draw(image)
    draw.rectangle([0, 0, size - 1, size - 1], outline=(38, 43, 51, 255))
    draw.line([1, 1, size - 2, 1], fill=(150, 160, 174, 255))
    return image


def locator_band(size: int = 8) -> Image.Image:
    image = Image.new("RGBA", (size, size), (240, 194, 75, 255))
    draw = ImageDraw.Draw(image)
    draw.line([0, 0, size - 1, 0], fill=(255, 226, 140, 255))
    draw.line([0, size - 1, size - 1, size - 1], fill=(154, 111, 18, 255))
    return image


def locator_top(size: int = 16) -> Image.Image:
    image = Image.new("RGBA", (size, size), _GRAPHITE + (255,))
    draw = ImageDraw.Draw(image)
    draw.rectangle([0, 0, size - 1, size - 1], outline=_GRAPHITE_DARK + (255,))
    draw.ellipse([6, 6, 9, 9], fill=(12, 14, 18, 255))
    draw.arc([2, 2, size - 3, size - 3], 200, 340, fill=_GRAPHITE_LIGHT + (255,))
    return image


# ---------------------------------------------------------------------------
# Иконка пака
# ---------------------------------------------------------------------------

def pack_icon(px: int = 128, color_key: str = "blue") -> Image.Image:
    icon = Image.new("RGBA", (px, px), (17, 19, 24, 255))
    draw = ImageDraw.Draw(icon)
    draw.rounded_rectangle([0, 0, px - 1, px - 1], radius=18,
                           outline=(58, 63, 72, 255), width=4)
    # Круглый трекер крупно.
    face = tracker_top(color_key).resize((px - 28, px - 28), Image.LANCZOS)
    icon.alpha_composite(face, (14, 10))
    # Жёлтая стрелка снизу-справа — намёк на навигацию.
    arrow = [(px - 34, px - 30), (px - 12, px - 30), (px - 12, px - 38),
             (px - 4, px - 24), (px - 12, px - 10), (px - 12, px - 18),
             (px - 34, px - 18)]
    draw.polygon(arrow, fill=(245, 199, 66, 255), outline=(20, 22, 26, 255))
    return icon
