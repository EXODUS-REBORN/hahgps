package ru.hahgps.update;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {

    /** Лимит вложенности для рекурсивного парсера: защита от StackOverflowError. */
    private static final int MAX_DEPTH = 64;

    private final String src;
    private int pos;
    private int depth;

    private Json(String src) {
        this.src = src;
    }

    static Object parse(String text) {
        Json p = new Json(text == null ? "" : text);
        p.skipWs();
        Object value = p.value();
        p.skipWs();
        if (p.pos != p.src.length()) {
            throw p.error("лишние данные после JSON");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        Object parsed = parse(text);
        return parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(String text) {
        Object parsed = parse(text);
        return parsed instanceof List ? (List<Object>) parsed : List.of();
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private Object value() {
        if (depth >= MAX_DEPTH) {
            // Рекурсивный парсер без лимита стека: глубоко вложенный JSON
            // (ответ API под атакой) давал StackOverflowError, который не
            // ловится catch (Exception) и утекал сырым стеком в консоль.
            throw error("слишком глубокая вложенность (лимит " + MAX_DEPTH + ")");
        }
        if (pos >= src.length()) throw error("неожиданный конец документа");
        depth++;
        try {
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> objectBody();
                case '[' -> arrayBody();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        } finally {
            depth--;
        }
    }

    private Object literal(String word, Object value) {
        if (!src.startsWith(word, pos)) throw error("ожидалось " + word);
        pos += word.length();
        return value;
    }

    private Map<String, Object> objectBody() {
        Map<String, Object> out = new LinkedHashMap<>();
        pos++;
        skipWs();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipWs();
            String key = string();
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != ':') throw error("ожидалось ':'");
            pos++;
            skipWs();
            out.put(key, value());
            skipWs();
            if (pos >= src.length()) throw error("объект не закрыт");
            char c = src.charAt(pos++);
            if (c == '}') return out;
            if (c != ',') throw error("ожидалось ',' или '}'");
        }
    }

    private List<Object> arrayBody() {
        List<Object> out = new ArrayList<>();
        pos++;
        skipWs();
        if (pos < src.length() && src.charAt(pos) == ']') {
            pos++;
            return out;
        }
        while (true) {
            skipWs();
            out.add(value());
            skipWs();
            if (pos >= src.length()) throw error("массив не закрыт");
            char c = src.charAt(pos++);
            if (c == ']') return out;
            if (c != ',') throw error("ожидалось ',' или ']'");
        }
    }

    private String string() {
        if (pos >= src.length() || src.charAt(pos) != '"') throw error("ожидалась строка");
        pos++;
        StringBuilder out = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') return out.toString();
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (pos >= src.length()) break;
            char esc = src.charAt(pos++);
            switch (esc) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (pos + 4 > src.length()) throw error("обрыв \\u-последовательности");
                    String hex = src.substring(pos, pos + 4);
                    try {
                        out.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException bad) {
                        throw error("некорректная \\u-последовательность: " + hex);
                    }
                    pos += 4;
                }
                default -> out.append(esc);
            }
        }
        throw error("строка не закрыта");
    }

    private Object number() {
        int start = pos;
        while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) pos++;
        String text = src.substring(start, pos);
        if (text.isEmpty()) throw error("не число");
        try {
            return Double.valueOf(text);
        } catch (NumberFormatException bad) {
            throw error("некорректное число: " + text);
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("JSON: " + message + " (позиция " + pos + ")");
    }
}
