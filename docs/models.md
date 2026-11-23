# Модели ресурспака HahGPS

Актуальный состав пака: стрелки HUD, 32×32 иконки меню (навигация, главное
меню, точки, категории, поиск, настройки, транспорт), 3D-трекеры iTrak всех
цветов, 3D-локаторы. Исходники и сборка — `respack/` (`python3 respack/build.py`),
краткое описание — [respack/README.md](../respack/README.md).

Источник истины по номерам — `ru.hahgps.item.ItemIds`; менять выпущенные номера
нельзя, ресурспак привязан к ним через `custom_model_data`.

## Предметы

| Что | CMD | Основа | Модель |
|---|---|---|---|
| Трекер, 8 цветов × обычный/водонепроницаемый | 2001–2016 | лазурит, призмарин, изумруд, золотой самородок, магма, редстоун, аметист, кварц | `hahgps:item/tracker_<цвет>` и `_wp` |
| Локатор, 20 блоков | 2020 | `recovery_compass` | `hahgps:item/locator` |
| Дальний локатор, 30 блоков | 2021 | `recovery_compass` | `hahgps:item/locator_long` |
| Иконки меню: навигация | 1001–1007 | `spectral_arrow`, `lime_dye`, `barrier`, `book`, `paper`, синее стекло | `hahgps:item/gui_nav_*`, `gui_page_info`, `gui_brand`, `gui_filler` |
| Иконки меню: главное меню | 1102, 1104, 1105 | `book`, `paper`, `redstone` | `hahgps:item/gui_categories/search/settings` |
| Иконки меню: точки | 1201–1206 | `compass`, `beacon`, `book`, `ender_pearl`, `nether_star`, `paper` | `hahgps:item/gui_poi_*` |
| Иконки меню: категории | 1401–1403, 1102 | `book`, `emerald`, `lava_bucket` | `hahgps:item/gui_category*` |
| Иконки меню: поиск | 1501–1505, 1104 | `paper`, `barrier`, `repeater` | `hahgps:item/gui_search*` |
| Иконки меню: настройки | 1601–1602, 1105 | `book`, `paper`, `redstone` | `hahgps:item/gui_language`, `gui_logging`, `gui_settings` |
| Иконки меню: транспорт | 1701–1705 | `leather`, `minecart`, `rail`, `feather`, `oak_boat` | `hahgps:item/gui_transport_*` |
| Главное меню: 3D-иконки | 1101, 1106, 1107 | `compass`, `recovery_compass` | `hahgps:item/main_points`, `main_catalog`, `main_trackers` |
| Карточка точки | 1301–1304 | `paper`, `compass`, `nether_star`, `lava_bucket` | `hahgps:item/gui_ui_poi_info/goto/rate/delete` |
| Карточка трекера и каталог | 2030, 2032–2039, 2044 | `book`, `compass`, `spyglass`, `glass`, `barrier`, `chest`, `name_tag`, `lava_bucket`, `lapis_block` | `hahgps:item/gui_ui_itrak_*` |
| Глифы HUD/чата | шрифт | `hahgps:arrow` | 4 ванильные стрелки, перекрашенные в жёлтый |

Разделение 3D/2D осознанное: **объёмные** иконки — у трёх разделов главного
меню (карта с компасом и пином, витрина с трекером под стеклом, жетон на
стальном держателе, `ui_models.py`); **плоские** 32×32 — у кнопок и карточек
точки/трекера (досье, пунктирный маршрут, звезда, корзина, глаз слежения,
радар, каталог, бирка, замок, «пусто», `ui_art.py`). Читаемость значка в
слоте важнее объёма, а объём — у разделов, где он помогает узнать «вещь».

Все трекеры — один тонкий 3D-диск: белое лицо с головой крипера, цветной бампер,
скруглённые кромки, металлическая задняя крышка. Водонепроницаемая версия
отличается бирюзовым кантом и каплей, а не бликом зачарования. У выброшенного
предмета модель объёмная (`display.ground`), блик отключён в самом предмете
(`enchantment_glint_override: false`).

Локаторы — ручные сканеры: корпус, экран с радаром, кнопки и антенна. Дальняя
версия: жёлтые кольца и длинная антенна с жёлтым кантом.

## Ванильные предметы

Пак переопределяет только `custom_model_data`-диспетчеры, а не сами предметы:

- 8 материалов трекеров — fallback на ванильный предмет;
- материалы иконок (`spectral_arrow`, `lime_dye`, `barrier`, `book`, `paper`,
  синее стекло, `compass`, `beacon`, `ender_pearl`, `nether_star`, `emerald`,
  `redstone`, `lava_bucket`, `repeater`, `leather`, `minecart`, `rail`,
  `feather`, `oak_boat`) — fallback на ванильный предмет, чужие CMD на них
  записаны в диспетчер отдельно, порог `1 000 000` тоже уводит в ваниль;
  у `compass` fallback — полная ванильная модель со стрелкой;
- `recovery_compass` — fallback на **полную ванильную модель компаса** (стрелка
  на точку смерти сохраняется);

## Превью

- `respack/preview/01-hud.png` — глифы стрелок;
- `respack/preview/02-icons.png` — 31 иконок меню с номерами CMD;
- `respack/preview/03-trackers.png` — 16 трекеров;
- `respack/preview/04-locators.png` — локаторы;
- `respack/preview/05-dropped.png` — предметы на земле;
- `respack/preview/06-gui-catalog.png` — каталог выдачи;
- `respack/preview/07-gui-my-trackers.png` — «Мои трекеры»;
- `respack/preview/08-cards.png` — карточки точки и трекера;
- `respack/preview/09-main-3d.png` — 3D-иконки главного меню;
- `respack/preview/10-gui-main-menu.png` — главное меню;
- `respack/preview/11-gui-poi-card.png` — карточка точки;
- `respack/preview/12-gui-tracker-card.png` — карточка трекера;

Иконки рисуются в 32×32 (`respack/gui_art.py` и `respack/ui_art.py`): объекты реального мира, а не
абстрактные символы. Группы: Навигация (7), Главное меню (3), Иконки точек по категориям (6), Категории (3), Поиск (5), Настройки (2), Транспорт (5). Иконки iTrak (CMD 2030+) пока остаются
ванильными предметами.
