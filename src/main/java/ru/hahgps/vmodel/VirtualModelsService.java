package ru.hahgps.vmodel;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.config.MessagesManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Виртуальные модели в мире: каталог из панели EXODUS(OS) плюс локальный {@code models.yml},
 * коллизия шалкерами, точки «сесть», плейсмент призраком.
 *
 * <p>Перенос физики из {@code EXODUS-REBORN/EXODUS-OS-PERSONAL}
 * ({@code os.exodus.bridge.vmodel}); исходный репозиторий при переносе не менялся. Описание
 * поведения — {@code docs/vmodel.md}.</p>
 */
public final class VirtualModelsService implements Listener {

    private static final String CONFIG_ROOT = "vmodel";

    private final HahGpsPlugin plugin;
    private final ModelCatalog catalog;
    private final ModelStorage storage;
    private final ModelScheduler scheduler;
    private final ModelEditor editor;
    private final ModelSolids solids;
    private final ModelSeats seats;
    private final PanelClient panel;
    private final NamespacedKey markerKey;
    private final File radiusFile;
    private final File modelsFile;

    private volatile boolean enabled = true;
    private volatile int radius = 16;
    private volatile boolean panelSync = true;
    private volatile boolean panelBackground = true;
    private volatile int syncInterval = 300;
    private volatile int tickPeriod = 10;
    private volatile boolean editorEnabled = true;
    private volatile boolean allowRemove = true;
    private volatile long lastSyncMs;
    private volatile String lastSyncError = "";
    private volatile BukkitTask tickTask;
    private int syncGeneration;

    public VirtualModelsService(HahGpsPlugin plugin) {
        this.plugin = plugin;
        this.catalog = new ModelCatalog(plugin);
        this.storage = new ModelStorage();
        this.scheduler = new ModelScheduler(catalog, storage);
        this.editor = new ModelEditor(catalog, storage);
        this.solids = new ModelSolids();
        this.seats = new ModelSeats(plugin);
        this.panel = new PanelClient(plugin);
        this.markerKey = new NamespacedKey(plugin, "vmodel_key");
        this.radiusFile = new File(plugin.getDataFolder(), "vmodel-radius.yml");
        this.modelsFile = new File(plugin.getDataFolder(), "models.yml");
    }

    public void start() {
        PanelClient.ensureTokenFile(plugin);
        ModelPackets.bind(plugin);
        reload();
        if (!enabled) {
            plugin.getLogger().info("HahGps: модели выключены (vmodel.enabled: false).");
            return;
        }
        loadRadius();
        ensureLocalSample();
        reloadLocal();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(solids, plugin);
        plugin.getServer().getPluginManager().registerEvents(seats, plugin);
        solids.sweep();
        startTicker();
        plugin.getLogger().info("HahGps: модели — " + statusLine());
        if (panel.configured()) {
            syncAsync(false);
        }
    }

