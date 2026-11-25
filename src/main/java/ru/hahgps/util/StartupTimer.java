package ru.hahgps.util;

import ru.hahgps.HahGpsPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Замер этапов включения и выключения плагина.
 *
 * Владелец жаловался на скачок MSPT при перезагрузке плагина: главный поток
 * сервера занят, пока идёт onEnable/onDisable, и без цифр причину приходилось
 * искать в коде. Таймер печатает одной строкой, сколько занял каждый этап,
 * поэтому видно, что именно стоит оптимизировать на живом сервере.
 *
 * Печатается только когда общее время заметное ({@link #REPORT_MS}): не
 * засоряем консоль при обычном старте.
 */
public final class StartupTimer {

    /** Порог, после которого строка уходит в лог. */
    public static final long REPORT_MS = 50L;

    private final String what;
    private final List<String> phases = new ArrayList<>(6);
    private final long start = System.nanoTime();
    private long mark = start;
    private boolean reported;

    public StartupTimer(String what) {
        this.what = what;
    }

    /** Отметить конец этапа: в отчёт уйдёт «имя=сколько мс» с прошлой отметки. */
    public void phase(String name) {
        long now = System.nanoTime();
        phases.add(name + "=" + millis(now - mark));
        mark = now;
    }

    /** Итог одной строкой; повторный вызов ничего не делает. */
    public void report(HahGpsPlugin plugin) {
        if (reported) {
            return;
        }
        reported = true;
        long total = (System.nanoTime() - start) / 1_000_000L;
        if (total < REPORT_MS) {
            return;
        }
        StringBuilder line = new StringBuilder();
        line.append("HahGps: ").append(what).append(' ').append(total).append(" мс");
        if (!phases.isEmpty()) {
            line.append(" (");
            for (int i = 0; i < phases.size(); i++) {
                if (i > 0) {
                    line.append(", ");
                }
                line.append(phases.get(i));
            }
            line.append(')');
        }
        try {
            plugin.getLogger().info(line.toString());
        } catch (Throwable ignored) {
            // Логирование не должно ронять включение.
        }
    }

    public long totalMs() {
        return (System.nanoTime() - start) / 1_000_000L;
    }

    private static long millis(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }
}
