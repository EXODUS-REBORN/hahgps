#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Проверяет и чинит парность hex-тегов MiniMessage в сообщениях.

MiniMessage закрывает только совпадающий тег: `<#8fc6ff>а</#3f86ff>` выводит
закрывающий тег как обычный текст прямо в чате. Скрипт идёт по тегам
последовательно со стеком (вложенные теги не путаются) и подгоняет закрытие
под последний открытый цвет. Идемпотентен.
"""
import re
import sys
from pathlib import Path

TAG = re.compile(r"</?(#[0-9a-fA-F]{6})>")


def fix_value(text: str) -> tuple[str, int]:
    stack = []
    out = []
    pos = 0
    changed = 0
    for m in TAG.finditer(text):
        out.append(text[pos:m.start()])
        color = m.group(1)
        closing = text[m.start() + 1] == "/"
        if not closing:
            stack.append(color)
            out.append(m.group(0))
        elif stack:
            expected = stack.pop()
            if expected.lower() != color.lower():
                changed += 1
                out.append(f"</{expected}>")
            else:
                out.append(m.group(0))
        else:
            # лишний закрывающий тег — просто убираем
            changed += 1
        pos = m.end()
    out.append(text[pos:])
    return "".join(out), changed


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    total = 0
    for name in ("messages_ru.yml", "messages_en.yml"):
        path = root / "src" / "main" / "resources" / name
        if not path.is_file():
            continue
        original = path.read_text(encoding="utf-8")
        fixed_lines = []
        count = 0
        for line in original.split("\n"):
            fixed, n = fix_value(line)
            fixed_lines.append(fixed)
            count += n
        if count:
            path.write_text("\n".join(fixed_lines), encoding="utf-8", newline="\n")
            total += count
            print(f"{name}: исправлено несовпадающих закрытий: {count}")
        else:
            print(f"{name}: теги согласованы")
    print(f"всего исправлено: {total}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
