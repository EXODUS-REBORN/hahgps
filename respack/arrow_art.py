#!/usr/bin/env python3
"""HUD-стрелки маршрута для HahGPS.

Ряд 8×8 — строка BossBar: жёлтые ванильные стрелки, снятые с ванильного
шрифта Minecraft и перекрашенные. Крупные 3D-знаки из пака убраны — в игре
маршрут показывается только глифами HUD и чата.
"""
from __future__ import annotations

from PIL import Image

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

# Строка боссбара — 8 пикселей в высоту, поэтому глиф нарисован по пикселям, а
# не масштабирован: тонкие штрихи в 8 px читаются лучше сглаженных.
_GLYPHS = {
    # Пиксельные маски, снятые с ванильного шрифта Minecraft 1.21.11
    # (assets/minecraft/font/nonlatin_european.png, строка с ←↑→↓).
    # Рисунок не меняем — только красим в жёлтый.
    "straight": (
        "..#.....",
        ".###....",
        "#####...",
        "..#.....",
        "..#.....",
        "..#.....",
        "..#.....",
        "........",
    ),
    "left": (
        "........",
        "..#.....",
        ".##.....",
        "#######.",
        ".##.....",
        "..#.....",
        "........",
        "........",
    ),
    "right": (
        "........",
        ".....#..",
        ".....##.",
        "#######.",
        ".....##.",
        ".....#..",
        "........",
        "........",
    ),
    "back": (
        "..#.....",
        "..#.....",
        "..#.....",
        "..#.....",
        "#####...",
        ".###....",
        "..#.....",
        "........",
    ),
}

# Ванильных диагоналей в шрифте нет — для «слегка/резко» берём ту же
# оригинальную стрелку влево/вправо. Разницу направлений несёт текст.
_GLYPH_ALIAS = {
    "slight-left": "left",
    "sharp-left": "left",
    "slight-right": "right",
    "sharp-right": "right",
}

YELLOW = (245, 199, 66)


def glyph(direction: str) -> Image.Image:
    """8×8 глиф: оригинальная ванильная стрелка, перекрашенная в жёлтый."""
    direction = _GLYPH_ALIAS.get(direction, direction)
    rows = _GLYPHS[direction]
    img = Image.new("RGBA", (8, 8), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, cell in enumerate(row):
            if cell == "#":
                img.putpixel((x, y), YELLOW + (255,))
    return img


def font_atlas() -> Image.Image:
    """Все 8 глифов в ряд: 8 × 8 пикселей на глиф."""
    atlas = Image.new("RGBA", (8 * len(DIRECTIONS), 8), (0, 0, 0, 0))
    for i, direction in enumerate(DIRECTIONS):
        atlas.paste(glyph(direction), (i * 8, 0))
    return atlas