    private void startTicker() {
        stopTicker();
        long period = Math.max(4, Math.min(100, tickPeriod));
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                tick();
                if (panelBackground && syncDue()) {
                    syncAsync(false);
                }
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "HahGps: тик моделей упал", error);
            }
        }, period, period);
    }

    private void stopTicker() {
        BukkitTask task = tickTask;
        tickTask = null;
        if (task != null) {
            task.cancel();
        }
    }

    public void stop() {
        stopTicker();
        saveRadius();
        clearLive();
    }

    private void clearLive() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduler.hideAll(player);
        }
        editor.clearAll();
        seats.dropAll();
        solids.dropAll();
        ModelPackets.dropAll();
    }

    public void reload() {
        FileConfiguration cfg = plugin.getConfigManager() == null
                ? plugin.getConfig()
                : plugin.getConfigManager().get();
        if (cfg == null) {
            cfg = plugin.getConfig();
        }
        enabled = cfg.getBoolean(CONFIG_ROOT + ".enabled", true);
        radius = clamp(cfg.getInt(CONFIG_ROOT + ".radius", 16), 4, 128);
        tickPeriod = clamp(cfg.getInt(CONFIG_ROOT + ".tick-period", 10), 4, 100);
        panelSync = cfg.getBoolean(CONFIG_ROOT + ".panel.enabled", true);
        panelBackground = cfg.getBoolean(CONFIG_ROOT + ".panel.background", true);
        syncInterval = clamp(cfg.getInt(CONFIG_ROOT + ".panel.interval-seconds", 300), 30, 86400);
        editorEnabled = cfg.getBoolean(CONFIG_ROOT + ".editor", true);
        allowRemove = cfg.getBoolean(CONFIG_ROOT + ".allow-remove", true);
        panel.reload();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (scheduler.radiusOf(player.getUniqueId()) <= 0) {
                scheduler.setRadius(player.getUniqueId(), radius);
            }
        }
        if (enabled) {
            ensureTicker();
        } else {
            stopTicker();
            clearLive();
        }
    }

    private void ensureTicker() {
        if (!plugin.isEnabled()) {
            return;
        }
        startTicker();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public boolean enabled() {
        return enabled;
    }

    public int radius() {
        return radius;
    }

    public PanelClient panel() {
        return panel;
    }

    public ModelCatalog catalog() {
        return catalog;
    }

    public ModelStorage storage() {
        return storage;
    }

    public ModelScheduler scheduler() {
        return scheduler;
    }

    public ModelEditor editor() {
        return editor;
    }

    public File modelsFile() {
        return modelsFile;
    }

    public long lastSyncMs() {
        return lastSyncMs;
    }

    public String lastSyncError() {
        return lastSyncError;
    }

    public int solidCells() {
        return solids.liveCount();
    }

    public int seatCount() {
        return seats.liveCount();
    }

    public int viewers() {
        return scheduler.viewerCount();
    }

    public String statusLine() {
        MessagesManager messages = plugin.getMessages();
        return messages.get("vmodel-status", "models", String.valueOf(catalog.size()),
                "placed", String.valueOf(storage.size()), "cells", String.valueOf(solidCells()),
                "panel", panel.configured() ? messages.get("vmodel-panel-on")
                        : messages.get("vmodel-panel-off"));
    }

    public void tick() {
        if (!enabled) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (scheduler.radiusOf(player.getUniqueId()) <= 0) {
                scheduler.setRadius(player.getUniqueId(), radius);
            }
            try {
                scheduler.tickPlayer(player);
                if (editorEnabled) {
                    editor.tick(player, keyOf(player.getInventory().getItemInMainHand()));
                }
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.FINE, "HahGps: модели — тик игрока", error);
            }
        }
    }

    public boolean syncDue() {
        if (!enabled || !panelSync || !panel.configured()) {
            return false;
        }
        return System.currentTimeMillis() - lastSyncMs >= syncInterval * 1000L;
    }

    public void syncAsync(boolean announce) {
        if (!panel.configured()) {
            lastSyncError = plugin.getMessages().get("vmodel-no-token");
            return;
        }
        int generation = ++syncGeneration;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            byte[] body = panel.get("/api/minecraft/v1/vmodels/");
            String error = "";
            List<ModelDefinition> definitions = List.of();
            List<PlacedModel> placed = List.of();
            if (body == null) {
                error = plugin.getMessages().get("vmodel-panel-error");
            } else {
                try {
                    Object payload = Json.parse(new String(body, StandardCharsets.UTF_8));
                    definitions = ModelParse.definitions(Json.object(payload).get("definitions"));
                    placed = ModelParse.placedList(Json.object(payload).get("placed"));
                } catch (IllegalArgumentException broken) {
                    plugin.getLogger().log(Level.FINE, "HahGps: модели — битый ответ панели", broken);
                    error = plugin.getMessages().get("vmodel-panel-broken");
                }
            }
            String applyError = error;
            List<ModelDefinition> applyDefinitions = definitions;
            List<PlacedModel> applyPlaced = placed;
            Bukkit.getScheduler().runTask(plugin, () ->
                    apply(generation, applyDefinitions, applyPlaced, applyError, announce));
        });
    }

    public void apply(int generation, List<ModelDefinition> definitions, List<PlacedModel> placed,
                      String error, boolean announce) {
        if (generation != syncGeneration) {
            return;
        }
        lastSyncMs = System.currentTimeMillis();
        lastSyncError = error == null ? "" : error;
        if (!lastSyncError.isEmpty()) {
            if (announce) {
                plugin.getLogger().warning("HahGps: модели — " + lastSyncError);
            }
            return;
        }
        LocalSource local = localSource();
        List<ModelDefinition> merged = new ArrayList<>(definitions);
        List<PlacedModel> mergedPlaced = new ArrayList<>(placed);
        merged.addAll(local.definitions());
        mergedPlaced.addAll(local.placed());
        catalog.replaceAll(merged);
        storage.replaceAll(mergedPlaced);
        refreshAll();
        rebuildSolids();
        if (announce) {
            plugin.getLogger().info("HahGps: модели — из панели " + definitions.size()
                    + " определений и " + placed.size() + " расстановок, локально +"
                    + local.definitions().size() + "/" + local.placed().size());
        }
    }

    public void refreshAll() {
        if (!plugin.isEnabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduler.refreshPlayer(player);
        }
    }

    public void rebuildSolids() {
        if (!plugin.isEnabled()) {
            return;
        }
        solids.dropAll();
        if (!enabled) {
            return;
        }
        for (PlacedModel placed : storage.all()) {
            solids.ensure(placed, catalog.get(placed.modelKey()));
        }
    }

    public void reloadLocal() {
        LocalSource local = localSource();
        catalog.putAll(local.definitions());
        storage.merge(local.placed());
        rebuildSolids();
        refreshAll();
    }

    private LocalSource localSource() {
        List<ModelDefinition> definitions = new ArrayList<>();
        List<PlacedModel> placed = new ArrayList<>();
        if (!modelsFile.isFile()) {
            return new LocalSource(definitions, placed);
        }
        YamlConfiguration yaml;
        try {
            yaml = YamlConfiguration.loadConfiguration(modelsFile);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.FINE, "HahGps: models.yml не прочитан", error);
            return new LocalSource(definitions, placed);
        }
        for (Map<?, ?> entry : yaml.getMapList("models")) {
            try {
                ModelDefinition def = ModelParse.definition(entry);
                if (def != null) {
                    definitions.add(def);
                }
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.FINE, "HahGps: запись models.yml пропущена", error);
            }
        }
        for (Map<?, ?> entry : yaml.getMapList("placements")) {
            try {
                PlacedModel row = ModelParse.placed(entry);
                if (row != null) {
                    placed.add(row);
                }
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.FINE, "HahGps: расстановка models.yml пропущена", error);
            }
        }
        return new LocalSource(definitions, placed);
    }

    public record LocalSource(List<ModelDefinition> definitions, List<PlacedModel> placed) {
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (!enabled || !editorEnabled) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        Action action = event.getAction();
        boolean left = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        if (!left && !right) {
            return;
        }
        String key = keyOf(player.getInventory().getItemInMainHand());
        PlacedModel looking = editor.findPlaced(player);
        if (left) {
            if (player.isSneaking() && looking != null && allowRemove && canBuild(player)) {
                event.setCancelled(true);
                takePlaced(player, looking.id(), true);
                return;
            }
            if (player.isSneaking() && key != null && editor.rotateGhost(player)) {
                event.setCancelled(true);
                return;
            }
            if (!player.isSneaking() && looking != null) {
                event.setCancelled(true);
                interact(player, looking);
                return;
            }
            if (!player.isSneaking() && key != null) {
                event.setCancelled(true);
                editor.cycleStep(player);
            }
            return;
        }
        if (looking != null && !player.isSneaking()) {
            event.setCancelled(true);
            if (!seats.sit(player, looking, catalog.get(looking.modelKey()))) {
                interact(player, looking);
            }
            return;
        }
        if (key != null) {
            event.setCancelled(true);
            tryPlace(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHitDisplay(EntityDamageByEntityEvent event) {
        if (!enabled) {
            return;
        }
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        UUID id = ModelPackets.idOf(event.getEntity());
        if (id == null) {
            return;
        }
        event.setCancelled(true);
        PlacedModel looking = editor.findPlaced(player);
        if (looking == null || !looking.id().equals(id)) {
            return;
        }
        if (player.isSneaking()) {
            if (allowRemove && canBuild(player)) {
                takePlaced(player, id, true);
            }
            return;
        }
        interact(player, looking);
    }

    /**
     * Второй путь удара: по display-сущностям Paper часто присылает только
     * PrePlayerAttackEntity, без EntityDamageByEntityEvent — в оригинале EXODUS-OS
     * обработчиков тоже два, и без этого Shift+ЛКМ не снимает модель.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAttackEntity(io.papermc.paper.event.player.PrePlayerAttackEntityEvent event) {
        if (!enabled) {
            return;
        }
        UUID id = ModelPackets.idOf(event.getAttacked());
        if (id == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        PlacedModel looking = editor.findPlaced(player);
        if (looking == null || !looking.id().equals(id)) {
            return;
        }
        if (player.isSneaking()) {
            if (allowRemove && canBuild(player)) {
                takePlaced(player, id, true);
            }
            return;
        }
        interact(player, looking);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUseDisplay(PlayerInteractAtEntityEvent event) {
        if (!enabled) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (useDisplay(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    /**
     * По display-сущностям клиент чаще присылает обычный INTERACT, а не INTERACT_AT (как и у
     * катушек в hahtojer) — без этого обработчика посадка не срабатывает вовсе.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUseDisplayPlain(PlayerInteractEntityEvent event) {
        if (!enabled) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (useDisplay(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    private boolean useDisplay(Player player, org.bukkit.entity.Entity clicked) {
        UUID id = ModelPackets.idOf(clicked);
        if (id == null) {
            return false;
        }
        PlacedModel looking = editor.findPlaced(player);
        if (player.isSneaking() || looking == null || !looking.id().equals(id)) {
            return true;
        }
        PlacedModel placed = storage.get(id);
        if (placed != null && !seats.sit(player, placed, catalog.get(placed.modelKey()))) {
            interact(player, placed);
        }
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        scheduler.forget(id);
        editor.forget(id);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) {
            return;
        }
        Player player = event.getPlayer();
        scheduler.setRadius(player.getUniqueId(), radius);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                scheduler.refreshPlayer(player);
            }
        }, 5L);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        if (!enabled) {
            return;
        }
        scheduler.refreshPlayer(event.getPlayer());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (!enabled) {
            return;
        }
        Location to = event.getTo();
        Player player = event.getPlayer();
        if (to == null) {
            return;
        }
        if (event.getFrom().getWorld() != to.getWorld() || event.getFrom().distanceSquared(to) > 64) {
            Bukkit.getScheduler().runTask(plugin, () -> scheduler.refreshPlayer(player));
        }
    }

    private void interact(Player player, PlacedModel placed) {
        ModelDefinition def = catalog.get(placed.modelKey());
        player.sendMessage(plugin.getMessages().getPrefixed("vmodel-interact",
                "model", def == null ? placed.modelKey() : def.key()));
    }

    public boolean tryPlace(Player player) {
        if (player == null) {
            return false;
        }
        String key = keyOf(player.getInventory().getItemInMainHand());
        if (key == null) {
            return false;
        }
        if (!canBuild(player)) {
            player.sendMessage(plugin.getMessages().getPrefixed("vmodel-no-permission"));
            return true;
        }
        ModelDefinition def = catalog.require(key);
        if (def == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("vmodel-not-found", "model", key));
            return true;
        }
        ModelEditor.PlaceTarget target = editor.currentPlace(player);
        if (target == null) {
            target = editor.findPlace(player, def);
        }
        if (target == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("vmodel-no-space"));
            return true;
        }
        if (target.blocked() || editor.collides(target.location(), target.location().getYaw(), def, null)) {
            player.sendMessage(plugin.getMessages().getPrefixed("vmodel-overlap"));
            return true;
        }
        Location origin = target.location();
        if (origin.getWorld() == null) {
            return true;
        }
        PlacedModel placed = new PlacedModel(UUID.randomUUID(), origin.getWorld().getName(),
                origin.getX(), origin.getY(), origin.getZ(), origin.getYaw(), origin.getPitch(),
                def.key());
        storage.put(placed);
        consumeHeldMarker(player);
        scheduler.tickPlayer(player);
        solids.ensure(placed, def);
        pushPlace(placed, player.getName());
        player.sendMessage(plugin.getMessages().getPrefixed("vmodel-placed", "model", def.key()));
        return true;
    }

    private void pushPlace(PlacedModel placed, String playerName) {
        if (!panelSync || !panel.configured()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                panel.post("/api/minecraft/v1/vmodels/", ModelStorage.toJson(placed, playerName)));
    }

    public void removeLooking(Player player) {
        if (player == null) {
            return;
        }
        PlacedModel nearest = editor.findPlaced(player);
        if (nearest == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("vmodel-look-at-model"));
            return;
        }
        takePlaced(player, nearest.id(), true);
    }

    public boolean takePlaced(Player player, UUID id, boolean toHand) {
        if (player == null || id == null) {
            return false;
        }
        if (!canBuild(player)) {
            player.sendMessage(plugin.getMessages().getPrefixed("vmodel-no-permission"));
            return false;
        }
        PlacedModel placed = storage.remove(id);
        if (placed == null) {
            return false;
        }
        ModelPackets.drop(id);
        solids.drop(id);
        seats.drop(id);
        scheduler.refreshKey(id);
        if (panelSync && panel.configured()) {
            UUID placedId = id;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                    panel.post("/api/minecraft/v1/vmodels/", ModelStorage.removeJson(placedId)));
        }
        if (toHand) {
            giveMarker(player, placed.modelKey(), true);
        }
        player.sendMessage(plugin.getMessages().getPrefixed(
                toHand ? "vmodel-taken-hand" : "vmodel-taken"));
        return true;
    }

    private void consumeHeldMarker(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (keyOf(hand) == null) {
            return;
        }
        if (hand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        } else {
            hand.setAmount(hand.getAmount() - 1);
        }
    }

    public boolean giveMarker(Player player, String key, boolean toHand) {
        if (player == null || key == null || key.isBlank()) {
            return false;
        }
        ModelDefinition def = catalog.require(key);
        ItemStack give = marker(def != null ? def : ModelDefinition.fallback(key));
        if (toHand) {
            putInHand(player, give);
            return true;
        }
        for (ItemStack leftover : player.getInventory().addItem(give).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        return true;
    }

    private void putInHand(Player player, ItemStack give) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        String giveKey = keyOf(give);
        if (hand == null || hand.getType().isAir()) {
            player.getInventory().setItemInMainHand(give);
            return;
        }
        if (giveKey != null && giveKey.equals(keyOf(hand))
                && hand.getAmount() < hand.getMaxStackSize()) {
            hand.setAmount(hand.getAmount() + 1);
            return;
        }
        ItemStack previous = hand.clone();
        player.getInventory().setItemInMainHand(give);
        for (ItemStack leftover : player.getInventory().addItem(previous).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    public ItemStack marker(ModelDefinition def) {
        Material material = Material.matchMaterial(def == null ? "" : def.displayItem());
        if (material == null || material.isAir()) {
            material = Material.PAPER;
        }
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            ModelPackets.applyLooks(meta, def);
            meta.displayName(plugin.getMessages().getComponentPlain("vmodel-marker-name",
                    "model", def == null ? "" : def.key()));
            meta.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING,
                    def == null ? "" : def.key());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public String keyOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        String key = stack.getItemMeta().getPersistentDataContainer()
                .get(markerKey, PersistentDataType.STRING);
        return key == null || key.isBlank() ? null : key;
    }

    public static boolean canBuild(Player player) {
        return player != null && (player.hasPermission("gps.models")
                || player.hasPermission("gps.admin") || player.isOp());
    }

    public Location placeOrigin(Block block, BlockFace face, ModelDefinition def) {
        return Placement.origin(block, face, def);
    }

    public Location placePrecise(Vector hit, BlockFace face, ModelDefinition def,
                                 org.bukkit.World world) {
        return Placement.precise(hit, face, def, world);
    }

    public void loadRadius() {
        if (!radiusFile.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(radiusFile);
        Map<String, Integer> data = new HashMap<>();
        for (String key : yaml.getKeys(false)) {
            data.put(key, yaml.getInt(key, radius));
        }
        scheduler.loadRadius(data);
    }

    public void saveRadius() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> entry : scheduler.snapshotRadius().entrySet()) {
            yaml.set(entry.getKey().toString(), entry.getValue());
        }
        try {
            yaml.save(radiusFile);
        } catch (IOException error) {
            plugin.getLogger().log(Level.FINE, "HahGps: vmodel-radius.yml не сохранён", error);
        }
    }

    public void ensureLocalSample() {
        if (modelsFile.isFile()) {
            return;
        }
        try {
            File parent = modelsFile.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            Files.writeString(modelsFile.toPath(), "models: []\nplacements: []\n",
                    StandardCharsets.UTF_8);
        } catch (IOException error) {
            plugin.getLogger().log(Level.FINE, "HahGps: models.yml не создан", error);
        }
    }

    public List<String> keys() {
        List<String> out = new ArrayList<>(catalog.keys());
        out.sort(String::compareTo);
        return out;
    }
}
