#!/usr/bin/env python3
"""2D-иконки для ресурспака HahGPS: 16x16, палитра из docs/models.md.

Каждая иконка — функция draw_<ключ>(d), которая рисует в ImageDraw 16x16.
После рисования icons2d.render() добавляет контур и лёгкий светотеневой
градиент, поэтому сами иконки можно рисовать «плоско».
"""
from __future__ import annotations

import math

from PIL import Image, ImageDraw

DEEP = (8, 18, 43, 255)
DARK = (14, 36, 80, 255)
MID = (20, 57, 126, 255)
BASE = (29, 81, 168, 255)
BLUE = (36, 100, 200, 255)
BRIGHT = (63, 134, 255, 255)
LIGHT = (102, 170, 255, 255)
PALE = (143, 198, 255, 255)
CYAN = (79, 209, 224, 255)
ICE = (143, 233, 245, 255)
WHITE = (234, 244, 255, 255)
RED = (235, 87, 87, 255)
GREEN = (85, 195, 107, 255)
YELLOW = (240, 194, 75, 255)
ORANGE = (242, 153, 74, 255)
PURPLE = (155, 81, 224, 255)


def new() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def rect(d, x0, y0, x1, y1, fill):
    d.rectangle([x0, y0, x1, y1], fill=fill)


def poly(d, pts, fill):
    d.polygon(pts, fill=fill)


def ell(d, x0, y0, x1, y1, fill):
    d.ellipse([x0, y0, x1, y1], fill=fill)


def line(d, pts, fill, width=1):
    d.line(pts, fill=fill, width=width)


# --- отделка: контур и объём -------------------------------------------------

def _shade(img: Image.Image) -> None:
    """Верхние кромки светлее, нижние темнее — объём без теней."""
    px = img.load()
    w, h = img.size
    src = [[px[x, y] for x in range(w)] for y in range(h)]
    for y in range(h):
        for x in range(w):
            r, g, b, a = src[y][x]
            if a == 0:
                continue
            above = src[y - 1][x][3] if y > 0 else 0
            below = src[y + 1][x][3] if y < h - 1 else 0
            if above == 0:
                r, g, b = min(255, r + 26), min(255, g + 26), min(255, b + 26)
            elif below == 0:
                r, g, b = int(r * 0.8), int(g * 0.8), int(b * 0.8)
            px[x, y] = (r, g, b, a)


def _outline(img: Image.Image, color=DEEP) -> None:
    px = img.load()
    w, h = img.size
    solid = {(x, y) for x in range(w) for y in range(h) if px[x, y][3] > 0}
    for (x, y) in solid:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < w and 0 <= ny < h and (nx, ny) not in solid:
                px[nx, ny] = color


def render(draw_fn) -> Image.Image:
    img, d = new()
    draw_fn(d)
    _shade(img)
    _outline(img)
    return img


def icon(draw_fn, size=16) -> Image.Image:
    return render(draw_fn) if size == 16 else render(draw_fn).resize((size, size), Image.NEAREST)


# --- примитивы-глифы ---------------------------------------------------------

def plate(d, fill=MID, border=DEEP):
    rect(d, 0, 0, 15, 15, fill)
    rect(d, 0, 0, 15, 0, DARK)
    rect(d, 0, 15, 15, 15, DEEP)
    rect(d, 0, 0, 0, 15, DARK)
    rect(d, 15, 0, 15, 15, DEEP)


def arrow(d, direction=-1, color=BRIGHT):
    """Стрелка: древко и треугольный наконечник."""
    if direction < 0:
        rect(d, 7, 6, 13, 9, color)
        poly(d, [(2, 8), (8, 3), (8, 13)], color)
    else:
        rect(d, 2, 6, 8, 9, color)
        poly(d, [(13, 8), (7, 3), (7, 13)], color)


def cross(d, color=RED):
    for i in range(3):
        line(d, [(4 + i, 4), (12 - i, 12)], color, 1)
        line(d, [(4 + i, 12), (12 - i, 4)], color, 1)


def info_ring(d, color=BRIGHT):
    ell(d, 3, 3, 12, 12, color)
    ell(d, 5, 5, 10, 10, DARK)
    rect(d, 7, 6, 8, 7, PALE)
    rect(d, 7, 8, 8, 11, PALE)


