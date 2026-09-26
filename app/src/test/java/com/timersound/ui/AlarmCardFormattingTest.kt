package com.timersound.ui

import org.junit.Test
import kotlin.test.assertEquals
import com.timersound.model.Defaults
import com.timersound.model.SceneMode
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Тесты форматирования и разбора ввода времени в AlarmCard.kt.
 */
class AlarmCardFormattingTest {

    // ------------------------------------------------------------------ parseHm

    @Test
    fun parseHm_validInput() {
        assertEquals(60, parseHm("01:00"))
        assertEquals(90, parseHm("01:30"))
        assertEquals(0, parseHm("00:00"))
        assertEquals(24 * 60, parseHm("24:00"))
        assertEquals(59, parseHm("00:59"))
        assertEquals(570, parseHm("9:30")) // без ведущего нуля — как обещает подсказка «930 → 09:30»
        assertEquals(9, parseHm("9"))      // одна цифра — минуты
    }

    @Test
    fun parseHm_invalidInput_returnsNull() {
        assertNull(parseHm(""))
        assertNull(parseHm(":"))
        assertNull(parseHm("93"))    // минуты > 59
        assertNull(parseHm("930"))   // три цифры — маска не пропустит, но парсер обязан отвергнуть
        assertNull(parseHm("1234:45"))
        assertNull(parseHm("ab:cd"))
        assertNull(parseHm("25:00"))  // часы > 24
        assertNull(parseHm("12:60"))  // минуты > 59
    }

    @Test
    fun formatMinutes_correctOutput() {
        assertEquals("00:00", formatMinutes(0))
        assertEquals("01:00", formatMinutes(60))
        assertEquals("01:30", formatMinutes(90))
        assertEquals("24:00", formatMinutes(24 * 60))
        assertEquals("00:59", formatMinutes(59))
    }

    // ------------------------------------------------------------------ parseHms

    @Test
    fun parseHms_validInput() {
        assertEquals(3600_000L, parseHms("01:00:00"))
        assertEquals(5400_000L, parseHms("01:30:00"))
        assertEquals(0L, parseHms("00:00:00"))
        assertEquals(60_000L, parseHms("00:01:00"))
        assertEquals(1_000L, parseHms("00:00:01"))
        assertEquals(3_600_000L, parseHms("1:00:00")) // без ведущего нуля в часах
        assertEquals(180_000L, parseHms("3:00"))      // две части — минуты:секунды
        assertEquals(3_000L, parseHms("3"))           // одна цифра — секунды
        assertEquals(30_000L, parseHms("30"))
    }

    @Test
    fun parseHms_invalidInput_returnsNull() {
        assertNull(parseHms(""))
        assertNull(parseHms("12:34:567"))  // слишком длинное
        assertNull(parseHms("ab:cd:ef"))
        assertNull(parseHms("01:60:00"))   // минуты > 59
        assertNull(parseHms("01:00:60"))   // секунды > 59
        assertNull(parseHms("1:2:3:4"))    // слишком много частей
    }

    // ------------------------------------------------------------------ маски ввода

    @Test
    fun formatHmInput_insertsColon() {
        assertEquals("", formatHmInput(""))
        assertEquals("9", formatHmInput("9"))
        assertEquals("93", formatHmInput("93"))
        assertEquals("9:30", formatHmInput("930"))     // подсказка в UI: 930 → 09:30
        assertEquals("09:30", formatHmInput("0930"))
        assertEquals("23:59", formatHmInput("2359"))
        assertEquals(9 * 60 + 30, parseHm(formatHmInput("930")))
    }

    @Test
    fun formatHmInput_filtersAndClamps() {
        assertEquals("9:30", formatHmInput("9a3b0"))   // не-цифры отброшены
        assertEquals("12:34", formatHmInput("123456")) // не больше 4 цифр
    }

    @Test
    fun formatHmsInput_insertsColons() {
        assertEquals("", formatHmsInput(""))
        assertEquals("3", formatHmsInput("3"))
        assertEquals("30", formatHmsInput("30"))
        assertEquals("3:00", formatHmsInput("300"))
        assertEquals("13:00", formatHmsInput("1300"))
        assertEquals("1:30:00", formatHmsInput("13000"))
        assertEquals("00:00:03", formatHmsInput("000003"))
        assertEquals("00:00:30", formatHmsInput("000030"))
        assertEquals("01:00:00", formatHmsInput("010000"))
        assertEquals("12:34:56", formatHmsInput("1234567")) // не больше 6 цифр
    }

    @Test
    fun formattedHmsInputIsParsable() {
        assertEquals(3_000L, parseHms(formatHmsInput("000003")))
        assertEquals(30_000L, parseHms(formatHmsInput("000030")))
        assertEquals(3_600_000L, parseHms(formatHmsInput("010000")))
        assertEquals(180_000L, parseHms(formatHmsInput("300")))
    }

    // ------------------------------------------------------------------ печать в заполненном поле

    @Test
    fun restartIfFullTreatsFirstDigitAsNewValue() {
        // «00:05:00» (6 цифр, поле заполнено) + «2» → «2», иначе маска срезала бы цифру и поле «замерло».
        assertEquals("2", restartIfFull("00:05:002", "00:05:00", MAX_HMS_DIGITS))
        assertEquals("2", restartIfFull("11:382", "11:38", MAX_HM_DIGITS))
        // Вставка нескольких цифр сразу — берём только добавленные.
        assertEquals("25", restartIfFull("00:05:0025", "00:05:00", MAX_HMS_DIGITS))
    }

