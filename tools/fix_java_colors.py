#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Чинит §-коды в Java-файлах по правилам палитры hahtojer.

Разрешены: §f (значения), §7 (пояснения), §8 (разделители), §c (только ошибки),
§9/§b (синий/голубой) и hex-тона палитры. Остальное переводится в разрешённое.
Скрипт идемпотентен.
"""
import sys
from pathlib import Path

# запрещённый legacy-код -> разрешённый (см. docs/gui.md)
LEGACY_MAP = {
    "a": "9",  # зелёный -> синий
    "e": "b",  # жёлтый -> голубой
    "6": "9",  # золотой -> синий
    "d": "b",  # розовый -> голубой
    "2": "3",  # тёмно-зелёный -> тёмно-голубой
    "5": "9",  # фиолетовый -> синий
    "4": "c",  # тёмно-красный -> красный (ошибки)
}


def process(text: str) -> str:
    # Только §: «&» — это обычный текст (параметры URL, амперсанды), трогать его нельзя
    for old, new in LEGACY_MAP.items():
        text = text.replace(f"§{old}", f"§{new}")
    return text


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    changed = 0
    for path in sorted((root / "src" / "main" / "java").rglob("*.java")):
        original = path.read_text(encoding="utf-8")
        text = process(original)
        if text != original:
            path.write_text(text, encoding="utf-8", newline="\n")
            changed += 1
            print(f"{path.name}: §-коды приведены к палитре")
    print(f"файлов изменено: {changed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
