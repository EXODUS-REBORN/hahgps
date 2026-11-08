package ru.hahgps.update;

import java.util.Locale;

/**
 * Проверка, жив ли загрузчик классов текущей копии плагина.
 *
 * <p>Paper закрывает загрузчик вместе с его jar в {@code disablePlugin()}
 * ({@code JavaPluginLoader.disablePlugin} → {@code PluginClassLoader.close()}).
 * Если сервер затем поднимет тот же экземпляр обратно (перезагрузка сервера,
 * плагин-менеджер, неудачная горячая подмена), уже загруженные классы работают,
 * а любой ещё не загруженный падает с «The plugin classloader for HahGps has
 * thrown a zip file error» и причиной «zip file closed».
 *
 * <p>Проба — ресурсы из корня нашего jar: живой загрузчик отдаёт хотя бы один,
 * закрытый не отдаёт ни одного ({@code URLClassLoader} после {@code close()}
 * возвращает {@code null}, тест {@code LoaderStateTest}). Класс чистый, без
 * Bukkit: его зовут и тогда, когда загрузчик уже мёртв, поэтому прогрев —
 * {@link HotSwap#prime()}.
 */
public final class LoaderState {

    private static final String[] ANCHORS = {"plugin.yml", "hahtojer.token"};

    private LoaderState() {
    }

    /** {@code true} — загрузчик закрыт, подмена невозможна: нужен перезапуск сервера. */
    public static boolean closed(ClassLoader loader) {
        if (loader == null) return false;
        for (String anchor : ANCHORS) {
            try {
                if (loader.getResource(anchor) != null) return false;
            } catch (Throwable t) {
                if (zipClosed(t)) return true;
            }
        }
        return true;
    }

    static boolean zipClosed(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("zip file closed")) {
                return true;
            }
        }
        return false;
    }
}
