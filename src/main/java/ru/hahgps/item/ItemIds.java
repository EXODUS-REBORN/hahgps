package ru.hahgps.item;

import ru.hahgps.model.TrackerColor;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Кастомные ID (CustomModelData) всех предметов плагина.
 *
 * Значение — это идентификатор модели для будущего ресурспака: текстуры
 * рисуются отдельно, а сервер уже сейчас отдаёт стабильный номер, поэтому
 * менять его у выпущенного предмета нельзя. Диапазоны:
 * <ul>
 *   <li>1000–1099 — общая навигация GUI;</li>
 *   <li>1100–1199 — главное меню;</li>
 *   <li>1200–1299 — точки и категории;</li>
 *   <li>1300–1399 — карточка точки;</li>
 *   <li>1400–1499 — категории;</li>
 *   <li>1500–1599 — поиск;</li>
 *   <li>1600–1699 — настройки;</li>
 *   <li>1700–1799 — транспорт;</li>
 *   <li>1800–1899 — знаки маршрута (1810–1817 предмет, 1820–1827 блок);</li>
 *   <li>2000–2099 — iTrak.</li>
 * </ul>
 */
public final class ItemIds {

    private ItemIds() {
    }

    public static final int NAV_BACK = 1001;
    public static final int NAV_FORWARD = 1002;
    public static final int NAV_INFO = 1003;
    public static final int NAV_CLOSE = 1004;
    public static final int PAGE_INFO = 1005;
    public static final int BRAND = 1006;
    public static final int FILLER = 1007;

    public static final int MAIN_POINTS = 1101;
    public static final int MAIN_CATEGORIES = 1102;
    public static final int MAIN_SEARCH = 1104;
    public static final int MAIN_SETTINGS = 1105;
    /** «Мои трекеры» — список установленных маячков. */
    public static final int MAIN_ITRAK = 1106;
    /** «Трекеры и локаторы» — каталог выдачи предметов. */
    public static final int MAIN_ITRAK_GIVE = 1107;

    public static final int POI_ICON_BASE = 1201;
    public static final int POI_ICON_COUNT = 6;

    public static final int POI_INFO = 1301;
    public static final int POI_GOTO = 1302;
    public static final int POI_RATE = 1303;
    public static final int POI_DELETE = 1304;

    public static final int CATEGORY_ENTRY = 1401;
    public static final int CATEGORY_CREATE = 1402;
    public static final int CATEGORY_DELETE = 1403;

    public static final int SEARCH_INPUT = 1501;
    public static final int SEARCH_QUICK = 1502;
    public static final int SEARCH_RESULT = 1503;
    public static final int SEARCH_EMPTY = 1504;
    /** Кнопка «сколько мест показывать» в поиске (1..25). */
    public static final int SEARCH_LIMIT = 1505;

    public static final int SETTINGS_LANGUAGE = 1601;
    public static final int SETTINGS_LOGGING = 1602;

    public static final int TRANSPORT_BASE = 1701;

    /**
     * 8 номеров 3D-предмета «знак-указатель» (стрелка на чёрной плите).
     * Номера рисует ресурспак (`respack/build.py`), выдаче в игре они пока не
     * нужны: пак сам подставляет модель на `minecraft:oak_sign` по CMD.
     */
    public static final int ROUTE_SIGN_BASE = 1810;

    /** 8 номеров 3D-блока-знака: та же стрелка на бетонной опоре. */
    public static final int ROUTE_BLOCK_BASE = 1820;

    /** 16 номеров: 8 цветов × (обычный, водонепроницаемый). */
    public static final int TRACKER_BASE = 2001;

    public static final int ITRAK_LOCATOR = 2020;
    /** Дальний локатор (30+ блоков): отдельная модель под свой ресурс. */
    public static final int ITRAK_LOCATOR_LONG = 2021;
    public static final int ITRAK_MENU_ENTRY = 2030;
    public static final int ITRAK_DETAIL = 2031;
    public static final int ITRAK_ROUTE = 2032;
    public static final int ITRAK_WATCH = 2033;
    public static final int ITRAK_TAKE = 2034;
    public static final int ITRAK_REMOVE = 2035;
    public static final int ITRAK_SCAN = 2036;
    public static final int ITRAK_GIVE = 2037;
    public static final int ITRAK_REFRESH = 2038;
    public static final int ITRAK_EMPTY = 2039;
    public static final int ITRAK_CHARGE = 2041;
    public static final int ITRAK_TERMINAL = 2042;
    public static final int ITRAK_CRAFT = 2043;
    public static final int ITRAK_RENAME = 2044;

    /** ID предмета-трекера: цвет и водозащита различаются номером модели. */
    public static int tracker(TrackerColor color, boolean waterproof) {
        if (color == null) {
            return TRACKER_BASE;
        }
        return TRACKER_BASE + color.ordinal() * 2 + (waterproof ? 1 : 0);
    }

    /** Все занятые номера — тест проверяет, что они не пересекаются. */
    public static Set<Integer> all() {
        Set<Integer> ids = new LinkedHashSet<>();
        for (int i = NAV_BACK; i <= FILLER; i++) {
            ids.add(i);
        }
        ids.add(MAIN_POINTS);
        ids.add(MAIN_CATEGORIES);
        ids.add(MAIN_SEARCH);
        ids.add(MAIN_SETTINGS);
        ids.add(MAIN_ITRAK);
        ids.add(MAIN_ITRAK_GIVE);
        for (int i = 0; i < POI_ICON_COUNT; i++) {
            ids.add(POI_ICON_BASE + i);
        }
        ids.add(POI_INFO);
        ids.add(POI_GOTO);
        ids.add(POI_RATE);
        ids.add(POI_DELETE);
        ids.add(CATEGORY_ENTRY);
        ids.add(CATEGORY_CREATE);
        ids.add(CATEGORY_DELETE);
        ids.add(SEARCH_INPUT);
        ids.add(SEARCH_QUICK);
        ids.add(SEARCH_RESULT);
        ids.add(SEARCH_EMPTY);
        ids.add(SEARCH_LIMIT);
        ids.add(SETTINGS_LANGUAGE);
        ids.add(SETTINGS_LOGGING);
        for (int i = 0; i < 5; i++) {
            ids.add(TRANSPORT_BASE + i);
        }
        for (int i = 0; i < 8; i++) {
            ids.add(ROUTE_SIGN_BASE + i);
            ids.add(ROUTE_BLOCK_BASE + i);
        }
        for (TrackerColor color : TrackerColor.values()) {
            ids.add(tracker(color, false));
            ids.add(tracker(color, true));
        }
        ids.add(ITRAK_LOCATOR);
        ids.add(ITRAK_LOCATOR_LONG);
        ids.add(ITRAK_MENU_ENTRY);
        ids.add(ITRAK_DETAIL);
        ids.add(ITRAK_ROUTE);
        ids.add(ITRAK_WATCH);
        ids.add(ITRAK_TAKE);
        ids.add(ITRAK_REMOVE);
        ids.add(ITRAK_SCAN);
        ids.add(ITRAK_GIVE);
        ids.add(ITRAK_REFRESH);
        ids.add(ITRAK_EMPTY);
        ids.add(ITRAK_CHARGE);
        ids.add(ITRAK_TERMINAL);
        ids.add(ITRAK_CRAFT);
        ids.add(ITRAK_RENAME);
        return ids;
    }
}