def page(d, color=PALE, fold=True):
    rect(d, 4, 2, 11, 14, color)
    if fold:
        poly(d, [(9, 2), (11, 2), (11, 4)], DARK)
        rect(d, 9, 2, 11, 4, LIGHT)
    for y in (6, 8, 10):
        rect(d, 6, y, 9, y, MID)


def brand_plate(d):
    """Логотип: тёмная плашка, синий пин-компас и волны."""
    plate(d, BASE)
    rect(d, 1, 1, 14, 14, MID)
    pin(d, 8, 11, BRIGHT, PALE, size=4)
    for i, y in enumerate((12, 14)):
        rect(d, 3, y, 12, y, CYAN)


def pin(d, cx=8, cy=11, color=BRIGHT, inner=None, size=4, tip=True):
    """Маркер: круг с остриём вниз."""
    ell(d, cx - size, cy - size - 2, cx + size, cy + size - 2, color)
    if tip:
        poly(d, [(cx - size + 1, cy + size - 3), (cx + size - 1, cy + size - 3),
                 (cx, cy + size + 3)], color)
    if inner is not None:
        ell(d, cx - 1, cy - size, cx + 1, cy - size + 2, inner)


def star(d, cx=8, cy=8, color=YELLOW, r=6):
    pts = []
    for i in range(10):
        ang = -math.pi / 2 + i * math.pi / 5
        rr = r if i % 2 == 0 else r * 0.45
        pts.append((cx + rr * math.cos(ang), cy + rr * math.sin(ang)))
    poly(d, pts, color)


def gear(d, color=PALE):
    ell(d, 4, 4, 12, 12, color)
    for (x, y) in ((7, 1), (8, 1), (7, 14), (8, 14), (1, 7), (1, 8), (14, 7), (14, 8)):
        rect(d, x, y, x + (0 if y not in (1, 14) else 1) + (1 if y in (1, 14) else 0), y, color)
    rect(d, 6, 1, 9, 2, color)
    rect(d, 6, 13, 9, 14, color)
    rect(d, 1, 6, 2, 9, color)
    rect(d, 13, 6, 14, 9, color)
    ell(d, 6, 6, 9, 9, DARK)


def folder(d, color=BRIGHT, plus=False, label=False):
    rect(d, 2, 5, 13, 13, color)
    rect(d, 2, 3, 7, 5, PALE)
    rect(d, 3, 6, 12, 7, LIGHT)
    if plus:
        rect(d, 7, 8, 8, 12, WHITE)
        rect(d, 5, 10, 11, 11, WHITE)
    if label:
        rect(d, 4, 8, 11, 12, LIGHT)
        rect(d, 5, 9, 10, 9, MID)
        rect(d, 5, 11, 9, 11, MID)


def magnifier(d, color=PALE, inner=None):
    ell(d, 3, 3, 11, 11, color)
    ell(d, 5, 5, 9, 9, DARK)
    rect(d, 5, 5, 6, 6, MID)
    line(d, [(10, 10), (13, 13)], color, 3)
    if inner == "bolt":
        poly(d, [(7, 4), (11, 4), (8, 7), (11, 7), (6, 12), (8, 8), (6, 8)], YELLOW)
    elif inner == "plus":
        rect(d, 6, 4, 8, 10, LIGHT)
        rect(d, 4, 6, 10, 8, LIGHT)
    elif inner == "minus":
        rect(d, 4, 6, 10, 8, LIGHT)


def trash(d, color=PALE):
    rect(d, 4, 5, 11, 13, color)
    rect(d, 3, 3, 12, 4, LIGHT)
    rect(d, 6, 1, 9, 3, LIGHT)
    rect(d, 6, 7, 6, 11, MID)
    rect(d, 9, 7, 9, 11, MID)


def pencil(d, color=YELLOW):
    poly(d, [(3, 12), (11, 4), (13, 6), (5, 14)], color)
    poly(d, [(11, 4), (13, 2), (14, 3), (13, 6)], WHITE)
    rect(d, 3, 12, 4, 13, DARK)


def chat(d, color=BRIGHT):
    rect(d, 2, 3, 13, 11, color)
    rect(d, 4, 12, 6, 13, color)
    for x in (5, 8, 11):
        rect(d, x, 6, x, 8, DARK)


