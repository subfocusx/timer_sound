# Timer Sound — аудит покрытия и план тестирования на устройстве

Дата аудита: 2026-09-26
Ветка: `master`, HEAD = `da201ca` (Task 1: rename scene modes, unify AlarmCard summary on mode.label)
Аудит выполнен по **рабочему дереву** (есть незакоммиченные изменения A1–A5: `TickPolicy`,
`renewWakeLock`, thermal-listener, A4-баннер `wasInterrupted`, `AppLog`, fade-in,
`droppedRingsCount`; `git status` — 12 изменённых main-файлов).
Стек: Kotlin 2.4.20, AGP 9.4.0, Compose, `minSdk 26`, `targetSdk/compileSdk 37`.

Устройство для ручного прогона: **Huawei COL-L29 (P20 Pro), Android 10 / SDK 29, EMUI 12.0.0,
патч безопасности 2020-04, 1080×2280**. Приложение установлено (`com.timersound`,
versionCode=1, lastUpdate 2026-09-23 — **старше рабочего дерева**, перед прогоном обязателен rebuild + reinstall).

---

## Статус на 2026-09-26 (после выполнения согласованного плана)

| Пункт | Состояние | Доказательство |
|---|---|---|
| P0: починка 6 падающих тестов | ✅ выполнено | `gradlew testDebugUnitTest` → 109 тестов, 0 падений |
| Миграция v1→v2 на реальном DataStore (вместо копии логики) | ✅ выполнено | `PreferencesRepositoryMigrationTest` (13 тестов, Robolectric); `PreferencesRepositoryTestHelper` удалён |
| Instrumentation-инфраструктура (`testInstrumentationRunner`, `androidTest`) | ✅ выполнено | `assembleDebugAndroidTest` + `connectedDebugAndroidTest` собираются и запускаются |
| Compose UI-тесты | ✅ выполнено | `AppUiTest` — 11 тестов, 0 падений на Huawei COL-L29 (Android 10) |
| `testTag`/`semantics`/`liveRegion` в UI | ✅ выполнено | ~20 тегов в `App.kt`/`AlarmCard.kt`/`ConfirmDialogs.kt`, `liveRegion` на статус-бейдже, декоративный `Icon` вместо символа `⚠` |
| Мёртвый UI: snackbar-хост и «Удалить все» | ✅ выполнено | `SnackbarHost` подключён; кнопка «Удалить все» + диалог доступны |
| Дефект: активная кнопка «Старт» при пустом файле | ✅ исправлено | `canStart = !isLocked && canStartConfig`; покрыто `startDisabledAndHintShown*` |
| Audio focus / отключение наушников | ⏳ не делалось (нужно отдельное согласование) | код не тронут |
| Ручной чек-лист §5 | ⏳ ожидает прогона на устройстве | — |

Команды:

```bash
cd <путь-к-проекту>
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
sh gradlew testDebugUnitTest            # 109 unit-тестов (JVM + Robolectric)
sh gradlew connectedDebugAndroidTest    # 11 Compose UI-тестов на подключённом устройстве
```

---
## 0. Метод и исходные данные

| Проверка | Результат (факт) |
|---|---|
| `gradlew testDebugUnitTest` | было **101 тест, 6 падений**; сейчас **109 тестов, 0 падений** |
| `app/src/androidTest` | был **не существует**; добавлен `AppUiTest` (11 тестов), проходят на устройстве |
| `testInstrumentationRunner` | был **не задан**; выставлен `androidx.test.runner.AndroidJUnitRunner` + `testOptions.animationsDisabled` |
| `espresso-core`, `ui-test-junit4`, `ui-test-manifest` | были мёртвыми; `ui-test-junit4`/`ui-test-manifest` теперь используются, добавлены `androidx.test:runner`/`rules` |
| Стенд-хуки adb (проверены живыми) | `dumpsys deviceidle` (ACTIVE), `dumpsys thermalservice` (Status: 0), `dumpsys audio` (Audio Focus stack), `dumpsys batterystats --checkin`, `settings get global low_power`, `dumpsys activity services`, `run-as` → `files/datastore/timer_sound.preferences_pb` (есть) |

Запуск юнит-тестов (JAVA_HOME обязателен, java нет в PATH):

```bash
cd <путь-к-проекту>
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
sh gradlew testDebugUnitTest --console=plain
```

---

## 1. ГРУППА A — уже реализовано (не дублировать)

101 тест в `app/src/test` (JVM, JUnit4; `TimerViewModelTest` — Robolectric).

| Файл | Тестов | Что реально проверяет |
|---|---|---|
| `timer/TimerSessionTest.kt` | 27 | start/first-fire, срабатывание ровно на границе, catch-up пропущенных, кламп интервала до 1 с, независимость каналов, отсев disabled/без файла, pause/resume без «протухания», no-op паузы из IDLE, stop, markCompleted/reset, авто-стоп по дедлайну и по счётчику, `nextSoundDescription`, `nextFireForChannel`, 4 режима на уровне сессии, `maxTotalFiresPerSession`, `formatHms`, `describeWallMoment` |
| `timer/SceneSchedulerTest.kt` | 16 | wall→elapsed, локальная полночь, `nextClockElapsed` сегодня/завтра, floor интервала, `fireTimesFor` по режимам, детерминированный/различный RANDOM, фолбэки на завтра, исключения на невозможных параметрах |
| `timer/AllModesBehaviorTest.kt` | 8 | Сквозное поведение каждого из 4 режимов (сессия + scheduler) |
| `ui/AlarmCardFormattingTest.kt` | 15 | Чистые функции ввода/форматирования: `parseHm`, `parseHms`, маски, `restartIfFull`, `pastTimeWarning`, `nowMinutesOfDay` |
| `TimerViewModelTest.kt` | 12 | Robolectric: id-уникальность, лимит 100, rename trim/clamp, лок правок при RUNNING/PAUSED, `canEdit`, `removeFile` |
| `data/PreferencesRepositoryTest.kt` | 9 | JSON round-trip (1 и 100), ignoreUnknownKeys, legacy-миграция (через **копию** логики), дефолты |
| `model/TimerConfigTest.kt` | 7 | `scheduleValid`, invalid/missing/disabled, `overlappingAlarms` |
| `audio/AudioEngineDroppedTest.kt` | 4 | Только чистая математика `droppedFor` (лимит 10) |
| `timer/TickPolicyTest.kt` | 3 | Пороги адаптивного тика 250/1000/5000 мс |

Покрытие (заявлено в README, не перемерялось): 18,1 % строк / 18,7 % ветвей;
ядро `TimerSession`/`SceneScheduler`/`TimerConfig` — 88–100 %.

### Категории

| Категория | Автотесты |
|---|---|
| UI / E2E | ❌ нет (нет ни одного `testTag`; `androidTest` пуст) |
| Интеграционные | ⚠️ только VM↔DataStore и Session↔Scheduler |
| Жизненный цикл | ❌ нет |
| Прерывания/уведомления | ❌ нет + **нет кода** под звонки/наушники (audio focus) |
| Сеть/офлайн | ✅ N/A (нет `INTERNET`; media3 тянет `ACCESS_NETWORK_STATE`, сеть не используется) |
| Производительность | ⚠️ только `TickPolicy` |
| Совместимость | ❌ один `@Config(sdk=[34])`, ветки Q/S/33+ не покрыты |
| Разрешения | ❌ нет |
| Батарея/фон | ❌ нет |
| Установка/обновление | ❌ `ensureMigrated()` **не исполняется ни одним тестом** |
| Accessibility | ❌ нет |
| Регрессионные | ⚠️ юнит есть, но сьют красный |

