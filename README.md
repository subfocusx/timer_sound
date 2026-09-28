# Timer Sound

Локальный интервальный аудиотаймер для Android с **группами будильников**.
Kotlin + Jetpack Compose (Material 3) + Android Foreground Service.
Без сети, без бэкенда, без Room: вся конфигурация — локально (DataStore Preferences + JSON).

---

## Возможности

- **Группы будильников** (лимит 30): создание пустой или из пресета, переименование,
  копирование («… (копия)», новые id, общие SAF-URI не освобождаются),
  удаление с подтверждением, включение/выключение.
- **В группе до 100 будильников**, id стабильны и не переиспользуются. У каждого:
  - свой аудиофайл (MP3 / WAV / OGG через системный пикер, persistable-права, файлы не копируются);
  - свой интервал `ЧЧ:ММ:СС`, своя громкость 0–100 %, переключатель ВКЛ/ВЫКЛ;
  - режим сценария (Повтор / Один раз / N раз / Случайно) с режим-специфичными полями;
  - предпросмотр, замена и удаление файла; имя файла сохраняется (`DISPLAY_NAME`).
- **Первый запуск**: группа «Основная» с одним будильником — встроенным сигналом (`res/raw/beep.wav`).
- **Пресеты** (в коде, не в хранилище, все со встроенным бипом): Пустая, Подъём (Пн–Пт),
  Помодоро, Таблетки, Разминка каждый час, Случайные проверки, Медитация.
- **Несколько групп работают одновременно**: у каждой свой `TimerSession`,
  сервис держит `Map<groupId, TimerSession>`. Пауза/стоп/продолжить/перезапуск —
  на одну группу независимо; есть «Пауза всех» и «Стоп всех» (главный экран и уведомление).
- **Блокировка правок по группе**: редактируется всё, кроме группы в RUNNING/PAUSED
  (`isLocked(groupId)`).
- **Расписание по дням недели** (битовая маска ISO 1..7, 0 — только ручной запуск).
  Включённая группа с расписанием **автозапускается сама** в свой день и время
  даже при закрытом приложении (точный `AlarmManager.setAlarmClock` + `BroadcastReceiver`
  со стартом foreground-сервиса) и после перезагрузки телефона (`RECEIVE_BOOT_COMPLETED`).
  Время автозапуска = минимальный `startMinutes` среди играбельных будильников.
  Один общий точный алярм на всё (минимум из событий сессий и автозапусков).
- **Предупреждения о пересечениях** расписаний (`ScheduleConflicts`, чистая функция):
  SPAN_OVERLAP (интервалы, с учётом перехода через полночь) и NEAR_FIRE (срабатывания
  ближе 60 с). Только предупреждения: бейдж на карточке, раздел «Расписание»,
  диалог при конфликте — никогда не блокируют.
- **Общий таймер группы**: у запущенной — из `snapshot` сессии (до ближайшего,
  до конца/«∞», остатки; в паузе заморожен, отсчёт считает UI); у незапущенной
  армированной — следующий запуск по дням недели с датой («пн 5 окт в 16:06»).
- **Глобальный fade-in** (плавное нарастание ~2 с) — один на все группы.
- **Управление**: Старт / Пауза / Продолжить / Стоп / Перезапуск (атомарный Stop+Start
  с новым epoch) / Сброс, чёткая машина состояний на группу.
- **Фон**: foreground-сервис (`mediaPlayback`), агрегированное уведомление
  («N групп активно; ближайшее: …») с кнопками «Пауза всех»/«Стоп всех»,
  частичный wakelock, MediaSession (обязательна на Android 15+).
- **Тайминг — по реальному времени** (`SystemClock.elapsedRealtime()`).
- **Настройки сохраняются** (DataStore, схема v3). Обновление со старой версии:
  плоский список становится группой «Основная». Баннер «прервано системой» — по группам.
- **Параллельное воспроизведение**: у каждого звука ключ `(groupId, alarmId)`,
  общий лимит `MAX_CONCURRENT_RINGS=10`. Стоп одной группы не глушит другую.

## Режимы сценариев

| Режим | Название | Описание |
|---|---|---|
| REPEAT | Повтор | Звук через intervalMs без ограничения. Без времени старта первое срабатывание — через один интервал; с HH:MM — в ближайшее наступление. Без startMinutes расписание группы невозможно. |
| ONCE_TIME | Один раз | Один звук в HH:MM. Ручной старт — ближайшее наступление; автозапуск — по якорю планового времени (не уезжает на завтра из-за задержки алярма). |
| INTERVAL | N раз | Первый звук в HH:MM, далее каждый intervalMs, всего launchCount раз. |
| RANDOM | Случайно | launchCount различных моментов строго внутри окна [startMinutes, endMinutes) одного дня; фиксируются при старте. |

