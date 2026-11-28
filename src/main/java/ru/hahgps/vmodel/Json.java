package ru.hahgps.vmodel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {

    private static final int MAX_DEPTH = 64;

    private Json() {
    }

    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("пустой ответ");
        }
        Parser parser = new Parser(text);
        parser.skipSpace();
        Object value = parser.value(0);
        parser.skipSpace();
        if (!parser.done()) {
            throw new IllegalArgumentException("лишние символы в JSON на позиции " + parser.pos);
        }
        return value;
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    public static Map<String, Object> object(Object parent, String key) {
        return object(object(parent).get(key));
    }

    public static List<Object> array(Object parent, String key) {
        return array(object(parent).get(key));
    }

    public static String string(Object parent, String key, String fallback) {
        Object value = object(parent).get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? fallback : text;
    }

    public static double number(Object parent, String key, double fallback) {
        return number(object(parent).get(key), fallback);
    }

    public static double number(Object value, double fallback) {
        if (value instanceof Number n) {
            double out = n.doubleValue();
            return Double.isFinite(out) ? out : fallback;
        }
        if (value instanceof String s) {
            try {
                double out = Double.parseDouble(s.trim());
                return Double.isFinite(out) ? out : fallback;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static int integer(Object parent, String key, int fallback) {
        return (int) Math.round(number(parent, key, fallback));
    }

    public static boolean bool(Object parent, String key, boolean fallback) {
        Object value = object(parent).get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            if (s.equalsIgnoreCase("true")) return true;
            if (s.equalsIgnoreCase("false")) return false;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0;
        }
        return fallback;
    }

    public static List<Double> numbers(Object value) {
        List<Double> out = new ArrayList<>();
        for (Object item : array(value)) {
            if (item instanceof Number n) {
                out.add(n.doubleValue());
            } else if (item instanceof String s) {
                try {
                    out.add(Double.parseDouble(s.trim()));
                } catch (NumberFormatException ignored) {
                    out.add(0.0);
                }
            } else {
                out.add(0.0);
            }
        }
        return out;
    }

    public static double at(List<Double> values, int index, double fallback) {
        if (values == null || index < 0 || index >= values.size()) {
            return fallback;
        }
        Double value = values.get(index);
        return value == null ? fallback : value;
    }

    public static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }

    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return out;
    }

    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof String s) {
            writeString(out, s);
            return;
        }
        if (value instanceof Boolean b) {
            out.append(b ? "true" : "false");
            return;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) < 1.0E15) {
                out.append((long) d);
            } else if (Double.isFinite(d)) {
                out.append(d);
            } else {
                out.append('0');
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                writeValue(out, entry.getValue());
            }
            out.append('}');
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) out.append(',');
                first = false;
                writeValue(out, item);
            }
            out.append(']');
            return;
        }
        if (value instanceof double[] doubles) {
            out.append('[');
            for (int i = 0; i < doubles.length; i++) {
                if (i > 0) out.append(',');
                writeValue(out, doubles[i]);
            }
            out.append(']');
            return;
        }
        writeString(out, String.valueOf(value));
    }

    private static void writeString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean done() {
            return pos >= text.length();
        }

        void skipSpace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("слишком глубокая вложенность JSON");
            }
            skipSpace();
            if (done()) {
                throw new IllegalArgumentException("неожиданный конец JSON");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't', 'f' -> bool();
                case 'n' -> nul();
                default -> number();
            };
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> out = new LinkedHashMap<>();
            pos++;
            skipSpace();
            if (!done() && text.charAt(pos) == '}') {
                pos++;
                return out;
            }
            while (true) {
                skipSpace();
                if (done() || text.charAt(pos) != '"') {
                    throw new IllegalArgumentException("ожидалось имя поля на позиции " + pos);
                }
                String key = string();
                skipSpace();
                if (done() || text.charAt(pos) != ':') {
                    throw new IllegalArgumentException("ожидалось ':' на позиции " + pos);
                }
                pos++;
                out.put(key, value(depth + 1));
                skipSpace();
                if (done()) {
                    throw new IllegalArgumentException("незакрытый объект JSON");
                }
                char next = text.charAt(pos);
                if (next == ',') {
                    pos++;
                    continue;
                }
                if (next == '}') {
                    pos++;
                    return out;
                }
                throw new IllegalArgumentException("ожидалось ',' или '}' на позиции " + pos);
            }
        }

        private List<Object> array(int depth) {
            List<Object> out = new ArrayList<>();
            pos++;
            skipSpace();
            if (!done() && text.charAt(pos) == ']') {
                pos++;
                return out;
            }
            while (true) {
                out.add(value(depth + 1));
                skipSpace();
                if (done()) {
                    throw new IllegalArgumentException("незакрытый массив JSON");
                }
                char next = text.charAt(pos);
                if (next == ',') {
                    pos++;
                    continue;
                }
                if (next == ']') {
                    pos++;
                    return out;
                }
                throw new IllegalArgumentException("ожидалось ',' или ']' на позиции " + pos);
            }
        }

        private String string() {
            pos++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (done()) {
                    throw new IllegalArgumentException("незакрытая строка JSON");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (done()) {
                    throw new IllegalArgumentException("обрыв escape-последовательности");
                }
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw new IllegalArgumentException("битый \\u в JSON");
                        }
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("неизвестный escape-символ " + escape);
                }
            }
        }

        private Boolean bool() {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("ожидалось true/false на позиции " + pos);
        }

        private Object nul() {
            if (!text.startsWith("null", pos)) {
                throw new IllegalArgumentException("ожидалось null на позиции " + pos);
            }
            pos += 4;
            return null;
        }

        private Double number() {
            int start = pos;
            if (!done() && (text.charAt(pos) == '-' || text.charAt(pos) == '+')) {
                pos++;
            }
            while (!done()) {
                char c = text.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                        || c == '-' || c == '+') {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new IllegalArgumentException("ожидалось число на позиции " + start);
            }
            try {
                return Double.valueOf(text.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("битое число на позиции " + start);
            }
        }
    }
}
