package ru.hahgps.update;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Самописный парсер JSON: глубоко вложенный документ больше не роняет
 * StackOverflowError (он — Error и не ловился catch (Exception)).
 */
class JsonTest {

    @Test
    void parsesObjectAndArray() {
        Map<String, Object> object = Json.object("{\"a\": [1, 2.5, \"x\"], \"b\": true, \"c\": null}");
        assertEquals(List.of(1.0, 2.5, "x"), object.get("a"));
        assertEquals(Boolean.TRUE, object.get("b"));
        assertNull(object.get("c"));
    }

    @Test
    void rejectsTooDeepNesting() {
        String deep = "[".repeat(500) + "]".repeat(500);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Json.parse(deep));
        assertTrue(error.getMessage().contains("вложенность"), error.getMessage());
    }

    @Test
    void rejectsBrokenNumber() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Json.parse("[1.2.3]"));
        assertTrue(error.getMessage().contains("число"), error.getMessage());
    }

    @Test
    void rejectsBrokenUnicodeEscape() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Json.parse("\"\\uZZZZ\""));
        assertTrue(error.getMessage().contains("\\u"), error.getMessage());
    }

    @Test
    void rejectsTrailingGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} trailing"));
    }

    @Test
    void arrayOfErrorObjectIsVisibleToCaller() {
        // GitHub на 200 иногда отдаёт объект ошибки: array() вернул бы пусто,
        // и «релизов нет» маскировало бы rate limit.
        Object parsed = Json.parse("{\"message\": \"API rate limit exceeded\"}");
        assertTrue(parsed instanceof Map);
    }
}
