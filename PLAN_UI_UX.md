# План: динамические будильники (до 100), плеер в карточке, редизайн списка

**Файл:** `E:\Code\Android\timer_sound\PLAN_UI_UX.md`
**Статус:** план согласован с владельцем по 7 решениям, код не менялся (анализ + план).
**База:** ветка `master`, HEAD `e697255`, рабочее дерево чистое (кроме неотслеживаемого `TEST_COVERAGE_PERCENTAGE.md`).
**Объём проекта:** 2691 строка Kotlin (13 файлов main + 4 файла test), 41 юнит-тест.

---

## 1. Цель

1. **Динамический список будильников** вместо жёстко зашитых 5 каналов: добавление, удаление одного, удаление всех, лимит **100**.
2. **Карточка = самостоятельный плеер**: play/pause и прогресс воспроизведения своего звука, независимо от сессии таймера.
3. **UX-разгрузка экрана** под 100 элементов: свёрнутые карточки с раскрытием, переименование будильников, счётчик, empty state.
4. Не сломать то, что уже проверено на живом устройстве: движок расписаний, FGS, точный `AlarmManager`, звук срабатываний.

## 2. Границы

**В плане:** модель данных, хранилище + миграция, ViewModel API, UI-спецификация, аудио-слой, тесты, этапы, риски.

**Не в плане (по решению владельца):** ручной порядок drag&drop, дублирование будильника, вкладки/группы, поиск и фильтры, экспорт/импорт, локализация строк, Room, переработка машины состояний, автозапуск после перезагрузки, тёмная тема.

**Код в рамках этого документа не пишется.** Реализация — после утверждения плана.

---

## 3. Зафиксированные решения (ответы владельца)

| № | Вопрос | Решение |
|---|---|---|
| Р-1 | Структура UI | **Единый список + «Добавить»**. Вкладок нет: сверху статус/авто-стоп, снизу FAB «+ Добавить», на карточке «× Удалить», в шапке «Удалить все» и счётчик `N/100` |
| Р-2 | Что такое «карточка-плеер» | **Полноценный плеер**: play/pause + прогресс трека (не one-shot «Прослушать») |
| Р-3 | Хранилище | **DataStore + JSON-список** (`alarms_json`) с миграцией старых `ch0..ch4` |
| Р-4 | Аудио-путь | **Ringtone (срабатывания таймера) + media3 ExoPlayer (плеер)**. Срабатывания не трогаем; плеер — на ExoPlayer. Обязателен спайк-гейт на устройстве |
| Р-5 | Правки при идущей сессии | **Заблокировать**: пока `RUNNING`/`PAUSED` — карточки read-only, `+`/`×`/«Удалить все» неактивны |
| Р-6 | Управление карточками | **Переименование** + **свёрнутая карточка с раскрытием**. Drag&drop и дублирование — вне плана |
| Р-7 | Файл плана | `E:\Code\Android\timer_sound\PLAN_UI_UX.md` (этот файл) |

---

## 4. Состояние «как есть» (факты, не догадки)

### 4.1 Раскладка

```
app/src/main/java/com/timersound/
├── MainActivity.kt (27)          showWhenLocked + turnScreenOn + App()
├── TimerViewModel.kt (212)       владелец конфига, команды сервису
├── model/TimerConfig.kt (90)     ChannelConfig, TimerConfig, Defaults (CHANNEL_COUNT = 5)
├── model/SceneMode.kt (15)       REPEAT / ONCE_TIME / INTERVAL / RANDOM
├── data/PreferencesRepository.kt (104)
├── timer/TimerSession.kt (215)   машина состояний (IDLE/RUNNING/PAUSED/COMPLETED)
├── timer/SceneScheduler.kt (76)  чистая математика расписаний
├── service/TimerSoundService.kt (502)  FGS + уведомление + AlarmManager + очередь команд
├── service/TimerStateHolder.kt (35)
├── audio/AudioEngine.kt (113)    по одному Ringtone на сценарий
└── ui/App.kt (490), ui/Theme.kt (37)
```

### 4.2 Ключевые факты