def globe(d, color=BRIGHT):
    ell(d, 2, 2, 13, 13, color)
    line(d, [(7, 2), (7, 13)], PALE)
    line(d, [(8, 2), (8, 13)], PALE)
    line(d, [(2, 8), (13, 8)], PALE)
    line(d, [(3, 5), (12, 5)], PALE)
    line(d, [(3, 11), (12, 11)], PALE)


def map_sheet(d, color=PALE, zoom=False):
    rect(d, 2, 3, 13, 13, color)
    rect(d, 3, 4, 12, 12, MID)
    line(d, [(4, 9), (7, 7), (10, 10), (12, 8)], CYAN)
    if zoom:
        ell(d, 8, 8, 13, 13, PALE)
        ell(d, 9, 9, 12, 12, DARK)
        line(d, [(12, 12), (14, 14)], PALE, 2)
    else:
        rect(d, 4, 5, 5, 5, CYAN)


def slider(d):
    rect(d, 1, 7, 14, 9, MID)
    rect(d, 1, 7, 14, 7, LIGHT)
    rect(d, 6, 5, 9, 11, PALE)
    rect(d, 7, 6, 8, 10, DARK)
    for x in (2, 13):
        rect(d, x, 4, x, 12, DARK)


def boot(d):
    rect(d, 4, 3, 7, 11, PALE)
    rect(d, 3, 10, 11, 13, PALE)
    rect(d, 3, 12, 12, 13, LIGHT)
    rect(d, 4, 4, 6, 5, WHITE)
    rect(d, 8, 8, 11, 9, MID)


def car(d):
    rect(d, 2, 8, 13, 11, BRIGHT)
    rect(d, 4, 5, 11, 8, LIGHT)
    rect(d, 5, 6, 7, 7, DARK)
    rect(d, 8, 6, 10, 7, DARK)
    for x in (3, 10):
        rect(d, x, 11, x + 2, 13, DARK)


def bus(d):
    rect(d, 2, 4, 13, 12, BRIGHT)
    rect(d, 3, 5, 12, 8, LIGHT)
    rect(d, 5, 6, 6, 7, DARK)
    rect(d, 8, 6, 9, 7, DARK)
    rect(d, 11, 6, 12, 7, DARK)
    rect(d, 2, 9, 13, 10, CYAN)
    rect(d, 3, 12, 5, 13, DARK)
    rect(d, 10, 12, 12, 13, DARK)


def plane(d):
    poly(d, [(1, 9), (15, 4), (15, 6), (7, 10)], PALE)
    poly(d, [(9, 5), (11, 1), (12, 1), (12, 6)], LIGHT)
    poly(d, [(6, 9), (5, 13), (6, 13), (9, 10)], LIGHT)


def boat(d):
    poly(d, [(2, 9), (14, 9), (12, 13), (4, 13)], BRIGHT)
    rect(d, 3, 9, 13, 10, LIGHT)
    rect(d, 7, 3, 9, 9, PALE)
    poly(d, [(9, 3), (13, 8), (9, 8)], LIGHT)


def eye(d, off=False):
    poly(d, [(1, 8), (8, 3), (15, 8), (8, 13)], PALE)
    ell(d, 5, 5, 11, 11, CYAN)
    ell(d, 7, 7, 9, 9, DEEP)
    if off:
        line(d, [(2, 13), (14, 3)], RED, 2)


def hand(d, off=False):
    """Раскрытая ладонь: три пальца и большой."""
    rect(d, 4, 7, 11, 13, PALE)          # ладонь
    rect(d, 5, 3, 7, 8, PALE)            # палец
    rect(d, 8, 2, 10, 8, PALE)           # средний
    rect(d, 11, 4, 12, 8, PALE)          # указательный сбоку
    rect(d, 3, 9, 4, 12, PALE)           # большой
    rect(d, 5, 8, 11, 8, LIGHT)
    rect(d, 5, 11, 10, 11, MID)
    if off:
        line(d, [(2, 13), (14, 3)], RED, 2)


