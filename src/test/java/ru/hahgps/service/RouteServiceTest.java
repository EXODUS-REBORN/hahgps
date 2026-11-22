package ru.hahgps.service;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import ru.hahgps.testsupport.FakeWorld;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Выбор между графом дорог и пешим путём по блокам.
 *
 * Раньше любой непустой граф побеждал: если админ один раз записал дорогу,
 * маршрут к цели рядом мог уйти в объезд по этой дороге. Теперь пешеходу
 * разрешено выбрать более короткий путь по блокам, но только когда крюк
 * действительно заметный.
 */
class RouteServiceTest {

    private static final World WORLD = FakeWorld.named("world");

    private static Location at(double x, double z) {
        return new Location(WORLD, x, 64, z);
    }

    private final RouteService service = new RouteService(null, null);

    @Test
    void straightLineFromMissingGraphIsReplacedByWalkPath() {
        assertTrue(service.shouldTryWalk(List.of(at(0, 0), at(50, 0)), at(0, 0), at(50, 0)));
    }

    @Test
    void smallRoadBendKeepsTheGraphRoute() {
        // 100 блоков прямой и 118 по дороге — это нормальный изгиб дороги.
        List<Location> route = List.of(at(0, 0), at(30, 10), at(70, 10), at(100, 0));
        assertFalse(service.shouldTryWalk(route, at(0, 0), at(100, 0)));
    }

    @Test
    void bigDetourAlongRecordedRoadIsRejected() {
        // Прямая 40 блоков, а записанная дорога уводит на 140 — пешком короче.
        List<Location> route = List.of(at(0, 0), at(0, 70), at(40, 70), at(40, 0));
        assertTrue(service.shouldTryWalk(route, at(0, 0), at(40, 0)));
    }

    @Test
    void tooShortDetourStillWinsForWalking() {
        // 20 блоков прямой, 60 по дороге: крюк в 40 блоков уже не «погрешность».
        List<Location> route = List.of(at(0, 0), at(0, 30), at(20, 30), at(20, 0));
        assertTrue(service.shouldTryWalk(route, at(0, 0), at(20, 0)));
    }

    @Test
    void missingLocationsDoNotAskForWalkPath() {
        assertFalse(service.shouldTryWalk(List.of(), at(0, 0), at(10, 0)));
        assertFalse(service.shouldTryWalk(List.of(at(0, 0), at(1, 0), at(2, 0)), null, null));
    }
}
