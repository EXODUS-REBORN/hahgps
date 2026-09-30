#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Переводит цвета сообщений HahGps в палитру основного плагина hahtojer:
синий + голубой во всех тонах. Красный оставляем только для ошибок/опасных
действий, серый/белый — нейтральные. Запрещённые у hahtojer тона
(gold/green/yellow/aqua/light_purple/dark_purple/dark_red/dark_green) убираем.
"""
import re
import sys
from pathlib import Path

# MiniMessage-тег → hex-тон палитры hahtojer (или None = оставить как есть)
TAG_MAP = {
    "gold": "#4fd1e0",          # бирюза (тон 8) — заголовки
    "yellow": "#8fe9f5",        # светло-бирюзовый (тон 9) — значения
    "green": "#8fc6ff",         # светло-голубой (тон 7) — действия
    "dark_green": "#66aaff",    # голубой (тон 6)
    "aqua": "#4fd1e0",
    "dark_aqua": "#2464c8",     # синий ярче (тон 4)
    "blue": "#3f86ff",          # ярко-синий (тон 5)
    "dark_blue": "#14397e",     # тёмно-синий (тон 2)
    "light_purple": "#66aaff",
    "dark_purple": "#2464c8",
    "dark_red": "red",
}

# legacy-коды §/& → палитра
LEGACY_MAP = {
    "a": "b",   # green → aqua
    "e": "b",   # yellow → aqua
    "6": "9",   # gold → blue
    "d": "b",   # light_purple → aqua
    "2": "3",   # dark_green → dark_aqua
    "5": "9",   # dark_purple → blue
    "4": "c",   # dark_red → red
}

TAG_RE = re.compile(r"<(/?)([a-z_]+)(:[^>]*)?>")


def fix_tags(text: str) -> str:
    def repl(m: re.Match) -> str:
        closing, name, extra = m.group(1), m.group(2), m.group(3) or ""
        if name in TAG_MAP:
            target = TAG_MAP[name]
            return f"<{closing}{target}{extra}>"
        return m.group(0)

    return TAG_RE.sub(repl, text)


def fix_legacy(text: str) -> str:
    def repl(m: re.Match) -> str:
        sym, code = m.group(1), m.group(2).lower()
        if code in LEGACY_MAP:
            return sym + LEGACY_MAP[code]
        return m.group(0)

    return re.sub(r"([\u00a7&])([0-9a-fk-or])", repl, text)


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    changed = 0
    for name in ("messages_ru.yml", "messages_en.yml"):
        path = root / "src" / "main" / "resources" / name
        if not path.is_file():
            print(f"нет файла: {path}")
            continue
        original = path.read_text(encoding="utf-8")
        text = fix_tags(original)
        text = fix_legacy(text)
        if text != original:
            path.write_text(text, encoding="utf-8", newline="\n")
            changed += 1
            print(f"{name}: цвета переведены в палитру hahtojer")
        else:
            print(f"{name}: менять нечего")
    return 0 if changed else 0


if __name__ == "__main__":
    sys.exit(main())