def radar(d):
    ell(d, 2, 2, 13, 13, MID)
    for r in (2, 4, 5):
        ell(d, 8 - r, 8 - r, 8 + r, 8 + r, None) if False else None
    ell(d, 3, 3, 12, 12, DARK)
    ell(d, 5, 5, 10, 10, MID)
    ell(d, 7, 7, 8, 8, CYAN)
    line(d, [(8, 8), (13, 4)], CYAN, 1)
    for (x, y) in ((4, 6), (11, 10), (6, 11)):
        rect(d, x, y, x, y, ICE)


def gift(d, color=YELLOW):
    rect(d, 3, 7, 12, 13, color)
    rect(d, 2, 5, 13, 7, LIGHT)
    rect(d, 7, 5, 8, 13, RED)
    ell(d, 4, 2, 7, 5, RED)
    ell(d, 8, 2, 11, 5, RED)


def refresh(d):
    ell(d, 3, 3, 12, 12, PALE)
    ell(d, 5, 5, 10, 10, None) if False else None
    poly(d, [(8, 2), (8, 8), (12, 5)], CYAN)
    poly(d, [(8, 14), (8, 8), (4, 11)], CYAN)
    ell(d, 6, 6, 9, 9, DARK)


def empty_slot(d):
    rect(d, 2, 2, 13, 13, None) if False else None
    for i in range(2, 14, 2):
        rect(d, i, 2, i + 1, 3, MID)
        rect(d, i, 12, i + 1, 13, MID)
        rect(d, 2, i, 3, i + 1, MID)
        rect(d, 12, i, 13, i + 1, MID)
    cross(d, DARK)


def terminal(d):
    rect(d, 2, 4, 13, 12, PALE)
    rect(d, 3, 5, 12, 9, DARK)
    line(d, [(5, 7), (8, 7)], CYAN)
    line(d, [(5, 8), (10, 8)], CYAN)
    rect(d, 4, 10, 6, 11, MID)
    rect(d, 8, 10, 11, 11, MID)
    rect(d, 12, 1, 13, 4, PALE)
    ell(d, 11, 0, 13, 2, CYAN)


def tag(d):
    poly(d, [(3, 4), (11, 4), (14, 8), (11, 12), (3, 12)], YELLOW)
    ell(d, 4, 6, 7, 9, DARK)
    rect(d, 8, 7, 12, 8, WHITE)


def tracker_flat(d, color=BRIGHT):
    rect(d, 4, 4, 11, 12, color)
    rect(d, 5, 5, 10, 9, DARK)
    rect(d, 6, 6, 9, 6, CYAN)
    rect(d, 7, 10, 8, 11, PALE)
    rect(d, 7, 1, 8, 4, PALE)
    ell(d, 6, 0, 9, 2, CYAN)


def signal(d):
    rect(d, 7, 8, 8, 14, PALE)
    for r, c in ((3, CYAN), (5, PALE), (7, LIGHT)):
        for ang in (-55, -35, 0, 35, 55):
            pass
    for r in (2, 4, 6):
        d.arc([8 - r, 9 - r, 8 + r, 9 + r], 200, 340, fill=ICE if r == 2 else PALE)


def compass_face(d):
    ell(d, 2, 2, 13, 13, PALE)
    ell(d, 4, 4, 11, 11, DARK)
    poly(d, [(8, 4), (10, 8), (8, 12), (6, 8)], BRIGHT)
    poly(d, [(8, 4), (10, 8), (8, 8)], CYAN)
    rect(d, 7, 0, 8, 2, PALE)


def locator(d, long=False):
    ell(d, 2, 2, 13, 13, PALE)
    ell(d, 4, 4, 11, 11, DARK)
    for r in ((4,) if not long else (3, 5)):
        d.arc([8 - r, 8 - r, 8 + r, 8 + r], 210, 330, fill=CYAN if r % 2 else ICE)
    ell(d, 7, 7, 8, 8, WHITE)
    if long:
        d.arc([1, 1, 14, 14], 200, 340, fill=CYAN)
    rect(d, 7, 0, 8, 2, PALE)