---

## 2. ГРУППА B — недостающие автоматизированные тесты

### P0 — ✅ ВЫПОЛНЕНО (6 падений устранены, 4 из них — баг изоляции тестов)

Воспроизведено и локализовано. 4 из 6 падений — **баг изоляции тестов**, 2 — ошибки в тестах.

| # | Падение | Корневая причина (проверено прогоном в изоляции) | Правка |
|---|---|---|---|
| 1 | `TimerViewModelTest.addAlarmRespectsMaxAlarmsLimit` | Глобальный `object TimerStateHolder` остаётся `RUNNING` от теста `editsRejectedDuringRunningSession`; нет сброса → все `addAlarm()` отклоняются. **В изоляции проходит** | `TimerStateHolder.reset()` в `@Before`/`@After` |
| 2 | `TimerViewModelTest.removeFileMakesUriEmpty` | То же (в изоляции проходит) | То же |
| 3 | `TimerViewModelTest.editsAllowedAfterSessionCompleted` | То же (`IndexOutOfBounds: Index 1, Size 1`) | То же |
| 4 | `TimerViewModelTest.renameAlarm_trimsAndClamps` | Ошибка ожидания: вход после trim = 37 симв., тест ждёт `MAX_NAME_LENGTH`=40. Провал **и в изоляции** | Вход >40 симв. либо проверка фактической длины |
| 5 | `PreferencesRepositoryTest.jsonIgnoresUnknownKeys` | В JSON есть `"volumePercent":80`, тест ждёт 0 («defaults from encodeDefaults»). Провал **и в изоляции** | Убрать поле из JSON либо ожидать 80; игнор неизвестного ключа — отдельным ассертом |
| 6 | `PreferencesRepositoryTest.migrationLegacyFiveToFiveAlarms` | `ch2_volume` пишется через `longPreferencesKey`, читается через `intPreferencesKey` → `ClassCastException: Long→Integer`. Провал **и в изоляции** | `intPreferencesKey` в setup-е теста |

**Обязательно до доверия к «покрытию миграции»:** `PreferencesRepositoryTestHelper` — это **копия**
продакшн-логики `readLegacyAlarm`. Тесты миграции проверяют сами себя; `ensureMigrated()`
(удаление legacy-ключей, `next_alarm_id`, идемпотентность, `schema_version`) не исполняется вообще.
Нужен реальный тест на `PreferencesRepository` под Robolectric (v1→v2; ключи удалены в той же
транзакции; `next_alarm_id = max+1`; повторный вызов — no-op; битый `alarms_json` → `firstRunConfig`;
`corruptionHandler` → пустые префы) + удаление дублирующего хелпера.

### P1 — ✅ частично выполнено (пункты 2, 7, 8), остальное — далее

7. ✅ **Compose UI** — `AppUiTest`, 11 тестов: пустой стейт + добавление; дизейбл добавления при 100;
   блокировка правок при RUNNING (баннер, отсутствие удаления/раскрытия); дефолты первого запуска;
   «Старт» выключен при пустом файле/невалидном расписании + текст причины; завершённая сессия
   (Завершено/Заново/Сброс); «Удалить все»; удаление одного с подтверждением и отменой;
   предупреждение о наложении; раскрытие карточки.
8. ✅ **Регресс на дефекты UI**: недостижимость «Удалить все» — покрыта `deleteAllFlowEmptiesTheList`;
   активная кнопка «Старт» без файла — покрыта `startDisabledAndHintShownWhenAlarmHasNoFile`;
   snackbar-хост подключён (путь срабатывания через UI по-прежнему недостижим — сообщение защитное).
9. **Сервис под Robolectric** (`Robolectric.buildService`): `onStartCommand` для 6 actions →
   переходы состояний; `START_NOT_STICKY`; `stopForeground`; acquire/release wakelock;
   ID 1001/1002/1003.
10. **Exact-alarm**: `canScheduleExactAlarms()==false` и `SecurityException` → `usingExactAlarm=false`,
    сессия не падает; при разрешении — `setAlarmClock` (ShadowAlarmManager).
11. **A4**: `setSessionActive(true)` → новый VM → `wasInterrupted==true`; `dismissInterrupted()`
    гасит флаг и преф.
12. **`AudioEngine` под ShadowRingtone**: `play`, повтор канала, `stopChannel`, `releaseAll`,
    путь >`MAX_CONCURRENT_RINGS`, сброс `droppedRingsCount`, изоляция `preview`.
13. **`PreviewPlayer`**: одна активная дорожка, play→pause→resume→stop, `release()`.
14. **Мульти-SDK Robolectric**: `@Config(sdk=[26,29,31,33,34])` для `canScheduleExactAlarm`,
    `NotificationPermissionRequest`, `showWhenLocked`.

### P2 — instrumentation/device-автотесты

15. UI Automator / Espresso-Intents: SAF-пикер, промпт POST_NOTIFICATIONS (13+), отзыв exact-alarm.
16. Screenshot/screenshot-тесты (Roborazzi) для визуального регресса — опционально.

---

## 3. Требует улучшения (структурно)

- `TimerStateHolder` — мутабельный синглтон без reset-контракта → течи между тестами (P0 №1–3).
- Тесты миграции дублируют продакшн-код (ложная уверенность) — исправлено: `PreferencesRepositoryTestHelper` удалён, добавлен `PreferencesRepositoryMigrationTest` на реальном DataStore.
- Ни одного `testTag`/`semantics` в Compose → E2E-автоматизация невозможна без правок разметки.
- Весь текст захардкожен в Kotlin, `strings.xml` содержит только `app_name`
  (при `supportsRtl="true"` RTL не проверяется, локализация не тестируема).
- README ссылается на удалённый `TEST_COVERAGE_PERCENTAGE.md`; `PLAN_UI_UX.md` удалён в рабочем дереве.
- Мёртвые зависимости: `espresso-core`, `ui-test-junit4`, `ui-test-manifest`.
- `ensureMigrated()` удаляет legacy-ключи циклом `0 until 5` жёстко, тогда как читает
  `channel_count ?: 5` → при `channel_count > 5` ключи id≥5 остаются (минорный мусор).

---

## 4. Дефекты в коде (найдены при аудите, тестами не покрыты)

