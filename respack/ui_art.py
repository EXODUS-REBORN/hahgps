#!/usr/bin/env python3
"""Рисование интерфейсных текстур HahGPS.

Здесь две группы картинок:

* **2D-иконки 32×32** для кнопок и карточек в меню (`ICONS`): бумажные
  карточки точек, стрелки, звезда рейтинга, корзина, глаз слежения, радар,
  каталог, бирка, замок, «пусто» — всё плоское, как значки, а не предметы.
* **текстуры для 3D-иконок главного меню** (`main_map_top`, `main_book_cover`
  и т.д.): по ним `ui_models.py` собирает объёмные карту, книгу, витрину,
  лупу, шестерёнку и брелок.

Палитра — та же, что в остальном паке и в текстах плагина: тёмный графит,
синяя линейка GUITheme, бирюзовый акцент. Никаких новых цветов, кроме
«бумаги» карточек.
"""
from __future__ import annotations

import math

from PIL import Image, ImageDraw

import tracker_art

ICON_SIZE = 32
SS = 4  # суперсэмплинг: иконки 32×32, рисуем в 128 и уменьшаем

# Палитра: совпадает с GuiTheme.TONES и текстурами трекеров.
DEEP = (8, 18, 43)
NAVY = (14, 36, 80)
DARK = (20, 57, 126)
BLUE = (29, 81, 168)
SKY = (36, 100, 200)
BRIGHT = (63, 134, 255)
LIGHT = (102, 170, 255)
PALE = (143, 198, 255)
CYAN = (79, 209, 224)
MINT = (143, 233, 245)
INK = (6, 16, 36)
GRAPHITE = (37, 41, 48)
GRAPHITE_LT = (60, 66, 76)
GRAPHITE_DK = (20, 23, 28)
STEEL = (176, 181, 187)
STEEL_DK = (103, 109, 117)
STEEL_LT = (224, 228, 232)
WHITE = (242, 245, 250)
PAPER = (233, 238, 245)
PAPER_DK = (196, 205, 218)
ROAD = (201, 214, 230)
GRASS = (32, 58, 48)
WATER = (22, 54, 82)
FOREST = (26, 74, 62)


def rgb(hex_color: str) -> tuple[int, int, int]:
    value = hex_color.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))


def mix(a, b, t: float):
    t = max(0.0, min(1.0, t))
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


def shade(color, factor: float):
    return tuple(max(0, min(255, int(c * factor))) for c in color)


def _a(color, alpha: int = 255):
    if len(color) == 4:
        return tuple(color)
    return tuple(color) + (alpha,)


def _canvas(size: int = ICON_SIZE):
    image = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    return image, ImageDraw.Draw(image)


def _finish(image: Image.Image, size: int = ICON_SIZE) -> Image.Image:
    return image.resize((size, size), Image.LANCZOS)


def _rr(draw, box, radius, fill=None, outline=None, width=0):
    """Скруглённый прямоугольник в координатах 32×32 (масштабируется на SS)."""
    x0, y0, x1, y1 = (v * SS for v in box)
    if fill is not None:
        fill = _a(fill) if len(fill) == 3 else fill
    if outline is not None:
        outline = _a(outline) if len(outline) == 3 else outline
    draw.rounded_rectangle([x0, y0, x1, y1], radius=int(radius * SS), fill=fill,
                           outline=outline, width=int(round(width * SS)))


def _poly(draw, points, fill=None, outline=None, width=0):
    pts = [(x * SS, y * SS) for x, y in points]
    if fill is not None:
        fill = _a(fill) if len(fill) == 3 else fill
    if outline is not None:
        outline = _a(outline) if len(outline) == 3 else outline
    draw.polygon(pts, fill=fill, outline=outline, width=int(round(width * SS)))


def _line(draw, points, fill, width=1, joint="curve"):
    pts = [(x * SS, y * SS) for x, y in points]
    fill = _a(fill) if len(fill) == 3 else fill
    draw.line(pts, fill=fill, width=int(width * SS), joint=joint)


def _ellipse(draw, box, fill=None, outline=None, width=0):
    x0, y0, x1, y1 = (v * SS for v in box)
    if fill is not None:
        fill = _a(fill) if len(fill) == 3 else fill
    if outline is not None:
        outline = _a(outline) if len(outline) == 3 else outline
    draw.ellipse([x0, y0, x1, y1], fill=fill, outline=outline,
                 width=int(width * SS))


def _gloss(draw, box, color=PALE, alpha=150, width=1):
    """Верхний блик: короткая линия внутри скруглённой формы."""
    x0, y0, x1, y1 = box
    _line(draw, [(x0 + 2, y0 + 1.5), (x1 - 2, y0 + 1.5)],
          (*color, alpha), width)


def _vgradient(top, bottom, size=(ICON_SIZE * SS, ICON_SIZE * SS)):
    image = Image.new("RGBA", size, _a(top))
    draw = ImageDraw.Draw(image)
    h = size[1]
    for y in range(h):
        t = y / max(1, h - 1)
        draw.line([0, y, size[0], y], fill=_a(mix(top, bottom, t)))
    return image


def _gradient_icon(box, radius, top, bottom, outline=INK, width=1.5):
    """Скруглённая плашка с вертикальным градиентом (возвращает RGBA 32×32)."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    grad = _vgradient(top, bottom)
    mask = Image.new("L", grad.size, 0)
    md = ImageDraw.Draw(mask)
    x0, y0, x1, y1 = (v * SS for v in box)
    md.rounded_rectangle([x0, y0, x1, y1], radius=int(radius * SS), fill=255)
    base.paste(grad, (0, 0), mask)
    d = ImageDraw.Draw(base)
    _rr(d, box, radius, outline=outline, width=int(width * SS))
    return base


def _paste_icon(base: Image.Image, layer: Image.Image, box) -> None:
    """Наложить слой (в координатах 32×32) на base."""
    x0, y0, x1, y1 = (int(v * SS) for v in box)
    if layer.size != (x1 - x0, y1 - y0):
        layer = layer.resize((x1 - x0, y1 - y0), Image.LANCZOS)
    base.alpha_composite(layer, (x0, y0))


def _star_points(cx, cy, outer, inner, points=5):
    pts = []
    for i in range(points * 2):
        radius = outer if i % 2 == 0 else inner
        angle = -math.pi / 2 + i * math.pi / points
        pts.append((cx + radius * math.cos(angle), cy + radius * math.sin(angle)))
    return pts


# ---------------------------------------------------------------------------
# 2D-иконки кнопок и карточек
# ---------------------------------------------------------------------------

def icon_brand() -> Image.Image:
    """Логотип: синяя карточка со стрелкой вверх и двумя строками."""
    base = _gradient_icon((4, 5, 28, 27), 5, SKY, NAVY)
    d = ImageDraw.Draw(base)
    _gloss(d, (4, 5, 28, 27), PALE, 140)
    _poly(d, [(16, 8), (21.4, 15.4), (17.2, 15.4), (17.2, 20.6),
              (14.8, 20.6), (14.8, 15.4), (10.6, 15.4)],
          fill=WHITE, outline=INK, width=1)
    _line(d, [(11, 23.4), (21, 23.4)], (*PALE, 150), 1.2)
    _line(d, [(12.4, 25.4), (19.6, 25.4)], (*PALE, 110), 1.2)
    return _finish(base)


def icon_nav_back() -> Image.Image:
    """Шеврон влево — «Назад»."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    pts = [(20.5, 6.5), (10, 16), (20.5, 25.5), (23.5, 22.2), (16.2, 16),
           (23.5, 9.8)]
    _poly(d, pts, fill=INK)
    _poly(d, [(19.8, 8.2), (11.2, 16), (19.8, 23.8), (21.9, 21.9), (15.0, 16),
              (21.9, 10.1)], fill=BRIGHT)
    _line(d, [(11.6, 16), (18.6, 9.2)], (*MINT, 170), 1)
    return _finish(base)


