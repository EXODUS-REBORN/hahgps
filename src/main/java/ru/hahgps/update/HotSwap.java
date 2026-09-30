package ru.hahgps.update;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.hahgps.HahGpsPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Горячая подмена плагина: новая версия ставится и запускается <b>без перезапуска сервера</b>.
 *
 * <p>Как это работает (Paper 1.21+):
 * <ol>
 *   <li>свежий jar копируется в {@code plugins/hahgps-<версия>.jar} — до всякой выгрузки,
 *       поэтому неудача на этом шаге ничего не ломает;</li>
 *   <li>у старого экземпляра закрываются открытые меню игроков и снимаются команды (иначе
 *       {@code /gps} остался бы за выгруженным плагином и отвечал «plugin is disabled»,
 *       а из «мёртвого» меню можно было бы забрать предметы);</li>
 *   <li>{@code PluginManager.disablePlugin} сохраняет данные и закрывает classloader старого
 *       плагина — заодно отпускается блокировка jar-файла (важно для Windows);</li>
 *   <li>{@code PluginManager.loadPlugin} + {@code enablePlugin} поднимают новую версию из свежего
 *       jar; она сама открывает базы и перечитывает состояние;</li>
 *   <li>старый jar удаляется, команды пересобираются в Brigadier — игроки ничего не замечают.</li>
 * </ol>
 *
 * <p>Если на любом шаге после выгрузки что-то падает — откат: прежний jar загружается обратно,
 * версия помечается как «не встала на ходу» (повтор автоматически не долбим), а в лог и админам
 * уходит причина. Обновление в этом случае применится при следующем рестарте сервера.
 *
 * <p><b>Важно про classloader.</b> Код этого класса продолжает выполняться уже после того, как
 * classloader старого плагина закрыт. Поэтому после выгрузки нельзя ни загружать новые классы
 * плагина, ни звать его утилиты: всё, что нужно показать, готовится заранее, а после подмены
 * используются только JDK, Bukkit API и файлы-маркеры, которые читает новая (или возвращённая)
 * копия плагина при своём {@code onEnable}.
 */
public final class HotSwap {

    /** Что получилось. {@code reason} — причина отказа для лога и маркера. */
    public record Result(boolean applied, String version, String reason) {
    }

    /** Результат последней попытки (для статуса/диагностики). */
    private static volatile String lastResult = "";

    /** Идёт ли подмена прямо сейчас (повторно не запускаем). */
    private static volatile boolean running;

    /** Подмена уже поставлена в очередь задач (повторно не планируем). */
    private static volatile boolean queued;

    /** Файл-маркер: версия, которая не встала на ходу (автоповтор не делаем). */
    private static final String FAILED = "hot-failed.txt";

    /** Файл-маркер: результат последней подмены — его читает уже новая копия плагина. */
    private static final String STATE = "hot-last.txt";

    /** Через сколько минут маркер результата считается устаревшим (после падения сервера). */
    private static final long STATE_FRESH_MS = 5L * 60L * 1000L;

    private HotSwap() {
    }

    public static boolean running() {
        return running;
    }

    public static String lastResult() {
        return lastResult;
    }

    // ── запуск ───────────────────────────────────────────────────────────────────

    /**
     * Подменяет плагин на скачанный jar и перезагружает его на ходу.
     *
     * <p>Работа всегда уходит в отдельную задачу сервера: команда (или проверка обновления),
     * которая попросила подмену, успевает завершиться <b>до</b> выгрузки, поэтому после подмены
     * управление не возвращается в код уже закрытого classloader'а.
     *
     * @param staged   подготовленный (полностью скачанный) jar
     * @param force    {@code true} — ручная команда: пробуем даже после прошлой неудачи
     * @param feedback куда сказать про начало попытки (до выгрузки; после — уже молчит)
     */
    public static Result apply(HahGpsPlugin plugin, Path staged, boolean force, Consumer<String> feedback) {
        return apply(plugin, staged, force, feedback, false);
    }

