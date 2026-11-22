#!/usr/bin/env python3
"""Рисование стрелок маршрута для HahGPS.

Две линейки:
  * HUD-глифы 8×8 — жёлтые стрелки для строки BossBar (высота строки в игре
    всего 8 пикселей, поэтому крупнее туда не влезает);
  * знаки 16×16 — жёлтая световозвращающая стрелка на чёрной плите, из них
    собираются 3D-модели предмета и блока.

Все текстуры — свои, namespace `hahgps`; ванильные текстуры блоков/предметов
не переопределяются.
"""
from __future__ import annotations

import math

from PIL import Image, ImageChops, ImageDraw, ImageFilter

# Порядок совпадает с ru.hahgps.util.RouteArrow.
DIRECTIONS = (
    "straight",
    "slight-left",
    "left",
    "sharp-left",
    "back",
    "sharp-right",
    "right",
    "slight-right",
)

# --- HUD: 8×8 -----------------------------------------------------------------
# Строка боссбара — 8 пикселей в высоту, поэтому глиф нарисован по пикселям, а
# не масштабирован: тонкие штрихи в 8 px читаются лучше сглаженных.
_GLYPHS = {
    "straight": (
        "...##...",
        "..####..",
        ".######.",
        "#######.",
        "...##...",
        "...##...",
        "...##...",
        "...##...",
    ),
    "left": (
        "........",
        "....##..",
        "...####.",
        "#######.",
        "...####.",
        "....##..",
        "........",
        "........",
    ),
    "slight-left": (
        "#.......",
        "##......",
        "###.....",
        ".####...",
        "..###...",
        "...###..",
        "....###.",
        ".....###",
    ),
    "sharp-left": (
        ".....###",
        "....###.",
        "...####.",
        "..###...",
        ".###....",
        "##......",
        "#.......",
        "........",
    ),
    "back": (
        "...##...",
        "...##...",
        "...##...",
        "...##...",
        "#######.",
        ".######.",
        "..####..",
        "...##...",
    ),
}
_GLYPH_MIRROR = {
    "right": "left",
    "slight-right": "slight-left",
    "sharp-right": "sharp-left",
}


# --- Знаки: 16×16 -------------------------------------------------------------
INK = (23, 25, 29)
INK_EDGE = (61, 66, 76)
INK_HI = (43, 47, 55)
YELLOW = (245, 199, 66)
YELLOW_HI = (255, 227, 128)
YELLOW_DARK = (138, 102, 18)
STEEL = (104, 110, 121)
STEEL_HI = (146, 152, 164)
STEEL_DARK = (58, 62, 70)
ASPHALT = (47, 50, 55)
ASPHALT_DARK = (37, 40, 44)
SIGN = 16

# Геометрия в сетке 16×16: (стержень, кончик, направление наконечника,
# толщина, длина головки, полуширина головки).
_SIGN_SHAPES = {
    "straight": ([(7.5, 16), (7.5, 7.0)], (7.5, 0.8), (0.0, -1.0), 3.0, 6.4, 4.4),
    "slight-left": ([(9.0, 16), (9.0, 10.5), (5.6, 7.0)], (2.4, 3.8),
                    (-0.707, -0.707), 2.8, 5.4, 4.2),
    "left": ([(9.0, 16), (9.0, 8.5), (6.5, 8.5)], (1.4, 8.5),
             (-1.0, 0.0), 2.8, 5.8, 4.4),
    "sharp-left": ([(13.0, 3.0), (6.6, 10.4)], (3.0, 14.0),
                   (-0.707, 0.707), 2.8, 5.6, 4.2),
    "back": ([(5.5, 16), (5.5, 6.0), (9.5, 3.5), (12.0, 6.5), (12.0, 10.5)],
             (12.0, 16.0), (0.0, 1.0), 2.8, 5.4, 4.2),
}
_SIGN_MIRROR = {
    "slight-right": "slight-left",
    "right": "left",
    "sharp-right": "sharp-left",
}


def _head(draw: ImageDraw.ImageDraw, tip, direction, length: float,
          half: float) -> None:
    dx, dy = direction
    norm = math.hypot(dx, dy) or 1.0
    dx, dy = dx / norm, dy / norm
    bx, by = tip[0] - dx * length, tip[1] - dy * length
    px, py = -dy, dx
    draw.polygon(
        [(tip[0], tip[1]),
         (bx + px * half, by + py * half),
         (bx - px * half, by - py * half)],
        fill=255,
    )