def icon_nav_forward() -> Image.Image:
    """Шеврон вправо — «Вперёд»."""
    image = icon_nav_back().transpose(Image.FLIP_LEFT_RIGHT)
    return image


def icon_nav_close() -> Image.Image:
    """Крест — «Закрыть»."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    for pts in (((8.5, 8.5), (23.5, 23.5)), ((23.5, 8.5), (8.5, 23.5))):
        _line(d, pts, INK, 7)
    for pts in (((8.5, 8.5), (23.5, 23.5)), ((23.5, 8.5), (8.5, 23.5))):
        _line(d, pts, SKY, 4.6)
    for pts in (((9.4, 9.4), (15.0, 15.0)), ((22.6, 9.4), (17.0, 15.0))):
        _line(d, pts, LIGHT, 1.2)
    return _finish(base)


def icon_page_info() -> Image.Image:
    """Блокнот страниц — «страница N из M»."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    _rr(d, (10, 4, 26, 28), 2, fill=PAPER_DK, outline=INK, width=1)
    _rr(d, (7, 5, 23, 27), 2, fill=PAPER, outline=INK, width=1)
    _poly(d, [(19, 5), (23, 9), (19, 9)], fill=PAPER_DK, outline=INK, width=0.8)
    _rr(d, (7, 5, 10, 27), 1.5, fill=DARK, outline=INK, width=0.8)
    for y in (11, 14, 17, 20):
        _line(d, [(12, y), (20, y)], (125, 138, 158), 0.9)
    _line(d, [(12, 23.5), (17, 23.5)], (125, 138, 158), 0.9)
    return _finish(base)


def icon_poi_info() -> Image.Image:
    """Карточка точки: лист-досье с мини-картой и пином."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    _rr(d, (6, 4, 26, 29), 2.5, fill=PAPER, outline=INK, width=1)
    _rr(d, (6, 4, 26, 9.5), 2.5, fill=NAVY, outline=INK, width=1)
    _line(d, [(9, 6.8), (16, 6.8)], (*PALE, 170), 1.1)
    # Мини-карта.
    _rr(d, (9, 12, 23, 22), 1.5, fill=(205, 226, 210), outline=(120, 150, 160),
        width=0.8)
    _line(d, [(9.5, 18), (13, 16), (16, 18.5), (20, 15.5), (22.5, 17)],
          (120, 170, 200), 1.1)
    _ellipse(d, (16, 17.5, 21, 21.5), fill=WATER)
    # Пин.
    _poly(d, [(19, 12.5), (22, 16.5), (20.2, 18.6), (17.8, 18.6), (16, 16.5)],
          fill=CYAN, outline=INK, width=0.9)
    _ellipse(d, (18.1, 14.6, 19.9, 16.4), fill=INK)
    # Строки досье.
    for y in (24.5, 26.6):
        _line(d, [(9, y), (19, y)], (125, 138, 158), 1)
    return _finish(base)


def icon_poi_goto() -> Image.Image:
    """Маршрут к точке: пунктир и пин со стрелкой."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    dots = [(7, 26), (9.6, 23.2), (12.2, 20.4), (14.8, 17.6), (17.4, 14.8)]
    for radius, (x, y) in zip((1.5, 1.5, 1.4, 1.3, 1.2), dots):
        _ellipse(d, (x - radius, y - radius, x + radius, y + radius),
                 fill=BRIGHT, outline=INK, width=0.6)
    _poly(d, [(17, 4.5), (27, 4.5), (27, 14.5), (22, 9.5)],
          fill=MINT, outline=INK, width=1)
    _line(d, [(19.4, 7), (24.2, 7)], (*WHITE, 200), 1.1)
    _ellipse(d, (20.4, 8.6, 24.4, 12.6), fill=CYAN, outline=INK, width=0.8)
    _ellipse(d, (21.5, 9.7, 23.3, 11.5), fill=WHITE)
    return _finish(base)


def _star_icon(outer: float, inner: float, cy: float = 16.4,
               top=MINT, bottom=BLUE) -> Image.Image:
    """Звезда: градиентная заливка по маске, тёмный контур, блик сверху."""
    size = ICON_SIZE * SS
    pts = _star_points(16, cy, outer, inner)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).polygon([(x * SS, y * SS) for x, y in pts], fill=255)
    base = _vgradient(top, bottom)
    base.putalpha(mask)
    d = ImageDraw.Draw(base)
    _poly(d, pts, outline=INK, width=1.3)
    # Блик: узкая полоса по левым лучам, только внутри звезды.
    gloss = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gd = ImageDraw.Draw(gloss)
    gd.polygon([(16 * SS, cy * SS - outer * SS),
                (16 * SS, cy * SS),
                ((16 - inner * 0.8) * SS, (cy - inner * 0.35) * SS)],
               fill=(255, 255, 255, 120))
    base.alpha_composite(Image.composite(gloss, Image.new("RGBA", (size, size)),
                                         mask))
    return _finish(base)


def icon_poi_rate() -> Image.Image:
    """Звезда рейтинга."""
    return _star_icon(12.2, 5.2, top=MINT, bottom=SKY)