**Последнее срабатывание конечного режима доигрывает файл** (до `MAX_LAST_RING_MS` = 2 мин),
затем teardown с проверкой epoch (перезапуск во время доигрывания не сносится).
Авто-остановка по таймеру глушит всё немедленно.

---

## Машина состояний (на группу)

```
IDLE --Старт--> RUNNING --Пауза--> PAUSED --Продолжить--> RUNNING
RUNNING/PAUSED --Стоп--> IDLE
RUNNING --авто-остановка--> COMPLETED (всё остановлено сразу, уведомление)
RUNNING --последнее срабатывание--> COMPLETED (звук доигрывает, затем teardown)
COMPLETED --Заново/Сброс--> RUNNING/IDLE
Автозапуск во время RUNNING/PAUSED пропускается (лог, без дублей).
```

## Архитектура

| Слой | Файлы | Ответственность |
|---|---|---|
| UI (Compose) | `ui/App.kt` (список групп, state-навигация), `ui/GroupCard.kt`, `ui/GroupDetail.kt` (экран группы + «Расписание»), `ui/AlarmCard.kt` (переиспользуется), `ui/ConfirmDialogs.kt` | Состояние ровно из ViewModel, без логики; тикер отсчёта на `elapsedRealtime`. |
| ViewModel | `TimerViewModel.kt` | Владелец `AppConfig`; мутации по (groupId, alarmId), debounce 300 мс; команды с `EXTRA_GROUP_ID`; `StateFlow<Map<Int, GroupRuntime>>`; `isLocked(groupId)`; rearm после мутаций. |
| Мост сервис→UI | `service/TimerStateHolder.kt` | `GroupRuntime` на группу (абсолютные elapsed-цели, публикация только по событиям) + legacy-зеркало первой активной группы. |
| Мультисессии | `service/TimerSoundService.kt` | `Map<groupId, TimerSession>`, одна очередь команд `limitedParallelism(1)`; команды START/PAUSE/RESUME/STOP/RESET/RESTART/PAUSE_ALL/STOP_ALL/AUTO_START; `stopSelf` только без активных сессий. |
| Автозапуск | `service/WakeSchedulerRearm.kt`, `service/ScheduleReceivers.kt`, `timer/WakeScheduler.kt` | Один общий `setAlarmClock`: min(события сессий, автозапуски); якорь планового времени в intent (защита от «завтра»); rearm при BOOT/TIME_SET/TIMEZONE/MY_PACKAGE_REPLACED/мутациях. |
| Движок | `timer/TimerSession.kt` (`snapshot`, `planAnchorWallMs`), `timer/SceneScheduler.kt` (+дни недели), `timer/ScheduleConflicts.kt` | Чистая математика расписаний; wall→elapsed; конфликты без Android. |
| Модель | `model/AlarmGroup.kt` (+`Weekdays`), `model/AppConfig.kt`, `model/GroupPresets.kt`, `model/TimerConfig.kt`/`SceneMode.kt` | Группа → `toRunConfig(fadeIn)` («конфиг одного запуска» для движка без переделок). |
| Звук | `audio/AudioEngine.kt` (`RingKey`), `audio/PreviewPlayer.kt` | По одному `Ringtone` на (группу, будильник); preview — ExoPlayer, один на процесс. |
| Хранилище | `data/PreferencesRepository.kt`, `data/AlarmJson.kt` | DataStore + JSON (`groups_json`, схема v3, `ignoreUnknownKeys`); миграция v2→v3 одной транзакцией («Основная», старые ключи удаляются); битый JSON → лог и дефолт. |

Запуск FGS из `setAlarmClock` разрешён на Android 12+ (alarm-clock даёт окно для FGS).

## Сборка и запуск

Требуется JDK 17+ и Android SDK (`local.properties`: `sdk.dir`).

```bash
# из корня проекта (Windows):
gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest   # компиляция + unit-тесты
gradlew.bat :app:assembleDebug                                # APK: app/build/outputs/apk/debug/app-debug.apk
```

Ключевые параметры: `compileSdk/targetSdk = 37`, `minSdk = 26`,
AGP + Kotlin (плагин `org.jetbrains.kotlin.plugin.compose`).
Зависимости — только AndroidX/Compose/DataStore из `google()`/`mavenCentral()`;
`INTERNET`-разрешение не запрашивается. Новых зависимостей группы не добавляют.

