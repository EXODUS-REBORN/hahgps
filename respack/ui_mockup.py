#!/usr/bin/env python3
"""Макеты меню HahGPS для скриншотов релиза.

Это не рендер клиента Minecraft, а честная схема содержимого: те же слоты,
те же предметы и модели из собранного пака, те же подписи. Нужна, чтобы
показать каталог трекеров и «Мои трекеры» без запуска игрового клиента.
"""
from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import build
import tracker_art

ROOT = Path(__file__).resolve().parent
PACK = ROOT / "hahgps-pack"
PREVIEW = ROOT / "preview"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

SCALE = 2
SLOT = 36 * SCALE
MARGIN = 9 * SCALE
TITLE_H = 34 * SCALE


def _font(size: int, bold: bool = False):
    try:
        return ImageFont.truetype(FONT_BOLD if bold else FONT, size)
    except OSError:
        return ImageFont.load_default()


def _text(draw, xy, text, size=18, fill=(40, 40, 40), bold=False):
    draw.text(xy, text, font=_font(size, bold), fill=fill)


def item_icon(name: str, size: int = 64) -> Image.Image:
    """3D-иконка предмета из пака, как её видно в слоте."""
    model_path = PACK / "assets/hahgps/models/item" / f"{name}.json"
    model = json.loads(model_path.read_text(encoding="utf-8"))
    images = build.load_images()
    return build.render_model(model, images, size, yaw=-30, pitch=-30,
                              view=1.05, transparent=True)


def gui_icon(name: str, size: int = 64) -> Image.Image:
    """Иконка меню из собранного пака (32×32), увеличенная без сглаживания."""
    path = PACK / "assets/hahgps/textures/gui" / f"{name}.png"
    return Image.open(path).convert("RGBA").resize((size, size), Image.NEAREST)


def simple_icon(kind: str, size: int = 64) -> Image.Image:
    """Простые иконки служебных кнопок: книга, барьер, компас, стрелка."""
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(image)
    if kind == "book":
        d.rounded_rectangle([14, 8, 50, 56], radius=4, fill=(88, 132, 196, 255),
                            outline=(35, 62, 110, 255), width=3)
        d.line([32, 12, 32, 52], fill=(224, 232, 244, 255), width=2)
        d.line([21, 20, 29, 20], fill=(224, 232, 244, 255), width=2)
        d.line([35, 20, 43, 20], fill=(224, 232, 244, 255), width=2)
    elif kind == "barrier":
        d.ellipse([8, 8, 56, 56], fill=(210, 60, 60, 255), outline=(110, 18, 18, 255), width=3)
        d.line([20, 20, 44, 44], fill=(255, 230, 230, 255), width=6)
        d.line([44, 20, 20, 44], fill=(255, 230, 230, 255), width=6)
    elif kind == "compass":
        d.ellipse([8, 8, 56, 56], fill=(206, 212, 220, 255), outline=(92, 100, 112, 255), width=3)
        d.polygon([(32, 14), (38, 32), (32, 50), (26, 32)], fill=(220, 60, 60, 255))
        d.ellipse([28, 28, 36, 36], fill=(40, 44, 52, 255))
    elif kind == "spyglass":
        d.rounded_rectangle([10, 26, 54, 40], radius=7, fill=(60, 66, 78, 255),
                            outline=(28, 31, 38, 255), width=3)
        d.rectangle([44, 22, 56, 44], fill=(150, 160, 176, 255),
                    outline=(60, 66, 78, 255), width=2)
    elif kind == "lapis":
        d.rounded_rectangle([10, 12, 54, 52], radius=5, fill=(42, 84, 190, 255),
                            outline=(20, 40, 100, 255), width=3)
        d.line([16, 18, 48, 46], fill=(86, 130, 230, 255), width=3)
        d.line([48, 18, 16, 46], fill=(86, 130, 230, 255), width=3)
    elif kind == "glass":
        d.rounded_rectangle([10, 10, 54, 54], radius=4, fill=(52, 74, 130, 120),
                            outline=(24, 38, 78, 180), width=2)
    return image