| Дефект | Доказательство |
|---|---|
| Диалог **«Удалить все будильники» недостижим**: `showDeleteAllDialog` (`App.kt:85`) никогда не выставляется в `true` | `grep showDeleteAllDialog` → только объявление и блок `if` |
| **Snackbar-сообщения не показываются**: `snackbarEvents` (`TimerViewModel.kt:63`) не собирается нигде в UI → «Остановите таймер…», «Достигнут лимит 100…» невидимы | `grep snackbarEvents app/src/main/java/com/timersound/ui/` → пусто |
| **Нет audio focus и реакции на отключение наушников**: ни `requestAudioFocus`, ни `ACTION_AUDIO_BECOMING_NOISY`, ни телефонии | `grep AudioFocus\|BECOMING_NOISY\|Telephony` по `main` → пусто |
| Риск ANR/краша: `startForegroundService` вызывается из `TimerViewModel.start()`, а `startForeground()` — только после `prefs.config.first()` в корутине `handleStart()`; при медленном DataStore на API 26+ возможен `did not then call Service.startForeground()` | `TimerViewModel.start()` → `ACTION_START` → `handleStart()` читает DataStore до `startAsForeground()` |
| `setTurnScreenOn(true)` + `keepScreenOn` во время сессии → экран не гаснет всю сессию | `MainActivity.kt:20-23`, `App.kt:97-99` |
| **Кнопка «Старт» активна при отсутствии файла и молча ничего не делает**, а поясняющая подсказка недостижима: `canStart = !isLocked`, тогда как подсказка рисуется при `!canStart && !locked` (несовместимые условия); `TimerViewModel.start()` в этом случае выходит раньше | `App.kt` (вызов `StatusCard`), `StatusCard` (ветка подсказки), `TimerViewModel.start()` |
| Сообщения snackbar терялись: `snackbarEvents` не собирался в UI. Пользовательского пути к срабатыванию почти нет (контролы дизейблятся), но события терялись молча | `grep snackbarEvents app/src/main/java/com/timersound/ui/` → пусто |

---

## 5. ГРУППА C — чек-лист ручной проверки на Huawei P20 Pro (Android 10)

### 5.0 Подготовка стенда

```bash
ADB="$ANDROID_HOME/platform-tools/adb.exe"
$ADB devices -l                                   # ожидаем: <device>  device
cd <путь-к-проекту>
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
sh gradlew assembleDebug
$ADB install -r app/build/outputs/apk/debug/app-debug.apk   # ОБЯЗАТЕЛЬНО: сборка на устройстве от 2026-09-23
$ADB shell dumpsys package com.timersound | grep -E "versionCode|lastUpdateTime"
$ADB logcat -c
$ADB logcat -v time -s TimerSound:* PreviewPlayer:* AndroidRuntime:E > /tmp/ts.log &
```

Базовые замеры:

```bash
$ADB shell dumpsys deviceidle get deep                # ACTIVE
$ADB shell dumpsys thermalservice | head -3           # Thermal Status: 0
$ADB shell settings get global low_power              # 0
$ADB shell dumpsys activity services com.timersound   # пусто
```

### 5.1 UI и E2E (smoke, 13 шагов)

| # | Действие | Ожидаемый результат |
|---|---|---|
| 1 | Чистый запуск (после uninstall) | **5 каналов**: «Будильник 1» — ВКЛ со встроенным бипом, «Будильник 2–5» — выключены и без файла (контракт `ensureMigrated` для пустых префов: `channel_count ?: 5`). Статус «Готов», FAB активен, «Будильники: 5/100». ✅ подтверждено на устройстве (`freshInstallDefaultsAreStartable`), контракт миграции — `migrationOfEmptyPrefsCreatesFiveLegacyDefaults` |
| 2 | «Прослушать» на будильнике 1 | Слышен бип; иконка → «Пауза»; прогресс-бар и `00:00:0X / 00:00:0Y` |
| 3 | Preview на будильнике 1, затем на 2 | Первый останавливается, играет только второй (один preview на процесс) |
| 4 | Добавить до 100 будильников | На 100-м FAB дизейблится, счётчик «Будильники: 100/100» |
| 5 | Свернуть приложение при активном preview | Звук preview прекращается (`onDispose`) |
| 6 | Выбрать MP3/WAV/OGG через пикер | Имя файла появилось; после перезапуска приложения имя сохранено (не голый URI) |
| 7 | Раскрыть карточку, сменить режим | «N раз» → поля «Первый в (HH:MM)» + «Сколько раз»; «Случайно» → окно от/до |
| 8 | Ввести время только цифрами: `930` | Поле показывает `9:30`; после потери фокуса — `09:30` |
| 9 | Старт при пустом файле у всех каналов | «Старт» неактивна, красная подсказка «Для старта укажите файл: …». ✅ покрыто автотестом `startDisabledAndHintShownWhenAlarmHasNoFile` |
| 10 | Старт → попытка править карточку | Баннер «Идут срабатывания. Правки — после Стоп.», контролы неактивны, FAB дизейбл |
| 11 | Проверить snackbar при лимите 100 (нажать «+», когда 100) | Snackbar-хост подключён, но UI-путь к срабатыванию остаётся недостижимым: при лимите FAB дизейблится, а `addAlarm()` вызывается только им. Ожидаемый результат: сообщения нет, счётчик не меняется (покрыто `alarmLimitPreventsAddingMore`) |
| 12 | Найти элемент «Удалить все» | ✅ теперь есть в строке счётчика (только когда правки разрешены и список непуст); диалог подтверждения работает — покрыто `deleteAllFlowEmptiesTheList` |
| 13 | 5 каналов с разными интервалами и громкостями → Старт | Срабатывания слышны, громкость различается, «Следующий звук: …» обновляется |

### 5.2 Жизненный цикл и перезапуск сервиса

| # | Действие | Ожидаемый результат |
|---|---|---|
| 1 | Старт → Home | Уведомление живёт, срабатывания идут; `dumpsys activity services com.timersound` → 1 ServiceRecord, foreground |
| 2 | Старт → свайп из Recents | Сессия продолжается; повторный запуск приложения не создаёт второй сервис (`singleTask`) |
| 3 | Старт → поворот в landscape во время RUNNING | Состояние и отсчёт сохраняются, поля ввода не теряются |
| 4 | Старт → `adb shell am force-stop com.timersound` → открыть приложение | **Баннер «Таймер был прерван системой»** (метка `session_active` осталась); «Понятно» → баннер исчезает, статус «Готов» |
| 5 | Повторно открыть приложение после п.4 | Баннер НЕ появляется (флаг сброшен) |
| 6 | Старт → Стоп → открыть приложение | «Готов», уведомление 1001 снято, `session_active=false`, баннера нет |
| 7 | Старт → дождаться авто-стопа → открыть приложение | «Завершено» + «Запустить заново» и «Сброс»; баннера «прервано» нет |
| 8 | Убить процесс при живом сервисе (`adb shell am kill com.timersound` в фоне) | Сервис не поднимается сам (`START_NOT_STICKY`), `dumpsys activity services` пусто |

### 5.3 Уведомления

| # | Действие | Ожидаемый результат |
|---|---|---|
| 1 | Старт | FGS-уведомление id 1001: `Timer Sound` / `Идет · До авто-остановки: HH:MM:SS · Следующий звук: …`; кнопки **Пауза**, **Стоп** |
| 2 | «Пауза» в шторке | Текст → «Пауза …», кнопка → «Продолжить», отсчёт стоит |
| 3 | «Продолжить» | «Идет», остаток авто-стопа сохранён (не сброшен) |
| 4 | «Стоп» | 1001 удалено, сервис остановлен, UI «Готов» |
| 5 | Довести конечную серию до конца | 1001 снимается, появляется 1002 «Timer Sound: завершено» (AutoCancel) |
| 6 | Повторить п.1–5 на **заблокированном экране** | Кнопки работают; экран включается, UI поверх локскрина |
| 7 | Текст уведомления во время RUNNING | `dumpsys notification --noredact \| grep -A15 com.timersound` → канал `timer_running`, importance low, `showBadge=false`, текст обновляется ~1 раз/с |
| 8 | Канал уведомлений | `timer_running` / «Работа таймера» / IMPORTANCE_LOW (без звука и вибрации) |
| 9 | 11+ каналов с одинаковым временем (см. 5.8 п.7) | В тексте «Часть сигналов пропущена (много звучит одновременно)» |
| 10 | POST_NOTIFICATIONS | **На Android 10 не применимо** → эмулятор API 33+ (§5.9) |

