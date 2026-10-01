#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Переводит NamedTextColor в GUI-классах на тона палитры hahtojer.

Запрещённые цвета (зелёный, жёлтый, золотой, розовый, фиолетовый) убираются,
голубой/синий приводятся к hex-тонам GuiTheme.TONES. Серый остаётся серым
(пояснения), красный — только для ошибок.
"""
import sys
from pathlib import Path

# NamedTextColor -> выражение цвета палитры
REPLACEMENTS = [
    ("NamedTextColor.GREEN", "GuiTheme.toneColor(7)"),
    ("NamedTextColor.DARK_GREEN", "GuiTheme.toneColor(5)"),
    ("NamedTextColor.YELLOW", "GuiTheme.toneColor(9)"),
    ("NamedTextColor.GOLD", "GuiTheme.toneColor(8)"),
    ("NamedTextColor.LIGHT_PURPLE", "GuiTheme.toneColor(6)"),
    ("NamedTextColor.DARK_PURPLE", "GuiTheme.toneColor(4)"),
    ("NamedTextColor.AQUA", "GuiTheme.toneColor(7)"),
    ("NamedTextColor.BLUE", "GuiTheme.toneColor(5)"),
    ("NamedTextColor.DARK_BLUE", "GuiTheme.toneColor(2)"),
]


def process_file(path) -> bool:
    text = path.read_text(encoding="utf-8")
    original = text
    for old, new in REPLACEMENTS:
        text = text.replace(old, new)
    if text != original:
        path.write_text(text, encoding="utf-8", newline="\n")
        print(f"{path.name}: цвета заменены на тоны палитры")
        return True
    return False


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    changed = 0
    for path in sorted((root / "src" / "main" / "java").rglob("*.java")):
        if process_file(path):
            changed += 1
    print(f"файлов изменено: {changed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
