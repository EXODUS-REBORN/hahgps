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
public final class HotSwap {
    public record Result(boolean applied, String version, String reason) {
    }
    private static volatile String lastResult = "";
    private static volatile boolean running;
    private static volatile boolean queued;
    private static final String FAILED = "hot-failed.txt";
    private static final String STATE = "hot-last.txt";
    private static final long STATE_FRESH_MS = 5L * 60L * 1000L;
    private HotSwap() {
    }
    public static boolean running() {
        return running;
    }

    public static void reset() {
        queued = false;
        running = false;
    }
    public static String lastResult() {
        return lastResult;
    }
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

            // queued снимаем ДО перепланировки: иначе перепланированный вызов
            // увидит «подмена уже идёт» и навсегда останется без ответа.
            queued = false;
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

    private static Result doApply(HahGpsPlugin plugin, Path staged, boolean force, Consumer<String> feedback) {
        String version = versionFromJarName(staged);

        String textStart = "Горячее обновление: ставлю " + version + " на ходу…";
        String textMissing = "Файл обновления не найден: " + staged.getFileName();
        String textNoJar = "Не удалось найти собственный jar в plugins/ — горячая подмена невозможна.";
        String textAlready = "Версия " + version + " уже установлена.";
        String textRetry = "Версия " + version + " уже не встала на ходу — автоматически не повторяю. "
                + "Попробуй вручную: /gps update apply";
        String textOk = "Горячее обновление готово: " + version + " работает.";

        String failTemplate = "Горячее обновление " + version + " не удалось: %ошибка%";
        String rollbackTemplate = "Вернулся на прежнюю версию: %ошибка%";
        Path updateDir = plugin.getDataFolder().toPath().resolve("update");

        if (!Files.isRegularFile(staged)) {
            reply(feedback, textMissing);
            return new Result(false, version, "файла нет");
        }
        try {

            UpdaterService.ensureJar(staged);
        } catch (IOException e) {
            String reason = "повреждённый файл обновления: " + message(e);
            reply(feedback, "Файл обновления повреждён: " + message(e)
                    + " — скачай заново (/gps update install).");
            return new Result(false, version, reason);
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

        try {
            Files.copy(staged, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            String reason = "не удалось положить новый jar: " + message(e);
            reply(feedback, fill(failTemplate, version, reason));
            return new Result(false, version, reason);
        }

        reply(feedback, textStart);
        log(Level.INFO, "Горячая подмена: ставлю " + version + " на ходу…", null);

        closeOwnGuis(plugin);
        unregisterCommands(plugin);

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

        Bukkit.getLogger().info("Горячая подмена: старая версия выгружена, поднимаю " + version + "…");

        releaseInstance(plugin);

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

        // Новая версия работает: только теперь закрываем classloader прежней.
        // Не раньше — откат грузит старый jar заново, а закрытый загрузчик
        // классов уже не отдаст.
        closeClassLoader(plugin);

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
        // Игрок, выполнивший /gps update apply, должен отличить успех от зависания.
        reply(feedback, textOk);
        return new Result(true, version, "");
    }

    /**
     * Закрытие загрузчика классов выгруженного плагина. Paper'овский
     * PaperPluginClassLoader реализует Closeable; без этого каждый hot-swap
     * оставляет в куче классы, слушателей и DataService прежней версии.
     */
    private static void closeClassLoader(HahGpsPlugin plugin) {
        try {
            ClassLoader loader = plugin.getClass().getClassLoader();
            if (loader instanceof java.io.Closeable closeable) {
                closeable.close();
            }
        } catch (Throwable t) {
            log(Level.FINE, "Горячая подмена: classloader прежней версии не закрылся ("
                    + message(t) + ")", null);
        }
    }

    private static Result rollback(Path oldJar, Path target, Path updateDir,
                                   String version, String reason, String rollbackTemplate, Plugin failed) {
        log(Level.SEVERE, "Горячая подмена не удалась: " + reason + " — возвращаю прежнюю версию.", null);
        retire(target);
        markFailed(updateDir, version);
        writeState(updateDir, version, false, reason);

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

        return new Result(false, version, reason);
    }

    public static void announceLastResult(HahGpsPlugin plugin, UpdateAnnouncer announcer) {
        if (plugin == null) return;
        Path state = plugin.getDataFolder().toPath().resolve("update").resolve(STATE);
        if (!Files.isRegularFile(state)) return;

        boolean fresh;
        try {
            fresh = System.currentTimeMillis() - Files.getLastModifiedTime(state).toMillis() <= STATE_FRESH_MS;
        } catch (IOException e) {
            fresh = true;
        }
        Map<String, String> values = readState(state);
        if (values.isEmpty()) {
            // Файл прочитался пустым (прерванная запись): удаляем только
            // теперь — иначе провал никогда не был бы анонсирован.
            try {
                Files.deleteIfExists(state);
            } catch (IOException ignored) {
            }
            return;
        }
        try {
            Files.deleteIfExists(state);
        } catch (IOException ignored) {

        }
        String version = values.getOrDefault("version", "");
        boolean ok = Boolean.parseBoolean(values.getOrDefault("ok", "false"));
        String current = plugin.getPluginMeta().getVersion();
        if (ok) {

            if (!fresh || (!version.isBlank() && !version.equals(current))) return;
            lastResult = "применено на ходу: " + (version.isBlank() ? current : version);

            announcer.announce("update-installed", "version", version.isBlank() ? current : version);
            return;
        }
        String reason = values.getOrDefault("reason", "");
        lastResult = "не удалось: " + reason;
        announcer.announce("update-failed", "error", reason);
    }

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

            }
        }
    }

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

                }
            }
        } catch (Throwable t) {
            log(Level.WARNING, "Горячая подмена: команды снять не удалось (" + message(t) + ")", t);
        }
    }

    private static void releaseInstance(Plugin plugin) {
        if (plugin == null) return;
        try {
            scrub(Bukkit.getPluginManager(), plugin, 0);
        } catch (Throwable t) {
            log(Level.WARNING, "Горячая подмена: реестры менеджера плагинов почистить не удалось ("
                    + message(t) + ")", t);
        }
    }

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

        }
    }

    private static void dropIdentity(java.util.Collection<?> collection, Plugin plugin) {
        try {
            java.util.Iterator<?> it = collection.iterator();
            while (it.hasNext()) {
                if (it.next() == plugin) it.remove();
            }
        } catch (Throwable ignored) {

        }
    }

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

            }
        }
    }

    static Path pluginsFolder(HahGpsPlugin plugin) {
        try {
            java.io.File dir = Bukkit.getPluginsFolder();
            if (dir != null) return dir.toPath().toAbsolutePath();
        } catch (Throwable ignored) {

        }
        java.io.File parent = plugin.getDataFolder().getParentFile();
        return parent == null ? null : parent.toPath().toAbsolutePath();
    }

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

        if (pluginsDir == null || !Files.isDirectory(pluginsDir)) return null;
        String current = plugin.getPluginMeta().getVersion();
        Path fallback = null;
        boolean ambiguous = false;
        try (var stream = Files.newDirectoryStream(pluginsDir, UpdaterService.JAR_PREFIX + "*.jar")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) continue;
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!current.isEmpty() && name.contains(current)) return file.toAbsolutePath();
                if (fallback == null) {
                    fallback = file.toAbsolutePath();
                } else {
                    // Больше одного кандидата без версии в имени: угадывать
                    // нельзя — удалили бы не тот jar. Отказываемся.
                    ambiguous = true;
                }
            }
        } catch (IOException e) {
            return own;
        }
        return ambiguous ? null : (fallback == null ? own : fallback);
    }

    static boolean sameFile(Path a, Path b) {
        if (a == null || b == null) return false;
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    private static void retire(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log(Level.WARNING, "Горячая подмена: не удалось убрать " + file.getFileName()
                    + " (" + message(e) + ")", null);
        }
    }

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

    private static void writeFallbackScript(Path updateDir, Path oldJar) {
        if (updateDir == null) return;
        try {
            Files.createDirectories(updateDir);
            Files.writeString(updateDir.resolve("apply-update.cmd"),
                    "@echo off\r\n"
                            + "rem Обновление hahgps: убери прежний jar и перезапусти сервер.\r\n"
                            + "del /F /Q \"" + oldJar.toAbsolutePath() + "\"\r\n",
                    StandardCharsets.UTF_8);
            Path sh = updateDir.resolve("apply-update.sh");
            Files.writeString(sh,
                    "#!/bin/sh\n"
                            + "# Обновление hahgps: убери прежний jar и перезапусти сервер.\n"
                            + "rm -f '" + oldJar.toAbsolutePath() + "'\n",
                    StandardCharsets.UTF_8);
            try {
                // Без бита исполнения ./apply-update.sh даёт EACCES.
                Files.setPosixFilePermissions(sh, java.nio.file.attribute.PosixFilePermissions
                        .fromString("rwxr-xr-x"));
            } catch (Exception ignored) {
                // Не-POSIX (Windows) — там .cmd.
            }
            log(Level.WARNING, "Горячая подмена: прежний jar убрать не удалось — удали "
                    + oldJar.getFileName() + " из plugins/ перед рестартом (см. update/apply-update.cmd).", null);
        } catch (IOException e) {
            log(Level.WARNING, "Горячая подмена: скрипт записать не удалось (" + message(e) + ")", e);
        }
    }

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

        }
    }

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

        }
    }

    private static String message(Throwable t) {
        if (t == null) return "";
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
    }
}