### 5.4 Прерывания (звонок / наушники / музыка)

| # | Действие | Ожидаемый результат |
|---|---|---|
| 1 | Во время срабатывания — **входящий звонок** | ⚠️ Ожидаемо по коду: звук таймера **продолжает играть** (нет `requestAudioFocus`, `USAGE_MEDIA`). `dumpsys audio` → «Audio Focus stack entries»: фокус у dialer, `Remote Audio Focus stack` пуст, но звук идёт. Дефект |
| 2 | Играет музыка (YouTube Music) → Старт таймера | Ожидаемо: оба звучат одновременно, музыка не приглушается. Дефект |
| 3 | Во время звука **выдернуть наушники** | Ожидаемо по коду: звук переходит в динамик и продолжает играть (нет `ACTION_AUDIO_BECOMING_NOISY`). Дефект |
| 4 | Heads-up уведомление (сообщение) во время сессии | Тайминг не сбивается: `tick alarmFired=true` в ожидаемые моменты |
| 5 | Системный будильник / другой таймер одновременно | Оба звучат; наши срабатывания не теряются |
| 6 | Изменение громкости медиа во время звука | Громкость сигнала меняется вместе с медиа-потоком (ожидаемо, `USAGE_MEDIA`) |
| 7 | Режим «Не беспокоить» включён | Зафиксировать фактическое поведение: `Ringtone` в DND может быть заглушён системой |

### 5.5 Фон, Doze, энергопотребление

| # | Действие | Ожидаемый результат |
|---|---|---|
| 1 | Старт → выключить экран, 10+ минут (интервал 1–2 мин) | Срабатывания по времени; в логе между ними `tick alarmFired=false` с шагом 5 с (адаптивный тик), а не 250 мс |
| 2 | `dumpsys alarm \| grep -i timersound` во время сессии / после паузы | Есть `setAlarmClock`; после паузы/стопа — записи нет, в логе `exactAlarmCancelled=true` |
| 3 | `settings put global low_power 1` → сессия 10 мин → `low_power 0` | Срабатывания приходят |
| 4 | `dumpsys deviceidle force-idle` → ждать срабатывания → `deviceidle get deep` = `IDLE` → `deviceidle unforce` | Срабатывание в Doze пришло вовремя; после unforce обычный тик восстановлен |
| 5 | Во время сессии: `dumpsys power \| grep -i "com.timersound:timer"` | Лок удерживается и продлевается короткими интервалами (не более ~2 мин «в один присест») |
| 6 | После Стоп: `dumpsys power \| grep -i wake` | Нашего wakelock **нет** |
| 7 | `dumpsys batterystats --reset` → 30 мин сессии → `dumpsys batterystats --checkin > bs.csv` | Battery Historian: нет непрерывного wakelock >2 мин; после Стоп нет активного сервиса/лока |
| 8 | EMUI: Settings → Battery → App launch → com.timersound = Manage manually, все 3 тумблера | 30-мин сессия с выключенным экраном не прерывается; при «auto» зафиксировать, через сколько минут EMUI убивает сессию |
| 9 | Во время RUNNING выключить экран вручную | ⚠️ `keepScreenOn`/`setTurnScreenOn`: зафиксировать, загорается ли экран при каждом срабатывании и как это влияет на расход |
| 10 | 1 час с интервалом 1 мин: `adb shell top -n 1 -b -p <pid>` | CPU% приложения мал (<2–3 %), нагрева нет; сверить `dumpsys thermalservice` до/после |
| 11 | `dumpsys thermalservice override-status 3` при активной сессии | Тик переключается на 5 с; при `CRITICAL (4)` приходит уведомление 1003 «Устройство перегревается» с «Пауза». Сброс: `override-status 0` |

### 5.6 Разрешения и точные будильники

| # | Действие | Ожидаемый результат |
|---|---|---|
| 1 | Android 10 (SDK<31): `canScheduleExactAlarm()` = true без разрешений | `setAlarmClock` планируется всегда при RUNNING |
| 2 | Пауза → `dumpsys alarm \| grep -i timersound` | Пусто; после «Продолжить» — снова запланирован |
| 3 | SAF: выбрать файл → перезапустить приложение | `takePersistableUriPermission` сработал: звук играет без повторного выбора |
| 4 | Удалить выбранный файл из хранилища → Старт | Сценарий молча не срабатывает, сессия продолжается, **краша нет** |
| 5 | Удалить 10 будильников с файлами → проверить освобождение прав | Нет накопления/ошибок; файл, занятый другим каналом, продолжает работать |
| 6 | 13+/31+ (эмулятор): `appops set com.timersound SCHEDULE_EXACT_ALARM deny` | `logcat`: `exact alarm denied` / `usingExactAlarm=false`; сессия продолжается на тик-цикле |
| 7 | 33+ (эмулятор): deny POST_NOTIFICATIONS | FGS-сессия работает, уведомления нет, UI не падает |

### 5.7 Установка и обновление

| # | Действие | Ожидаемый результат |
|---|---|---|
| 1 | `adb uninstall` → `adb install app-debug.apk` → запуск | 1 будильник с бипом, промптов нет |
| 2 | `adb install -r` (та же версия) → запуск | Конфиг сохранён полностью, `lastUpdateTime` обновился |
| 3 | **Миграция v1→v2**: установить сборку до миграции, задать 5 каналов (в т.ч. RANDOM с окном), `adb install -r` текущей | Все 5 будильников с сохранёнными полями; в `files/datastore/timer_sound.preferences_pb` есть `schema_version=2`; legacy-ключи `ch*_` отсутствуют |
| 4 | Повторный запуск после п.3 | Повторной миграции нет (идемпотентность), конфиг не изменился |
| 5 | `run-as com.timersound sh -c 'echo garbage > files/datastore/timer_sound.preferences_pb'` → запуск | Не падает, откат на `firstRunConfig`, в логе `AppLog.e` про битый файл / битый `alarms_json` |
| 6 | `adb reboot` → запуск | Автостарта нет (нет `BOOT_COMPLETED`), конфиг цел, статус «Готов» |
| 7 | Release APK поверх debug | Ожидаемо `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (разные ключи подписи) — зафиксировать |

### 5.8 Производительность, стабильность, утечки

| # | Действие | Ожидаемый результат |
|---|---|---|
| 1 | Soak 20–30 мин: 5 каналов, интервалы 10–30 с | Нет `AndroidRuntime:E` в logcat, нет ANR, нет «зомби»-сервиса |
| 2 | `dumpsys meminfo com.timersound` до старта / после 20 срабатываний / после Стоп | Память не растёт монотонно; после Стоп RSS близок к исходному |
| 3 | 50 срабатываний → Стоп → `dumpsys audio \| grep -i timer` | Активных треков приложения нет (`releaseAll()`) |
| 4 | После завершения серии: `dumpsys activity services com.timersound` | **ServiceRecord отсутствует** |
| 5 | Долгий REPEAT (интервал 5 мин, 30 мин) | Шаг тика 5 с между событиями; CPU не греется; wakelock продлевается |
| 6 | `dumpsys procstats --hours 1 com.timersound` | Нет аномальных перезапусков/падений |
| 7 | 11+ каналов с одинаковым временем старта (разные файлы) | Звучат максимум 10; в уведомлении предупреждение о пропущенных; UI не тормозит |
| 8 | Fade-in ВКЛ / ВЫКЛ | ВКЛ: нарастание ~2 с без щелчка; ВЫКЛ: мгновенный |
| 9 | Preview большого файла (10+ мин) → свёртывание → повторный запуск | Preview корректно останавливается и освобождается, играет с начала |
| 10 | 3 preview подряд на разных каналах | Всегда играет только один, наложения нет |
| 11 | `am start -n com.timersound/.MainActivity` 20 раз подряд | Нет накопления Activity/сервисов (`dumpsys activity activities \| grep -c MainActivity` == 1) |

### 5.9 Совместимость версий Android

На P20 Pro (SDK 29) **невозможно** проверить:

| Требуется | Почему | Чем закрыть |
|---|---|---|
| POST_NOTIFICATIONS (промпт, deny) | API 33+ | эмулятор API 33/34 |
| `SCHEDULE_EXACT_ALARM` deny + фолбэк | API 31+ | эмулятор API 34/35 (на 34+ разрешение по умолчанию **denied** для новых установок) |
| Обязательная `MediaSession` для FGS `mediaPlayback` | API 35+ | эмулятор API 35/36 |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` runtime-ограничения | API 34+ | эмулятор API 34 |
| `startForeground` без типа | API 26–28 | эмулятор API 26 (minSdk) |

