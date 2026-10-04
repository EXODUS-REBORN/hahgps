import os

def strip_java_comments(text):
    result = []
    i = 0
    n = len(text)
    while i < n:
        if text[i] == '"':
            j = i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                j += 1
            result.append(text[i:j])
            i = j
            continue
        if text[i] == "'":
            j = i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == "'":
                    j += 1
                    break
                j += 1
            result.append(text[i:j])
            i = j
            continue
        if text[i:i+2] == '//':
            j = text.find('\n', i)
            if j == -1:
                i = n
            else:
                i = j
            continue
        if text[i:i+2] == '/*':
            j = text.find('*/', i + 2)
            if j == -1:
                i = n
            else:
                i = j + 2
            continue
        result.append(text[i])
        i += 1
    return ''.join(result)

def process_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    stripped = strip_java_comments(content)
    lines = stripped.split('\n')
    cleaned_lines = [line.rstrip() for line in lines]
    while cleaned_lines and cleaned_lines[0] == '':
        cleaned_lines.pop(0)
    while cleaned_lines and cleaned_lines[-1] == '':
        cleaned_lines.pop()
    result = '\n'.join(cleaned_lines) + '\n'
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(result)
    return len(content) - len(result)

def main():
    src_dir = r'E:\для проекта NewJersey\hahgps\src\test\java'
    total_removed = 0
    file_count = 0
    for root, dirs, files in os.walk(src_dir):
        for fname in files:
            if fname.endswith('.java'):
                fpath = os.path.join(root, fname)
                removed = process_file(fpath)
                total_removed += removed
                file_count += 1
                print(f'  {fname}: -{removed} chars')
    print(f'\nTotal: {file_count} files, {total_removed} chars removed')

if __name__ == '__main__':
    main()
