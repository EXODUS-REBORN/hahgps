#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Удаляет все комментарии из Java-файлов, сохраняя строковые литералы."""
import sys
from pathlib import Path


def remove_comments(source):
    result = []
    line_had_comment = {}
    i = 0
    n = len(source)
    state = 'normal'
    line_num = 0

    while i < n:
        if state == 'normal':
            if source[i] == '"':
                if i + 2 < n and source[i + 1] == '"' and source[i + 2] == '"':
                    state = 'text_block'
                    result.append(source[i:i + 3])
                    i += 3
                    continue
                state = 'string'
                result.append(source[i])
                i += 1
            elif source[i] == "'":
                state = 'char'
                result.append(source[i])
                i += 1
            elif source[i] == '/' and i + 1 < n and source[i + 1] == '/':
                state = 'line_comment'
                line_had_comment[line_num] = True
                i += 2
            elif source[i] == '/' and i + 1 < n and source[i + 1] == '*':
                state = 'block_comment'
                line_had_comment[line_num] = True
                i += 2
            else:
                result.append(source[i])
                i += 1
        elif state == 'string':
            if source[i] == '\\':
                result.append(source[i])
                if i + 1 < n:
                    result.append(source[i + 1])
                    i += 2
                else:
                    i += 1
            elif source[i] == '"':
                state = 'normal'
                result.append(source[i])
                i += 1
            else:
                result.append(source[i])
                i += 1
        elif state == 'char':
            if source[i] == '\\':
                result.append(source[i])
                if i + 1 < n:
                    result.append(source[i + 1])
                    i += 2
                else:
                    i += 1
            elif source[i] == "'":
                state = 'normal'
                result.append(source[i])
                i += 1
            else:
                result.append(source[i])
                i += 1
        elif state == 'text_block':
            if source[i] == '"' and i + 2 < n and source[i + 1] == '"' and source[i + 2] == '"':
                state = 'normal'
                result.append(source[i:i + 3])
                i += 3
            else:
                result.append(source[i])
                i += 1
        elif state == 'line_comment':
            line_had_comment[line_num] = True
            if source[i] == '\n':
                state = 'normal'
                result.append('\n')
                line_num += 1
                i += 1
            else:
                i += 1
        elif state == 'block_comment':
            line_had_comment[line_num] = True
            if source[i] == '*' and i + 1 < n and source[i + 1] == '/':
                state = 'normal'
                i += 2
            else:
                if source[i] == '\n':
                    result.append('\n')
                    line_num += 1
                i += 1

    return ''.join(result), line_had_comment


def process_file(source):
    cleaned, line_had_comment = remove_comments(source)
    lines = cleaned.split('\n')
    result = []
    for i, line in enumerate(lines):
        stripped = line.rstrip()
        if stripped == '' and line_had_comment.get(i, False):
            continue
        result.append(stripped)
    final = []
    prev_blank = False
    for line in result:
        if line == '':
            if prev_blank:
                continue
            prev_blank = True
        else:
            prev_blank = False
        final.append(line)
    return '\n'.join(final)


def main():
    root = Path(__file__).resolve().parents[1]
    java_dir = root / 'src' / 'main' / 'java'
    changed = 0
    for path in sorted(java_dir.rglob('*.java')):
        original = path.read_text(encoding='utf-8')
        processed = process_file(original)
        if processed != original:
            path.write_text(processed, encoding='utf-8', newline='\n')
            changed += 1
            print(f'{path.relative_to(root)}: комментарии удалены')
    print(f'файлов изменено: {changed}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
