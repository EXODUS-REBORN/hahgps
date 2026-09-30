#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Меняет захардкоженный NamedTextColor.YELLOW в GUI-классах на тон палитры
основного плагина hahtojer (светло-бирюзовый, тон 9) через GuiUtil.tonedName.
Жёлтый в палитре hahtojer запрещён.
"""
import re
import sys
from pathlib import Path

PATTERN = re.compile(r"Component\.text\(\s*([^,()]+(?:\([^()]*\))?[^,()]*?)\s*,\s*NamedTextColor\.YELLOW\s*\)")


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    gui = root / "src" / "main" / "java" / "ru" / "hahgps" / "gui"
    changed = 0
    for path in sorted(gui.glob("*.java")):
        original = path.read_text(encoding="utf-8")
        text = PATTERN.sub(lambda m: f"GuiUtil.tonedName(9, {m.group(1).strip()})", original)
        if text != original:
            path.write_text(text, encoding="utf-8", newline="\n")
            changed += 1
            print(f"{path.name}: YELLOW -> ton palitry")
    print(f"файлов изменено: {changed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