- **Вкладок нет.** `App.kt` — одна `LazyColumn`: `StatusCard` → `AutoStopCard` → `items(config.channels)`. «Виды будильников» = 4 режима (`SceneMode`), выбираются `FilterChip` внутри карточки.
- **Список фиксирован**: `Defaults.CHANNEL_COUNT = 5`, `id = 0..4`, канал 0 = встроенный бип (`Defaults.BUILT_IN_BEEP = "@beep"`).
- **Операций add/remove/reorder нет вообще** — в `TimerViewModel` только сеттеры значений.
- **Имя не персистится**: `readChannelConfig` подставляет `name = Defaults.defaultChannel(id).name`, то есть «Канал ${id+1}` берётся из кода. Переименование сейчас невозможно в принципе.
- **Смешанный API по id и по индексу**: `setEnabled/setVolume/setInterval` — по `id`; `setMode/setStartMinutes/setEndMinutes/setLaunchCount/setIntervalAt` — по `index` (`updateConfig(index, …)`). UI передаёт `index = config.channels.indexOf(channel)` — при динамическом списке это источник багов.
- **`save(config)` переписывает все каналы**: `context.dataStore.edit { channels.forEach { writeChannelConfig(it) }; setChannelCount(size); autoStop }` — 5 каналов × 9 ключей = 45 записей на любое изменение; при 100 будильниках это ~900 записей на каждый тап слайдера.
- **Особый случай `id == 0`** в `removeFile`: возврат к встроенному бипу привязан к номеру канала, а не к «встроенному сигналу как выбору».
- **Пикер файла живёт внутри карточки**: `rememberLauncherForActivityResult` в `ChannelCard` — при переиспользовании элементов `LazyColumn` это ненадёжно.
- **`AudioEngine`**: `Map<Int, Ringtone>` по id; `play()` пересоздаёт Ringtone, если предыдущий ещё звучит; `preview()` отдельная карта; `releaseAll()` гарантирует очистку. Ringtone **не умеет** позицию и pause/resume.
- **README §16 фиксирует причину выбора Ringtone**: `MediaPlayer` на целевом устройстве (Huawei P20 Pro, Android 10 / EMUI) молча не воспроизводил файл — именно это выглядело как баг «таймер срабатывает, звука нет». Переход назад на `MediaPlayer` без проверки на устройстве запрещён.
- **Тесты завязаны на «5 каналов»**: `PreferencesRepositoryTest` (`absentChannelCountKeepsFiveLegacyChannels`, `negativeStoredChannelCountMeansNoChannels`), `TimerConfigTest`, `TimerSessionTest` — фабрики `channel(id = …)`.
- **Зависимости** (`gradle/libs.versions.toml`): AGP 9.4.0, Kotlin 2.4.20, composeBom 2026.09.00, lifecycle 2.11.0, datastore 1.2.1, coroutines 1.11.0, JUnit 4.13.2. Репозитории — `google()` + `mavenCentral()`, добавление библиотеки возможно.
- **Манифест**: разрешения `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `POST_NOTIFICATIONS`; сервис `foregroundServiceType="mediaPlayback"`. Для ExoPlayer новых разрешений не нужно (`INTERNET` не требуется — играем локальные URI).

---

## 5. Целевая модель данных

### 5.1 `model/TimerConfig.kt`

```kotlin
data class AlarmConfig(              // был ChannelConfig
    val id: Int,                     // СТАБИЛЬНЫЙ, монотонный, НЕ переиспользуется
    val name: String,                // персистится; пустое -> "Будильник {id}" на рендере
    val fileUri: String,             // SAF-URI | BUILT_IN_BEEP | ""
    val fileName: String? = null,
    val mode: SceneMode = SceneMode.REPEAT,
    val intervalMs: Long,
    val startMinutes: Int? = null,
    val endMinutes: Int? = null,
    val launchCount: Int = 0,
    val volumePercent: Int,
    val enabled: Boolean,
) { /* hasFile, isBuiltInBeep, scheduleValid — без изменений */ }

data class TimerConfig(
    val alarms: List<AlarmConfig>,   // был channels; порядок списка = порядок на экране
    val autoStopMs: Long,
) { playableAlarms() / missingFileAlarms() / invalidScheduleAlarms() }

object Defaults {
    const val MAX_ALARMS = 100                  // NEW
    const val MIN_INTERVAL_MS = 1_000L
    const val MINUTES_PER_DAY = 24 * 60
    const val DEFAULT_INTERVAL_MS = 5 * 60_000L
    const val DEFAULT_VOLUME_PERCENT = 80
    const val BUILT_IN_BEEP = "@beep"
    const val MAX_NAME_LENGTH = 40              // NEW (UI-валидация)

    fun newAlarm(id: Int, orderNumber: Int): AlarmConfig   // пустой файл, enabled = false
    fun firstRunConfig(): TimerConfig                      // 1 будильник с встроенным бипом, ВКЛ
}
```