def icon_poi_delete() -> Image.Image:
    """Корзина — удалить точку."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    _line(d, [(9, 8.5), (23, 8.5)], GRAPHITE_LT, 2.4)
    _poly(d, [(12, 5.6), (20, 5.6), (21, 8.4), (11, 8.4)],
          fill=STEEL, outline=INK, width=0.9)
    body = [(9.6, 10.4), (22.4, 10.4), (20.6, 28), (11.4, 28)]
    _poly(d, body, fill=GRAPHITE, outline=INK, width=1)
    _line(d, [(9.9, 10.6), (22.1, 10.6)], STEEL_LT, 1.2)
    for x in (13.2, 16, 18.8):
        _line(d, [(x, 12.6), (x + (0.4 if x < 16 else -0.4), 26.4)],
              (120, 130, 142), 1.1)
    _line(d, [(12.6, 17.2), (19.4, 24.0)], CYAN, 1.5)
    _line(d, [(19.4, 17.2), (12.6, 24.0)], CYAN, 1.5)
    return _finish(base)


def icon_itrak_route() -> Image.Image:
    """Компас с пунктиром — маршрут к трекеру."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    _ellipse(d, (4, 4, 28, 28), fill=NAVY, outline=INK, width=1.5)
    _ellipse(d, (5.6, 5.6, 26.4, 26.4), outline=STEEL, width=1.4)
    _ellipse(d, (4, 4, 28, 28), outline=STEEL_DK, width=0.7)
    # Стрелка: одна половина светлее.
    _poly(d, [(16, 5.6), (20.4, 16), (16, 19.4)], fill=PALE)
    _poly(d, [(16, 5.6), (11.6, 16), (16, 19.4)], fill=BRIGHT)
    _poly(d, [(16, 19.4), (20.4, 16), (16, 26.4)], fill=(70, 110, 180))
    _poly(d, [(16, 19.4), (11.6, 16), (16, 26.4)], fill=(52, 86, 150))
    _ellipse(d, (14.6, 14.6, 17.4, 17.4), fill=WHITE, outline=INK, width=0.6)
    for x, y in ((3.4, 27.4), (6.2, 29.0)):
        _ellipse(d, (x - 1.2, y - 1.2, x + 1.2, y + 1.2), fill=CYAN,
                 outline=INK, width=0.5)
    return _finish(base)


def _eye_shape(d, color, pupil=True):
    _poly(d, [(3.5, 16), (8, 10.4), (16, 8.2), (24, 10.4), (28.5, 16),
              (24, 21.6), (16, 23.8), (8, 21.6)],
          fill=INK)
    _poly(d, [(5.2, 16), (9.2, 11.8), (16, 10.1), (22.8, 11.8), (26.8, 16),
              (22.8, 20.2), (16, 21.9), (9.2, 20.2)],
          fill=WHITE)
    _ellipse(d, (11.4, 10.6, 20.6, 21.4), fill=color)
    _ellipse(d, (11.4, 10.6, 20.6, 21.4), outline=INK, width=0.7)
    if pupil:
        _ellipse(d, (14.2, 13.0, 17.8, 19.0), fill=INK)
        _ellipse(d, (15.0, 13.8, 16.4, 15.6), fill=WHITE)
    _line(d, [(9.6, 12.4), (13.2, 12.0)], (*PALE, 220), 1)


def icon_itrak_watch() -> Image.Image:
    """Глаз — слежение включено."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    _eye_shape(d, CYAN)
    return _finish(base)


def icon_itrak_watch_off() -> Image.Image:
    """Глаз с диагональю — слежение выключено."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    _eye_shape(d, (128, 140, 158), pupil=False)
    _line(d, [(5.5, 26.5), (26.5, 5.5)], INK, 4.4)
    _line(d, [(6.4, 25.6), (25.6, 6.4)], LIGHT, 2.2)
    return _finish(base)


def icon_itrak_take() -> Image.Image:
    """Сундук — забрать трекер."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    _rr(d, (4.5, 11, 27.5, 28), 2, fill=DARK, outline=INK, width=1.3)
    _rr(d, (4.5, 7.5, 27.5, 16), 2.5, fill=BLUE, outline=INK, width=1.3)
    _line(d, [(5.4, 9.2), (26.6, 9.2)], (*LIGHT, 190), 1.2)
    _line(d, [(4.7, 16), (27.3, 16)], INK, 1.6)
    _line(d, [(13.4, 11.6), (13.4, 25.4)], (*PALE, 90), 1)
    _rr(d, (13.6, 14.6, 18.4, 19.4), 1.2, fill=MINT, outline=INK, width=0.9)
    _ellipse(d, (15.2, 16.0, 16.8, 17.6), fill=INK)
    return _finish(base)


def icon_itrak_locked() -> Image.Image:
    """Замок — трекер далеко, забрать нельзя."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    arc_box = [10.4 * SS, 5.4 * SS, 21.6 * SS, 20 * SS]
    d.arc(arc_box, 180, 360, fill=_a(STEEL), width=int(2.6 * SS))
    _line(d, [(10.6, 12.6), (10.6, 16.4)], STEEL, 2.6)
    _line(d, [(21.4, 12.6), (21.4, 16.4)], STEEL_DK, 2.6)
    _rr(d, (7.5, 14.5, 24.5, 28), 3.5, fill=GRAPHITE, outline=INK, width=1.3)
    _line(d, [(8.5, 16.4), (23.5, 16.4)], GRAPHITE_LT, 1.2)
    _ellipse(d, (14.4, 18.4, 17.6, 21.6), fill=CYAN, outline=INK, width=0.7)
    _line(d, [(16, 21.2), (16, 24.8)], CYAN, 1.6)
    return _finish(base)


def icon_itrak_remove() -> Image.Image:
    """Корзина с жетоном — удалить трекер."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    _line(d, [(8.4, 10.2), (23.6, 10.2)], GRAPHITE_LT, 2.2)
    _poly(d, [(11.4, 7.4), (20.6, 7.4), (21.6, 10.1), (10.4, 10.1)],
          fill=STEEL, outline=INK, width=0.8)
    _poly(d, [(8.6, 11.6), (23.4, 11.6), (21.6, 29), (10.4, 29)],
          fill=GRAPHITE, outline=INK, width=1.1)
    for x in (12.6, 19.4):
        _line(d, [(x, 13.4), (x + (-0.4 if x > 16 else 0.4), 27.4)],
              (112, 122, 134), 1.1)
    # Жетон трекера с головой крипера.
    _ellipse(d, (10.6, 1.6, 21.4, 12.4), fill=BLUE, outline=INK, width=1)
    _ellipse(d, (12.0, 3.0, 20.0, 11.0), fill=WHITE, outline=(180, 188, 198),
             width=0.6)
    _rr(d, (13.6, 4.6, 18.4, 9.4), 1, fill=(85, 195, 107), outline=(37, 92, 46),
        width=0.7)
    _line(d, [(14.2, 5.6), (17.8, 5.6)], (118, 218, 128), 0.7)
    _ellipse(d, (14.2, 6.2, 15.6, 7.6), fill=(10, 24, 12))
    _ellipse(d, (16.4, 6.2, 17.8, 7.6), fill=(10, 24, 12))
    _line(d, [(14.6, 8.2), (17.4, 8.2)], (10, 24, 12), 0.8)
    return _finish(base)


def icon_itrak_rename() -> Image.Image:
    """Бирка — переименовать трекер."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    # Шнурок.
    _line(d, [(5.4, 7.2), (10.6, 12.4)], (150, 160, 176), 1.4)
    _ellipse(d, (4.2, 5.6, 7.6, 9.0), outline=(150, 160, 176), width=1.2)
    _poly(d, [(9, 10), (13.5, 5.5), (27.5, 12), (27.5, 24), (22, 28),
              (9.5, 24)], fill=PAPER, outline=INK, width=1.2)
    _poly(d, [(10.4, 11.6), (14.2, 8), (26, 13.4), (26, 22.6), (21.6, 25.8),
              (10.6, 22.6)], fill=WHITE, outline=PAPER_DK, width=0.7)
    _ellipse(d, (10.6, 14.4, 13.4, 17.2), fill=PAPER_DK, outline=INK, width=0.7)
    _line(d, [(15, 15.4), (24, 18.6)], (125, 138, 158), 1.1)
    _line(d, [(14.4, 18.4), (21.4, 21.0)], (125, 138, 158), 1.1)
    return _finish(base)


