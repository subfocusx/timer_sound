package com.timersound.model

/**
 * Статический каталог пресетов (в коде, не в хранилище). Каждый — фабрика
 * AlarmGroup с редактируемыми значениями; обязательно BUILT_IN_BEEP,
 * чтобы старт не блокировался отсутствием файла.
 */
object GroupPresets {

    fun empty(id: Int, nextAlarmId: Int): AlarmGroup = AlarmGroup(
        id = id,
        name = "Группа ${id + 1}",
        alarms = listOf(
            Defaults.newAlarm(nextAlarmId, 0).copy(fileUri = Defaults.BUILT_IN_BEEP, enabled = true),
        ),
    )

    fun morning(id: Int, base: Int): AlarmGroup {
        val starts = listOf(7 * 60, 7 * 60 + 10, 7 * 60 + 20)
        return AlarmGroup(
            id = id, name = "Подъём",
            alarms = starts.mapIndexed { i, m ->
                Defaults.newAlarm(base + i, i).copy(
                    name = "Подъём ${i + 1}", fileUri = Defaults.BUILT_IN_BEEP, enabled = true,
                    mode = SceneMode.ONCE_TIME, startMinutes = m,
                )
            },
            weekdays = Weekdays.WEEKDAYS,
        )
    }

    fun pomodoro(id: Int, base: Int): AlarmGroup = AlarmGroup(
        id = id, name = "Помодоро",
        alarms = listOf(
            Defaults.newAlarm(base, 0).copy(
                name = "Помодоро", fileUri = Defaults.BUILT_IN_BEEP, enabled = true,
                mode = SceneMode.INTERVAL, intervalMs = 30 * 60_000L,
                startMinutes = 9 * 60, launchCount = 8,
            ),
        ),
    )

    fun pills(id: Int, base: Int): AlarmGroup {
        val starts = listOf(8 * 60, 14 * 60, 20 * 60)
        return AlarmGroup(
            id = id, name = "Таблетки",
            alarms = starts.mapIndexed { i, m ->
                Defaults.newAlarm(base + i, i).copy(
                    name = "Приём ${i + 1}", fileUri = Defaults.BUILT_IN_BEEP, enabled = true,
                    mode = SceneMode.ONCE_TIME, startMinutes = m,
                )
            },
            weekdays = Weekdays.ALL,
        )
    }

    fun hourlyStretch(id: Int, base: Int): AlarmGroup = AlarmGroup(
        id = id, name = "Разминка каждый час",
        alarms = listOf(
            Defaults.newAlarm(base, 0).copy(
                name = "Разминка", fileUri = Defaults.BUILT_IN_BEEP, enabled = true,
                mode = SceneMode.INTERVAL, intervalMs = 60 * 60_000L,
                startMinutes = 9 * 60, launchCount = 8,
            ),
        ),
        weekdays = Weekdays.WEEKDAYS,
    )

    fun randomChecks(id: Int, base: Int): AlarmGroup = AlarmGroup(
        id = id, name = "Случайные проверки",
        alarms = listOf(
            Defaults.newAlarm(base, 0).copy(
                name = "Проверка", fileUri = Defaults.BUILT_IN_BEEP, enabled = true,
                mode = SceneMode.RANDOM, intervalMs = Defaults.DEFAULT_INTERVAL_MS,
                startMinutes = 9 * 60, endMinutes = 18 * 60, launchCount = 5,
            ),
        ),
    )

    fun meditation(id: Int, base: Int): AlarmGroup = AlarmGroup(
        id = id, name = "Медитация",
        alarms = listOf(
            Defaults.newAlarm(base, 0).copy(
                name = "Медитация", fileUri = Defaults.BUILT_IN_BEEP, enabled = true,
                mode = SceneMode.ONCE_TIME, startMinutes = 21 * 60,
            ),
        ),
        autoStopMs = 20 * 60_000L,
    )

    /** Все пресеты для диалога выбора. */
    fun all(id: Int, base: Int): List<AlarmGroup> = listOf(
        empty(id, base), morning(id, base), pomodoro(id, base), pills(id, base),
        hourlyStretch(id, base), randomChecks(id, base), meditation(id, base),
    )

    fun presetNames(): List<String> =
        listOf("Пустая", "Подъём", "Помодоро", "Таблетки", "Разминка каждый час", "Случайные проверки", "Медитация")
}