def gui_frame(title: str, rows: int = 6):
    width = 9 * SLOT + 2 * MARGIN
    height = rows * SLOT + TITLE_H + 2 * MARGIN
    image = Image.new("RGB", (width, height), (198, 198, 198))
    draw = ImageDraw.Draw(image)
    # Заголовок и панель инвентаря.
    draw.rectangle([0, 0, width, height], fill=(198, 198, 198))
    draw.rectangle([2 * SCALE, 2 * SCALE, width - 3 * SCALE, height - 3 * SCALE],
                   outline=(70, 70, 70), width=2 * SCALE)
    tw = draw.textlength(title, font=_font(19 * SCALE, True))
    _text(draw, ((width - tw) / 2, 8 * SCALE), title, size=19 * SCALE,
          fill=(40, 40, 40), bold=True)
    top = TITLE_H
    for row in range(rows):
        for col in range(9):
            x = MARGIN + col * SLOT
            y = top + row * SLOT
            draw.rectangle([x, y, x + SLOT, y + SLOT], fill=(139, 139, 139))
            draw.rectangle([x + 2 * SCALE, y + 2 * SCALE, x + SLOT - 2 * SCALE, y + SLOT - 2 * SCALE],
                           fill=(198, 198, 198))
            draw.line([x + 2 * SCALE, y + 2 * SCALE, x + SLOT - 2 * SCALE, y + 2 * SCALE],
                      fill=(255, 255, 255))
            draw.line([x + 2 * SCALE, y + 2 * SCALE, x + 2 * SCALE, y + SLOT - 2 * SCALE],
                      fill=(255, 255, 255))
    return image, draw, width, height


def slot_xy(slot: int):
    row, col = divmod(slot, 9)
    return MARGIN + col * SLOT, TITLE_H + row * SLOT


def put(image: Image.Image, slot: int, icon: Image.Image) -> None:
    x, y = slot_xy(slot)
    size = SLOT - 6 * SCALE
    icon = icon.resize((size, size), Image.LANCZOS)
    image.paste(icon, (x + 3 * SCALE, y + 3 * SCALE), icon)


def tooltip(image: Image.Image, slot: int, lines: list[str]) -> None:
    draw = ImageDraw.Draw(image)
    x, y = slot_xy(slot)
    width = max(draw.textlength(line, font=_font(14 * SCALE)) for line in lines) + 16 * SCALE
    height = len(lines) * 18 * SCALE + 10 * SCALE
    tx = min(x + SLOT + 4 * SCALE, image.width - width - 4 * SCALE)
    ty = min(y, image.height - height - 4 * SCALE)
    draw.rectangle([tx, ty, tx + width, ty + height], fill=(16, 4, 30))
    draw.rectangle([tx, ty, tx + width, ty + height], outline=(86, 60, 150), width=2 * SCALE)
    for i, line in enumerate(lines):
        color = (255, 255, 255) if i == 0 else (180, 190, 210)
        draw.text((tx + 8 * SCALE, ty + 6 * SCALE + i * 18 * SCALE), line,
                  font=_font((14 if i else 15) * SCALE, bold=i == 0), fill=color)


def compose(gui: Image.Image, heading: str, lines: list[str]) -> Image.Image:
    """Готовый скриншот: меню слева, пояснение справа (чтобы тултип не закрывал слоты)."""
    panel_w = 300
    canvas = Image.new("RGB", (gui.width + panel_w, gui.height), (18, 21, 27))
    canvas.paste(gui, (0, 0))
    draw = ImageDraw.Draw(canvas)
    x = gui.width + 18
    draw.rectangle([gui.width, 0, canvas.width, canvas.height], fill=(18, 21, 27))
    draw.line([gui.width, 0, gui.width, canvas.height], fill=(46, 53, 66), width=2)
    _text(draw, (x, 22), "HahGPS · iTrak", 16, (143, 198, 255), bold=True)
    _text(draw, (x, 50), heading, 15, (226, 232, 242), bold=True)
    y = 84
    for line in lines:
        draw.ellipse([x, y + 6, x + 5, y + 11], fill=(79, 209, 224))
        draw.text((x + 14, y), line, font=_font(12), fill=(178, 190, 208))
        y += 26
    return canvas


def fill_panes(image: Image.Image, occupied: set[int],
               rows: int = 6) -> None:
    pane = gui_icon("filler")
    for slot in range(rows * 9):
        if slot not in occupied:
            put(image, slot, pane)


def pane() -> Image.Image:
    return gui_icon("filler")


def brand() -> Image.Image:
    return gui_icon("brand")


def catalog() -> Path:
    image, draw, width, height = gui_frame("Трекеры и локаторы")
    colors = list(tracker_art.COLORS)
    occupied = {4, 30, 32, 34, 45, 53}
    occupied.update(10 + i for i in range(8))
    occupied.update(19 + i for i in range(8))
    fill_panes(image, occupied)
    put(image, 4, gui_icon("ui_itrak_catalog"))
    for i, color in enumerate(colors):
        put(image, 10 + i, item_icon(f"tracker_{color}"))
        put(image, 19 + i, item_icon(f"tracker_{color}_wp"))
    put(image, 30, item_icon("locator"))
    put(image, 32, item_icon("locator_long"))
    put(image, 44, brand())
    put(image, 45, gui_icon("nav_back"))
    put(image, 53, gui_icon("nav_close"))
    image = compose(image, "Каталог выдачи", [
        "8 цветов трекеров",
        "обычный и водонепроницаемый",
        "локатор 20 / дальний 30",
        "ЛКМ — получить предмет",
    ])
    path = PREVIEW / "06-gui-catalog.png"
    image.save(path)
    return path