def icon_itrak_catalog() -> Image.Image:
    """Книга-каталог с жетоном на обложке."""
    base = _gradient_icon((5, 4.5, 27, 28), 2.5, DARK, DEEP)
    d = ImageDraw.Draw(base)
    _gloss(d, (5, 4.5, 27, 28), PALE, 120)
    _rr(d, (5.4, 4.9, 9.4, 27.6), 1.5, fill=NAVY, outline=INK, width=0.8)
    _poly(d, [(16.4, 3.4), (19.4, 3.4), (19.4, 10.4), (17.9, 8.8),
              (16.4, 10.4)], fill=CYAN, outline=INK, width=0.8)
    _ellipse(d, (12.4, 11.4, 22.4, 21.4), fill=BLUE, outline=INK, width=0.9)
    _ellipse(d, (13.8, 12.8, 21.0, 20.0), fill=WHITE, outline=(176, 184, 196),
             width=0.7)
    _rr(d, (15.6, 14.6, 19.2, 18.2), 0.8, fill=(85, 195, 107),
        outline=(37, 92, 46), width=0.6)
    _ellipse(d, (16.0, 15.2, 17.0, 16.2), fill=(10, 24, 12))
    _ellipse(d, (17.8, 15.2, 18.8, 16.2), fill=(10, 24, 12))
    _line(d, [(14, 23.6), (23.6, 23.6)], (*PALE, 150), 1.2)
    return _finish(base)


def icon_itrak_scan() -> Image.Image:
    """Экран радара — сканировать трекеры."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    _ellipse(d, (3.5, 3.5, 28.5, 28.5), fill=(5, 17, 27), outline=INK, width=1.4)
    _ellipse(d, (4.6, 4.6, 27.4, 27.4), outline=(28, 121, 138), width=1.1)
    for radius in (4.4, 8.2, 11.6):
        _ellipse(d, (16 - radius, 16 - radius, 16 + radius, 16 + radius),
                 outline=(*_a(CYAN)[:3], 120), width=0.7)
    _line(d, [(5.2, 16), (26.8, 16)], (*CYAN, 70), 0.7)
    _line(d, [(16, 5.2), (16, 26.8)], (*CYAN, 70), 0.7)
    sector = [(16 + 12 * math.cos(math.radians(a)),
               16 + 12 * math.sin(math.radians(a)))
              for a in range(300, 351, 10)]
    _poly(d, [(16, 16)] + sector, fill=(*CYAN, 60))
    for bx, by in ((-7, 4), (5, -6), (7, 7), (-4, -5)):
        _ellipse(d, (16 + bx - 1.2, 16 + by - 1.2, 16 + bx + 1.2, 16 + by + 1.2),
                 fill=MINT, outline=INK, width=0.4)
    _ellipse(d, (14.8, 14.8, 17.2, 17.2), fill=WHITE)
    return _finish(base)


def icon_itrak_give() -> Image.Image:
    """Подарочная коробка — каталог выдачи."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    _rr(d, (4.5, 11.4, 27.5, 28), 2.2, fill=DARK, outline=INK, width=1.2)
    _rr(d, (3.5, 6.6, 28.5, 12.4), 2.0, fill=BLUE, outline=INK, width=1.2)
    _line(d, [(4.4, 8.2), (27.6, 8.2)], (*LIGHT, 190), 1.1)
    _rr(d, (13.4, 6.8, 18.6, 27.8), 1.0, fill=MINT, outline=INK, width=0.9)
    _poly(d, [(14.4, 5.4), (11.2, 2.2), (13.6, 1.6), (16.2, 4.6)],
          fill=CYAN, outline=INK, width=0.7)
    _poly(d, [(17.6, 5.4), (20.8, 2.2), (18.4, 1.6), (15.8, 4.6)],
          fill=CYAN, outline=INK, width=0.7)
    _ellipse(d, (15.0, 3.4, 17.6, 6.0), fill=MINT, outline=INK, width=0.7)
    _line(d, [(6.6, 14.4), (10.5, 14.4)], (*PALE, 140), 1)
    return _finish(base)


def icon_itrak_refresh() -> Image.Image:
    """Круговые стрелки — обновить список."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    d.arc([6 * SS, 6 * SS, 26 * SS, 26 * SS], 25, 150, fill=_a(INK),
          width=int(3.4 * SS))
    d.arc([6 * SS, 6 * SS, 26 * SS, 26 * SS], 205, 330, fill=_a(INK),
          width=int(3.4 * SS))
    d.arc([6 * SS, 6 * SS, 26 * SS, 26 * SS], 25, 150, fill=_a(PALE),
          width=int(1.8 * SS))
    d.arc([6 * SS, 6 * SS, 26 * SS, 26 * SS], 205, 330, fill=_a(BRIGHT),
          width=int(1.8 * SS))
    _poly(d, [(25.6, 5.6), (27.6, 13.4), (19.4, 10.6)], fill=LIGHT, outline=INK,
          width=0.8)
    _poly(d, [(6.4, 26.4), (4.4, 18.6), (12.6, 21.4)], fill=SKY, outline=INK,
          width=0.8)
    return _finish(base)


def icon_itrak_empty() -> Image.Image:
    """Пунктирный жетон — трекеров нет."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    for start in range(0, 360, 45):
        d.arc([6 * SS, 6 * SS, 26 * SS, 26 * SS], start + 6, start + 39,
              fill=_a((118, 130, 148)), width=int(2.4 * SS))
    _ellipse(d, (14.4, 14.4, 17.6, 17.6), outline=(118, 130, 148), width=1.1)
    return _finish(base)


def icon_poi_type_compass() -> Image.Image:
    """Тип точки: ориентир — компас."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    _ellipse(d, (5, 5, 27, 27), fill=NAVY, outline=INK, width=1.3)
    _ellipse(d, (6.4, 6.4, 25.6, 25.6), outline=STEEL, width=1.1)
    _poly(d, [(16, 8), (19.6, 16), (16, 18.6)], fill=PALE)
    _poly(d, [(16, 8), (12.4, 16), (16, 18.6)], fill=BRIGHT)
    _poly(d, [(16, 18.6), (19.6, 16), (16, 24)], fill=(64, 100, 168))
    _poly(d, [(16, 18.6), (12.4, 16), (16, 24)], fill=(48, 78, 138))
    _ellipse(d, (14.8, 14.8, 17.2, 17.2), fill=WHITE)
    return _finish(base)


def icon_poi_type_beacon() -> Image.Image:
    """Тип точки: инфраструктура — маяк с лучом."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    _poly(d, [(11.6, 3), (20.4, 3), (17.2, 11), (14.8, 11)],
          fill=(*CYAN, 90), outline=(*CYAN, 200), width=0.8)
    _poly(d, [(8.5, 25), (23.5, 25), (21.5, 29), (10.5, 29)],
          fill=GRAPHITE, outline=INK, width=1)
    _rr(d, (12.4, 10.6, 19.6, 25.4), 1.5, fill=BLUE, outline=INK, width=1)
    _line(d, [(13.6, 12.2), (13.6, 23.6)], (*PALE, 130), 1.1)
    _poly(d, [(16, 13.4), (18.4, 17.0), (16, 20.6), (13.6, 17.0)], fill=MINT,
          outline=INK, width=0.8)
    _ellipse(d, (14.4, 21.8, 17.6, 24.2), fill=INK)
    return _finish(base)


