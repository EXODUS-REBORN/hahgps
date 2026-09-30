#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Заменяет §-коды на голубой/синий/белый тона во всех Java-файлах."""
import sys
from pathlib import Path

REPLACEMENTS = [
    ("§c", "§9"),
    ("§8", "§1"),
    ("§7", "§9"),
]


def process_file(path):
    text = path.read_text(encoding="utf-8")
    original = text
    for old, new in REPLACEMENTS:
        text = text.replace(old, new)
    if text != original:
        path.write_text(text, encoding="utf-8", newline="\n")
        print(f"{path.name}: §-коды заменены")


def main():
    root = Path(__file__).resolve().parents[1]
    java_dir = root / "src" / "main" / "java"
    for path in sorted(java_dir.rglob("*.java")):
        process_file(path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