def my_trackers() -> Path:
    image, draw, width, height = gui_frame("Мои трекеры")
    samples = [
        ("tracker_blue", "Хайсовская 10"),
        ("tracker_green", "Лодка"),
        ("tracker_red", "Свинья"),
        ("tracker_white_wp", "Тайник"),
    ]
    occupied = {i for i in range(4)} | {45, 48, 49, 50, 51, 53}
    fill_panes(image, occupied)
    for i, (model, label) in enumerate(samples):
        put(image, i, item_icon(model))
    put(image, 44, brand())
    put(image, 45, gui_icon("nav_back"))
    put(image, 48, gui_icon("ui_itrak_scan"))
    put(image, 49, gui_icon("page_info"))
    put(image, 50, gui_icon("ui_itrak_give"))
    put(image, 51, gui_icon("ui_itrak_refresh"))
    put(image, 53, gui_icon("nav_close"))
    _text(draw, (slot_xy(49)[0] + 4 * SCALE, slot_xy(49)[1] + 6 * SCALE),
          "1/1", 11 * SCALE, (40, 40, 40), bold=True)
    image = compose(image, "Мои трекеры", [
        "живые координаты раз в секунду",
        "статус: в сети / офлайн",
        "маршрут и слежение",
        "карточка по клику на трекер",
        "отдельная кнопка в главном меню",
    ])
    path = PREVIEW / "07-gui-my-trackers.png"
    image.save(path)
    return path


def main_menu() -> Path:
    """Главное меню: 3D-иконки разделов и плоские служебные кнопки."""
    image, draw, width, height = gui_frame("HahGPS", rows=3)
    occupied = {10, 12, 14, 16, 22, 24, 26}
    fill_panes(image, occupied, rows=3)
    put(image, 10, item_icon("main_points"))
    put(image, 12, gui_icon("categories"))
    put(image, 14, item_icon("main_catalog"))
    put(image, 16, gui_icon("search"))
    put(image, 22, gui_icon("settings"))
    put(image, 24, item_icon("main_trackers"))
    put(image, 26, brand())
    image = compose(image, "Главное меню", [
        "мои точки · категории · каталог",
        "поиск · настройки · мои трекеры",
        "объёмные иконки у разделов",
        "плоские кнопки у служебных",
    ])
    path = PREVIEW / "10-gui-main-menu.png"
    image.save(path)
    return path


def poi_card() -> Path:
    """Карточка точки: 2D-карточка досье и кнопки действий."""
    image, draw, width, height = gui_frame("Точка: Хайсовская 10", rows=3)
    occupied = {10, 12, 14, 16, 22, 26}
    fill_panes(image, occupied, rows=3)
    put(image, 10, gui_icon("ui_poi_info"))
    put(image, 12, gui_icon("ui_poi_goto"))
    put(image, 14, gui_icon("ui_poi_rate"))
    put(image, 16, gui_icon("ui_poi_delete"))
    put(image, 22, gui_icon("nav_back"))
    put(image, 26, brand())
    image = compose(image, "Карточка точки", [
        "досье: координаты и оценка",
        "маршрут к точке",
        "поставить оценку 1–10",
        "удалить (с правом gps.point.delete)",
    ])
    path = PREVIEW / "11-gui-poi-card.png"
    image.save(path)
    return path


def tracker_card() -> Path:
    """Карточка трекера: 3D-модель жетона и 2D-кнопки действий."""
    image, draw, width, height = gui_frame("Трекер: Хайсовская 10", rows=3)
    occupied = {4, 10, 12, 13, 14, 16, 22, 26}
    fill_panes(image, occupied, rows=3)
    put(image, 4, item_icon("tracker_blue"))
    put(image, 10, gui_icon("ui_itrak_route"))
    put(image, 12, gui_icon("ui_itrak_watch"))
    put(image, 13, gui_icon("ui_itrak_rename"))
    put(image, 14, gui_icon("ui_itrak_take"))
    put(image, 16, gui_icon("ui_itrak_remove"))
    put(image, 22, gui_icon("nav_back"))
    put(image, 26, brand())
    image = compose(image, "Карточка трекера", [
        "сам трекер — 3D-модель",
        "маршрут, слежение, переименование",
        "забрать или удалить",
        "слежение: глаз / перечёркнутый",
    ])
    path = PREVIEW / "12-gui-tracker-card.png"
    image.save(path)
    return path


def main() -> None:
    PREVIEW.mkdir(parents=True, exist_ok=True)
    for path in (catalog(), my_trackers(), main_menu(), poi_card(),
                 tracker_card()):
        print("макет:", path)


if __name__ == "__main__":
    main()
