#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Приводит сообщения к палитре hahtojer (GuiTheme.TONES).

Запрещённые тона (чужие цвета) переводятся в ближайший тон палитры,
<red>/<gray>/<white>/<dark_gray> и hex-тоны палитры остаются как есть.
Скрипт идемпотентен: повторный запуск ничего не меняет.
"""
import sys
from pathlib import Path

# вне-палитурный hex -> тон палитры
HEX_MAP = {
    "#b8dcff": "#8fe9f5",  # было "почти бело-голубой" -> тон 9
    "#c8e4ff": "#66aaff",  # подписи -> тон 6
    "#e8f4ff": "#8fe9f5",  # подсветка -> тон 9
    "#d8ecff": "#8fc6ff",
}

# запрещённые именованные теги -> палитра/нейтраль
TAG_MAP = {
    "gold": "#4fd1e0",
    "yellow": "#8fe9f5",
    "green": "#8fc6ff",
    "dark_green": "#3f86ff",
    "aqua": "#8fc6ff",
    "dark_aqua": "#2464c8",
    "blue": "#3f86ff",
    "dark_blue": "#14397e",
    "light_purple": "#66aaff",
    "dark_purple": "#2464c8",
    "dark_red": "red",
}

# legacy §/& -> разрешённый legacy (см. docs/gui.md)
LEGACY_MAP = {
    "a": "9",
    "e": "b",
    "6": "9",
    "d": "b",
    "2": "3",
    "5": "9",
    "4": "c",
}


def process(text: str) -> str:
    for old, new in HEX_MAP.items():
        text = text.replace(old, new)
    for name, target in TAG_MAP.items():
        text = text.replace(f"<{name}>", f"<{target}>")
        text = text.replace(f"</{name}>", f"</{target}>")
    for old, new in LEGACY_MAP.items():
        text = text.replace(f"§{old}", f"§{new}")
    return text


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    changed = 0
    for name in ("messages_ru.yml", "messages_en.yml"):
        path = root / "src" / "main" / "resources" / name
        if not path.is_file():
            print(f"нет файла: {path}")
            continue
        original = path.read_text(encoding="utf-8")
        text = process(original)
        if text != original:
            path.write_text(text, encoding="utf-8", newline="\n")
            changed += 1
            print(f"{name}: палитра приведена к hahtojer")
        else:
            print(f"{name}: палитра уже чистая")
    return 0 if changed else 0


if __name__ == "__main__":
    sys.exit(main())