**Почему `id: Int`, а не UUID:** стабильный int сохраняет ключи `Map<Int, Ringtone>` в `AudioEngine`, id уведомлений и простоту тестов. Монотонность обязательна: после «удалить → добавить» id не должен совпасть с ещё звучащим Ringtone. Источник — персистентный счётчик `next_alarm_id`.

**Переименование `channels → alarms`** — механический рефактор по всем call-site (`TimerViewModel`, `TimerSession.start(config)`, `TimerSoundService`, `ui/App.kt`, тесты). Делать **отдельным коммитом** на Этапе 1, чтобы диффы читались.

### 5.2 Что НЕ меняется

- `SceneMode` (4 режима) и их семантика.
- `TimerSession` (машина состояний, `elapsedRealtime`, пауза через остатки, `markCompleted`).
- `SceneScheduler` (wall→elapsed, конечные серии, RANDOM с клампом по ширине окна).
- Правила валидации старта: `scheduleValid`, `missingFileAlarms`, `invalidScheduleAlarms`.
- Отсутствие сети, Room и автовозобновления сессии после перезапуска процесса.

---

## 6. Хранилище и миграция

### 6.1 Схема

Тот же DataStore-файл `timer_sound` (миграция читает старые ключи там же):

| Ключ | Тип | Назначение |
|---|---|---|
| `alarms_json` | String | Сериализованный список будильников (единственный источник истины) |
| `schema_version` | Int | `2` — новая схема; отсутствие ключа = версия 1 (legacy) |
| `next_alarm_id` | Int | Счётчик выдачи стабильных id |
| `auto_stop_ms` | Long | Без изменений |
| `ch{i}_*`, `channel_count` | — | **Удаляются** после успешной миграции |

### 6.2 Сериализация

**kotlinx.serialization** (`org.jetbrains.kotlinx:kotlinx-serialization-json` + плагин `org.jetbrains.kotlin.plugin.serialization`), а не `org.json`:

- `org.json` в JVM-юнит-тестах Android — заглушка (`not mocked`), пришлось бы добавлять `testImplementation("org.json:json")` и `unitTests.returnDefaultValues` — скрытая хрупкость в тестах, которые сейчас чистые.
- kotlinx.serialization работает и на JVM, и в рантайме; `AlarmConfig` помечается `@Serializable`, DTO отдельный (`AlarmDto`), чтобы формат на диске не был жёстко связан с доменной моделью.

**Точные версии зависимостей не выдумываю** — на реализации подобрать совместимые с Kotlin 2.4.20 / AGP 9.4.0 и зафиксировать в `gradle/libs.versions.toml`. Задача отмечена в Этапе 1 как «проверить доступную версию».

### 6.3 Миграция (v1 → v2), идемпотентная

```kotlin
suspend fun PreferencesRepository.ensureMigrated() {
    // 1. schema_version >= 2 -> выход (идемпотентность)
    // 2. собрать legacy-список: (0 until channelCountFrom(p)).map { readChannelConfig(p, it) }
    //    channel_count отсутствует -> 5 (legacy-поведение), channel_count = 0 -> пустой список
    // 3. map: id = i, name = "Будильник ${i + 1}", остальные поля как есть
    // 4. edit { alarms_json = Json.encodeToString(list); next_alarm_id = maxId + 1
    //           schema_version = 2; удалить все ключи ch{i}_* и channel_count }
}
```

Вызов: один раз из `TimerViewModel.init` **до** подписки на `repo.config` (или из `runBlocking`-эквивалента в корутине — планируемо, не в `map`-операторе).

