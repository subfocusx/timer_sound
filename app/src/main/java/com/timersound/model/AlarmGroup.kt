package com.timersound.model

/**
 * Группа будильников: единица управления, расписания и запуска.
 *
 * @param id идентификатор группы (монотонный, не переиспользуется).
 * @param name отображаемое имя.
 * @param alarms будильники группы; лимит — [Defaults.MAX_ALARMS] на группу.
 * @param enabled группа включена (участвует в автозапуске и предупреждениях).
 * @param weekdays битовая маска дней недели ISO 1..7 (бит 0 = Пн … бит 6 = Вс);
 *   0 — только ручной запуск, без автозапуска по расписанию.
 * @param autoStopMs автоостановка сессии группы, 0 — без ограничения.
 * @param maxTotalFiresPerSession лимит срабатываний сессии группы, 0 — без ограничения.
 */
data class AlarmGroup(
    val id: Int,
    val name: String,
    val alarms: List<AlarmConfig> = emptyList(),
    val enabled: Boolean = true,
    val weekdays: Int = 0,
    val autoStopMs: Long = 0L,
    val maxTotalFiresPerSession: Int = 0,
) {
    /** Будильники, которые должны звучать: включены и имеют файл. */
    fun playableAlarms(): List<AlarmConfig> = alarms.filter { it.enabled && it.hasFile }

    /** Конфиг одного запуска для существующего движка (TimerSession/сервис без изменений). */
    fun toRunConfig(fadeIn: Boolean): TimerConfig = TimerConfig(
        alarms = alarms,
        autoStopMs = autoStopMs.coerceAtLeast(0L),
        maxTotalFiresPerSession = maxTotalFiresPerSession.coerceAtLeast(0),
        fadeInEnabled = fadeIn,
    )

    /**
     * Время автозапуска группы = минимальный startMinutes среди играбельных
     * будильников (для RANDOM — начало окна). null — автозапуск невозможен
     * (нет играбельных будильников со startMinutes).
     */
    fun autoStartMinutes(): Int? =
        playableAlarms().mapNotNull { it.startMinutes }.minOrNull()

    /** Валидация расписания: включённое расписание требует играбельного startMinutes. */
    fun scheduleValid(): Boolean =
        weekdays == 0 || autoStartMinutes() != null
}

/** Битовые операции над маской дней недели ISO 1..7 (1=Пн … 7=Вс). */
object Weekdays {
    const val NONE = 0
    const val ALL = 0b111_1111
    const val WEEKDAYS = 0b001_1111 // Пн–Пт
    const val WEEKEND = 0b110_0000 // Сб–Вс

    /** День ISO (1..7) → бит маски. */
    fun bit(isoDay: Int): Int {
        require(isoDay in 1..7) { "isoDay must be 1..7, got $isoDay" }
        return 1 shl (isoDay - 1)
    }

    fun isSet(mask: Int, isoDay: Int): Boolean = mask and bit(isoDay) != 0

    fun set(mask: Int, isoDay: Int, on: Boolean): Int =
        if (on) mask or bit(isoDay) else mask and bit(isoDay).inv() and ALL

    /** Маска → список ISO-дней по порядку Пн..Вс. */
    fun toList(mask: Int): List<Int> = (1..7).filter { isSet(mask, it) }
}
