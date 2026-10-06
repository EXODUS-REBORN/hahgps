package ru.hahgps.service;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Запись маршрута: игрок проходит дорогу, плагин запоминает точки каждые
 * {@link #MIN_STEP} блоков, потом маршрут сохраняется в БД.
 *
 * Зачем это здесь: граф дорог собирается из сохранённых маршрутов, а команд для
 * их создания в плагине не было — то есть на живом сервере маршрутизация всегда
 * вырождалась в прямую линию, а процедурный поиск пути просто не работал.
 */
public class RouteRecordingService {

    /** Минимальное расстояние между точками: чаще — мусор, реже — углы теряются. */
    public static final double MIN_STEP = 4.0;

    /** Ограничение на длину записи: 2000 точек по 4 блока — это 8 км пути. */
    public static final int MAX_POINTS = 2000;

    private static final class Session {
        private final List<Location> points = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean recording;
    }

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public boolean isRecording(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.recording;
    }

    public void start(Player player) {
        Session session = new Session();
        session.points.add(player.getLocation().clone());
        session.recording = true;
        sessions.put(player.getUniqueId(), session);
    }

    /** Добавляет точку, если игрок отошёл от предыдущей не меньше чем на MIN_STEP. */
    public boolean add(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.recording) {
            return false;
        }
        Location loc = player.getLocation();
        synchronized (session.points) {
            if (session.points.isEmpty()) {
                session.points.add(loc.clone());
                return true;
            }
            Location last = session.points.get(session.points.size() - 1);
            // Смена мира разрывает линию: маршрут в графе живёт внутри одного мира.
            if (last.getWorld() == null || !last.getWorld().equals(loc.getWorld())) {
                return false;
            }
            if (last.distance(loc) < MIN_STEP) {
                return false;
            }
            if (session.points.size() >= MAX_POINTS) {
                return false;
            }
            session.points.add(loc.clone());
            return true;
        }
    }

    /** Останавливает запись и оставляет точки ждать сохранения. */
    public List<Location> stop(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return List.of();
        }
        session.recording = false;
        synchronized (session.points) {
            return new ArrayList<>(session.points);
        }
    }

    /** Записанный, но ещё не сохранённый маршрут. */
    public List<Location> pending(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.recording) {
            return List.of();
        }
        synchronized (session.points) {
            return new ArrayList<>(session.points);
        }
    }

    public void forget(Player player) {
        sessions.remove(player.getUniqueId());
    }
}
