#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Исправляет закрывающие теги цветов, которые не были заменены."""
import sys
from pathlib import Path

REPLACEMENTS = [
    ("</#4fd1e0>", "</#8fc6ff>"),
    ("</#8fe9f5>", "</#b8dcff>"),
    ("</red>", "</#3f86ff>"),
    ("</gray>", "</#c8e4ff>"),
]


def process_file(path):
    text = path.read_text(encoding="utf-8")
    original = text
    for old, new in REPLACEMENTS:
        text = text.replace(old, new)
    if text != original:
        path.write_text(text, encoding="utf-8", newline="\n")
        print(f"{path.name}: закрывающие теги исправлены")


def main():
    root = Path(__file__).resolve().parents[1]
    for name in ("messages_ru.yml", "messages_en.yml"):
        path = root / "src" / "main" / "resources" / name
        if path.is_file():
            process_file(path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
