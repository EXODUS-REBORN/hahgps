package ru.hahgps.util;

import org.bukkit.entity.EntityType;
import ru.hahgps.model.TrackerColor;

/**
 * Русские названия носителей iTrak и цвет, который трекер получает сам.
 *
 * Зачем не {@link EntityType#getName()}: у ванильного типа имя английское
 * («OAK_BOAT», «HORSE»), и трекер на лодке назывался «Boat». Игроку нужно
 * «Лодка», «Лошадь», «Свинья», поэтому подписи живут здесь, а не в клиенте:
 * сервер не знает язык клиента и перевести тип сам не может.
 *
 * Цвет тоже задаёт тип носителя: на карте и в локаторе маячок сразу понятен —
 * лодки синие, лошади жёлтые, животные зелёные, монстры красные, техника и
 * декорации белые. Для типа без своего цвета трекер сохраняет цвет предмета:
 * игрок сам выбрал его при крафте.
 */
public final class EntityNames {

    /** Подпись для типа, которого нет в таблице (новый тип после обновления API). */
    public static final String FALLBACK = "Существо";

    private EntityNames() {
    }

    /**
     * Русское название типа сущности. Пустых ответов нет: неизвестный тип —
     * «Существо», а не английский ключ.
     */
    public static String name(EntityType type) {
        if (type == null) {
            return FALLBACK;
        }
        return switch (type) {
            // --- лодки и плоты ---
            case OAK_BOAT, SPRUCE_BOAT, BIRCH_BOAT, JUNGLE_BOAT, ACACIA_BOAT,
                 DARK_OAK_BOAT, MANGROVE_BOAT, CHERRY_BOAT, PALE_OAK_BOAT -> "Лодка";
            case OAK_CHEST_BOAT, SPRUCE_CHEST_BOAT, BIRCH_CHEST_BOAT, JUNGLE_CHEST_BOAT,
                 ACACIA_CHEST_BOAT, DARK_OAK_CHEST_BOAT, MANGROVE_CHEST_BOAT,
                 CHERRY_CHEST_BOAT, PALE_OAK_CHEST_BOAT -> "Лодка с сундуком";
            case BAMBOO_RAFT -> "Плот";
            case BAMBOO_CHEST_RAFT -> "Плот с сундуком";

            // --- вагонетки ---
            case MINECART -> "Вагонетка";
            case CHEST_MINECART -> "Вагонетка с сундуком";
            case FURNACE_MINECART -> "Вагонетка с печью";
            case HOPPER_MINECART -> "Вагонетка с воронкой";
            case TNT_MINECART -> "Вагонетка с динамитом";
            case COMMAND_BLOCK_MINECART -> "Вагонетка с командным блоком";
            case SPAWNER_MINECART -> "Вагонетка со спавнером";

            // --- животные ---
            case PIG -> "Свинья";
            case COW -> "Корова";
            case MOOSHROOM -> "Грибная корова";
            case SHEEP -> "Овца";
            case CHICKEN -> "Курица";
            case RABBIT -> "Кролик";
            case GOAT -> "Коза";
            case ARMADILLO -> "Броненосец";
            case BEE -> "Пчела";
            case FROG -> "Лягушка";
            case SNIFFER -> "Нюхач";
            case TURTLE -> "Черепаха";
            case AXOLOTL -> "Аксолотль";
            case DOLPHIN -> "Дельфин";
            case SQUID -> "Спрут";
            case GLOW_SQUID -> "Светящийся спрут";
            case COD -> "Треска";
            case SALMON -> "Лосось";
            case TROPICAL_FISH -> "Тропическая рыба";
            case PUFFERFISH -> "Рыба-ёж";
            case TADPOLE -> "Головастик";
            case NAUTILUS -> "Наутилус";
            case STRIDER -> "Страйдер";

            // --- лошади и вьючные ---
            case HORSE -> "Лошадь";
            case SKELETON_HORSE -> "Лошадь-скелет";
            case ZOMBIE_HORSE -> "Зомби-лошадь";
            case DONKEY -> "Осёл";
            case MULE -> "Мул";
            case LLAMA -> "Лама";
            case TRADER_LLAMA -> "Караван-лама";
            case CAMEL -> "Верблюд";
            case CAMEL_HUSK -> "Верблюд-кадавр";

            // --- питомцы ---
            case WOLF -> "Волк";
            case CAT -> "Кошка";
            case OCELOT -> "Оцелот";
            case FOX -> "Лиса";
            case PARROT -> "Попугай";
            case PANDA -> "Панда";
            case POLAR_BEAR -> "Белый медведь";
            case ALLAY -> "Тихоня";
            case BAT -> "Летучая мышь";

            // --- жители и големы ---
            case VILLAGER -> "Житель";
            case WANDERING_TRADER -> "Странствующий торговец";
            case ZOMBIE_VILLAGER -> "Зомби-житель";
            case IRON_GOLEM -> "Железный голем";
            case SNOW_GOLEM -> "Снежный голем";
            case COPPER_GOLEM -> "Медный голем";

            // --- нежить и монстры ---
            case ZOMBIE -> "Зомби";
            case HUSK -> "Кадавр";
            case DROWNED -> "Утопленник";
            case ZOMBIE_NAUTILUS -> "Зомби-наутилус";
            case SKELETON -> "Скелет";
            case STRAY -> "Зимогор";
            case BOGGED -> "Болотник";
            case PARCHED -> "Иссохший";
            case WITHER_SKELETON -> "Скелет-иссушитель";
            case CREEPER -> "Крипер";
            case SPIDER -> "Паук";
            case CAVE_SPIDER -> "Пещерный паук";
            case SILVERFISH -> "Чешуйница";
            case ENDERMITE -> "Эндермит";
            case ENDERMAN -> "Эндермен";
            case BLAZE -> "Всполох";
            case GHAST -> "Гаст";
            case HAPPY_GHAST -> "Счастливый гаст";
            case MAGMA_CUBE -> "Магмовый куб";
            case SLIME -> "Слизень";
            case PHANTOM -> "Фантом";
            case SHULKER -> "Шалкер";
            case GUARDIAN -> "Страж";
            case ELDER_GUARDIAN -> "Древний страж";
            case EVOKER -> "Вызыватель";
            case VINDICATOR -> "Поборник";
            case PILLAGER -> "Разбойник";
            case ILLUSIONER -> "Иллюзионист";
            case RAVAGER -> "Разоритель";
            case VEX -> "Досаждатель";
            case WITCH -> "Ведьма";
            case WARDEN -> "Хранитель";
            case CREAKING -> "Скрипун";
            case BREEZE -> "Вихрь";
            case GIANT -> "Гигант";
            case WITHER -> "Иссушитель";
            case ENDER_DRAGON -> "Дракон Края";
            case PIGLIN -> "Пиглин";
            case PIGLIN_BRUTE -> "Пиглин-громила";
            case ZOMBIFIED_PIGLIN -> "Зомбифицированный пиглин";
            case HOGLIN -> "Хоглин";
            case ZOGLIN -> "Зоглин";

            // --- игрок ---
            case PLAYER -> "Игрок";

            // --- техника и декорации ---
            case ARMOR_STAND -> "Стойка для брони";
            case MANNEQUIN -> "Манекен";
            case ITEM_FRAME -> "Рамка";
            case GLOW_ITEM_FRAME -> "Светящаяся рамка";
            case PAINTING -> "Картина";
            case ITEM -> "Предмет";
            case BLOCK_DISPLAY -> "Отображение блока";
            case ITEM_DISPLAY -> "Отображение предмета";
            case TEXT_DISPLAY -> "Отображение текста";
            case INTERACTION -> "Маркер взаимодействия";
            case MARKER -> "Маркер";
            case LEASH_KNOT -> "Узел поводка";
            case OMINOUS_ITEM_SPAWNER -> "Зловещий спавнер предметов";

            // --- снаряды и эффекты ---
            case ARROW -> "Стрела";
            case SPECTRAL_ARROW -> "Спектральная стрела";
            case TRIDENT -> "Трезубец";
            case SNOWBALL -> "Снежок";
            case EGG -> "Яйцо";
            case ENDER_PEARL -> "Эндер-жемчуг";
            case EYE_OF_ENDER -> "Око Эндера";
            case FIREWORK_ROCKET -> "Фейерверк";
            case FISHING_BOBBER -> "Поплавок";
            case EXPERIENCE_ORB -> "Сфера опыта";
            case EXPERIENCE_BOTTLE -> "Бутылочка опыта";
            case LINGERING_POTION -> "Туманное зелье";
            case SPLASH_POTION -> "Взрывное зелье";
            case FALLING_BLOCK -> "Падающий блок";
            case TNT -> "Динамит";
            case LIGHTNING_BOLT -> "Молния";
            case AREA_EFFECT_CLOUD -> "Облако эффекта";
            case END_CRYSTAL -> "Кристалл Края";
            case FIREBALL -> "Огненный шар";
            case SMALL_FIREBALL -> "Малый огненный шар";
            case DRAGON_FIREBALL -> "Драконий файрбол";
            case WITHER_SKULL -> "Череп иссушителя";
            case SHULKER_BULLET -> "Снаряд шалкера";
            case LLAMA_SPIT -> "Плевок ламы";
            case EVOKER_FANGS -> "Клыки вызывателя";
            case WIND_CHARGE -> "Ветровой заряд";
            case BREEZE_WIND_CHARGE -> "Ветровой заряд вихря";

            default -> FALLBACK;
        };
    }