Правила безопасности миграции:

- старые ключи удаляются **в той же** `edit`-транзакции, что и запись `alarms_json`, — иначе потеря настроек при падении;
- битый/нечитаемый `alarms_json` → не падаем: логируем и стартуем с `firstRunConfig()`;
- переполнение поля `launchCount`/`volumePercent` — те же клампы, что сейчас (`coerceIn(0, 100)`, `coerceAtLeast(MIN_INTERVAL_MS)`);
- миграция обязана покрываться тестом на «набор legacy-ключей» (уже есть в `PreferencesRepositoryTest` — переписать).

### 6.4 Политика записи

- Запись — **всего списка** (100 будильников ≈ 10–15 КБ JSON): одна транзакция, без 900 промежуточных ключей.
- **Слайдеры** (громкость, интервал) больше не пишут на каждое движение: локальный `mutableStateOf` на время перетаскивания + commit в `onValueChangeFinished` (у M3 `Slider` это есть). Для текстовых полей — commit по валидному разбору.
- Debounce на запись (≈300 мс) живёт в ViewModel, не в Compose.
- `releasePersistableUriPermission` при «Удалить файл»/удалении будильника — **только если** этот URI не используется другими будильниками (иначе сломаем соседа).

---

## 7. API ViewModel (`TimerViewModel.kt`)

### 7.1 Все мутации — по стабильному id

| Было | Стало |
|---|---|
| `setMode(index, mode)` | `setMode(id, mode)` |
| `setStartMinutes(index, m)` / `setEndMinutes` / `setLaunchCount` | `... (id, ...)` |
| `setInterval(channelId, ms)` + `setIntervalAt(index, ms)` | `setInterval(id, ms)` (одна функция) |
| `setEnabled/setVolume(channelId, …)` | `... (id, …)` без изменения |
| `updateConfig(index, transform)` | удаляется; остаётся один путь `updateAlarm(id, transform)` |

### 7.2 Новые операции

```kotlin
fun addAlarm()                       // лимит MAX_ALARMS; новый = без файла, ВЫКЛ, REPEAT, 5 мин, 80%
fun deleteAlarm(id: Int)             // стоп preview/Ringtone этого id + release SAF-права (если не занят)
fun deleteAllAlarms()                // очистка списка + releaseAll()/release всех preview
fun renameAlarm(id: Int, name: String)   // trim, обрезка до MAX_NAME_LENGTH, пустое -> автоимя
fun setBuiltInBeep(id: Int)          // «взять встроенный сигнал» для ЛЮБОГО будильника
fun onFilePicked(id: Int, uri: Uri)  // как сейчас + авто-включение (см. D-2)
fun removeFile(id: Int)              // без спецслучая id == 0: просто пустой fileUri
```

### 7.3 Блокировка правок (Р-5)

Единая точка — `updateAlarm` / `addAlarm` / `delete*`:

```kotlin
private val editingLocked get() =
    runtime.value.state == TimerState.RUNNING || runtime.value.state == TimerState.PAUSED
```

- Ранний выход + разовый UI-сигнал (Snackbar «Остановите таймер, чтобы менять будильники»).
- UI дополнительно дизейблит контролы (защита в глубину), чтобы пользователь не видел «мёртвый» тап.
- Следствие: сервис продолжает работать по снапшоту конфига, взятому на `ACTION_START`; гонок «сессия читает конфиг, пока UI его правит» не возникает — это и есть причина такого решения.

### 7.4 Плеер-состояние

`canEdit: StateFlow<Boolean>`, `previewState: StateFlow<PreviewState>` (`id`, `isPlaying`, `positionMs`, `durationMs`), `playerToggle(id)`, `playerStop()`.
Правило: **активен ровно один preview** — запуск на другом будильнике останавливает предыдущий; `playerStop()` вызывается при `start()` сессии и при уходе с экрана.

---

## 8. UI-спецификация (`ui/App.kt`)

### 8.1 Каркас

