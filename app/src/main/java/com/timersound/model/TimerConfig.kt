package com.timersound.model

/**
 * Настройка одного будильника.
 *
 * @param id            Стабильный монотонный идентификатор (не переиспользуется).
 * @param name          Отображаемое имя; пустое → «Будильник {id}» на рендере.
 * @param fileUri       URI выбранного аудиофайла (SAF, persistable) или [BUILT_IN_BEEP]
 *                      для встроенного сигнала, или "" если файл не задан.
 * @param fileName      Сохранённое отображаемое имя файла (ТЗ §11); если null — имени нет.
 * @param mode          Способ запуска звуков сценария.
 * @param intervalMs    Интервал между воспроизведениями, мс (минимум [MIN_INTERVAL_MS]).
 * @param startMinutes  Минуты с полуночи для запуска; null — поле не используется.
 * @param endMinutes    Минуты с полуночи, конец RANDOM-окна (exclusive).
 * @param launchCount   Количество срабатываний для INTERVAL/RANDOM; 0 — не задано.
 * @param volumePercent Громкость будильника, 0..100.
 * @param enabled       Будильник включён в сессию таймера.
 */
data class AlarmConfig(
    val id: Int,
    val name: String,
    val fileUri: String,
    val fileName: String? = null,
    val mode: SceneMode = SceneMode.REPEAT,
    val intervalMs: Long,
    /** Минуты с полуночи (0..1439) — когда запустить; null — не используется. */
    val startMinutes: Int? = null,
    /** Минуты с полуночи — конец RANDOM-окна (exclusive). */
    val endMinutes: Int? = null,
    /** Сколько срабатываний (INTERVAL / RANDOM); 0 — не задано. */
    val launchCount: Int = 0,
    val volumePercent: Int,
    val enabled: Boolean,
) {
    val hasFile: Boolean get() = fileUri.isNotEmpty()
    val isBuiltInBeep: Boolean get() = fileUri == Defaults.BUILT_IN_BEEP

    /** Поля расписания валидны для текущего режима. */
    val scheduleValid: Boolean get() = when (mode) {
        SceneMode.REPEAT -> true
        SceneMode.ONCE_TIME -> startMinutes != null
        SceneMode.INTERVAL -> startMinutes != null && launchCount >= 1
        SceneMode.RANDOM ->
            startMinutes != null && endMinutes != null &&
            endMinutes > startMinutes && endMinutes <= Defaults.MINUTES_PER_DAY &&
            launchCount >= 1
    }
}

/** Полная конфигурация таймера. */
data class TimerConfig(
    val alarms: List<AlarmConfig>,
    /** 0 — без ограничения; иначе авто-остановка через это время от старта. */
    val autoStopMs: Long,
) {
    /** Будильники, которые должны звучать: включены и имеют файл. */
    fun playableAlarms(): List<AlarmConfig> = alarms.filter { it.enabled && it.hasFile }

    /** Включённые, но без файла — блокируют старт. */
    fun missingFileAlarms(): List<AlarmConfig> = alarms.filter { it.enabled && !it.hasFile }

    /** Включённые с файлом, но с невалидным расписанием — блокируют старт. */
    fun invalidScheduleAlarms(): List<AlarmConfig> =
        alarms.filter { it.enabled && it.hasFile && !it.scheduleValid }
}

object Defaults {
    const val MAX_ALARMS = 100
    const val MIN_INTERVAL_MS = 1_000L
    const val MINUTES_PER_DAY = 24 * 60
    const val DEFAULT_INTERVAL_MS = 5 * 60_000L
    const val DEFAULT_VOLUME_PERCENT = 80
    const val MAX_NAME_LENGTH = 40

    /** Маркер встроенного тестового сигнала (res/raw/beep.wav). */
    const val BUILT_IN_BEEP = "@beep"

    fun newAlarm(id: Int, orderNumber: Int): AlarmConfig = AlarmConfig(
        id = id,
        name = "Будильник ${orderNumber + 1}",
        // Первый будильник — встроенный тестовый сигнал, остальные пустые.
        fileUri = if (id == 0) BUILT_IN_BEEP else "",
        intervalMs = DEFAULT_INTERVAL_MS,
        volumePercent = DEFAULT_VOLUME_PERCENT,
        enabled = id == 0,
    )

    fun firstRunConfig(): TimerConfig = TimerConfig(
        alarms = listOf(newAlarm(0, 0)),
        autoStopMs = 0L,
    )
}