```bash
SDK="$ANDROID_HOME"
"$SDK/cmdline-tools/latest/bin/sdkmanager.bat" "system-images;android-33;google_apis;x86_64" \
                                               "system-images;android-35;google_apis;x86_64"
"$SDK/cmdline-tools/latest/bin/avdmanager.bat" create avd -n ts33 -k "system-images;android-33;google_apis;x86_64"
"$SDK/emulator/emulator.exe" -avd ts33      # аналогично ts35
```

Дополнительно на каждом варианте: малый экран / планшет, landscape, тёмная тема
(`settings put secure ui_night_mode 2`), размер шрифта, RTL.

### 5.10 Accessibility

| # | Действие | Ожидаемый результат |
|---|---|---|
| 1 | Включить TalkBack → обойти экран | Озвучиваются: «Справка», FAB «Добавить», «Удалить», «Свернуть/Раскрыть», «Пауза/Воспроизвести», имя будильника, Switch, кнопки Старт/Пауза/Стоп/Сброс. ⚠️ Символ `⚠` в баннере блокировки озвучивается как символ — дефект |
| 2 | Смена статуса IDLE→RUNNING→COMPLETED под TalkBack | ⚠️ Нет announce/liveRegion — статус меняется молча (дефект) |
| 3 | `settings put system font_scale 2.0` | Текст не обрезается; ⚠️ риск обрезки в статус-бейдже (`Surface` 48dp с текстом «Завершено», `App.kt:246-259`). Вернуть `1.0` |
| 4 | `settings put global debug.force_rtl 1` | Вёрстка не ломается (строки ru → RTL фактически N/A). Сброс `0` |
| 5 | Тач-таргеты | `IconButton` — 48dp (ок); ⚠️ `FilterChip` с `onClick={}` и `selected=true` (режим/сводка) объявляется интерактивным, но не нажимается — дефект |
| 6 | Контраст статус-бейджа (primary/tertiary/error на `surface`) | ≥ 4.5:1 для `labelMedium` |
| 7 | `sh gradlew lintDebug` | Отчёт по `contentDescription`, `ClickableViewAccessibility`, hardcoded text |
| 8 | Порядок фокуса (D-pad/клавиатура) | Фокус идёт логично: статус → FAB → авто-стоп → карточки |
| 9 | Landscape + `font_scale 2.0` | Кнопки/подсказки не уезжают за экран |

---

## 6. Требует согласования (правки кода/конфигов)

1. **P0-правки 6 тестов** — только `app/src/test/**`, код приложения не затрагивается:
   4 сброса `TimerStateHolder`, 2 исправленных ассерта, 1 тип ключа. Без этого прогон красный.
2. ✅ Реальный тест миграции на `PreferencesRepository.ensureMigrated()` (Robolectric, 13 тестов) +
   удаление дублирующего хелпера.
3. `testInstrumentationRunner` + `androidTest` source set в `app/build.gradle.kts`
   (иначе UI/E2E-автотесты невозможны).
4. `testTag`/`semantics` в `App.kt`/`AlarmCard.kt` + `liveRegion` для статуса.
5. Починка мёртвого UI: snackbar-хост, триггер «Удалить все».
6. Audio focus + `ACTION_AUDIO_BECOMING_NOISY` (иначе категория «прерывания» дефектна by design).

**Порядок работ:** (1) P0-правки → зелёный прогон → (3) runner + (2) тест миграции →
(4)/(5) → прогон чек-листа §5.1–5.10 на P20 Pro с фиксацией фактических результатов и дефектов.

---

## 8. Результаты ручного прогона на устройстве (26.09.2026)

Стенд: Huawei COL-L29 (P20 Pro), Android 10 / SDK 29, EMUI 12.0.0, debug-сборка рабочего дерева,
USB-подключение + `adb` (управление через `input`/`uiautomator`, наблюдение через `dumpsys`/`logcat`).
Аудио прослушать нельзя — воспроизведение подтверждалось по аудио-стеку.

Легенда: ✅ пройдено · ⚠️ пройдено с замечанием · ⛔ не выполнено (указана причина).
Дефекты 1, 3 и 4 исправлены по итогам прогона (см. ниже).