```
┌───────────────────────────────────────────────┐
│ Timer Sound                         [Идёт]  ⋮ │  ← TopAppBar + статус-бейдж
│ Будильники: 3/100                             │  ← счётчик
├───────────────────────────────────────────────┤
│ ┌───────────────────────────────────────────┐ │
│ │ Старт   (Пауза/Стоп при сессии)           │ │  ← StatusCard (как сейчас)
│ │ Следующий звук: 00:14  До авто-стоп: 02:31│ │
│ └───────────────────────────────────────────┘ │
│ ┌───────────────────────────────────────────┐ │
│ │ ⚠ Идут срабатывания. Правки — после Стоп. │ │  ← баннер блокировки (Р-5)
│ └───────────────────────────────────────────┘ │
│ ┌───────────────────────────────────────────┐ │
│ │ Авто-остановка                      [ ●]  │ │  ← AutoStopCard (без изменений)
│ └───────────────────────────────────────────┘ │
│                                               │
│ Будильники                                    │
│ ┌───────────────────────────────────────────┐ │
│ │ Духовка          [ВКЛ]        ×    ⌄      │ │  ← свёрнутая карточка
│ │ ▶ oven.mp3   ▁▁▁▁▁▁░░░░░░░  0:12 / 3:40   │ │     (плеер + прогресс)
│ │ ● Повтор · 5 мин · 80%                    │ │
│ └───────────────────────────────────────────┘ │
│ ┌───────────────────────────────────────────┐ │
│ │ Будильник 2      [ВЫКЛ]       ×    ⌄      │ │
│ │ Файл не выбран                            │ │
│ │ ● Время · 07:30                           │ │
│ └───────────────────────────────────────────┘ │
│                                               │
│                    [ + Добавить ]             │  ← ExtendedFAB
└───────────────────────────────────────────────┘
```

### 8.2 Свёрнутая карточка (по умолчанию)

| Строка | Содержимое |
|---|---|
| 1 | Имя (тап по строке = раскрыть/свернуть), `Switch` ВКЛ/ВЫКЛ, `×` удалить, `⌄/⌃` |
| 2 | Файл (имя или «Файл не выбран» — красным) + кнопка плеера `▶/⏸` + `LinearProgressIndicator` + `0:12 / 3:40` |
| 3 | Сводка чипами: режим · интервал/время/окно · громкость |
| 4 | Строка ошибки (только если режим требует поля и они невалидны) |

### 8.3 Раскрытая карточка (дополнение)

Имя (редактируемое поле) → Режим (4 `FilterChip`) → поля режима (как сейчас: `Начать с / Время / Первый в / Окно от-до / Сколько раз`) → Интервал `ЧЧ:ММ:СС` + пресеты → Громкость слайдер → работа с файлом: `Выбрать` / `Заменить` / `Встроенный бип` / `Удалить`.

### 8.4 Состояния

| Состояние | Поведение |
|---|---|
| Пустой список | Empty state: «Будильников нет. Нажмите + Добавить» + FAB активен |
| 100 будильников | FAB дизейблен, тап → подсказка «Достигнут лимит 100 будильников», счётчик `100/100` подсвечен |
| Сессия `RUNNING`/`PAUSED` | Все поля/переключатели/`+`/`×`/«Удалить все» дизейблены, баннер блокировки, кнопки сессии активны |
| Сессия `COMPLETED` | Правки разрешены (как `IDLE`), кнопка «Запустить заново» |
| Воспроизведение | Ровно одна дорожка; на остальных карточках кнопки `▶` |

### 8.5 Исправления UI-долга (в этом же редизайне)

1. **Один пикер файла на экран** вместо `rememberLauncherForActivityResult` внутри карточки: `pendingAlarmId: Int?` в состоянии экрана, launcher на уровне `App`. Критерий: выбор файла всегда попадает в ту карточку, с которой запущен.
2. **`items(alarms, key = { it.id })` без `indexOf`** — сейчас `config.channels.indexOf(channel)` даёт O(n²) и ломается при перестановках.
3. **Контролы ≥48dp**, `contentDescription` на `▶/⏸/×/⌄`, у `Switch` есть текстовая метка (сейчас «ВКЛ/ВЫКЛ» рядом — сохранить).
4. **Диалоги подтверждения** (обязательны по политике профиля `safety.require_confirmation.delete`): удаление одного — с именем; «Удалить все» — с количеством и destructive-акцентом. При активной сессии обе операции недоступны.
5. **Плеер** освобождается `DisposableEffect` при уходе с экрана.

