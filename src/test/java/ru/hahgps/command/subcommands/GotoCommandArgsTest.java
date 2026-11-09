package ru.hahgps.command.subcommands;

import org.junit.jupiter.api.Test;
import ru.hahgps.model.TransportType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Кнопка «построить маршрут» в поиске вызывает {@code /gps goto <имя>}: имя
 * может быть из нескольких слов, поэтому все слова до последнего должны
 * остаться названием, а транспорт — только явный последний токен.
 */
class GotoCommandArgsTest {

    @Test
    void singleWordIsAFullName() {
        GotoCommand.RouteArgs args = GotoCommand.parseArgs(new String[]{"Дом"});
        assertEquals("Дом", args.name());
        assertEquals(TransportType.FOOT, args.transport());
        assertFalse(args.explicitTransport());
    }

    @Test
    void multiWordNameKeepsEveryWord() {
        GotoCommand.RouteArgs args = GotoCommand.parseArgs(new String[]{"Хайсовская", "10"});
        assertEquals("Хайсовская 10", args.name());
        assertEquals(TransportType.FOOT, args.transport());
        assertFalse(args.explicitTransport());
    }

    @Test
    void trailingTransportIsSplitFromTheName() {
        GotoCommand.RouteArgs args = GotoCommand.parseArgs(
                new String[]{"Хайсовская", "10", "air"});
        assertEquals("Хайсовская 10", args.name());
        assertEquals(TransportType.AIR, args.transport());
        assertTrue(args.explicitTransport());
    }

    @Test
    void pointNamedLikeATransportStillIsAName() {
        GotoCommand.RouteArgs args = GotoCommand.parseArgs(new String[]{"air"});
        assertEquals("air", args.name());
        assertEquals(TransportType.FOOT, args.transport());
        assertFalse(args.explicitTransport());
    }
}