def icon_poi_type_book() -> Image.Image:
    """Тип точки: знание — книга."""
    base = _gradient_icon((6, 6.5, 26, 26), 1.6, DARK, NAVY)
    d = ImageDraw.Draw(base)
    _rr(d, (6, 6.5, 10.4, 26), 1.2, fill=NAVY, outline=INK, width=0.8)
    _line(d, [(11.6, 10), (23, 10)], (*PALE, 160), 1.1)
    _line(d, [(11.6, 13), (21, 13)], (*PALE, 120), 1.1)
    _line(d, [(11.6, 16), (23, 16)], (*PALE, 120), 1.1)
    _line(d, [(14.4, 19.6), (22.4, 19.6)], fill=CYAN, width=1.2)
    return _finish(base)


def icon_poi_type_pearl() -> Image.Image:
    """Тип точки: лут — жемчужина."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    _ellipse(d, (6, 6, 26, 26), fill=NAVY, outline=INK, width=1.2)
    _ellipse(d, (7.5, 7.5, 24.5, 24.5), fill=(38, 74, 120), outline=STEEL_DK,
             width=0.8)
    _ellipse(d, (12.6, 12.6, 19.4, 19.4), fill=(198, 242, 248),
             outline=(36, 90, 110), width=0.8)
    _ellipse(d, (13.6, 13.2, 17.0, 16.0), fill=WHITE)
    _line(d, [(9.4, 9.4), (12.0, 12.0)], (*PALE, 180), 0.9)
    return _finish(base)


def icon_poi_type_star() -> Image.Image:
    """Тип точки: событие — звезда."""
    return _star_icon(11.2, 4.7, top=PALE, bottom=NAVY)


def icon_poi_type_paper() -> Image.Image:
    """Тип точки: записка — лист."""
    base = Image.new("RGBA", (ICON_SIZE * SS, ICON_SIZE * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    _poly(d, [(7, 5), (20, 5), (25, 10), (25, 27), (7, 27)],
          fill=PAPER, outline=INK, width=1.2)
    _poly(d, [(20, 5), (20, 10), (25, 10)], fill=PAPER_DK, outline=INK, width=0.9)
    for y in (13, 16.4, 19.8):
        _line(d, [(10.4, y), (21.4, y)], (125, 138, 158), 1.1)
    return _finish(base)


# ---------------------------------------------------------------------------
# Текстуры для 3D-иконок главного меню
# ---------------------------------------------------------------------------

def _upscaled(size: int, scale: int):
    image = Image.new("RGBA", (size * scale, size * scale), (0, 0, 0, 0))
    return image, ImageDraw.Draw(image)


def _downscale(image: Image.Image, size: int) -> Image.Image:
    if image.size == (size, size):
        return image
    return image.resize((size, size), Image.LANCZOS)


def main_map_top(size: int = 64) -> Image.Image:
    """Ночная навигационная карта: поля, вода, дороги, пин и роза ветров."""
    scale = 8 if size >= 64 else 4
    image, draw = _upscaled(size, scale)
    edge = size * scale

    # Поле и вода.
    draw.rectangle([0, 0, edge, edge], fill=_a(GRASS))
    draw.rectangle([0, int(edge * 0.62), edge, edge], fill=_a(WATER))
    draw.rectangle([int(edge * 0.0), int(edge * 0.60), edge, int(edge * 0.63)],
                   fill=_a((58, 92, 76)))

    # Пятна леса — мелкие квадратики в шахматном порядке, как на карте.
    forest_cells = [(2, 1), (3, 1), (2, 2), (6, 2), (7, 2), (1, 3), (2, 3),
                    (5, 1), (6, 1), (4, 3), (5, 3)]
    cell = size // 8
    for cx, cy in forest_cells:
        x, y = cx * cell, cy * cell
        draw.rectangle([x * scale, y * scale, (x + cell) * scale,
                        (y + cell) * scale], fill=_a(FOREST))

    # Дороги: пара ломаных линий.
    roads = [
        [(0, 0.38), (0.22, 0.34), (0.38, 0.46), (0.55, 0.40), (0.72, 0.50),
         (1.0, 0.44)],
        [(0.30, 0.0), (0.34, 0.18), (0.28, 0.34), (0.40, 0.52), (0.34, 0.72),
         (0.42, 1.0)],
        [(0.62, 0.0), (0.66, 0.16), (0.60, 0.34), (0.70, 0.56), (0.68, 0.74),
         (0.76, 1.0)],
    ]
    for road in roads:
        pts = [(x * edge, y * edge) for x, y in road]
        draw.line(pts, fill=_a(ROAD), width=2 * scale, joint="curve")
    # Пунктирная тропа.
    for t in range(0, 11):
        x = 0.12 + t * 0.075
        y = 0.78 - t * 0.035
        draw.rectangle([x * edge - scale, y * edge - scale,
                        x * edge + scale, y * edge + scale], fill=_a(MINT))

    # Река.
    draw.line([(0.0, 0.66), (0.18, 0.70), (0.38, 0.66), (0.62, 0.72),
               (1.0, 0.68)], fill=_a((36, 86, 126)), width=3 * scale, joint="curve")

    # Пин в точке назначения.
    px, py = int(0.68 * edge), int(0.40 * edge)
    r = int(edge * 0.075)
    draw.ellipse([px - r, py - r - 2 * r, px + r, py + r - 2 * r], fill=_a(CYAN))
    draw.polygon([(px - r, py - 2 * r), (px + r, py - 2 * r), (px, py + r)],
                 fill=_a(CYAN))
    draw.ellipse([px - r // 2, py - 2 * r + r // 2, px + r // 2, py - r],
                 fill=_a(WHITE))
    draw.line([(px - r, py - 2 * r), (px + r, py - 2 * r)], fill=_a(DEEP),
              width=scale)

    # Роза ветров: окружность с лучом на север.
    cx, cy = int(edge * 0.16), int(edge * 0.14)
    rr = int(edge * 0.085)
    draw.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], outline=_a(WHITE),
                 width=scale)
    draw.polygon([(cx, cy - rr), (cx + rr // 2, cy + rr // 2),
                  (cx, cy + rr // 3), (cx - rr // 2, cy + rr // 2)],
                 fill=_a(WHITE))

    # Сетка карты и рамка.
    grid = max(2, size // 8) * scale
    for x in range(0, edge, grid):
        draw.line([(x, 0), (x, edge)], fill=(0, 0, 0, 26))
    for y in range(0, edge, grid):
        draw.line([(0, y), (edge, y)], fill=(0, 0, 0, 26))
    draw.rectangle([0, 0, edge - 1, edge - 1], outline=_a(DEEP), width=2 * scale)
    return _downscale(image, size)


def main_map_side(size: int = 16) -> Image.Image:
    """Срез карты: бумага и тёмный торец."""
    image = Image.new("RGBA", (size, size), _a(PAPER_DK))
    draw = ImageDraw.Draw(image)
    for y in range(size):
        shade_color = mix(PAPER, PAPER_DK, y / max(1, size - 1))
        draw.line([0, y, size - 1, y], fill=_a(shade_color))
    draw.line([0, 0, size - 1, 0], fill=_a(WHITE))
    draw.line([0, size - 1, size - 1, size - 1], fill=_a((120, 132, 148)))
    for x in range(2, size, 4):
        draw.point((x, size // 2), fill=_a((150, 162, 178)))
    return image


def main_map_back(size: int = 32) -> Image.Image:
    """Обратная сторона карты: тёмная кожа с тиснёной эмблемой."""
    image = Image.new("RGBA", (size, size), _a(DEEP))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle([0, 0, size - 1, size - 1], radius=3,
                           outline=_a(INK), width=2)
    draw.rounded_rectangle([3, 3, size - 4, size - 4], radius=2,
                           outline=_a(NAVY), width=1)
    cx, cy = size // 2, size // 2
    draw.polygon([(cx, cy - 8), (cx + 5, cy), (cx + 1, cy), (cx + 1, cy + 7),
                  (cx - 1, cy + 7), (cx - 1, cy), (cx - 5, cy)],
                 fill=_a(NAVY))
    draw.line([(cx - 6, cy + 10), (cx + 6, cy + 10)], fill=_a(NAVY))
    draw.line([(cx - 4, cy + 12), (cx + 4, cy + 12)], fill=_a(NAVY))
    return image


def main_book_cover(size: int = 64) -> Image.Image:
    """Обложка книги-каталога: синяя ткань, тиснение, эмблема."""
    scale = 8 if size >= 64 else 4
    image, draw = _upscaled(size, scale)
    edge = size * scale
    # Ткань с вертикальным градиентом.
    for y in range(edge):
        t = y / max(1, edge - 1)
        draw.line([0, y, edge, y], fill=_a(mix(DARK, DEEP, t)))
    # Тискнение: двойная рамка.
    draw.rectangle([2 * scale, 2 * scale, edge - 3 * scale, edge - 3 * scale],
                   outline=_a(NAVY), width=2 * scale)
    draw.rectangle([5 * scale, 5 * scale, edge - 6 * scale, edge - 6 * scale],
                   outline=_a(SKY), width=scale)
    # Эмблема: компас-стрелка в круге.
    cx, cy, r = edge // 2, int(edge * 0.42), int(edge * 0.18)
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], outline=_a(PALE),
                 width=2 * scale)
    draw.polygon([(cx, cy - r + 2 * scale), (cx + r // 2, cy),
                  (cx, cy + r - 2 * scale), (cx - r // 2, cy)],
                 fill=_a(WHITE))
    draw.polygon([(cx, cy - r + 2 * scale), (cx + r // 2, cy), (cx, cy)],
                 fill=_a(CYAN))
    # Подпись: три короткие строки.
    for i, width in enumerate((int(edge * 0.34), int(edge * 0.26), int(edge * 0.30))):
        y = int(edge * 0.66) + i * int(edge * 0.055)
        draw.line([cx - width // 2, y, cx + width // 2, y],
                  fill=(*PALE, 170), width=max(1, scale // 2))
    # Накладка-корешок слева.
    draw.rectangle([0, 0, 4 * scale, edge], fill=_a(NAVY))
    draw.line([4 * scale, 0, 4 * scale, edge], fill=_a(INK), width=scale)
    draw.line([scale, 0, scale, edge], fill=_a(DARK), width=scale)
    return _downscale(image, size)


def main_book_pages(size: int = 16) -> Image.Image:
    """Страничный блок: полосы бумаги."""
    image = Image.new("RGBA", (size, size), _a(PAPER))
    draw = ImageDraw.Draw(image)
    for y in range(size):
        if y % 3 == 0:
            draw.line([0, y, size - 1, y], fill=_a(PAPER_DK))
        elif y % 3 == 1:
            draw.line([0, y, size - 1, y], fill=_a(WHITE))
    draw.line([0, 0, size - 1, 0], fill=_a((255, 255, 255, 210)))
    draw.line([0, size - 1, size - 1, size - 1], fill=_a((150, 160, 176)))
    return image


def main_book_tabs(size: int = 16) -> Image.Image:
    """Торец с закладками: три цветные полоски."""
    image = Image.new("RGBA", (size, size), _a(PAPER_DK))
    draw = ImageDraw.Draw(image)
    draw.rectangle([0, 0, size - 1, size - 1], fill=_a(DEEP))
    for y, color in ((2, CYAN), (7, BRIGHT), (12, PALE)):
        draw.rectangle([0, y, size - 1, y + 2], fill=_a(color))
        draw.line([0, y, size - 1, y], fill=_a(WHITE))
    return image


def main_book_spine(size: int = 16) -> Image.Image:
    image = Image.new("RGBA", (size, size), _a(NAVY))
    draw = ImageDraw.Draw(image)
    draw.line([0, 0, 0, size - 1], fill=_a(INK), width=2)
    draw.line([size - 2, 0, size - 2, size - 1], fill=_a(DARK), width=2)
    draw.line([size // 2, 2, size // 2, size - 3], fill=_a(SKY), width=1)
    return image


def main_catalog_glass(size: int = 32) -> Image.Image:
    """Стекло витрины: голубое, полупрозрачное, с бликом."""
    image = Image.new("RGBA", (size, size), (150, 214, 240, 118))
    draw = ImageDraw.Draw(image)
    draw.rectangle([0, 0, size - 1, size - 1], outline=(224, 246, 255, 190),
                   width=2)
    draw.line([(2, size - 4), (size - 4, 2)], fill=(255, 255, 255, 150),
              width=3)
    draw.line([(2, size - 9), (size - 9, 2)], fill=(255, 255, 255, 90),
              width=1)
    draw.rectangle([0, 0, size - 1, size - 1], outline=(70, 130, 170, 180),
                   width=1)
    return image


def main_catalog_base(size: int = 32) -> Image.Image:
    """Подставка витрины: тёмный металл с кантом и болтами."""
    image = Image.new("RGBA", (size, size), _a(GRAPHITE))
    draw = ImageDraw.Draw(image)
    for y in range(size):
        t = y / max(1, size - 1)
        draw.line([0, y, size - 1, y],
                  fill=_a(mix(GRAPHITE_LT, GRAPHITE_DK, t)))
    draw.rectangle([0, 0, size - 1, size - 1], outline=_a(INK), width=2)
    draw.rectangle([2, 2, size - 3, size - 3], outline=_a((74, 82, 94)), width=1)
    for cx, cy in ((5, 5), (size - 6, 5), (5, size - 6), (size - 6, size - 6)):
        draw.ellipse([cx - 1, cy - 1, cx + 1, cy + 1], fill=_a(STEEL_DK))
    return image


def main_catalog_band(size: int = 16) -> Image.Image:
    image = Image.new("RGBA", (size, size), _a(CYAN))
    draw = ImageDraw.Draw(image)
    draw.line([0, 0, size - 1, 0], fill=_a(MINT), width=2)
    draw.line([0, size - 1, size - 1, size - 1], fill=_a((28, 121, 138)),
              width=2)
    draw.line([0, size // 2, size - 1, size // 2], fill=(255, 255, 255, 120),
              width=1)
    return image


def main_search_ring(size: int = 32) -> Image.Image:
    """Металл обода лупы: светлая сталь с бликом и тенью."""
    image = Image.new("RGBA", (size, size), _a(STEEL))
    draw = ImageDraw.Draw(image)
    for y in range(size):
        t = y / max(1, size - 1)
        draw.line([0, y, size - 1, y],
                  fill=_a(mix(STEEL_LT, STEEL_DK, t)))
    draw.line([0, 1, size - 1, 1], fill=_a(WHITE), width=2)
    draw.line([0, size - 2, size - 1, size - 2], fill=_a((80, 86, 96)))
    return image


def main_search_handle(size: int = 16) -> Image.Image:
    image = Image.new("RGBA", (size, size), _a(GRAPHITE))
    draw = ImageDraw.Draw(image)
    for y in range(size):
        t = y / max(1, size - 1)
        draw.line([0, y, size - 1, y], fill=_a(mix(GRAPHITE_LT, GRAPHITE_DK, t)))
    draw.line([1, 1, size - 2, 1], fill=_a(STEEL_DK), width=1)
    draw.rectangle([0, 0, size - 1, size - 1], outline=_a(INK), width=1)
    return image


def main_search_lens(size: int = 32) -> Image.Image:
    """Линза: голубое стекло с диагональным бликом."""
    image = Image.new("RGBA", (size, size), (168, 220, 246, 150))
    draw = ImageDraw.Draw(image)
    draw.ellipse([0, 0, size - 1, size - 1], outline=(236, 250, 255, 220),
                 width=2)
    draw.line([(5, size - 9), (size - 10, 4)], fill=(255, 255, 255, 130),
              width=4)
    draw.line([(3, size - 14), (size - 15, 2)], fill=(255, 255, 255, 80),
              width=2)
    return image


def main_gear_face(size: int = 64) -> Image.Image:
    """Лицо шестерёнки: синяя сталь, насечки и болты."""
    scale = 8 if size >= 64 else 4
    image, draw = _upscaled(size, scale)
    edge = size * scale
    for y in range(edge):
        t = y / max(1, edge - 1)
        draw.line([0, y, edge, y], fill=_a(mix((120, 146, 186), (44, 58, 82), t)))
    draw.ellipse([0, 0, edge, edge], fill=None)
    # Насечки: радиальные короткие штрихи.
    cx = cy = edge / 2
    for i in range(24):
        angle = i * math.pi / 12
        x0 = cx + math.cos(angle) * edge * 0.10
        y0 = cy + math.sin(angle) * edge * 0.10
        x1 = cx + math.cos(angle) * edge * 0.44
        y1 = cy + math.sin(angle) * edge * 0.44
        draw.line([(x0, y0), (x1, y1)], fill=(255, 255, 255, 36), width=scale)
    for cx_, cy_ in ((0.24, 0.24), (0.76, 0.24), (0.24, 0.76), (0.76, 0.76)):
        x, y = int(cx_ * edge), int(cy_ * edge)
        r = int(edge * 0.035)
        draw.ellipse([x - r, y - r, x + r, y + r], fill=_a(DEEP))
        draw.ellipse([x - r, y - r, x + r, y + r], outline=_a(INK), width=scale)
    # Кольцо обода.
    draw.ellipse([2 * scale, 2 * scale, edge - 2 * scale, edge - 2 * scale],
                 outline=_a(INK), width=2 * scale)
    draw.ellipse([3 * scale, 3 * scale, edge - 4 * scale, edge - 4 * scale],
                 outline=_a((176, 202, 236)), width=scale)
    return _downscale(image, size)


def main_gear_side(size: int = 16) -> Image.Image:
    image = Image.new("RGBA", (size, size), _a((58, 74, 102)))
    draw = ImageDraw.Draw(image)
    for y in range(size):
        t = y / max(1, size - 1)
        draw.line([0, y, size - 1, y],
                  fill=_a(mix((96, 116, 150), (36, 46, 66), t)))
    draw.line([0, 0, size - 1, 0], fill=_a((176, 202, 236)))
    draw.line([0, size - 1, size - 1, size - 1], fill=_a(INK))
    return image


def main_gear_hub(size: int = 16) -> Image.Image:
    image = Image.new("RGBA", (size, size), _a((84, 100, 128)))
    draw = ImageDraw.Draw(image)
    draw.rectangle([0, 0, size - 1, size - 1], outline=_a(INK), width=1)
    draw.rectangle([2, 2, size - 3, size - 3], outline=_a((156, 178, 208)),
                   width=1)
    draw.rectangle([size // 2 - 1, 3, size // 2 + 1, size - 4], fill=_a(INK))
    draw.rectangle([3, size // 2 - 1, size - 4, size // 2 + 1], fill=_a(INK))
    return image


def main_tag_ring(size: int = 16) -> Image.Image:
    image = Image.new("RGBA", (size, size), _a(STEEL))
    draw = ImageDraw.Draw(image)
    for y in range(size):
        t = y / max(1, size - 1)
        draw.line([0, y, size - 1, y], fill=_a(mix(STEEL_LT, STEEL_DK, t)))
    draw.line([0, 1, size - 1, 1], fill=_a(WHITE))
    draw.line([0, size - 2, size - 1, size - 2], fill=_a((86, 92, 102)))
    return image


def main_compass_face(size: int = 64) -> Image.Image:
    """Циферблат на карте: круглый, углы прозрачные, стрелка и деления."""
    scale = 8 if size >= 64 else 4
    image, draw = _upscaled(size, scale)
    edge = size * scale
    # Стальной обод.
    draw.ellipse([0, 0, edge - 1, edge - 1], fill=_a(STEEL_DK))
    draw.ellipse([scale, scale, edge - 1 - scale, edge - 1 - scale],
                 fill=_a(STEEL))
    draw.ellipse([scale, scale, edge - 1 - scale, edge - 1 - scale],
                 outline=_a(WHITE), width=scale)
    # Синий циферблат.
    inset = 4 * scale
    draw.ellipse([inset, inset, edge - 1 - inset, edge - 1 - inset],
                 fill=_a(NAVY), outline=_a(DEEP), width=scale)
    inner = inset + 3 * scale
    draw.ellipse([inner, inner, edge - 1 - inner, edge - 1 - inner],
                 outline=_a(DARK), width=scale)
    # Деления по кругу.
    cx = cy = edge / 2
    for i in range(12):
        angle = i * math.pi / 6 - math.pi / 2
        major = i % 3 == 0
        r0 = edge * (0.34 if major else 0.37)
        r1 = edge * 0.44
        draw.line([(cx + math.cos(angle) * r0, cy + math.sin(angle) * r0),
                   (cx + math.cos(angle) * r1, cy + math.sin(angle) * r1)],
                  fill=_a(PALE if major else (90, 120, 176)),
                  width=(2 if major else 1) * scale)
    # Стрелка: светлая половина и синяя.
    r = edge * 0.32
    draw.polygon([(cx, cy - r), (cx + r * 0.42, cy), (cx, cy + r * 0.18)],
                 fill=_a(WHITE))
    draw.polygon([(cx, cy - r), (cx - r * 0.42, cy), (cx, cy + r * 0.18)],
                 fill=_a(BRIGHT))
    draw.polygon([(cx, cy + r * 0.18), (cx + r * 0.42, cy), (cx, cy + r * 0.30)],
                 fill=_a(CYAN))
    draw.ellipse([cx - 2 * scale, cy - 2 * scale, cx + 2 * scale, cy + 2 * scale],
                 fill=_a(WHITE), outline=_a(INK), width=max(1, scale // 2))
    return _downscale(image, size)


def main_compass_side(size: int = 16) -> Image.Image:
    """Боковина корпуса компаса: тёмный металл."""
    image = Image.new("RGBA", (size, size), _a(GRAPHITE))
    draw = ImageDraw.Draw(image)
    for y in range(size):
        t = y / max(1, size - 1)
        draw.line([0, y, size - 1, y],
                  fill=_a(mix(STEEL_DK, GRAPHITE_DK, t)))
    draw.line([0, 0, size - 1, 0], fill=_a(STEEL_LT))
    draw.line([0, size - 1, size - 1, size - 1], fill=_a(INK))
    return image


def main_pin(size: int = 16) -> Image.Image:
    """Стержень пина: металл."""
    image = Image.new("RGBA", (size, size), _a(STEEL))
    draw = ImageDraw.Draw(image)
    draw.line([0, 0, size - 1, 0], fill=_a(WHITE), width=2)
    draw.line([0, size - 1, size - 1, size - 1], fill=_a(STEEL_DK), width=2)
    return image


def main_pin_top(size: int = 16) -> Image.Image:
    """Головка пина: бирюза с бликом."""
    image = Image.new("RGBA", (size, size), _a(CYAN))
    draw = ImageDraw.Draw(image)
    draw.line([0, 0, size - 1, 0], fill=_a(MINT), width=3)
    draw.line([0, size - 1, size - 1, size - 1], fill=_a((28, 121, 138)),
              width=3)
    draw.ellipse([4, 4, size - 5, size - 5], fill=(255, 255, 255, 140))
    return image


def main_catalog_plate(size: int = 16) -> Image.Image:
    """Этикетка витрины: тёмная плашка с бирюзовой полосой."""
    image = Image.new("RGBA", (size, size), _a(DEEP))
    draw = ImageDraw.Draw(image)
    draw.rectangle([1, 1, size - 2, size - 2], outline=_a(NAVY), width=1)
    draw.line([3, size - 5, size - 4, size - 5], fill=_a(CYAN), width=1)
    draw.line([3, size - 7, size - 6, size - 7], fill=_a((*PALE, 160)), width=1)
    return image


# ---------------------------------------------------------------------------
# Реестр и контактный лист
# ---------------------------------------------------------------------------

ICONS = {
    "brand": icon_brand,
    "nav_back": icon_nav_back,
    "nav_forward": icon_nav_forward,
    "nav_close": icon_nav_close,
    "page_info": icon_page_info,
    "poi_info": icon_poi_info,
    "poi_goto": icon_poi_goto,
    "poi_rate": icon_poi_rate,
    "poi_delete": icon_poi_delete,
    "poi_type_compass": icon_poi_type_compass,
    "poi_type_beacon": icon_poi_type_beacon,
    "poi_type_book": icon_poi_type_book,
    "poi_type_pearl": icon_poi_type_pearl,
    "poi_type_star": icon_poi_type_star,
    "poi_type_paper": icon_poi_type_paper,
    "itrak_route": icon_itrak_route,
    "itrak_watch": icon_itrak_watch,
    "itrak_watch_off": icon_itrak_watch_off,
    "itrak_take": icon_itrak_take,
    "itrak_locked": icon_itrak_locked,
    "itrak_remove": icon_itrak_remove,
    "itrak_rename": icon_itrak_rename,
    "itrak_catalog": icon_itrak_catalog,
    "itrak_scan": icon_itrak_scan,
    "itrak_give": icon_itrak_give,
    "itrak_refresh": icon_itrak_refresh,
    "itrak_empty": icon_itrak_empty,
}

MAIN_TEXTURES = {
    "main_map_top": main_map_top,
    "main_map_side": main_map_side,
    "main_map_back": main_map_back,
    "main_book_cover": main_book_cover,
    "main_book_pages": main_book_pages,
    "main_book_tabs": main_book_tabs,
    "main_book_spine": main_book_spine,
    "main_catalog_glass": main_catalog_glass,
    "main_catalog_base": main_catalog_base,
    "main_catalog_band": main_catalog_band,
    "main_catalog_plate": main_catalog_plate,
    "main_search_ring": main_search_ring,
    "main_search_handle": main_search_handle,
    "main_search_lens": main_search_lens,
    "main_gear_face": main_gear_face,
    "main_gear_side": main_gear_side,
    "main_gear_hub": main_gear_hub,
    "main_tag_ring": main_tag_ring,
    "main_compass_face": main_compass_face,
    "main_compass_side": main_compass_side,
    "main_pin": main_pin,
    "main_pin_top": main_pin_top,
}


def icon(name: str) -> Image.Image:
    return ICONS[name]()


def contact_sheet(path: str = "/tmp/ui-icons.png", scale: int = 4,
                  cols: int = 7) -> None:
    """Контактный лист 2D-иконок — быстрая проверка формы и читаемости."""
    names = list(ICONS)
    tile = ICON_SIZE * scale
    pad = 10
    rows = (len(names) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * (tile + pad) + pad, rows * (tile + 34) + 40),
                      (24, 27, 33))
    draw = ImageDraw.Draw(sheet)
    draw.text((10, 10), "HahGPS: 2D-иконки GUI", fill=(224, 232, 244))
    for index, name in enumerate(names):
        x = pad + (index % cols) * (tile + pad)
        y = 34 + (index // cols) * (tile + 34)
        draw.rectangle([x, y, x + tile, y + tile], fill=(198, 198, 198),
                       outline=(120, 122, 126))
        image = ICONS[name]().resize((tile, tile), Image.LANCZOS)
        sheet.paste(image, (x, y), image)
        draw.text((x, y + tile + 6), name, fill=(170, 180, 196))
    sheet.save(path)


if __name__ == "__main__":
    contact_sheet()
    print("контактный лист: /tmp/ui-icons.png")