### 8.6 Мокап раскрытой карточки

```
┌───────────────────────────────────────────────┐
│ [Духовка__________________]   [ВКЛ]  ×   ⌃    │
│ oven.mp3      ▶  ▁▁▁▁▁░░░░░░░   0:12 / 3:40   │
│ Режим:  [Повтор] [Время] [Интервал] [Рандом]  │
│ Первый в (HH:MM): 07:30                       │
│ Сколько раз: 3                                │
│ Интервал: 00:05:00                            │
│ [1м][3м][5м][10м][15м][30м]                   │
│ Громкость  ▁▁▁▁▁▁▁▁░░  80%                    │
│ [Выбрать] [Встроенный бип] [Удалить файл]     │
└───────────────────────────────────────────────┘
```

---

## 9. Аудио-слой

### 9.1 Разделение путей (Р-4)

| Путь | Движок | Почему |
|---|---|---|
| Срабатывания сессии | `Ringtone` (как сейчас, `AudioEngine.play`) | Единственный путь, который на целевом Huawei реально звонит (README §16); полноценный play/pause там не нужен — звук короткий/повторяемый |
| Плеер в карточке | `androidx.media3.exoplayer.ExoPlayer` | Даёт позицию, pause/resume, seek, громкость, аудиофокус — то, чего у Ringtone нет |

### 9.2 `audio/PreviewPlayer.kt` (новый)

```kotlin
object PreviewPlayer {
    // Один инстанс на процесс: 100 ExoPlayer'ов одновременно = OOM и конфликт аудиофокуса.
    fun play(context: Context, alarm: AlarmConfig)
    fun pause(); fun resume(); fun stop(); fun release()
    val state: StateFlow<PreviewState>   // alarmId, isPlaying, positionMs, durationMs
}
```

- `AudioAttributes(USAGE_MEDIA, CONTENT_TYPE_MUSIC)`, `handleAudioFocus = true`.
- Встроенный бип: `MediaItem.fromUri("android.resource://$pkg/${R.raw.beep}")` — media3 обслуживает resource-URI через `RawResourceDataSource`; **точный URI проверить в спайке** (если не сработает — временно писать beep во внутренний кэш и играть оттуда).
- Прогресс: опрос `currentPosition` в `LaunchedEffect` каждые 250 мс (просто и надёжно) + `DisposableEffect { release() }`.
- Совместимость: `minSdk 26` для media3 подходит; при срабатывании сессии preview останавливается.

### 9.3 Гварды в `AudioEngine`

- `MAX_CONCURRENT_RINGS = 10` (решение D-1): при одновременном срабатывании >10 будильников останавливаем самые старые, пишем `Log.w`. Мотивация: 100 будильников с `intervalMs = 1 c` создают 100 живых `Ringtone` — риск по памяти и поведению вендора. Значение — предмет стресс-теста на устройстве.
- `stopChannel(id)` вызывать при `deleteAlarm` / `removeFile` / `deleteAllAlarms`.
- Сохранить `releaseAll()` на СТОП/авто-стоп/`onDestroy` без изменений.
- Диагностика остаётся: `Log.i(TAG, "AudioEngine.play: …")` — по ней уже ловились баги; не удалять.

### 9.4 Зависимости и манифест

- `gradle/libs.versions.toml`: `media3 = "…"` + `androidx-media3-exoplayer`; `app/build.gradle.kts`: `implementation(libs.androidx.media3.exoplayer)`.
- Манифест: **без новых разрешений**. `INTERNET` не запрашиваем. FGS-тип `mediaPlayback` уже есть.

---

## 10. Этапы

### Этап 0 — Спайк аудио на устройстве (ГЕЙТ)

Цель: до переработки плеера убедиться, что ExoPlayer звучит там, где Ringtone звучит, а `MediaPlayer` молчал.

Задачи: минимальный экран/ветка в `MainActivity` — «играть выбранный mp3 через Ringtone» и «играть тот же mp3 через ExoPlayer», кнопки play/pause/seek, лог обеих дорожек.