| Раздел | Проверка | Результат | Доказательство |
|---|---|---|---|
| 5.1 | Чистый запуск | ✅ | «Будильники: 5/100», Будильник 1 = «Встроенный сигнал (бип)» включён, остальные пустые/выкл, статус «Готов», FAB активен, есть «Удалить все» |
| 5.1 | Изменение интервала через маску | ✅ | Поле `00:05:00` → тап → ввод `10` → `offsetsMs=[10000]` в `TimerSession.start` |
| 5.1 | Preview сигнала | ⚠️ | Каждое нажатие создаёт новый `AudioTrack` приложения с `usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC` (`dumpsys audio`, журнал событий); иконка возвращается к «Воспроизвести» мгновенно — бип ≈0,22 с, визуально не поймать |
| 5.1 | Preview на канале без файла | ✅ | Нажатие корректно ничего не делает (ранний выход `playerToggle`) |
| 5.1 | Блокировка правок при RUNNING | ✅ | Баннер «Идут срабатывания. Правки — после Стоп.», подсказка «Сессия идёт…», у карточек нет «Удалить», нет «Удалить все», раскрытие не даёт контролов |
| 5.1 | Добавление будильника | ✅ | «Будильники: 5/100» → 6/100 (случайное нажатие FAB), значение пережило перезапуск |
| 5.2 | Сворачивание (Home) | ✅ | Сервис остаётся `isForeground=true`, тик-цикл продолжается |
| 5.2 | Экран выключен | ✅ | Срабатывания каждые 10 с при погашенном экране (лог `alarmFired=true`) |
| 5.2 | Убийство процесса (`am force-stop`) | ✅ | Сервис и уведомление сняты; после запуска баннер «Таймер был прерван системой (не штатная остановка).» + «Понятно»; повторный запуск — без баннера |
| 5.2 | Свайп из Recents | ⛔ | Устройство ушло на локскрин (нужен владелец). По контракту платформы FGS без `stopWithTask` не завершается при удалении задачи |
| 5.3 | Уведомление 1001 | ✅ | «Идет · До авто-остановки: 00:00:13 · Следующий звук: Будильник 1: через 3 с», кнопки Пауза/Стоп, канал `timer_running`, importance LOW, `showBadge=false` |
| 5.3 | Пауза из шторки | ✅ | `RUNNING -> PAUSED (pause)`, `exactAlarmCancelled=true`, wakelock освобождён, тик-цикл остановлен |
| 5.3 | Продолжить из шторки | ✅ | `PAUSED -> RUNNING (resume)`, остаток сохранён точно: после 56 с паузы `delayMs=2064` и срабатывание по расписанию |
| 5.3 | Стоп из UI | ✅ | `dumpsys activity services` → `(nothing)`, wakelock/алярм/уведомление сняты, UI «Готов» |
| 5.3 | Авто-остановка 00:00:20 | ✅ | `RUNNING -> COMPLETED (markCompleted)` через **20,008 с**, уведомление 1002 «Серия срабатываний завершена, все каналы остановлены.», 1001 снято, сервис удалён, UI «Завершено» + «Запустить заново»/«Сброс» |
| 5.3 | Локскрин при активной сессии | ✅ | UI показывается поверх ключного экрана (`setShowWhenLocked`/`setTurnScreenOn`), статус «Идёт», отсчёт идёт |
| 5.5 | Wakelock | ✅ | `PARTIAL_WAKE_LOCK 'com.timersound:timer'` удерживается и продлевается; после паузы/стопа — отсутствует |
| 5.5 | Адаптивный тик | ✅ | 250 мс рядом с событием, 1000 мс при 10 с до события (TickPolicy подтверждён на устройстве) |
| 5.5 | Doze deep-idle | ⛔ | Устройство на зарядке: `force-idle` → «Unable to go deep idle; stopped at INACTIVE». Косвенно: наш `setAlarmClock` виден как «Next wake from idle» в `dumpsys alarm` |
| 5.6 | Точные будильники (Android 10) | ✅ | `alarmScheduled=true` без разрешений; пауза/стоп → `exactAlarmCancelled=true`, активных `Alarm{}` нет |
| 5.6 | POST_NOTIFICATIONS / SCHEDULE_EXACT_ALARM deny | ⛔ | Требуется эмулятор API 33+/31+ |
| 5.7 | Персистентность настроек | ✅ | Интервал 10 с, авто-стоп 20 с и добавленный будильник пережили `force-stop`, перезапуск и повторные `install -r` |
| 5.7 | Битый файл настроек | ✅ | Файл затёрт мусором (1328 → 21 байт) → приложение не упало, откат на дефолты («Будильники: 5/100»), в логе `E/TimerSound: PreferencesRepository: битый файл настроек, откат на пустые: CorruptionException` |
| 5.7 | Миграция v1→v2 на устройстве / reboot | ⛔ | Нужна старая сборка; миграция покрыта `PreferencesRepositoryMigrationTest` (Robolectric). Reboot не выполнялся |
| 5.8 | Утечки ресурсов | ✅ | В `dumpsys audio` ровно один `MediaPlayer` приложения (Ringtone не накапливается); после завершения `dumpsys activity services` пуст, активных треков нет |
| 5.8 | Нагрузка 5 мин, интервал 10 с | ✅ | ~30 срабатываний без ошибок/ANR в логе; шаг тика и алярмы корректны |
| 5.8 | Лимит >10 одновременных звуков | ⛔ | Не проверялось (нужен сценарий 11+ каналов) |
| 5.9 | Совместимость 26/33/34/35 | ⛔ | Требуется эмулятор |
| 5.10 | font_scale 2.0 | ✅ | Вёрстка держится: счётчик, «Удалить все», «Старт», карточки на экране; статус-бейдж 102×102 px внутри круга 144×144 — без обрезки |
| 5.10 | TalkBack | ⛔ | Пакет `com.google.android.marvin.talkback` установлен, но не включался, чтобы не мешать автоматизации. `liveRegion` на статус-бейдже добавлен в код |
| 5.4 | Звонок / музыка / наушники | ⛔ | Нужны реальные действия владельца устройства; в коде по-прежнему нет audio focus и `ACTION_AUDIO_BECOMING_NOISY` |

### Дополнительно пройдено в устройством (вторая серия)

| Раздел | Проверка | Результат | Доказательство |
|---|---|---|---|
| 5.7 | **Reboot** устройства | ✅ | `uptime` сбросился (217 с), `boot_completed=1`; после загрузки: сервиса нет (**автостарта нет** — нет `BOOT_COMPLETED`), активных уведомлений нет, файл настроек цел → UI «Готов», «Будильники: 5/100», канал 1 с бипом |
| 5.2 | **Свайп задачи из Recents** | ✅ | После удаления карточки из списка недавних сервис остаётся `isForeground=true`, тики продолжаются, уведомление живо (FGS без `stopWithTask` не завершается платформой) |
| 5.1 | Preview (уточнение) | ✅ | Каждое нажатие «Воспроизвести» создаёт новый `AudioTrack` приложения (`usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC`); на карточке без файла нажатие — корректный no-op |

### Дефекты, найденные в прогоне, и их статус


1. 🔴 **Текст уведомления в паузе показывает «через 0 с».** Наблюдено: `Пауза · Следующий звук: Будильник 1: через 0 с`.
   Причина: `TimerSession.nextSoundDescription()` считает от текущего момента, а в паузе `nextFireElapsedMs` — уже прошедшая абсолютная метка;
   оставшееся время хранится в `remainingOnPauseMs`.
   **Исправлено**: `nextSoundDescription()` и `countdownToAutoStopMs()` в паузе берут сохранённые остатки (тот же дефект был и у отсчёта авто-остановки — в паузе он показывал `00:00:00`); тесты `nextSoundDescriptionInPauseShowsRemainingNotZero`, `countdownToAutoStopInPauseKeepsRemaining`.
2. 🟡 **Подпись кнопки в шторке не обновляется после паузы** (устройство/прошивка): в записи уведомления `actions[0]="Продолжить"`, но в шторке кнопка отрисована как «ПАУЗА»; нажатие при этом корректно возобновляет сессию.
   Требует проверки на другом устройстве/Android 13+ — возможно, артефакт SystemUI Huawei.
3. 🟡 **Включённый тумблер «Авто-остановка» с нулевым значением не останавливает сессию.** Прогон: тумблер ON, поле `00:00:00` → сессия стартовала, в уведомлении нет «До авто-остановки», таймер бесконечный.
   Причина: `AutoStopCard` при включении меняет только локальный `limited` и не задаёт ненулевое значение `autoStopMs`.
   **Исправлено**: при включённом тумблере и нулевом значении показывается предупреждение «Укажите время — без него авто-остановка не сработает.»
   (и аналогично у лимита срабатываний). Осознанно выбрано предупреждение, а не подстановка дефолта: иначе пользователь получал бы неожиданную авто-остановку.
   Теги `autostop_switch`/`autostop_warning`/`maxfires_switch`/`maxfires_warning`, UI-тесты `autostopSwitchWithoutValueWarns`, `autostopWarningHiddenWhenValueIsSet`, `maxFiresSwitchWithoutValueWarns`.