Debug-сборка содержит `debug`-only сид групп из adb (в релиз не попадает):
`adb shell am broadcast -a com.timersound.debug.SEED -n com.timersound/.debug.DebugSeedReceiver --es spec 'once+2'`
(spec: `once+N`, `interval+N`, `random`, `repeat`, `day=1..7`, `manual`;
команды: `--es cmd start|stop|pause|resume|restart|reset --ei group <id>`).

## Разрешения

- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — FGS,
- `WAKE_LOCK` — точность при погашенном экране,
- `POST_NOTIFICATIONS` — уведомление на Android 13+ (runtime),
- `SCHEDULE_EXACT_ALARM` + `USE_EXACT_ALARM` — точные будильники (Doze),
- `RECEIVE_BOOT_COMPLETED` — автозапуск расписаний после перезагрузки.

## Известные ограничения Android

- **Doze / EMUI.** `setAlarmClock` — единственный точный путь в Doze (проверено на Honor COL-L29).
  На EMUI держите приложение закреплённым, без оптимизации батареи, с автозапуском.
- **`force-stop` сносит все алярмы системно** (не лечится кодом): после force-stop автозапуск
  оживает только со следующим rearm (открытие приложения, BOOT). Обычное убийство свайпом алярмы не трогает.
- **Холодный старт процесса на broadcast под EMUI ненадёжен**: debug-сид работает при живом процессе.
- **Остановка процесса системой**: сессия после убийства не восстанавливается (флаг `active_group_ids` → баннер «прервано системой» по группам).
- **Персистентные SAF-права**: файл удалён/отозван — будильник молча пропускается, сессия продолжается.
- **MediaSession** обязательна для FGS `mediaPlayback` на Android 15+.

## Почему системный Ringtone (поток MEDIA)

Звук — `android.media.Ringtone`, `USAGE_MEDIA` + `CONTENT_TYPE_MUSIC`
(поток звонка часто ~1 — неслышно; медиа-громкость пользователь реально слышит;
совпадает с типом FGS). `MediaPlayer` на этом Honor молча не играл (проверено) —
поэтому Ringtone. Ноль DSP-кода, декодирование — на платформе. Утечек нет
(`stopGroup`/`releaseAll` на стоп/авто-стоп/destroy).

## Юнит-тесты

```bash
gradlew.bat :app:testDebugUnitTest
```

Классы: `timer/TimerSessionTest`, `timer/SceneSchedulerTest`, `timer/AllModesBehaviorTest`,
`timer/GroupLogicTest` (якорь 50–500 мс, snapshot/«∞»/пауза, конфликты, wake, дни недели),
`timer/TickPolicyTest`, `model/TimerConfigTest`, `model/PresetIndexTest` (имена↔фабрики),
`data/PreferencesRepositoryMigrationTest` (v1→v2→v3), `data/GroupsMigrationTest` (v2→v3),
`data/PreferencesRepositoryTest`, `service/StartStopRaceTest`, `service/CompletionRestartRaceTest`,
`audio/AudioEngineDroppedTest`, `TimerViewModelTest` + `GroupSessionTest` (Robolectric),
`ui/AlarmCardFormattingTest`. Плюс `androidTest`: `AppUiTest` (группы), `DeviceRaceTest`.

## Проверено в бою (Honor COL-L29, Android 10, 28.09.2026)

| Кейс | Итог |
|---|---|
| Миграция v2→v3 на данных пользователя | schema=3, группа «Основная», старые ключи удалены |
| ONCE автозапуск | EVT, drift 488 мс, COMPLETED→IDLE, без «завтра» |
| INTERVAL 3×15 с автозапуск | 3 EVT, drift ≤173 мс |
| RANDOM в окне, автозапуск | 2 EVT (окно частично прошло), COMPLETED |
| REPEAT ручной, тики 15 с | drift ≤32 мс |
| Пауза 20 с → продолжить | заморозка, фаза с остатка |
| Restart атомарный / Stop в середине | фаза сброшена / тишина после |
| Группа «только Вт» в Пн | тишина |
| Пользовательская ONCE 17:00 | fire по якорю (offsetsMs=-169) |
| Чипы Пн–Вс | все 7 влезают, «Вс» видно |

Осталось проверить только на устройстве: звук ухом при автозапуске с закрытым свайпом приложением,
иконка будильника в статус-баре, автозапуск после перезагрузки телефона.