Критерии приёмки: на Huawei P20 Pro (Android 10) ExoPlayer играет mp3 из SAF-URI, слышен, `pause/resume/seek` работают; громкость управляется; Ringtone-путь не деградировал. Артефакт: `adb logcat -s TimerSound:*` + короткая заметка в отчёте.

**Если ExoPlayer молчит** → ветка B: плеер на Ringtone (кнопка `Стоп` вместо паузы, без прогресса) + отдельный вопрос владельцу. **Этап 4 не начинается без пройденного Этапа 0.**

### Этап 1 — Модель, хранилище, миграция

- `AlarmConfig`, `Defaults.MAX_ALARMS/MAX_NAME_LENGTH/newAlarm/firstRunConfig`.
- Json-схема `alarms_json` + `schema_version` + `next_alarm_id`; `ensureMigrated()`.
- Механическое переименование `channels → alarms`, `ChannelConfig → AlarmConfig` (отдельный коммит).
- Точечная/батчевая запись, debounce.

Тесты (новые/переписанные): миграция legacy 5 → 5 будильников с сохранением полей; `channel_count = 0` → пустой список; битый JSON → дефолт; round-trip 100 будильников; `next_alarm_id` не переиспользует id; клампы.

DoD: `./gradlew test` зелёный; APK, установленный поверх старой версии, показывает старые 5 каналов как 5 будильников.

### Этап 2 — ViewModel

- Все сеттеры по id, `updateAlarm(id, …)`.
- `addAlarm/deleteAlarm/deleteAllAlarms/renameAlarm/setBuiltInBeep/removeFile`.
- Блокировка правок (Р-5), `canEdit`, debounce, release SAF-прав.

Тесты: правки отклоняются в `RUNNING`/`PAUSED`; `addAlarm` до 100 и no-op на 101-м; `deleteAllAlarms` очищает и релизит; `renameAlarm` trims/обрезает/фолбэк; id уникальны после серии add/delete.

### Этап 3 — UI

Свёрнутые/раскрытые карточки, FAB + счётчик, empty state, диалоги подтверждения, баннер блокировки, сводка чипами, один пикер на экран, `key = { it.id }`, доступность.

DoD: вручную — 100 карточек скроллятся без фризов; тап по карточке раскрывает только её; удаление одного и всех работают с подтверждением; при идущей сессии контролы неактивны.

### Этап 4 — Плеер (по результату Этапа 0)

`PreviewPlayer`, play/pause, прогресс, одна активная дорожка, стоп при старте сессии, release на выходе с экрана.

DoD: на устройстве звук воспроизводится, полоса прогресса движется, пауза/возобновление работают, запуск второй карточки останавливает первую.

### Этап 5 — Аудио-гварды

`MAX_CONCURRENT_RINGS`, `stopChannel` при удалении, диагностические логи, стресс-тест «100 будильников, интервал 1 с».

DoD: стресс-тест не приводит к отсутствию звука/ANR/OOM; в логе видно ограничение одновременных Ringtone.

### Этап 6 — Тесты, документация, проверка

- `README.md`: структура, лимит 100, плеер, миграция, обновлённый ручной план (см. §12).
- `TEST_COVERAGE_PERCENTAGE.md` (сейчас не отслеживается git) — обновить цифры или взять под версионный контроль.
- `./gradlew test` + `./gradlew assembleDebug` + прогон ручного плана на устройстве.

Коммиты по этапам в стиле истории репозитория (`feat(model)/feat(data)/feat(vm)/feat(ui)/feat(audio)/docs`).

---

## 11. Риски и митигации