4. ⚪ **Вводящий в заблуждение лог**. **Исправлено**: `onAlarmTick()` пишет `alarmWake=true` (пробуждение алярмом ≠ срабатывание), а строка тика без срабатывания больше не содержит `alarmFired=false`; фактическое срабатывание по-прежнему помечается `tick alarmFired=true` (маркер из §5.8/README сохранён).

### Soak-тест (25 минут, второй сеанс)

Условия: 1 канал (встроенный бип), `REPEAT`, интервал **10 с**, **fade-in включён**,
приложение свёрнуто, экран включён (`svc power stayon true`), сессия с 13:54:31 до 14:19:42.

| Метрика | Значение |
|---|---|
| Длительность / срабатываний | 25 мин, ~150 срабатываний (кадент ровно 10 с на всём протяжении, включая последнюю минуту) |
| Память, TOTAL PSS | старт 68 906 КБ → через 2 мин 69 469 КБ → через 25 мин **67 825 КБ** (роста нет) |
| Java Heap / Native Heap | 12 060 / 10 412 КБ → 12 020 / 10 092 КБ (плоско) |
| Утечки аудио | за ~150 срабатываний в `dumpsys audio` у приложения зарегистрирован **ровно один** `MediaPlayer` (создание/освобождение на каждое срабатывание — по журналу событий каждые 10 с) |
| Wakelock | `PARTIAL_WAKE_LOCK com.timersound:timer` удерживался непрерывно `ACQ=-25m11s` (продления работают, лок не «протух») |
| Стабильность | крэшей нет, событий `am_anr` — **0**, сервис `isForeground=true` всё время, уведомление 1001 активно |
| Завершение | Стоп → `dumpsys activity services` → `(nothing)`, wakelock снят |

Вывод: утечек `Ringtone`/памяти за длинную сессию с частыми срабатываниями и fade-in не обнаружено;
адаптивный тик, продление wakelock и перепланирование точного алярма работают в фоне десятки минут.

### Итог прогона

- Пройдено ✅: 22 проверки. С замечанием ⚠️: 2. Не выполнено ⛔: 6 (все — из-за требований к внешним действиям или другой версии Android).
- Найдено 4 дефекта, **3 из них исправлены** (дефект 2 требует другого устройства/Android 13+).
- После правок: `gradlew testDebugUnitTest` → **111 тестов, 0 падений**; `connectedDebugAndroidTest` → **14 Compose UI-тестов, 0 падений** на Huawei COL-L29.

---

## 9. Прогон на эмуляторе Android 15 (API 35)

Стенд: AVD `ts35` (профиль pixel_5), образ `system-images;android-35;default;x86_64` (AOSP),
эмулятор 37.1.11, ускорение WHPX. Запуск: `emulator -avd ts35 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect`.
Прогон на конкретном устройстве: `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`.

### Закрыто на API 35

