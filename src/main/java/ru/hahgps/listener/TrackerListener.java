package ru.hahgps.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.subcommands.ItrakCommand;
import ru.hahgps.item.ItemFactory;
import ru.hahgps.model.Tracker;
import ru.hahgps.service.TrackerService;
import ru.hahgps.util.BodyPartResolver;
import ru.hahgps.util.EntityNames;

/**
 * Действия с iTrak в мире:
 * <ul>
 *   <li>ПКМ трекером по блоку — прицепить, ПКМ в воздух — бросить (Shift — аккуратно);</li>
 *   <li>ПКМ <b>кисточкой</b> по блоку с трекером — «стереть» его: кисточку нужно
 *       подержать, наведясь на трекер; Shift+ПКМ кисточкой по существу — то же
 *       с носителя (пустая рука не снимает и ничего не подсказывает: iTrak —
 *       датчик, а не панелька);</li>
 *   <li>поломка опорного блока — предмет возвращается в мир.</li>
 * </ul>
 * События отменяются: трекер — предмет-устройство, а не блок и не инструмент.
 */
public class TrackerListener implements Listener {

    /** Снять iTrak можно только этим предметом: кисточка «подцепляет» датчик. */
    public static final Material REMOVE_TOOL = Material.BRUSH;

    private final HahGpsPlugin plugin;

    public TrackerListener(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Клик по блоку
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractBlock(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getConfigManager().isItrakEnabled()) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            // Предмет iTrak во второй руке не должен срабатывать как чужой
            // инструмент: компас — навигационная палочка WorldEdit, заряд —
            // редстоун и т.п. Гасим использование, скана тут нет.
            if (ItemFactory.isItrakItem(player.getInventory().getItemInOffHand())) {
                denyUse(event);
            }
            return;
        }
        Block clicked = event.getClickedBlock();
        ItemStack item = player.getInventory().getItemInMainHand();
        boolean rightClick = event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                || event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR;

        if (clicked != null && rightClick && player.hasPermission("gps.itrak")) {
            TrackerService service = plugin.getTrackerService();
            if (service != null) {
                Tracker onBlock = service.trackerOnBlock(clicked);
                if (onBlock != null && item.getType() == REMOVE_TOOL) {
                    // Кисточку надо подержать на трекере: клик не снимает его
                    // мгновенно. Пустой рукой — молчание: подсказка «снять
                    // можно только кисточкой» выдавала, что здесь стоит датчик.
                    denyUse(event);
                    // Чужой трекер не начинаем снимать: прогресс над хотбаром
                    // сам по себе сообщал бы, что под этим блоком стоит датчик.
                    if (service.canShare(player, onBlock)) {
                        startWipe(player, onBlock, null);
                    }
                    return;
                }
            }
        }

        if (ItemFactory.isLocator(item)) {
            if (!player.hasPermission("gps.itrak")) {
                return;
            }
            denyUse(event);
            if (!rightClick) {
                return;
            }
            upgradeLocator(player);
            runScan(player, ItemFactory.readLocatorRadius(
                    player.getInventory().getItemInMainHand()));
            return;
        }