| # | Риск | Митигация |
|---|---|---|
| R1 | media3 не звучит на Huawei (как когда-то `MediaPlayer`) | Гейт Этапа 0 + ветка B (Ringtone-плеер без прогресса); решение принимает владелец |
| R2 | 100 одновременных `Ringtone` | `MAX_CONCURRENT_RINGS = 10` (D-1) + стресс-тест на устройстве |
| R3 | Миграция теряет настройки | Одна транзакция записи+удаления, идемпотентность по `schema_version`, тесты на legacy-набор, проверка «APK поверх старой версии» |
| R4 | 100 карточек = тормоза | Keyed `items`, свёрнутый режим по умолчанию, никаких `indexOf`, `remember`/`derivedStateOf` |
| R5 | Запись JSON на каждое движение слайдера | Commit по `onValueChangeFinished` + debounce 300 мс |
| R6 | Лимит персистентных SAF-прав (сотни на процесс) | Не дублировать разрешения на один файл; обрабатывать `SecurityException` от `takePersistableUriPermission` и показывать «файл недоступен»; при удалении файла релизить права, только если URI не занят другим будильником |
| R7 | Удаление будильника, пока он звучит в сессии | Невозможно по Р-5 (правки заблокированы); preview останавливается через `stopChannel` |
| R8 | Разрастание диффа из-за переименования `channels → alarms` | Отдельный механический коммит, без поведенческих правок |
| R9 | Обратная совместимость формата при будущих полях | DTO + `schema_version` + игнор неизвестных полей (`ignoreUnknownKeys = true`) |

---

## 12. Ручной план проверки (дополнение к README §18)

1. Чистая установка → 1 будильник с встроенным бипом, ВКЛ, счётчик `1/100`.
2. **Обновление поверх старой версии**: 5 прежних каналов видны как 5 будильников с теми же файлами/интервалами/режимами/громкостями. Настройки не потеряны.
3. Добавить будильники до 100 → FAB дизейблен на 101-м, подсказка о лимите.
4. Переименовать будильник → имя сохраняется после перезапуска приложения.
5. Удалить один будильник (с подтверждением) → остальные сохраняются, id не переиспользуются.
6. «Удалить все» (с подтверждением) → пустой список + empty state; после перезапуска список пуст.
7. Плеер: `▶` играет файл карточки, прогресс движется, `⏸` пауза, `▶` продолжает, запуск второй карточки останавливает первую.
8. Старт сессии → плеер останавливается; карточки, `+`, `×`, «Удалить все» неактивны; баннер блокировки виден.
9. Стоп → правки снова доступны, изменения применяются к следующему старту.
10. Режимы: каждый из 4 режимов на коротком расписании срабатывает в нужный момент (регресс к уже проверенному набору).
11. Стресс: 100 будильников, интервал 1 с → звук не пропадает, нет ANR/OOM, в логе ограничение одновременных Ringtone.
12. Заблокированный экран: срабатывания идут, уведомление на месте (регресс).
13. По завершении серии: нет `ServiceRecord` в `dumpsys activity services com.timersound`, в шторке только уведомление «Завершено» (id=1002).
14. `./gradlew test` — зелёный; `./gradlew assembleDebug` — APK собирается.

---

## 13. Открытые решения (нужен ответ владельца до Этапа 5 / Этапа 2)

| № | Вопрос | Рекомендованный дефолт |
|---|---|---|
| D-1 | Сколько `Ringtone` разрешить звучать одновременно | **10**, самые старые останавливаются (лог + стресс-тест) |
| D-2 | Включать ли будильник автоматически после выбора файла | **Да**: сейчас после выбора файла канал остаётся ВЫКЛ и «молчит» — выглядит как баг |
| D-3 | Что происходит с последним удалением | Пустой список + empty state; конфигурация сохраняется пустой (никаких «дефолтов обратно») |

---

## 14. Чек-лист файлов

**Изменяются:** `model/TimerConfig.kt`, `data/PreferencesRepository.kt`, `TimerViewModel.kt`, `ui/App.kt`, `audio/AudioEngine.kt`, `service/TimerSoundService.kt` (переименование + чтение конфига), `timer/TimerSession.kt` (только переименование параметров), `app/build.gradle.kts`, `gradle/libs.versions.toml`, `README.md`.

**Создаются:** `audio/PreviewPlayer.kt`, `ui/AlarmCard.kt` (вынести карточку из `App.kt` — сейчас он 490 строк), `ui/ConfirmDialogs.kt`, `data/AlarmJson.kt` (DTO + кодек), `PLAN_UI_UX.md` (этот файл).

**Тесты:** переписать `data/PreferencesRepositoryTest.kt`, дополнить `model/TimerConfigTest.kt`, `timer/TimerSessionTest.kt` (фабрики), добавить тесты миграции и json-кодека, `TimerViewModelTest` (сейчас ViewModel не покрыт вообще).