    /**
     * Цвет трекера по типу носителя. {@code null} — у типа своего цвета нет,
     * трекер остаётся того цвета, каким был предмет.
     */
    public static TrackerColor color(EntityType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            // Вода и транспорт — синий/голубой: на карте сразу видно, что это лодка.
            case OAK_BOAT, SPRUCE_BOAT, BIRCH_BOAT, JUNGLE_BOAT, ACACIA_BOAT,
                 DARK_OAK_BOAT, MANGROVE_BOAT, CHERRY_BOAT, PALE_OAK_BOAT,
                 OAK_CHEST_BOAT, SPRUCE_CHEST_BOAT, BIRCH_CHEST_BOAT, JUNGLE_CHEST_BOAT,
                 ACACIA_CHEST_BOAT, DARK_OAK_CHEST_BOAT, MANGROVE_CHEST_BOAT,
                 CHERRY_CHEST_BOAT, PALE_OAK_CHEST_BOAT,
                 BAMBOO_RAFT, BAMBOO_CHEST_RAFT,
                 DOLPHIN, SQUID, GLOW_SQUID, COD, SALMON, TROPICAL_FISH,
                 PUFFERFISH, TADPOLE, TURTLE, AXOLOTL -> TrackerColor.BLUE;

            // Техника — голубой.
            case MINECART, CHEST_MINECART, FURNACE_MINECART, HOPPER_MINECART,
                 TNT_MINECART, COMMAND_BLOCK_MINECART, SPAWNER_MINECART -> TrackerColor.CYAN;

            // Лошади и вьючные — жёлтый.
            case HORSE, SKELETON_HORSE, ZOMBIE_HORSE, DONKEY, MULE,
                 LLAMA, TRADER_LLAMA, CAMEL, CAMEL_HUSK -> TrackerColor.YELLOW;

            // Питомцы — оранжевый.
            case WOLF, CAT, OCELOT, FOX, PARROT, PANDA, POLAR_BEAR, ALLAY,
                 BAT -> TrackerColor.ORANGE;

            // Мирные животные — зелёный.
            case PIG, COW, MOOSHROOM, SHEEP, CHICKEN, RABBIT, GOAT, ARMADILLO,
                 BEE, FROG, SNIFFER, STRIDER, NAUTILUS -> TrackerColor.GREEN;

            // Жители и големы — фиолетовый.
            case VILLAGER, WANDERING_TRADER, ZOMBIE_VILLAGER, IRON_GOLEM,
                 SNOW_GOLEM, COPPER_GOLEM, ENDERMAN, PIGLIN, ZOMBIFIED_PIGLIN,
                 HOGLIN, ZOGLIN -> TrackerColor.PURPLE;

            // Монстры и игрок — красный.
            case ZOMBIE, HUSK, DROWNED, ZOMBIE_NAUTILUS, SKELETON, STRAY, BOGGED,
                 PARCHED, WITHER_SKELETON, CREEPER, SPIDER, CAVE_SPIDER, SILVERFISH,
                 ENDERMITE, BLAZE, GHAST, HAPPY_GHAST, MAGMA_CUBE, SLIME, PHANTOM,
                 SHULKER, GUARDIAN, ELDER_GUARDIAN, EVOKER, VINDICATOR, PILLAGER,
                 ILLUSIONER, RAVAGER, VEX, WITCH, WARDEN, CREAKING, BREEZE, GIANT,
                 WITHER, ENDER_DRAGON, PIGLIN_BRUTE, PLAYER -> TrackerColor.RED;

            default -> null;
        };
    }

    /** Есть ли у типа собственное русское имя (всё, кроме заглушки). */
    public static boolean hasName(EntityType type) {
        return type != null && !FALLBACK.equals(name(type));
    }
}