| Пункт | Результат | Доказательство |
|---|---|---|
| Compose UI-набор (14 тестов) на Android 15 | ✅ | `connectedDebugAndroidTest` → BUILD SUCCESSFUL, 14/14 |
| POST_NOTIFICATIONS: запрос на первом запуске | ✅ | Диалог «Allow Timer Sound to send you notifications?» (ALLOW / DON'T ALLOW), `GrantPermissionsActivity` поверх приложения |
| POST_NOTIFICATIONS: отказ | ✅ | После «DON'T ALLOW» `POST_NOTIFICATIONS: granted=false`; сессия стартует (`IDLE -> RUNNING`), сервис `isForeground=true`, **уведомления нет, исключений/крэшей нет** |
| FGS `mediaPlayback` на Android 15 | ✅ | `isForeground=true foregroundId=1001 types=0x00000002` (mediaPlayback); `MediaSession: TimerSound com.timersound/TimerSound/7` активна |
| Точные будильники: **найден и исправлен дефект** | ✅ | см. дефект 5 ниже; после фикса `alarmScheduled=true ... delayMs=299806`, в `dumpsys alarm` — `RTC_WAKEUP ... com.timersound` и он же «Next wake from idle» |
| Дефект №2 (подпись кнопки в шторке) | ✅ закрыт как **артефакт EMUI** | На AOSP/Android 15 шторка обновляется корректно: текст `Пауза · Следующий звук: Будильник 1: через 4 мин`, кнопка `ПРОДОЛЖИТЬ`. Воспроизводится только на Huawei → не баг приложения |
| Фикс паузы (дефект 1) на устройстве | ✅ | В паузе API 35 уведомление показывает «через 4 мин» (остаток), а не «через 0 с» |

### Дефект 5 (новый, исправлен): точный будильник не планировался на Android 13+

Диагностика изнутри приложения на API 35 (`targetSdk=37`):

```
SCHEDULE_EXACT_ALARM=DENIED   USE_EXACT_ALARM=GRANTED
canScheduleExactAlarms=true   opScheduleExactAlarm=ALLOWED
```

`TimerSoundService.canScheduleExactAlarm()` требовал именно `SCHEDULE_EXACT_ALARM` и возвращал `false`,
хотя приложение объявляет `USE_EXACT_ALARM` (обычное разрешение, выдаётся при установке) и
`AlarmManager.canScheduleExactAlarms()` отвечает `true`. Итог: на API 33+ `setAlarmClock` **не вызывался вообще**
(тихий ранний выход без лога), и таймер опирался только на тик-цикл foreground-сервиса — то есть ровно
в Doze/фоне точность терялась.

**Исправлено**: гейт теперь доверяет `canScheduleExactAlarms()` (учитывает и appop, и `USE_EXACT_ALARM`),
отдельная проверка `SCHEDULE_EXACT_ALARM` и ставшие ненужными импорты убраны.
Проверено на API 35: алярм планируется и виден как «next wake from idle».

### Инфраструктурные заметки (для повторных прогонов)

1. **Перед instrumentation на API 33+ тест-стенд выдаёт POST_NOTIFICATIONS заранее** (`pm grant` в `@Before`).
   Без этого `App()` на первой же композиции показывает системный диалог, он перекрывает тестовую
   Activity (`RESUMED → PAUSED → DESTROYED`) и все Compose-тесты падают с «No compose hierarchies found».
2. **Не запускать instrumentation на телефоне при работающем эмуляторе**: при параллельной нагрузке
   часть Compose-тестов на телефоне падает тем же «No compose hierarchies» (7 из 14 в первом прогоне);
   после выключения эмулятора — 14/14.
3. Установка образа: `sdkmanager` из свежих cmdline-tools падает (код 3221226505) уже **после** успешной
   распаковки — образ остаётся рабочим; аргумент с `;` нужно передавать через `shell=True` (cmd режет `;`).

### Дефект 6 (новый, не исправлен): повторный запрос разрешения на каждом запуске

Пока пользователь не откажет дважды, `App()` показывает диалог POST_NOTIFICATIONS при **каждом** запуске
(`NotificationPermissionRequest` не хранит «уже спрашивали» и не смотрит `shouldShowRequestPermissionRationale`).
Наблюдалось: отказ → следующий запуск → диалог снова. Платформа заглушает его только после двух отказов.

---

## 10. Закрытие прогона: решения и остаточные ограничения

### Audio focus сознательно не делаем

Единственная незакрытая категория — «прерывания» (звонок / отключение наушников / чужая музыка).
**Решение: код не трогаем.** Обоснование:

- сценария с проводными наушниками в реальном использовании нет, а именно на него в первую очередь
  рассчитан `ACTION_AUDIO_BECOMING_NOISY`;
- при отключении Bluetooth, по наблюдениям владельца, звук корректно уходит на динамик телефона
  (в этом прогоне отдельно не проверялось — записано как наблюдение, не как измеренный факт);
- `requestAudioFocus` менял бы поведение основного сценария (таймер обязан звучать поверх остального),
  а выигрыш — только в сценарии «звонит телефон», который не заявлен в требованиях.

Остаточный риск: при входящем звонке или одновременной музыке сигнал таймера будет звучать вместе с ними
(`USAGE_MEDIA`), без приглушения. Это зафиксированный, осознанно принятый риск, а не дефект.

### Что осталось непроверенным (и почему)

| Пункт | Причина |
|---|---|
| Doze deep-IDLE с доставкой срабатывания | На эмуляторе deep IDLE достигается мгновенно **без** нашего приложения, но не достигается, пока активна сессия (даже после истечения wakelock по термальному правилу) — инференция: устройство держит вне Doze живой foreground-сервис/его активность, что для таймера желательно. Вместо полного IDLE-сценария проверено: (а) до фикса дефекта 5 точного алярма не существовало вообще; (б) после фикса алярм планируется, платформа помечает его как «next wake from idle», и он доставил срабатывание с точностью **+14 мс** |
| Реальный входящий звонок, отключение наушников | Нужны физические действия владельца |
| TalkBack-проход | Не включали, чтобы не мешать автоматизации |
| Лимит >10 одновременных звуков, миграция v1→v2 на устройстве | Нужен сценарий 11+ каналов / старая сборка |
| API 26 и 33 отдельными образами | Ограничились API 35 (покрывает требования 33+/34+/35+) и физическим Android 10 |

---

## 11. Итог прогона (закрытие)

### Дефекты: найдено 6, исправлено 5

| № | Дефект | Статус |
|---|---|---|
| 1 | В паузе уведомление показывало «через 0 с»; счётчик авто-остановки — `00:00:00` | ✅ исправлено |
| 2 | Подпись кнопки в шторке не обновлялась после паузы | ✅ закрыто как артефакт EMUI (на AOSP/15 обновляется корректно) |
| 3 | Включённый тумблер «Авто-остановка»/«Лимит срабатываний» с нулевым значением ничего не ограничивал молча | ✅ исправлено (предупреждение, а не подстановка дефолта) |
| 4 | Лог `onAlarmTick` писал `alarmFired=true` при простом пробуждении сервиса | ✅ исправлено (`alarmWake=true`) |
| 5 | **На API 33+ точный будильник не планировался вообще** (гейт требовал `SCHEDULE_EXACT_ALARM`, игнорируя выданное `USE_EXACT_ALARM`) | ✅ исправлено |
| 6 | Диалог POST_NOTIFICATIONS показывался при каждом запуске, пока пользователь не откажет дважды | ✅ исправлено (флаг «уже спрашивали» в DataStore) |

### Финальные результаты

```
testDebugUnitTest            → 112 тестов, 0 падений
connectedDebugAndroidTest    → 14 тестов, 0 падений
  · COL-L29 (Android 10) — на холодной установке (после adb uninstall)
  · emulator-5554 / ts35 (Android 15) — в том числе после фикса дефекта 5
assembleDebug                 → app-debug.apk (22,5 МБ)
assembleRelease               → app-release-unsigned.apk (15,6 МБ)
```

### Флейк холодного старта (поправлено в тестах)

Первый прогон после свежей установки ронял **первые 3–7** тестов с «No compose hierarchies found»:
на холодном процессе тестовая Activity не успевала запуститься и вызвать `setContent`.
Причина подтверждена дважды: при параллельно работающем эмуляторе (7 из 14) и при `clean`-сборке (3 из 14).
Исправлено прогревом (`AppUiTest.warmUpAppProcess()`: один запуск `MainActivity` на класс);
проверено на холодной установке — 14/14.

### Состояние стендов

- Huawei COL-L29: приложение (итоговая debug-сборка) установлено, сессия не запущена, сервиса/wakelock/уведомлений нет,
  `stayon=false`, `font_scale=1.0`, `low_power=0`, тестовый APK удалён.
- AVD `ts35` (Android 15, AOSP) выключен, готов к повторному запуску:
  `emulator -avd ts35 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect`.
- На устройстве/эмуляторе сброшены: thermal override, `battery unplug`, `deviceidle` enable/force.

---

## 12. Правка вёрстки карточек (репорт с устройства, 26.09)

### Что было (замеры на COL-L29 до правки)

```
чип «Бесконечно, каждые …»   w=473 px
чип «Один раз»               w=121 px
чип «N раз, каждые …»        h=1122 px  ← сжат до 122 px, подпись перенеслась по буквам
чип «Случайно, N раз в окне» — вообще за пределами экрана
FAB «Добавить»               перекрывал нижний ряд чипов
```

Причина одна на оба симптома: четыре подписи режима не влезали в строку, `Row` отдавал
последним чипам остаток ширины, подпись переносилась по буквам и строка режимов вырастала
до 1122 px. Пользователь видит это как «невероятно огромный зазор» между именем будильника
и полями ввода времени (это и была раздутая строка чипов), а сами подписи — обрезанными.

### Что сделано

- подписи режимов сокращены до одного слова: **«Повтор» / «Один раз» / «N раз» / «Случайно»**
  (было «Бесконечно, каждые …», «N раз, каждые …», «Случайно, N раз в окне»);
- строка режимов — `Row` с `horizontalScroll`: подписи никогда не сжимаются.
  `FlowRow` пробовали — он ужимает гибкий `Text` вместо переноса (воспроизведено на устройстве),
  поэтому от него отказались;
- сводка свёрнутой карточки стала компактной (`alarmSummary(alarm)`): подпись режима больше не
  дублируется (она и так в соседнем чипе), стенные моменты — в формате ЧЧ:ММ, интервал — ЧЧ:ММ:СС;
  сводка переносится внутри чипа (до 2 строк) и занимает остаток строки;
- `contentPadding` списка снизу 96 dp: FAB «Добавить» больше не перекрывает содержимое карточки.

### Что стало (те же замеры)

```
чипы режима:        y260-311, x122-945 — все четыре, высота 51 px, ничего не сжато
первое поле ниже:   +116 px (штатный отступ подписи поля, а не пустота)
свёрнутая карточка: «N раз» (172 px) + «каждые 00:05:00 · 80%» (729 px) — без обрезки
низ списка:         контент заканчивается на y1959, FAB начинается на y2137 — перекрытия нет
```

Проверено во всех четырёх режимах на COL-L29 (REPEAT / ONCE_TIME / INTERVAL / RANDOM):
наборы полей у каждого режима разворачиваются последовательно, разрывов нет.

Регресс-тесты: `sceneModeLabelsAreShortAndComplete` (подписи ≤ 9 символов и без «…»),
`alarmSummaryDoesNotDuplicateModeLabel`, `alarmSummaryPerMode`.