    @Test
    fun restartIfFullKeepsNormalTypingAndDeletion() {
        // Поле не заполнено — обычное дописывание.
        assertEquals("12", restartIfFull("12", "1", MAX_HMS_DIGITS))
        assertEquals("1:200", restartIfFull("1:200", "1:20", MAX_HMS_DIGITS))
        // Backspace: цифр стало меньше, значение не подменяем.
        assertEquals("00:05:0", restartIfFull("00:05:0", "00:05:00", MAX_HMS_DIGITS))
        assertEquals("11:3", restartIfFull("11:3", "11:38", MAX_HM_DIGITS))
    }

    @Test
    fun restartIfFullFeedsMaskAndParser() {
        // Итог пути «ввод → маска → парсер» для заполненного поля: 2, 0, 0 → 2 минуты.
        var text = "00:05:00"
        var model = 0L
        for (ch in "200") {
            val formatted = formatHmsInput(restartIfFull(text + ch, text, MAX_HMS_DIGITS))
            text = formatted
            parseHms(formatted)?.let { model = it }
        }
        assertEquals("2:00", text)
        assertEquals(120_000L, model)
    }

    // ------------------------------------------------------------------ предупреждение о прошедшем времени

    @Test
    fun pastTimeWarningIsNullForFutureTime() {
        // 01:41 уже на часах, 05:00 — впереди, предупреждать не о чем.
        assertNull(pastTimeWarning(value = 300, nowMinutes = 101))
        assertNull(pastTimeWarning(value = null, nowMinutes = 101))
    }

    @Test
    fun pastTimeWarningShownForPassedTime() {
        // 01:19 при текущих 01:41 — сегодня не сработает, уедет на завтра.
        assertEquals(
            "Это время сегодня уже прошло — сработает завтра.",
            pastTimeWarning(value = 79, nowMinutes = 101),
        )
        // Ровно текущая минута: момент 01:19:00 уже прошёл.
        assertEquals(
            "Это время сегодня уже прошло — сработает завтра.",
            pastTimeWarning(value = 101, nowMinutes = 101),
        )
    }

    @Test
    fun nowMinutesOfDayIsLocalTime() {
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            calendar.clear()
            calendar.set(2026, 8, 21, 13, 45, 30)
            assertEquals(13 * 60 + 45, nowMinutesOfDay(calendar.timeInMillis))
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    // ------------------------------------------------------------------ подписи и сводка карточки

    /**
     * Подписи режимов обязаны быть короткими: четыре чипа должны влезать в строку карточки.
     * Регресс: подписи вида «Бесконечно, каждые …» не влезали, чипы сжимались до ~100 px,
     * подпись ломалась в столбик букв и строка режимов вырастала до 1100+ px.
     */
    @Test
    fun sceneModeLabelsAreShortAndComplete() {
        SceneMode.values().forEach { mode ->
            assertTrue(mode.label.length <= 9, "подпись режима слишком длинная: ${mode.label}")
            assertTrue(!mode.label.contains("…"), "подпись режима не должна намекать на продолжение: ${mode.label}")
        }
        assertEquals("Повтор", SceneMode.REPEAT.label)
        assertEquals("Один раз", SceneMode.ONCE_TIME.label)
        assertEquals("N раз", SceneMode.INTERVAL.label)
        assertEquals("Случайно", SceneMode.RANDOM.label)
    }

    /** Сводка свёрнутой карточки не повторяет подпись режима (он и так в соседнем чипе). */
    @Test
    fun alarmSummaryDoesNotDuplicateModeLabel() {
        val alarm = Defaults.newAlarm(0, 0).copy(mode = SceneMode.INTERVAL, intervalMs = 10_000L, launchCount = 3)
        val summary = alarmSummary(alarm)
        SceneMode.values().forEach { mode ->
            assertTrue(!summary.contains(mode.label), "сводка повторяет подпись режима: $summary")
        }
        assertEquals("каждые 00:00:10, 3× · 80%", summary)
    }

    @Test
    fun alarmSummaryPerMode() {
        val base = Defaults.newAlarm(0, 0)
        assertEquals(
            "каждые 00:05:00 · 80%",
            alarmSummary(base.copy(mode = SceneMode.REPEAT, intervalMs = 300_000L)),
        )
        assertEquals(
            "с 09:30 · каждые 00:05:00 · 80%",
            alarmSummary(base.copy(mode = SceneMode.REPEAT, intervalMs = 300_000L, startMinutes = 9 * 60 + 30)),
        )
        assertEquals(
            "в 14:05 · 80%",
            alarmSummary(base.copy(mode = SceneMode.ONCE_TIME, startMinutes = 14 * 60 + 5)),
        )
        assertEquals(
            "время не задано · 80%",
            alarmSummary(base.copy(mode = SceneMode.ONCE_TIME, startMinutes = null)),
        )
        assertEquals(
            "09:00–09:30, 3× · 80%",
            alarmSummary(base.copy(mode = SceneMode.RANDOM, startMinutes = 9 * 60, endMinutes = 9 * 60 + 30, launchCount = 3)),
        )
        assertEquals(
            "окно не задано · 80%",
            alarmSummary(base.copy(mode = SceneMode.RANDOM, startMinutes = null, endMinutes = null)),
        )
    }
}