    private static Result apply(HahGpsPlugin plugin, Path staged, boolean force, Consumer<String> feedback,
                                boolean deferred) {
        if (running || queued) {
            reply(feedback, "Горячая подмена уже идёт — подожди.");
            return new Result(false, versionFromJarName(staged), "подмена уже идёт");
        }
        if (plugin == null || staged == null) {
            return new Result(false, "", "нечего ставить");
        }
        if (!deferred) {
            queued = true;
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    queued = false;
                    apply(plugin, staged, force, feedback, true);
                });
            } catch (Throwable t) {
                queued = false;
                String reason = "не удалось запланировать подмену: " + message(t);
                log(Level.SEVERE, "Горячая подмена: " + reason, t);
                return new Result(false, versionFromJarName(staged), reason);
            }
            return new Result(false, versionFromJarName(staged), "поставлено в очередь");
        }
        if (!Bukkit.isPrimaryThread()) {
            // Выгрузка/загрузка плагина — только в главном потоке.
            try {
                Bukkit.getScheduler().runTask(plugin, () -> apply(plugin, staged, force, feedback, true));
            } catch (Throwable t) {
                return new Result(false, versionFromJarName(staged), message(t));
            }
            return new Result(false, versionFromJarName(staged), "перенесено в главный поток");
        }

        running = true;
        try {
            Result result = doApply(plugin, staged, force, feedback);
            lastResult = result.applied()
                    ? "применено на ходу: " + result.version()
                    : "не удалось: " + result.reason();
            return result;
        } catch (Throwable t) {
            lastResult = "сбой: " + message(t);
            log(Level.SEVERE, "Горячая подмена: сбой — " + message(t), t);
            return new Result(false, versionFromJarName(staged), message(t));
        } finally {
            running = false;
        }
    }

    /**
     * Подтягиваем классы, которые понадобятся <b>после</b> закрытия classloader'а плагина.
     * Запись {@code Result} грузится лениво при первом {@code new Result(...)} — без прогрева
     * она падала бы уже после выгрузки («zip file closed»).
     */
    private static void warmup() {
        Result probe = new Result(false, "", "");
        probe.applied();
        probe.version();
        probe.reason();
    }

    private static Result doApply(HahGpsPlugin plugin, Path staged, boolean force, Consumer<String> feedback) {
        String version = versionFromJarName(staged);
        warmup();

        // Всё, что нужно сказать и записать, готовим ДО выгрузки: после закрытия classloader'а
        // обращаться к классам плагина уже нельзя.
        String textStart = "Горячее обновление: ставлю " + version + " на ходу…";
        String textMissing = "Файл обновления не найден: " + staged.getFileName();
        String textNoJar = "Не удалось найти собственный jar в plugins/ — горячая подмена невозможна.";
        String textAlready = "Версия " + version + " уже установлена.";
        String textRetry = "Версия " + version + " уже не встала на ходу — автоматически не повторяю. "
                + "Попробуй вручную: /gps update apply";
        String textOk = "Горячее обновление готово: " + version + " работает.";
        // Шаблоны с %-подстановками: заполняем их уже после выгрузки обычным replace.
        String failTemplate = "Горячее обновление " + version + " не удалось: %ошибка%";
        String rollbackTemplate = "Вернулся на прежнюю версию: %ошибка%";
        Path updateDir = plugin.getDataFolder().toPath().resolve("update");

        if (!Files.isRegularFile(staged)) {
            reply(feedback, textMissing);
            return new Result(false, version, "файла нет");
        }
        Path pluginsDir = pluginsFolder(plugin);
        Path oldJar = currentJar(plugin, pluginsDir);
        Path target = pluginsDir == null ? null
                : pluginsDir.resolve(UpdaterService.JAR_PREFIX + "-" + version + ".jar");
        if (pluginsDir == null || target == null) {
            reply(feedback, textNoJar);
            return new Result(false, version, "не найден каталог plugins/");
        }
        if (oldJar == null) {
            reply(feedback, textNoJar);
            return new Result(false, version, "не найден свой jar в plugins/");
        }
        if (sameFile(oldJar, target)) {
            reply(feedback, textAlready);
            return new Result(true, version, "уже эта версия");
        }
        if (!force && failedVersion(updateDir).equals(version)) {
            reply(feedback, textRetry);
            return new Result(false, version, "прошлая попытка не удалась");
        }

        // Шаг 1: кладём новый jar рядом со старым. Здесь ещё ничего не тронуто — любая ошибка безопасна.
        try {
            Files.copy(staged, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            String reason = "не удалось положить новый jar: " + message(e);
            reply(feedback, fill(failTemplate, version, reason));
            return new Result(false, version, reason);
        }

        reply(feedback, textStart);
        log(Level.INFO, "Горячая подмена: ставлю " + version + " на ходу…", null);

        // Шаг 2: закрываем открытые меню плагина и снимаем его команды со старого экземпляра.
        closeOwnGuis(plugin);
        unregisterCommands(plugin);

        // Шаг 3: выгружаем старую версию — данные сохраняются, classloader закрывается,
        //         блокировка jar снимается (важно для Windows).
        plugin.setHotSwapping(true);
        try {
            Bukkit.getPluginManager().disablePlugin(plugin);
        } catch (Throwable t) {
            plugin.setHotSwapping(false);
            retire(target);
            String reason = "не удалось выгрузить прежнюю версию: " + message(t);
            log(Level.SEVERE, "Горячая подмена: " + reason, t);
            return new Result(false, version, reason);
        }

        // Всё, что ниже, выполняется уже с закрытым classloader'ом старого плагина:
        // только JDK, Bukkit API и файлы.
        Bukkit.getLogger().info("Горячая подмена: старая версия выгружена, поднимаю " + version + "…");
        // Paper отказывается грузить jar с именем, которое уже занято (даже выключенным плагином):
        // «attempted to add duplicate plugin identifier … THIS WILL CREATE BUGS». Поэтому снимаем
        // старый экземпляр с реестров менеджера — как при настоящей выгрузке.
        releaseInstance(plugin);

        // Шаг 4: снимаем старый jar с дороги только ПОСЛЕ успешного запуска новой версии —
        //         иначе при откате нечего будет вернуть.
        Plugin loaded = null;
        try {
            loaded = Bukkit.getPluginManager().loadPlugin(target.toFile());
        } catch (Throwable t) {
            return rollback(oldJar, target, updateDir, version, "новая версия не загрузилась: " + message(t),
                    rollbackTemplate, null);
        }
        if (loaded == null) {
            return rollback(oldJar, target, updateDir, version, "новая версия не загрузилась",
                    rollbackTemplate, null);
        }

        writeState(updateDir, version, true, "");
        try {
            Bukkit.getPluginManager().enablePlugin(loaded);
        } catch (Throwable t) {
            return rollback(oldJar, target, updateDir, version, "новая версия не включилась: " + message(t),
                    rollbackTemplate, loaded);
        }
        if (!loaded.isEnabled()) {
            return rollback(oldJar, target, updateDir, version, "новая версия не включилась",
                    rollbackTemplate, loaded);
        }

        // Успех: убираем прежний jar и подготовленный файл, пересобираем команды в Brigadier.
        clearFailed(updateDir);
        retireJar(oldJar, updateDir);
        try {
            Files.deleteIfExists(staged);
        } catch (IOException e) {
            log(Level.WARNING, "Горячая подмена: не удалось удалить подготовленный файл ("
                    + message(e) + ")", null);
        }
        syncCommands();
        Bukkit.getLogger().info("Горячая подмена: " + version + " работает — сервер не перезапускался.");
        return new Result(true, version, "");
    }

    // ── откат ────────────────────────────────────────────────────────────────────

    /**
     * Откат: прежний jar поднимается обратно, версия помечается «не встала на ходу»,
     * а причину показывает уже возвращённая копия плагина (через маркер).
     */
    private static Result rollback(Path oldJar, Path target, Path updateDir,
                                   String version, String reason, String rollbackTemplate, Plugin failed) {
        log(Level.SEVERE, "Горячая подмена не удалась: " + reason + " — возвращаю прежнюю версию.", null);
        retire(target);
        markFailed(updateDir, version);
        writeState(updateDir, version, false, reason);
        // Если новая версия успела зарегистрироваться (не включилась) — снимаем и её,
        // иначе Paper не даст загрузить прежнюю под тем же именем.
        releaseInstance(failed);

        if (oldJar == null || !Files.isRegularFile(oldJar)) {
            Bukkit.getLogger().severe("Горячая подмена: прежний jar не найден — плагин поднимется после "
                    + "перезапуска сервера. Ошибка: " + reason);
            return new Result(false, version, reason);
        }
        Plugin back = null;
        try {
            back = Bukkit.getPluginManager().loadPlugin(oldJar.toFile());
        } catch (Throwable t) {
            log(Level.SEVERE, "Горячая подмена: прежняя версия не загрузилась (" + message(t) + ")", t);
        }
        if (back != null) {
            try {
                Bukkit.getPluginManager().enablePlugin(back);
            } catch (Throwable t) {
                log(Level.SEVERE, "Горячая подмена: прежняя версия не включилась (" + message(t) + ")", t);
            }
        }
        if (back == null || !back.isEnabled()) {
            Bukkit.getLogger().severe("Горячая подмена: откат не удался — плагин поднимется после перезапуска "
                    + "сервера. " + fill(rollbackTemplate, reason));
        } else {
            Bukkit.getLogger().warning("Горячая подмена: вернулся на прежнюю версию, обновление применится "
                    + "при перезапуске сервера.");
        }
        // Текст для игрока уже показала возвращённая копия плагина (маркер hot-last.txt).
        return new Result(false, version, reason);
    }

    // ── объявление результата (уже из живой копии плагина) ────────────────────────

    /**
     * Читает маркер последней подмены и говорит админам, что версия сменилась на ходу
     * (или почему не сменилась). Вызывается из {@code onEnable} — своих классов тут сколько угодно.
     */
    public static void announceLastResult(HahGpsPlugin plugin) {
        if (plugin == null) return;
        Path state = plugin.getDataFolder().toPath().resolve("update").resolve(STATE);
        if (!Files.isRegularFile(state)) return;
        // Свежесть смотрим ДО удаления: после падения сервера маркер может быть старым,
        // и объявлять «обновлено на ходу» при обычном запуске нельзя.
        boolean fresh;
        try {
            fresh = System.currentTimeMillis() - Files.getLastModifiedTime(state).toMillis() <= STATE_FRESH_MS;
        } catch (IOException e) {
            fresh = true;
        }
        Map<String, String> values = readState(state);
        try {
            Files.deleteIfExists(state);
        } catch (IOException ignored) {
            // маркер не удалился — не страшно: при следующем запуске он будет уже не свежим
        }
        if (values.isEmpty()) return;
        String version = values.getOrDefault("version", "");
        boolean ok = Boolean.parseBoolean(values.getOrDefault("ok", "false"));
        String current = plugin.getPluginMeta().getVersion();
        if (ok) {
            // Маркер успеха актуален только если мы и есть та самая новая версия и это не старый файл.
            if (!fresh || (!version.isBlank() && !version.equals(current))) return;
            lastResult = "применено на ходу: " + (version.isBlank() ? current : version);
            announce(plugin, "Горячее обновление готово: версия " + (version.isBlank() ? current : version)
                    + " работает — сервер не перезапускался.");
            return;
        }
        String reason = values.getOrDefault("reason", "");
        lastResult = "не удалось: " + reason;
        announce(plugin, "Горячее обновление " + (version.isBlank() ? current : version)
                + " не удалось: " + reason);
    }

    private static void announce(HahGpsPlugin plugin, String text) {
        if (text == null || text.isBlank()) return;
        plugin.getLogger().info(text);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("gps.admin") || player.isOp()) {
                player.sendMessage("§b" + text);
            }
        }
    }

    // ── команды и Brigadier ──────────────────────────────────────────────────────

    /**
     * Закрывает открытые меню плагина у игроков. После подмены их holder'ы принадлежат выгруженной
     * копии: клики по такому «мёртвому» меню уже никто не отменяет (слушатели старой копии сняты,
     * новой — не узнают чужой holder), а значит из него можно было бы забрать предметы.
     */
    private static void closeOwnGuis(HahGpsPlugin plugin) {
        ClassLoader own = plugin.getClass().getClassLoader();
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                org.bukkit.inventory.InventoryView view = player.getOpenInventory();
                if (view == null) continue;
                org.bukkit.inventory.Inventory top = view.getTopInventory();
                if (top == null || top.getHolder() == null) continue;
                if (top.getHolder().getClass().getClassLoader() == own) {
                    player.closeInventory();
                }
            } catch (Throwable ignored) {
                // не смогли закрыть меню — не повод срывать обмен версий
            }
        }
    }

    /**
     * Снимает команды плагина с командной карты. Без этого после выгрузки {@code /gps}
     * остаётся за старым (уже выключенным) экземпляром и отвечает «plugin is disabled».
     */
    private static void unregisterCommands(HahGpsPlugin plugin) {
        try {
            CommandMap map = Bukkit.getCommandMap();
            if (map == null) return;
            Map<String, Command> known = map.getKnownCommands();
            if (known == null) return;
            List<String> keys = new ArrayList<>();
            List<Command> mine = new ArrayList<>();
            for (Map.Entry<String, Command> entry : known.entrySet()) {
                Command command = entry.getValue();
                if (command instanceof PluginCommand pc && pc.getPlugin() == plugin) {
                    keys.add(entry.getKey());
                    if (!mine.contains(command)) mine.add(command);
                }
            }
            for (String key : keys) {
                known.remove(key);
            }
            for (Command command : mine) {
                try {
                    command.unregister(map);
                } catch (Throwable ignored) {
                    // запись из карты уже убрана выше — этого достаточно
                }
            }
        } catch (Throwable t) {
            log(Level.WARNING, "Горячая подмена: команды снять не удалось (" + message(t) + ")", t);
        }
    }

    /**
     * Снимает выгруженный экземпляр с реестров менеджера плагинов. Без этого Paper не даёт
     * загрузить jar с тем же именем: «attempted to add duplicate plugin identifier … THIS WILL
     * CREATE BUGS» — проверка идёт по {@code lookupNames} (имя занято даже выключенным плагином).
     *
     * <p>Имена полей у Paper меняются от версии к версии ({@code SimplePluginManager} →
     * {@code paperPluginManager} → {@code instanceManager} → {@code lookupNames}/{@code plugins}),
     * поэтому не угадываем их, а идём по объектам менеджера и вычищаем наш экземпляр из всех
     * коллекций и карт, где он лежит: по ссылке ({@code ==}) — чужие записи не тронем.
     */
    private static void releaseInstance(Plugin plugin) {
        if (plugin == null) return;
        try {
            scrub(Bukkit.getPluginManager(), plugin, 0);
        } catch (Throwable t) {
            log(Level.WARNING, "Горячая подмена: реестры менеджера плагинов почистить не удалось ("
                    + message(t) + ")", t);
        }
    }

    /** Обход объектов менеджера плагинов на 3 уровня вглубь: коллекции — чистим, «*Manager» — идём дальше. */
    private static void scrub(Object target, Plugin plugin, int depth) {
        if (target == null || depth > 3) return;
        Class<?> type = target.getClass();
        if (type.getName().startsWith("java.")) return;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            java.lang.reflect.Field[] declared;
            try {
                declared = current.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (java.lang.reflect.Field hold : declared) {
                if (java.lang.reflect.Modifier.isStatic(hold.getModifiers())) continue;
                Object value;
                try {
                    hold.setAccessible(true);
                    value = hold.get(target);
                } catch (Throwable t) {
                    continue;
                }
                if (value instanceof java.util.Map<?, ?> map) {
                    dropIdentity(map, plugin);
                } else if (value instanceof java.util.Collection<?> collection) {
                    dropIdentity(collection, plugin);
                } else if (value != null && value != plugin
                        && value.getClass().getName().contains("Manager")) {
                    scrub(value, plugin, depth + 1);
                }
            }
        }
    }

    private static void dropIdentity(java.util.Map<?, ?> map, Plugin plugin) {
        try {
            java.util.Iterator<?> it = map.values().iterator();
            while (it.hasNext()) {
                if (it.next() == plugin) it.remove();
            }
        } catch (Throwable ignored) {
            // неизменяемая карта — не наша забота
        }
    }

    private static void dropIdentity(java.util.Collection<?> collection, Plugin plugin) {
        try {
            java.util.Iterator<?> it = collection.iterator();
            while (it.hasNext()) {
                if (it.next() == plugin) it.remove();
            }
        } catch (Throwable ignored) {
            // неизменяемая коллекция — не наша забота
        }
    }

    /** Пересобирает дерево команд Brigadier, иначе клиент видит старое и падает на «plugin is disabled». */
    private static void syncCommands() {
        try {
            Object server = Bukkit.getServer();
            server.getClass().getMethod("syncCommands").invoke(server);
        } catch (Throwable t) {
            try {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.updateCommands();
                }
            } catch (Throwable ignored) {
                // без пересборки команд плагин всё равно работает: /gps идёт через CommandMap
            }
        }
    }

    // ── файлы ────────────────────────────────────────────────────────────────────

    /** Каталог plugins/ (туда кладём новую версию и оттуда убираем старую). */
    static Path pluginsFolder(HahGpsPlugin plugin) {
        try {
            java.io.File dir = Bukkit.getPluginsFolder();
            if (dir != null) return dir.toPath().toAbsolutePath();
        } catch (Throwable ignored) {
            // ниже — запасной путь через данные плагина
        }
        java.io.File parent = plugin.getDataFolder().getParentFile();
        return parent == null ? null : parent.toPath().toAbsolutePath();
    }

    /**
     * Собственный jar плагина: сначала путь из CodeSource (Paper грузит плагин прямо из
     * {@code plugins/}), иначе — поиск по имени в {@code plugins/}.
     */
    static Path currentJar(HahGpsPlugin plugin, Path pluginsDir) {
        Path own = null;
        try {
            var source = plugin.getClass().getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                own = Path.of(source.getLocation().toURI()).toAbsolutePath();
            }
        } catch (Throwable ignored) {
            own = null;
        }
        if (own != null && pluginsDir != null && own.getParent() != null
                && own.getParent().equals(pluginsDir) && Files.isRegularFile(own)) {
            return own;
        }
        // CodeSource не в plugins/ (например, кэш ремапа) — берём jar текущей версии по имени.
        if (pluginsDir == null || !Files.isDirectory(pluginsDir)) return null;
        String current = plugin.getPluginMeta().getVersion();
        Path fallback = null;
        try (var stream = Files.newDirectoryStream(pluginsDir, UpdaterService.JAR_PREFIX + "*.jar")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) continue;
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!current.isEmpty() && name.contains(current)) return file.toAbsolutePath();
                if (fallback == null) fallback = file.toAbsolutePath();
            }
        } catch (IOException e) {
            return own;
        }
        return fallback == null ? own : fallback;
    }

    static boolean sameFile(Path a, Path b) {
        if (a == null || b == null) return false;
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    /** Убирает файл, который не пригодился (новый jar при откате). */
    private static void retire(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log(Level.WARNING, "Горячая подмена: не удалось убрать " + file.getFileName()
                    + " (" + message(e) + ")", null);
        }
    }

    /**
     * Убирает прежний jar после успешной подмены. Если файл занят — переименовываем в {@code .old}
     * (два jar в plugins/ на следующем запуске дали бы два плагина) и пишем скрипт для ручного шага.
     */
    private static void retireJar(Path oldJar, Path updateDir) {
        if (oldJar == null || !Files.isRegularFile(oldJar)) return;
        try {
            Files.deleteIfExists(oldJar);
            return;
        } catch (IOException e) {
            log(Level.WARNING, "Горячая подмена: прежний jar занят (" + message(e) + "), переименовываю.", null);
        }
        Path backup = oldJar.resolveSibling(oldJar.getFileName().toString() + ".old");
        try {
            Files.move(oldJar, backup, StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (IOException e) {
            log(Level.SEVERE, "Горячая подмена: прежний jar не убрать (" + message(e) + "). Удали "
                    + oldJar.getFileName() + " из plugins/ вручную, иначе после рестарта будет два плагина.", e);
        }
        writeFallbackScript(updateDir, oldJar);
    }

    /** Скрипт для ручного шага, если автоматика не смогла убрать старый файл. */
    private static void writeFallbackScript(Path updateDir, Path oldJar) {
        if (updateDir == null) return;
        try {
            Files.createDirectories(updateDir);
            Files.writeString(updateDir.resolve("apply-update.cmd"),
                    "@echo off\r\n"
                            + "rem Обновление hahgps: убери прежний jar и перезапусти сервер.\r\n"
                            + "del /F /Q \"" + oldJar.toAbsolutePath() + "\"\r\n",
                    StandardCharsets.UTF_8);
            Files.writeString(updateDir.resolve("apply-update.sh"),
                    "#!/bin/sh\n"
                            + "# Обновление hahgps: убери прежний jar и перезапусти сервер.\n"
                            + "rm -f '" + oldJar.toAbsolutePath() + "'\n",
                    StandardCharsets.UTF_8);
            log(Level.WARNING, "Горячая подмена: прежний jar убрать не удалось — удали "
                    + oldJar.getFileName() + " из plugins/ перед рестартом (см. update/apply-update.cmd).", null);
        } catch (IOException e) {
            log(Level.WARNING, "Горячая подмена: скрипт записать не удалось (" + message(e) + ")", e);
        }
    }

    // ── маркеры ──────────────────────────────────────────────────────────────────
    /** Версия, которая уже не встала на ходу (автоматически больше не пробуем). */
    static String failedVersion(Path updateDir) {
        Path file = updateDir == null ? null : updateDir.resolve(FAILED);
        if (file == null || !Files.isRegularFile(file)) return "";
        try {
            return Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    static void markFailed(Path updateDir, String version) {
        if (updateDir == null) return;
        try {
            Files.createDirectories(updateDir);
            Files.writeString(updateDir.resolve(FAILED), version, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log(Level.WARNING, "Горячая подмена: маркер не записать (" + message(e) + ")", null);
        }
    }

    static void clearFailed(Path updateDir) {
        if (updateDir == null) return;
        try {
            Files.deleteIfExists(updateDir.resolve(FAILED));
        } catch (IOException ignored) {
            // не критично: маркер лишь запрещает автоповтор
        }
    }

    /** Результат последней подмены для следующего запуска кода плагина. */
    static void writeState(Path updateDir, String version, boolean ok, String reason) {
        if (updateDir == null) return;
        try {
            Files.createDirectories(updateDir);
            String body = "version=" + (version == null ? "" : version) + "\n"
                    + "ok=" + ok + "\n"
                    + "reason=" + (reason == null ? "" : reason.replace('\n', ' ')) + "\n";
            Files.writeString(updateDir.resolve(STATE), body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log(Level.WARNING, "Горячая подмена: состояние не записать (" + message(e) + ")", null);
        }
    }

    static Map<String, String> readState(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) return out;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
        return out;
    }

    // ── мелочи ───────────────────────────────────────────────────────────────────

    static String versionFromJarName(Path jar) {
        if (jar == null || jar.getFileName() == null) return "";
        return UpdaterService.versionFromJarName(jar);
    }

    private static String fill(String template, String version, String reason) {
        if (template == null) return "";
        return template.replace("%версия%", version == null ? "" : version)
                .replace("%ошибка%", reason == null ? "" : reason);
    }

    private static String fill(String template, String reason) {
        if (template == null) return "";
        return template.replace("%ошибка%", reason == null ? "" : reason);
    }

    private static void reply(Consumer<String> feedback, String text) {
        if (feedback == null || text == null) return;
        try {
            feedback.accept(text);
        } catch (Throwable ignored) {
            // отправитель мог уже отключиться — не повод срывать подмену
        }
    }

    private static void log(Level level, String text, Throwable error) {
        try {
            Logger logger = Bukkit.getLogger();
            if (logger == null) return;
            if (error == null) {
                logger.log(level, text);
            } else {
                logger.log(level, text, error);
            }
        } catch (Throwable ignored) {
            // вне сервера (unit-тесты) логировать некуда
        }
    }

    private static String message(Throwable t) {
        if (t == null) return "";
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
    }
}
