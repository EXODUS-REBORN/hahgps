package ru.hahgps.vmodel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void parsesNestedStructures() {
        Object value = Json.parse("""
                {"a":1,"b":[true,false,null,"x"],"c":{"d":-2.5e1}}
                """);
        assertEquals(1.0, Json.number(value, "a", 0), 1e-9, "число");
        assertEquals(4, Json.array(value, "b").size(), "массив null не теряет");
        assertEquals(-25.0, Json.number(Json.object(value, "c"), "d", 0), 1e-9, "экспонента");
        assertEquals("x", Json.array(value, "b").get(3), "строка в массиве");
    }

    @Test
    void parsesEscapedStrings() {
        Object value = Json.parse("{\"t\":\"a\\\"b\\\\c\\n\\u0416\"}");
        assertEquals("a\"b\\c\nЖ", Json.string(value, "t", ""), "escape-последовательности");
    }

    @Test
    void boolAndNumbersWithFallbacks() {
        Object value = Json.parse("{\"t\":true,\"f\":\"false\",\"n\":\"12\",\"bad\":\"ой\"}");
        assertTrue(Json.bool(value, "t", false), "true");
        assertEquals(false, Json.bool(value, "f", true), "строка false");
        assertEquals(12, Json.integer(value, "n", 0), "число из строки");
        assertEquals(7, Json.integer(value, "bad", 7), "мусор — fallback");
        assertEquals(7.0, Json.number(value, "missing", 7), 1e-9, "нет ключа — fallback");
    }

    @Test
    void numbersListKeepsShape() {
        List<Double> values = Json.numbers(Json.parse("[1,2.5,\"3\",null]"));
        assertEquals(4, values.size(), "длина сохраняется");
        assertEquals(3.0, Json.at(values, 2, 0), 1e-9, "число из строки");
        assertEquals(9.0, Json.at(values, 9, 9), 1e-9, "за пределами — fallback");
    }

    @Test
    void writesCompactJson() {
        String raw = Json.write(Json.map("a", 1, "b", List.of(true, "x"),
                "c", Json.map("d", 2.5)));
        assertEquals("{\"a\":1,\"b\":[true,\"x\"],\"c\":{\"d\":2.5}}", raw, "компактная запись");
    }

    @Test
    void rejectsBrokenInput() {
        for (String broken : List.of("", "{", "{\"a\":}", "[1,]x", "{\"a\" 1}")) {
            boolean failed = false;
            try {
                Json.parse(broken);
            } catch (IllegalArgumentException expected) {
                failed = true;
            }
            assertTrue(failed, "битый JSON должен падать: " + broken);
        }
    }
}
