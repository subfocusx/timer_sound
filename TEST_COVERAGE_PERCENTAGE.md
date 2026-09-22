# Процент покрытия тестами Android-проекта Timer Sound

Дата проверки: 2026-09-22

## Краткий итог

**Подтверждённый процент покрытия строк или веток кода сейчас не измерен.** В проекте нет настроенного JaCoCo, Kover, Grover или другого отчёта coverage, поэтому корректное значение line/branch coverage — **неизвестно**, а не `0%`.

Если использовать только грубый ориентир по production-файлам, то unit-тесты явно обращаются минимум к **5 из 12 Kotlin-файлов production-кода**:

**минимум 5 / 12 = 41,7% по файлам.**

Это **не процент покрытия строк и не процент покрытия веток**. Он показывает только долю файлов, затронутых тестами, и может завышать фактическое покрытие: например, в `PreferencesRepository.kt` проверены вспомогательные функции, но не весь класс `PreferencesRepository`.

## Измеренные показатели

| Показатель | Значение |
|---|---:|
| Production Kotlin-файлов | 16 |
| Unit-test suites | 4 |
| Unit-тестов | 41 |
| Unit-тестов пройдено | 41 |
| Unit-тестов с ошибками | 0 |
| Unit-тестов пропущено | 0 |
| Instrumented `androidTest` | 0 |
| Подтверждённый line coverage | не измерен |
| Подтверждённый branch coverage | не измерен |
| Грубый file-level proxy | минимум 31,3% (5/16) |

## Состав unit-тестов

| Тестовый класс | Количество тестов |
|---|---:|
| `TimerSessionTest` | 21 |
| `SceneSchedulerTest` | 10 |
| `PreferencesRepositoryTest` | 6 |
| `TimerConfigTest` | 4 |
| **Итого** | **41** |

Команда проверки:

```bash
./gradlew test --rerun-tasks
```

Результат: `BUILD SUCCESSFUL`; XML-отчёты содержат 41 тест, 0 failures, 0 errors и 0 skipped.

Отчёт Gradle: [app/build/reports/tests/testDebugUnitTest/index.html](app/build/reports/tests/testDebugUnitTest/index.html)

## Какие production-файлы затронуты тестами

Минимум пять production-файлов имеют явные обращения из unit-тестов:

- `app/src/main/java/com/timersound/data/PreferencesRepository.kt`;
- `app/src/main/java/com/timersound/model/SceneMode.kt`;
- `app/src/main/java/com/timersound/model/TimerConfig.kt`;
- `app/src/main/java/com/timersound/timer/SceneScheduler.kt`;
- `app/src/main/java/com/timersound/timer/TimerSession.kt`.

Часть из них проверяется напрямую, часть — через модели и вспомогательные функции. Этого недостаточно для вывода о покрытии всех строк или условий внутри файла.

## Что не покрыто unit-тестами

Отдельных unit-тестов нет для следующих production-файлов:

- `app/src/main/java/com/timersound/audio/AudioEngine.kt`;
- `app/src/main/java/com/timersound/audio/PreviewPlayer.kt`;
- `app/src/main/java/com/timersound/service/TimerSoundService.kt`;
- `app/src/main/java/com/timersound/service/TimerStateHolder.kt`;
- `app/src/main/java/com/timersound/TimerViewModel.kt`;
- `app/src/main/java/com/timersound/MainActivity.kt`;
- `app/src/main/java/com/timersound/ui/App.kt`;
- `app/src/main/java/com/timersound/ui/Theme.kt`;
- `app/src/main/java/com/timersound/ui/AlarmCard.kt`;
- `app/src/main/java/com/timersound/ui/ConfirmDialogs.kt`;
- `app/src/main/java/com/timersound/model/AlarmConfig` (частично покрыт через TimerConfigTest/SessionTest:
  scheduleValid, playableAlarms, invalidScheduleAlarms).
- `app/src/main/java/com/timersound/data/AlarmJson.kt` (частично покрыт
  через PreferencesRepositoryTest: JSON round-trip, ignoreUnknownKeys).
- `app/src/main/java/com/timersound/data/PreferencesRepository.kt` (частично:
  migration helpers, legacy key parsing).

Также отсутствует каталог `app/src/androidTest` с инструментированными тестами. Это означает отсутствие UI/instrumentation test suite, но само по себе не даёт процент line/branch coverage.

## Почему нельзя назвать 41 тест процентом покрытия

Количество тестов и покрытие кода — разные метрики:

- `41` — число выполненных тестовых методов;
- `41/41` — успешность запуска unit-тестов;
- `5/12` — приблизительная доля production-файлов, к которым есть обращения;
- line coverage — доля выполненных строк production-кода;
- branch coverage — доля выполненных ветвлений и условий.

Без инструмента покрытия последние две метрики нельзя корректно вычислить по числу тестов или по количеству файлов.

## Состояние конфигурации coverage

В [app/build.gradle.kts](app/build.gradle.kts) не обнаружены:

- плагин JaCoCo/Kover/Grover;
- включение unit-test coverage;
- task для формирования coverage-отчёта;
- готовый отчёт в `app/build/reports/jacoco` или аналогичном каталоге.

Зависимости `androidTestImplementation` присутствуют, но тестовые файлы для instrumented-запуска не добавлены.

## Итоговая формулировка для отчётности

> В проекте Timer Sound выполнено 41 unit-тест, все тесты проходят. Instrumented-тестов нет. Точный процент покрытия строк и веток не измеряется, поскольку coverage-инструмент не подключён. Грубый file-level ориентир составляет минимум 41,7% (5 из 12 production Kotlin-файлов), но его нельзя использовать как line/branch coverage.

Для получения достоверного процента необходимо подключить JaCoCo/Kover/Grover, включить unit-test coverage и сформировать отчёт после полного прогона тестов.