def _sign_arrow_mask(direction: str, scale: int = 12) -> Image.Image:
    if direction in _SIGN_MIRROR:
        return _sign_arrow_mask(_SIGN_MIRROR[direction], scale).transpose(
            Image.FLIP_LEFT_RIGHT)
    line, tip, head_dir, width, head_len, head_half = _SIGN_SHAPES[direction]
    # Отступ от края плиты: контур глифа не должен упираться в рамку.
    margin = 1.6
    fit = (SIGN - 2 * margin) / SIGN
    line = [(margin + x * fit, margin + y * fit) for x, y in line]
    tip = (margin + tip[0] * fit, margin + tip[1] * fit)
    head_len *= fit
    big = SIGN * scale
    mask = Image.new("L", (big, big), 0)
    draw = ImageDraw.Draw(mask)
    draw.line([(x * scale, y * scale) for x, y in line], fill=255,
              width=max(1, round(width * scale)), joint="curve")
    _head(draw, (tip[0] * scale, tip[1] * scale), head_dir, head_len * scale,
          head_half * fit * scale)
    small = mask.resize((SIGN, SIGN), Image.LANCZOS)
    return small.point(lambda v: 255 if v >= 110 else 0)


def _plate(full: bool = False) -> Image.Image:
    """Корпус знака: чёрная плита с тонкой светлой окантовкой."""
    img = Image.new("RGBA", (SIGN, SIGN), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    if full:
        draw.rectangle([0, 0, SIGN - 1, SIGN - 1], fill=INK + (255,))
        draw.rectangle([0, 0, SIGN - 1, SIGN - 1], outline=INK_EDGE + (255,))
        draw.line([1, 1, SIGN - 2, 1], fill=INK_HI + (255,))
        draw.line([1, 1, 1, SIGN - 2], fill=INK_HI + (255,))
    else:
        draw.rounded_rectangle([0, 0, SIGN - 1, SIGN - 1], radius=2,
                               fill=INK + (255,), outline=INK_EDGE + (255,))
        draw.line([2, 1, SIGN - 3, 1], fill=INK_HI + (255,))
        draw.line([1, 2, 1, SIGN - 3], fill=INK_HI + (255,))
    return img


def _put_arrow(base: Image.Image, mask: Image.Image) -> Image.Image:
    outline = mask.filter(ImageFilter.MaxFilter(3))
    out = base.copy()
    out.paste(Image.new("RGBA", base.size, YELLOW_DARK + (255,)), (0, 0), outline)
    out.paste(Image.new("RGBA", base.size, YELLOW + (255,)), (0, 0), mask)
    rim = ImageChops.subtract(mask, ImageChops.offset(mask, 1, 1))
    out.paste(Image.new("RGBA", base.size, YELLOW_HI + (255,)), (0, 0), rim)
    return out


def glyph(direction: str) -> Image.Image:
    """10×10 глиф для BossBar и чата: жёлтая стрелка с тёмным контуром.

    Тело 8×8, контур 1 px: в чате глиф ростом 10 px с посадкой 9 px влезает
    в строку (1 px уходит в выносную часть), в боссбаре тоже не задевает полосу.
    """
    if direction in _GLYPH_MIRROR:
        return glyph(_GLYPH_MIRROR[direction]).transpose(Image.FLIP_LEFT_RIGHT)
    rows = _GLYPHS[direction]
    body = Image.new("RGBA", (8, 8), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, cell in enumerate(row):
            if cell == "#":
                body.putpixel((x, y), YELLOW + (255,))
    mask = Image.new("L", (10, 10), 0)
    mask.paste(body.split()[3], (1, 1))
    outline = mask.filter(ImageFilter.MaxFilter(3))
    out = Image.new("RGBA", (10, 10), (0, 0, 0, 0))
    out.paste(Image.new("RGBA", (10, 10), (16, 18, 22, 255)), (0, 0), outline)
    out.paste(body, (1, 1), body.split()[3])
    return out


def sign_face(direction: str) -> Image.Image:
    """Лицевая грань 3D-знака: жёлтая стрелка на чёрной плите 16×16."""
    return _put_arrow(_plate(full=True), _sign_arrow_mask(direction))


def sign_back() -> Image.Image:
    """Задняя стенка знака: корпус с креплением."""
    img = _plate(full=True)
    draw = ImageDraw.Draw(img)
    draw.rectangle([5, 2, 10, SIGN - 3], fill=(34, 37, 43, 255))
    draw.rectangle([5, 2, 10, SIGN - 3], outline=(70, 75, 84, 255))
    draw.line([7, 4, 7, SIGN - 6], fill=(24, 26, 31, 255))
    draw.line([9, 4, 9, SIGN - 6], fill=(24, 26, 31, 255))
    return img


def _noise(img: Image.Image, seed: int) -> None:
    draw = ImageDraw.Draw(img)
    x = (seed * 37 + 11) % SIGN
    y = (seed * 53 + 7) % SIGN
    for i in range(22):
        x = (x * 5 + 3) % SIGN
        y = (y * 7 + 5) % SIGN
        shade = 8 if i % 3 else -9
        draw.point((x, y), fill=(max(0, ASPHALT[0] + shade),
                                 max(0, ASPHALT[1] + shade),
                                 max(0, ASPHALT[2] + shade), 255))


def block_top(direction: str) -> Image.Image:
    """Верх плиты-разметки: асфальт с жёлтой стрелкой."""
    img = Image.new("RGBA", (SIGN, SIGN), ASPHALT + (255,))
    _noise(img, 3)
    draw = ImageDraw.Draw(img)
    draw.rectangle([0, 0, SIGN - 1, SIGN - 1], outline=ASPHALT_DARK + (255,))
    return _put_arrow(img, _sign_arrow_mask(direction))


def block_side() -> Image.Image:
    """Боковина блока: бетон с жёлтой световозвращающей полосой."""
    img = Image.new("RGBA", (SIGN, SIGN), (78, 80, 85, 255))
    draw = ImageDraw.Draw(img)
    for y in range(SIGN):
        k = 0.92 + (y % 3) * 0.03
        draw.line([0, y, SIGN - 1, y],
                  fill=(int(78 * k), int(80 * k), int(86 * k), 255))
    draw.rectangle([0, 3, SIGN - 1, 5], fill=YELLOW + (255,))
    draw.line([0, 3, SIGN - 1, 3], fill=YELLOW_HI + (255,))
    draw.rectangle([0, 12, SIGN - 1, 13], fill=(56, 58, 63, 255))
    return img


def block_bottom() -> Image.Image:
    img = Image.new("RGBA", (SIGN, SIGN), (48, 50, 54, 255))
    _noise(img, 7)
    return img


def steel(kind: str = "post") -> Image.Image:
    """Стойка/окантовка: матовый металл без синих оттенков."""
    img = Image.new("RGBA", (SIGN, SIGN), STEEL_DARK + (255,))
    draw = ImageDraw.Draw(img)
    for x in range(SIGN):
        shade = 0.72 + (x % 4) * 0.09
        draw.line([x, 0, x, SIGN - 1],
                  fill=tuple(min(255, int(v * shade)) for v in STEEL) + (255,))
    if kind == "post":
        draw.line([2, 0, 2, SIGN - 1], fill=STEEL_HI + (255,))
        draw.line([13, 0, 13, SIGN - 1], fill=STEEL_DARK + (255,))
    else:
        draw.rectangle([0, 0, SIGN - 1, SIGN - 1], outline=STEEL_DARK + (255,))
        draw.line([1, 1, SIGN - 2, 1], fill=STEEL_HI + (255,))
    return img


def font_atlas() -> Image.Image:
    """Все 8 глифов в ряд: 10 × 10 пикселей на глиф."""
    atlas = Image.new("RGBA", (10 * len(DIRECTIONS), 10), (0, 0, 0, 0))
    for i, direction in enumerate(DIRECTIONS):
        atlas.paste(glyph(direction), (i * 10, 0))
    return atlas


def pack_icon(px: int = 128) -> Image.Image:
    """pack.png: знак «прямо» крупно, без сглаживания."""
    tile = sign_face("straight").resize((px - 24, px - 24), Image.NEAREST)
    icon = Image.new("RGBA", (px, px), (18, 20, 24, 255))
    draw = ImageDraw.Draw(icon)
    draw.rounded_rectangle([0, 0, px - 1, px - 1], radius=16,
                           outline=(58, 63, 72, 255), width=4)
    icon.paste(tile, (12, 12), tile.split()[3])
    return icon