        if (isTrackerMaterial(item)) {
            healTrackerItem(player);
        }
        ItemFactory.TrackerSpec spec = ItemFactory.readTracker(
                player.getInventory().getItemInMainHand());
        if (spec == null) {
            return;
        }
        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR) {
            denyUse(event);
            throwTracker(player, spec);
            return;
        }
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        denyUse(event);
        if (clicked == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-place-air"));
            return;
        }
        if (!checkAllowed(player)) {
            return;
        }
        place(player, spec, clicked, event.getBlockFace(), null);
    }

    /**
     * Отменить и событие, и ванильное использование предмета/блока: так клик
     * предметом iTrak не дойдёт ни до ванили, ни до чужих плагинов, которые
     * смотрят на результат использования (WorldEdit, лодестоун-компасы и т.п.).
     */
    private static void denyUse(PlayerInteractEvent event) {
        event.setCancelled(true);
        try {
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Локатор раньше был компасом — а компас по умолчанию «навигационная
     * палочка» WorldEdit: её ПКМ/ЛКМ телепортируют к блоку под прицелом (вниз —
     * внутрь земли). Кроме того, до 1.3.1 локаторы были на 10 и 15 блоков:
     * такие предметы меняем на актуальные (20 и 30), иначе в инвентарях
     * навсегда остались бы старые радиусы.
     */
    private void upgradeLocator(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        int normalRadius = (int) plugin.getConfigManager().getItrakScanRadius();
        int longRadius = plugin.getConfigManager().getItrakLocatorLongRadius();
        boolean legacy = ItemFactory.isLegacyLocator(item);
        ItemStack upgraded = ItemFactory.upgradedLocator(item, normalRadius, longRadius);
        if (upgraded == null) {
            return;
        }
        // Локаторы одинаковые и стакаются: без setAmount стек из N штук
        // схлопнулся бы в один предмет.
        upgraded.setAmount(Math.max(1, item.getAmount()));
        player.getInventory().setItemInMainHand(upgraded);
        player.sendMessage(legacy
                ? plugin.getMessages().getPrefixed("itrak-locator-upgraded")
                : plugin.getMessages().getPrefixed("itrak-locator-updated",
                "radius", String.valueOf(ItemFactory.readLocatorRadius(upgraded))));
    }

    /**
     * Материал трекера мог устареть (синий трекер был компасом восстановления,
     * как локатор). Подменяем предмет в руке на актуальный — данные в PDC те
     * же, меняется только вид.
     */
    private void healTrackerItem(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        // Раньше healTrackerItem вызывался на любом клике любым предметом и
        // копировал мету ради проверки. Теперь сначала материал.
        if (!isTrackerMaterial(hand)) {
            return;
        }
        ItemStack healed = ItemFactory.healedTrackerItem(hand);
        if (healed != null) {
            healed.setAmount(Math.max(1, hand.getAmount()));
            player.getInventory().setItemInMainHand(healed);
        }
    }

    /** Материал принадлежит трекеру (любой из восьми цветов). */
    private static boolean isTrackerMaterial(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        Material type = item.getType();
        for (ru.hahgps.model.TrackerColor color : ru.hahgps.model.TrackerColor.values()) {
            if (color.getMaterial() == type) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Клик по существу: прицепить или снять
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            if (plugin.getConfigManager().isItrakEnabled()
                    && ItemFactory.isItrakItem(event.getPlayer().getInventory().getItemInOffHand())) {
                event.setCancelled(true);
            }
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        Entity target = event.getRightClicked();
        TrackerService service = plugin.getTrackerService();

        if (item.getType() == REMOVE_TOOL && player.isSneaking() && service != null
                && player.hasPermission("gps.itrak")) {
            Tracker attached = trackerAimedAt(player, target, service);
            if (attached != null) {
                event.setCancelled(true);
                startWipe(player, attached, target);
            }
            return;
        }

        if (isTrackerMaterial(item)) {
            healTrackerItem(player);
        }
        ItemFactory.TrackerSpec spec = ItemFactory.readTracker(
                player.getInventory().getItemInMainHand());
        if (spec == null) {
            return;
        }
        event.setCancelled(true);
        if (service != null) {
            // Предмет-дубль: трекер с этим кодом уже стоит на этом носителе.
            // Раньше клик либо выдавал «уже установлен» (и предмет оставался в
            // инвентаре), либо перекодировал его и вешал ВТОРОЙ трекер на ту же
            // сущность — после смерти с неё падало два трекера. Лишний предмет
            // просто убираем и подтверждаем установку короткой строкой.
            Tracker already = service.byCode(spec.code());
            if (already != null && target.getUniqueId().equals(already.getEntityUuid())) {
                ItemFactory.consumeTrackerItem(player, spec.code());
                player.sendMessage(service.trackerLine("itrak-placed", already));
                return;
            }
        }
        if (target instanceof Player && !plugin.getConfigManager().isItrakAttachPlayers()) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-attach-players-off"));
            return;
        }
        // Прицепить можно к любой сущности: лодка, вагонетка, лошадь, свинья,
        // стойка для брони, рамка — ко всему, что двигается и стоит. Раньше
        // здесь пропускались только LivingEntity и Vehicle, и на вагонетку с
        // лодкой-сундуком трекер не ставился.
        if (target.getType() == EntityType.UNKNOWN) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-attach-unsupported"));
            return;
        }
        if (!checkAllowed(player)) {
            return;
        }
        // Куда попал — туда и цепляем: голова, туловище, руки, ноги.
        ru.hahgps.model.BodyPart part = BodyPartResolver.of(player, target);
        place(player, spec, target.getLocation().getBlock(), BlockFace.UP, target,
                part == null ? null : part.getKey());
    }

    // ------------------------------------------------------------------
    // Бросок
    // ------------------------------------------------------------------

    /**
     * Предмет, изготовленный старым генератором кодов, мог совпасть по коду с
     * уже установленным трекером: такой второй предмет нельзя было поставить,
     * а локатор продолжал показывать первый — «трекер на игроке, но трекер у
     * меня». Здесь предмет в руке получает новый уникальный код и работает
     * дальше как обычный.
     *
     * @return spec с гарантированно свободным кодом, исходный spec, если код
     *         свободен, или null, если код занят и починить нечего
     */
    private ItemFactory.TrackerSpec healDuplicateCode(Player player, TrackerService service,
                                                      ItemFactory.TrackerSpec spec) {
        if (service.byCode(spec.code()) == null) {
            return spec;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        ItemFactory.TrackerSpec inHand = ItemFactory.readTracker(hand);
        if (inHand == null || !inHand.code().equals(spec.code()) || hand.getAmount() != 1) {
            return null;
        }
        ItemFactory.TrackerSpec fresh = ItemFactory.withFreshCode(spec);
        player.getInventory().setItemInMainHand(ItemFactory.trackerItem(fresh));
        player.sendMessage(plugin.getMessages().getPrefixed("itrak-code-refreshed",
                "code", fresh.code()));
        return fresh;
    }

    private void throwTracker(Player player, ItemFactory.TrackerSpec spec) {
        TrackerService service = plugin.getTrackerService();
        if (service == null || !checkAllowed(player)) {
            return;
        }
        ItemFactory.TrackerSpec actual = healDuplicateCode(player, service, spec);
        if (actual == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-exists"));
            return;
        }
        boolean gentle = player.isSneaking();
        if (!service.throwTracker(player, actual, gentle)) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-thrown-failed"));
            return;
        }
        ItemFactory.consumeTrackerItem(player, actual.code());
        player.sendMessage(service.specLine("itrak-thrown", actual));
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        TrackerService thrownService = plugin.getTrackerService();
        if (thrownService != null) {
            // Убираем летящий предмет сразу, не дожидаясь тика анимации.
            thrownService.finishThrow(projectile);
        }
        ItemFactory.TrackerSpec spec = ItemFactory.readSpec(projectile);
        if (spec == null) {
            return;
        }
        java.util.UUID throwerId = ItemFactory.readThrower(projectile);
        // Shift-бросок: попав в воду, трекер должен лечь на дно.
        boolean sink = ItemFactory.readSink(projectile);
        Entity hitEntity = event.getHitEntity();
        // При броске точку попадания даёт сам снаряд: часть тела считаем по
        // ней, а не «по центру» — иначе голова и ноги не различались бы.
        ru.hahgps.model.BodyPart hitPart = hitEntity == null ? null
                : BodyPartResolver.at(projectile.getLocation().toVector(), hitEntity);
        String bodyPart = hitPart == null ? null : hitPart.getKey();
        Block hitBlock = event.getHitBlock();
        BlockFace face = event.getHitBlockFace();
        Location at = projectile.getLocation().clone();
        projectile.remove();
        Player thrower = throwerId == null ? null : Bukkit.getPlayer(throwerId);
        TrackerService service = plugin.getTrackerService();
        if (thrower == null || !thrower.isOnline() || service == null) {
            // Владелец вышел — трекер просто падает предметом на месте.
            if (at.getWorld() != null) {
                at.getWorld().dropItemNaturally(at, ItemFactory.trackerItem(spec));
            }
            return;
        }
        service.placeThrown(thrower, spec, hitBlock, face, hitEntity, at, sink, bodyPart)
                .whenComplete((outcome, error) -> plugin.getDataService().runSync(() -> {
                    if (!thrower.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING,
                                "HahGps: брошенный iTrak не установился", error);
                        ItemFactory.give(thrower, ItemFactory.trackerItem(spec));
                        thrower.sendMessage(plugin.getMessages().getPrefixed("itrak-place-failed"));
                        return;
                    }
                    reportPlacement(thrower, spec, outcome, hitEntity, true);
                }));
    }

    // ------------------------------------------------------------------
    // Наковальня: имя трекера до установки
    // ------------------------------------------------------------------

    /**
     * Переименование трекера в наковальне. Ванильный результат потерял бы
     * подпись: метка в PDC осталась бы прежней, и после установки трекер
     * назывался бы по-старому. Поэтому результат собираем сами — тот же
     * предмет, но с новым именем и без стоимости починки.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvil(org.bukkit.event.inventory.PrepareAnvilEvent event) {
        org.bukkit.inventory.AnvilInventory anvil = event.getInventory();
        ItemStack first = anvil.getItem(0);
        ItemFactory.TrackerSpec spec = ItemFactory.readTracker(first);
        if (spec == null) {
            return;
        }
        ItemStack second = anvil.getItem(1);
        if (second != null && !second.getType().isAir()) {
            // Второй предмет — это уже починка/склейка, а не переименование.
            return;
        }
        String rename = anvil.getRenameText();
        if (rename == null || rename.isBlank()) {
            return;
        }
        // Пока игрок ничего не менял, в поле лежит текущая подпись предмета:
        // её нельзя принимать за новое имя, иначе любой заход в наковальню
        // «переименовывал» трекер в «iTrak голубой».
        String current = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(first.displayName()).trim();
        if (rename.trim().equals(current)) {
            return;
        }
        String name = TrackerService.sanitizeName(rename);
        if (spec.name().trim().equals(name)) {
            return;
        }
        ItemStack result = ItemFactory.renamedTracker(first, name);
        if (result == null) {
            return;
        }
        anvil.setRepairCost(0);
        event.setResult(result);
    }

    // ------------------------------------------------------------------
    // Поломка блока
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        TrackerService service = plugin.getTrackerService();
        Tracker tracker = service == null ? null : service.trackerOnBlock(block);
        if (tracker == null) {
            return;
        }
        String name = tracker.getDisplayName();
        String code = tracker.getCode();
        int id = tracker.getId();
        String world = tracker.getWorld();
        double x = tracker.getX();
        double y = tracker.getY();
        double z = tracker.getZ();
        service.removeDropping(tracker, block.getLocation().add(0.5, 0.5, 0.5));
        player.sendMessage(plugin.getMessages().getPrefixed("itrak-tracker-fell",
                "name", name, "code", code, "id", String.valueOf(id)));
        Player owner = tracker.getOwnerUuid() == null ? null : Bukkit.getPlayer(tracker.getOwnerUuid());
        if (owner != null && owner.isOnline() && !owner.getUniqueId().equals(player.getUniqueId())) {
            owner.sendMessage(plugin.getMessages().getPrefixed("itrak-tracker-fell-owner",
                    "name", name, "code", code, "world", world == null ? "?" : world,
                    "x", TrackerService.formatCoord(x),
                    "y", TrackerService.formatCoord(y),
                    "z", TrackerService.formatCoord(z)));
        }
    }

    // ------------------------------------------------------------------
    // Общее
    // ------------------------------------------------------------------

    private boolean checkAllowed(Player player) {
        if (!plugin.getConfigManager().isItrakEnabled()) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-disabled"));
            return false;
        }
        if (plugin.getTrackerService() == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("loading-data"));
            return false;
        }
        if (!player.hasPermission("gps.itrak")) {
            player.sendMessage(plugin.getMessages().getPrefixed("no-permission"));
            return false;
        }
        return true;
    }

    private void place(Player player, ItemFactory.TrackerSpec spec,
                       Block block, BlockFace face, Entity target) {
        place(player, spec, block, face, target, null);
    }

    private void place(Player player, ItemFactory.TrackerSpec spec,
                       Block block, BlockFace face, Entity target, String bodyPart) {
        TrackerService service = plugin.getTrackerService();
        if (service == null) {
            return;
        }
        ItemFactory.TrackerSpec actual = healDuplicateCode(player, service, spec);
        if (actual == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-exists"));
            return;
        }
        // Shift-клик по дну: водонепроницаемый трекер топим намеренно, чтобы
        // он лежал под водой и продолжал передавать сигнал.
        service.place(player, actual, block, face, target, player.isSneaking(), bodyPart)
                .whenComplete((outcome, error) ->
                        plugin.getDataService().runSync(() -> {
                            if (!player.isOnline()) {
                                return;
                            }
                            if (error != null) {
                                plugin.getLogger().log(java.util.logging.Level.WARNING,
                                        "HahGps: iTrak не установлен", error);
                                player.sendMessage(plugin.getMessages().getPrefixed("itrak-place-failed"));
                                return;
                            }
                            reportPlacement(player, actual, outcome, target, false);
                        }));
    }

    /**
     * @param refundOnFailure вернуть предмет при неудаче (у броска предмет уже
     *                        израсходован на старте)
     */
    private void reportPlacement(Player player, ItemFactory.TrackerSpec spec,
                                 TrackerService.PlaceOutcome outcome, Entity target,
                                 boolean refundOnFailure) {
        TrackerService.PlaceResult result = outcome == null
                ? TrackerService.PlaceResult.FAILED : outcome.result();
        if (result == TrackerService.PlaceResult.ALREADY_PLACED) {
            // Двойное событие клика: трекер уже поставлен, а в инвентаре мог
            // остаться второй предмет с тем же кодом. Раньше игрок видел
            // «трекер уже установлен» и держал лишний предмет, а после смерти
            // носителя с него падало два трекера. Теперь лишний предмет просто
            // убирается, а строка — обычная короткая.
            ItemFactory.consumeTrackerItem(player, spec.code());
            Tracker existing = plugin.getTrackerService() == null ? null
                    : plugin.getTrackerService().byCode(spec.code());
            if (existing != null) {
                player.sendMessage(plugin.getTrackerService().trackerLine("itrak-placed", existing));
            }
            return;
        }
        if (result != TrackerService.PlaceResult.OK) {
            player.sendMessage(plugin.getMessages().getPrefixed(switch (result) {
                case ALREADY_PLACED -> "itrak-exists";
                case LIMIT -> "itrak-limit";
                case DISABLED -> "itrak-disabled";
                default -> "itrak-place-failed";
            }, "max", String.valueOf(plugin.getConfigManager().getItrakMaxPerPlayer())));
            if (refundOnFailure) {
                // Возврат с тем же кодом сделал бы второй неставимый предмет:
                // если код уже занят, у возвращённого будет новый.
                ItemFactory.TrackerSpec refund = result == TrackerService.PlaceResult.ALREADY_PLACED
                        ? ItemFactory.withFreshCode(spec) : spec;
                ItemFactory.give(player, ItemFactory.trackerItem(refund));
            }
            return;
        }
        Tracker tracker = outcome.tracker();
        if (!refundOnFailure) {
            ItemFactory.consumeTrackerItem(player, spec.code());
        }
        // Короткая строка: номер, цвет, тип, часть тела и координаты — в
        // подсказке при наведении. Мир, время установки и кнопка «снять» из
        // чата убраны: они и были тем самым «палевом» лишними строками.
        player.sendMessage(plugin.getTrackerService().trackerLine("itrak-placed", tracker));
        if (tracker.isSunk()) {
            player.sendMessage(plugin.getTrackerService().trackerLine(
                    tracker.isWaterproof() ? "itrak-placed-submerged" : "itrak-placed-sunk", tracker));
        } else if (tracker.isAttached()) {
            player.sendMessage(plugin.getTrackerService().trackerLine("itrak-attached", tracker,
                    "entity", target == null ? "?" : EntityNames.name(target.getType())));
        }
    }

    private void takeTracker(Player player, Tracker tracker) {
        TrackerService service = plugin.getTrackerService();
        if (service == null) {
            return;
        }
        if (!service.canShare(player, tracker)) {
            // Доступ к трекеру — это слежение и правка, а не право забрать
            // устройство: снять его может только владелец (или админ).
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-take-owner-only"));
            return;
        }
        if (!service.canTake(player, tracker)) {
            Location location = tracker.getLocation();
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-take-far",
                    "distance", ru.hahgps.util.LocationUtil.formatDistance(
                            location == null ? Double.MAX_VALUE
                                    : ru.hahgps.util.LocationUtil.distance(
                                    player.getLocation(), location)),
                    "radius", String.valueOf((int) plugin.getConfigManager().getItrakTakeRadius())));
            return;
        }
        service.take(player, tracker).whenComplete((ok, error) ->
                plugin.getDataService().runSync(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING,
                                "HahGps: не удалось снять iTrak", error);
                        return;
                    }
                    player.sendActionBar(net.kyori.adventure.text.Component.empty());
                    player.sendMessage(plugin.getTrackerService().trackerLine("itrak-taken", tracker));
                }));
    }

    /**
     * Какой из трекеров на существе снимаем: на одну сущность можно повесить
     * несколько (голова, руки, туловище, ноги), поэтому берём тот, на чью
     * часть тела сейчас направлена кисточка. Если не попали ни в одну часть —
     * первый доступный владельцу.
     */
    private Tracker trackerAimedAt(Player player, Entity target, TrackerService service) {
        java.util.List<Tracker> attached = service.trackersOnEntity(target.getUniqueId());
        if (attached.isEmpty()) {
            return null;
        }
        ru.hahgps.model.BodyPart aim = BodyPartResolver.of(player, target);
        Tracker fallback = null;
        for (Tracker tracker : attached) {
            if (!service.canShare(player, tracker)) {
                continue;
            }
            if (fallback == null) {
                fallback = tracker;
            }
            if (aim != null && aim == tracker.part()) {
                return tracker;
            }
        }
        return fallback;
    }

    // ------------------------------------------------------------------
    // Снятие кисточкой: держать и водить
    // ------------------------------------------------------------------

    /** Как часто проверяем удержание (тики). */
    private static final long WIPE_TICK = 2L;
    /** Насколько «в упор» нужно смотреть на трекер: косинус угла ~36°. */
    private static final double WIPE_COS = 0.8;
    /** Предельная дистанция удержания, блоки. */
    private static final double WIPE_REACH = 6.5;

    private final java.util.Map<java.util.UUID, Wipe> wipes = new java.util.HashMap<>();
    private BukkitTask wipeTask;

    /**
     * Идущее «стирание» трекера кисточкой. Хранится только в памяти: уйти с
     * сервера посреди снятия — не страшно, трекер остаётся на месте.
     */
    private record Wipe(int trackerId, java.util.UUID entityId, long startedAt, long holdMillis) {
    }

    /**
     * Начать снятие кисточкой. Трекер снимается не кликом, а удержанием:
     * кисточку надо «водить» по блоку (или по существу) — чем дольше, тем
     * ближе к снятию. Это и защита от случайного клика, и требование
     * владельца «снимать только кисточкой», и молчание вместо подсказок,
     * которые выдавали место датчика.
     */
    private void startWipe(Player player, Tracker tracker, Entity entity) {
        Wipe running = wipes.get(player.getUniqueId());
        if (running != null && running.trackerId() == tracker.getId()) {
            // Повторный клик по тому же трекеру не начинает счёт заново: иначе
            // «водить кисточкой» (частые клики) никогда не снимало бы трекер.
            return;
        }
        long hold = Math.max(200L, plugin.getConfigManager().getItrakBrushHoldMillis());
        wipes.put(player.getUniqueId(), new Wipe(tracker.getId(),
                entity == null ? null : entity.getUniqueId(),
                System.currentTimeMillis(), hold));
        ensureWipeTask();
        player.sendActionBar(plugin.getMessages().getComponent("itrak-brush-start"));
    }

    private void ensureWipeTask() {
        if (wipeTask != null) {
            return;
        }
        wipeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickWipes, WIPE_TICK, WIPE_TICK);
    }

    private void stopWipeTask() {
        BukkitTask task = wipeTask;
        wipeTask = null;
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    private void tickWipes() {
        if (wipes.isEmpty()) {
            stopWipeTask();
            return;
        }
        TrackerService service = plugin.getTrackerService();
        java.util.Iterator<java.util.Map.Entry<java.util.UUID, Wipe>> iterator = wipes.entrySet().iterator();
        while (iterator.hasNext()) {
            java.util.Map.Entry<java.util.UUID, Wipe> entry = iterator.next();
            Wipe wipe = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            Tracker tracker = service == null ? null : service.byId(wipe.trackerId());
            if (player == null || !player.isOnline() || tracker == null) {
                iterator.remove();
                continue;
            }
            if (!holdsBrush(player) || !stillAiming(player, tracker, wipe)) {
                // Отвернулся, убрал кисточку или отошёл — прогресс сброшен.
                iterator.remove();
                player.sendActionBar(net.kyori.adventure.text.Component.empty());
                continue;
            }
            long elapsed = System.currentTimeMillis() - wipe.startedAt();
            if (elapsed >= wipe.holdMillis()) {
                iterator.remove();
                takeTracker(player, tracker);
                continue;
            }
            int percent = (int) Math.max(0L, Math.min(99L, elapsed * 100L / wipe.holdMillis()));
            player.sendActionBar(plugin.getMessages().getComponent("itrak-brush-progress",
                    "percent", String.valueOf(percent)));
        }
        if (wipes.isEmpty()) {
            stopWipeTask();
        }
    }

    private static boolean holdsBrush(Player player) {
        return player.getInventory().getItemInMainHand().getType() == REMOVE_TOOL
                || player.getInventory().getItemInOffHand().getType() == REMOVE_TOOL;
    }

    /**
     * Игрок всё ещё наводится на тот же трекер: цель впереди, не дальше
     * вытянутой руки. Угол, а не точный блок, — трекер висит в воздухе у
     * поверхности, и требовать попасть в конкретный блок было бы мучением.
     */
    private boolean stillAiming(Player player, Tracker tracker, Wipe wipe) {
        Location target;
        if (wipe.entityId() != null) {
            Entity entity = Bukkit.getEntity(wipe.entityId());
            if (entity == null || !entity.isValid() || entity.isDead()) {
                return false;
            }
            target = entity.getLocation().add(0, Math.max(0.4, entity.getHeight() * 0.5), 0);
        } else {
            Location live = tracker.liveLocation();
            target = live == null ? tracker.getLocation() : live;
        }
        if (target == null || target.getWorld() == null) {
            return false;
        }
        Location eye = player.getEyeLocation();
        if (!eye.getWorld().equals(target.getWorld())) {
            return false;
        }
        Vector to = target.toVector().subtract(eye.toVector());
        double distance = to.length();
        if (distance < 0.05 || distance > WIPE_REACH) {
            return false;
        }
        return to.normalize().dot(eye.getDirection()) >= WIPE_COS;
    }

    private void runScan(Player player, int itemRadius) {
        // 0 — старый локатор без радиуса в PDC: берём радиус из конфига.
        int radius = itemRadius > 0 ? itemRadius
                : (int) plugin.getConfigManager().getItrakScanRadius();
        // Перезарядка (5 минут на 10 блоков, 7 — на дальний) проверяется и
        // здесь, и в /gps itrak scan: второй локатор не должен её обходить.
        if (!ItrakCommand.prepareScan(plugin, player, radius)) {
            return;
        }
        ItrakCommand.reportScan(plugin, player, radius);
    }

    /**
     * Перезаход сбрасывал ванильную полосу КД (она живёт в памяти сессии),
     * а сам КД — нет. Возвращаем полосу, чтобы игрок видел перезарядку.
     */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        TrackerService service = plugin.getTrackerService();
        if (service != null) {
            service.applyScanCooldownBar(event.getPlayer());
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        // Игроков обрабатывает onPlayerDeath: PlayerDeathEvent — подкласс
        // EntityDeathEvent, и без этой отсечки смерть игрока приходила дважды.
        if (event.getEntity() instanceof Player) {
            return;
        }
        TrackerService service = plugin.getTrackerService();
        if (service != null) {
            service.onEntityGone(event.getEntity());
        }
    }

    /**
     * Смерть игрока — отдельное событие: {@link EntityDeathEvent} для игроков
     * игнорируется в {@link #onEntityDeath(EntityDeathEvent)}. Без него трекер
     * оставался «на игроке» после респавна: запись ссылалась на мёртвую
     * сущность, а локатор продолжал показывать её в точке смерти.
     */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        TrackerService service = plugin.getTrackerService();
        if (service != null) {
            service.onEntityGone(event.getEntity());
        }
    }
}