def poipin(d, style):
    color = BRIGHT
    pin(d, 8, 10, color, None, size=4)
    cx, cy = 8, 5
    if style == "dot":
        ell(d, cx - 1, cy - 1, cx + 1, cy + 1, WHITE)
    elif style == "flag":
        rect(d, 7, 2, 8, 7, PALE)
        poly(d, [(8, 2), (12, 3), (8, 4)], CYAN)
    elif style == "diamond":
        poly(d, [(8, 2), (11, 5), (8, 8), (5, 5)], WHITE)
    elif style == "drop":
        ell(d, 6, 3, 10, 7, CYAN)
        poly(d, [(8, 7), (10, 5), (6, 5)], CYAN)
    elif style == "star":
        star(d, 8, 5, WHITE, r=3)
    elif style == "square":
        rect(d, 6, 3, 10, 7, WHITE)


def route_arrow(d):
    line(d, [(3, 13), (3, 8), (5, 6), (9, 6)], PALE, 2)
    poly(d, [(9, 3), (14, 7), (9, 11)], CYAN)


def watch(d, off=False):
    eye(d, off)


def take(d, off=False):
    hand(d, off)


def fill_tile(d):
    plate(d, MID)
    rect(d, 1, 1, 14, 14, BASE)
    rect(d, 2, 2, 13, 13, MID)
    rect(d, 2, 2, 13, 2, BLUE)
    rect(d, 2, 13, 13, 13, DARK)


def map_tablet(d):
    rect(d, 1, 3, 14, 13, LIGHT)
    rect(d, 2, 4, 13, 12, MID)
    rect(d, 3, 5, 12, 11, DARK)
    line(d, [(4, 9), (7, 6), (9, 9), (12, 7)], CYAN)
    rect(d, 5, 6, 6, 6, PALE)
    rect(d, 1, 2, 14, 3, PALE)


def brand_pin(d):
    brand_plate(d)


DRAW = {
    "nav-back": lambda d: arrow(d, -1),
    "nav-forward": lambda d: arrow(d, 1),
    "nav-info": lambda d: info_ring(d),
    "nav-close": lambda d: cross(d),
    "nav-page": lambda d: page(d),
    "brand": brand_pin,
    "filler": fill_tile,
    "main-points": lambda d: (compass_face(d), pin(d, 8, 12, CYAN, None, size=2)),
    "main-categories": lambda d: folder(d, plus=False, label=True),
    "main-map": map_tablet,
    "main-search": lambda d: (page(d), magnifier(d, PALE)),
    "main-settings": lambda d: gear(d),
    "main-itrak": lambda d: signal(d),
    "poi-pin-dot": lambda d: poipin(d, "dot"),
    "poi-pin-flag": lambda d: poipin(d, "flag"),
    "poi-pin-diamond": lambda d: poipin(d, "diamond"),
    "poi-pin-drop": lambda d: poipin(d, "drop"),
    "poi-pin-star": lambda d: poipin(d, "star"),
    "poi-pin-square": lambda d: poipin(d, "square"),
    "poi-info": lambda d: info_ring(d),
    "poi-route": route_arrow,
    "poi-rate": lambda d: star(d, 8, 8, YELLOW, 6),
    "poi-delete": lambda d: trash(d),
    "category-row": lambda d: folder(d, label=True),
    "category-create": lambda d: folder(d, plus=True),
    "category-delete": lambda d: trash(d, RED),
    "search-input": lambda d: (page(d), pencil(d)),
    "search-quick": lambda d: magnifier(d, PALE, "bolt"),
    "search-result": lambda d: poipin(d, "dot"),
    "search-empty": empty_slot,
    "search-limit": slider,
    "settings-language": globe,
    "settings-hints": chat,
    "settings-scale": lambda d: map_sheet(d, zoom=True),
    "transport-foot": boot,
    "transport-car": car,
    "transport-transit": bus,
    "transport-air": plane,
    "transport-water": boat,
    "map": map_tablet,
    "locator": lambda d: locator(d, False),
    "locator-long": lambda d: locator(d, True),
    "itrak-card": lambda d: tracker_flat(d, PALE),
    "itrak-route": route_arrow,
    "itrak-watch": lambda d: watch(d, False),
    "itrak-watch-off": lambda d: watch(d, True),
    "itrak-take": lambda d: take(d, False),
    "itrak-take-off": lambda d: take(d, True),
    "itrak-remove": lambda d: trash(d),
    "itrak-scan": radar,
    "itrak-give": gift,
    "itrak-refresh": refresh,
    "itrak-empty": lambda d: cross(d, MID),
    "terminal": terminal,
    "rename": tag,
}
