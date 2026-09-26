package com.timersound.timer

import com.timersound.model.AlarmConfig
import com.timersound.model.SceneMode
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Прогон ВСЕХ режимов через реальный TimerSession на маленьких задержках.
 * Проверяет расписание (SceneScheduler + TimerSession) без Android.
 */
class AllModesBehaviorTest {

    @Before
    fun utc() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private val minute = 60_000L

    private fun alarm(
        id: Int = 0,
        mode: SceneMode = SceneMode.REPEAT,
        intervalMs: Long = 60_000L,
        startMinutes: Int? = null,
        endMinutes: Int? = null,
        launchCount: Int = 0,
    ) = AlarmConfig(
        id = id,
        name = "Канал ${id + 1}",
        fileUri = "file:///tmp/ch$id.mp3",
        mode = mode,
        intervalMs = intervalMs,
        startMinutes = startMinutes,
        endMinutes = endMinutes,
        launchCount = launchCount,
        volumePercent = 80,
        enabled = true,
    )

    private fun config(vararg alarms: AlarmConfig) =
        com.timersound.model.TimerConfig(alarms = alarms.toList(), autoStopMs = 0L)

    /** wall-мс для UTC-времени. */
    private fun wall(hour: Int, minuteOfHour: Int, second: Int = 0): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(2026, Calendar.SEPTEMBER, 22, hour, minuteOfHour, second)
        return c.timeInMillis
    }

    /** Возвращает elapsed-моменты срабатываний при тике каждую секунду. */
    private fun collect(session: TimerSession, from: Long, to: Long, stepMs: Long = 1_000L): List<Long> {
        val fired = mutableListOf<Long>()
        var t = from
        while (t <= to) {
            session.tick(t) { fired += t }
            t += stepMs
        }
        return fired
    }

    // ------------------------------------------------------------------ REPEAT

    @Test
    fun repeatImmediatelyStartsAfterOneInterval() {
        val session = TimerSession()
        val now = 100_000L
        session.start(config(alarm(mode = SceneMode.REPEAT, intervalMs = 3_000L)), now)

        val fired = collect(session, now, now + 10_000L)
        assertEquals(listOf(now + 3_000L, now + 6_000L, now + 9_000L), fired)
    }

    @Test
    fun repeatWithStartTimeFiresAtClockThenEveryInterval() {
        val session = TimerSession()
        val nowElapsed = 0L
        val nowWall = wall(12, 0, 0)
        session.start(
            config(alarm(mode = SceneMode.REPEAT, intervalMs = 2_000L, startMinutes = 12 * 60 + 2)),
            nowElapsed,
            nowWall,
        )

        val fired = collect(session, nowElapsed, 3 * minute)
        // REPEAT бесконечен: первые три с интервалом, дальше продолжается.
        assertEquals(listOf(2 * minute, 2 * minute + 2_000L, 2 * minute + 4_000L), fired.take(3))
        assertTrue(fired.size > 3, "REPEAT должен продолжаться, получено ${fired.size} срабатываний")
        fired.zipWithNext().forEach { (a, b) -> assertEquals(2_000L, b - a) }
    }

    // ------------------------------------------------------------------ ONCE_TIME

    @Test
    fun onceTimeFiresExactlyOnce() {
        val session = TimerSession()
        val nowElapsed = 0L
        val nowWall = wall(12, 0, 0)
        session.start(
            config(alarm(mode = SceneMode.ONCE_TIME, startMinutes = 12 * 60 + 2)),
            nowElapsed,
            nowWall,
        )

        val fired = collect(session, nowElapsed, 5 * minute)
        assertEquals(listOf(2 * minute), fired)
        assertEquals(TimerState.COMPLETED, session.state)
    }

    // ------------------------------------------------------------------ INTERVAL ("N раз")

    @Test
    fun intervalFiresLaunchCountTimes() {
        val session = TimerSession()
        val nowElapsed = 0L
        val nowWall = wall(12, 0, 0)
        session.start(
            config(
                alarm(
                    mode = SceneMode.INTERVAL,
                    startMinutes = 12 * 60 + 1,
                    intervalMs = 3_000L,
                    launchCount = 3,
                )
            ),
            nowElapsed,
            nowWall,
        )

        val fired = collect(session, nowElapsed, 3 * minute)
        assertEquals(listOf(minute, minute + 3_000L, minute + 6_000L), fired)
        assertEquals(TimerState.COMPLETED, session.state)
    }

    // ------------------------------------------------------------------ RANDOM

    @Test
    fun randomWindowInFutureFiresInsideWindowToday() {
        val session = TimerSession()
        val nowElapsed = 0L
        val nowWall = wall(12, 0, 0)
        session.start(
            config(
                alarm(
                    mode = SceneMode.RANDOM,
                    startMinutes = 12 * 60 + 1,
                    endMinutes = 12 * 60 + 5,
                    launchCount = 3,
                )
            ),
            nowElapsed,
            nowWall,
        )

        val fired = collect(session, nowElapsed, 30 * minute)
        assertEquals(3, fired.size)
        fired.forEach { assertTrue(it in minute until 5 * minute, "срабатывание вне окна: $it") }
        assertEquals(fired.toSet().size, fired.size)
    }

    @Test
    fun randomWindowAlreadyOpenFiresTodayNotTomorrow() {
        val session = TimerSession()
        val nowElapsed = 0L
        val nowWall = wall(12, 2, 0) // сейчас ВНУТРИ окна 12:00–12:05
        session.start(
            config(
                alarm(
                    mode = SceneMode.RANDOM,
                    startMinutes = 12 * 60,
                    endMinutes = 12 * 60 + 5,
                    launchCount = 3,
                )
            ),
            nowElapsed,
            nowWall,
        )

        val fired = collect(session, nowElapsed, 60 * minute)
        assertTrue(fired.isNotEmpty(), "окно уже открыто — звуки должны быть сегодня, а не завтра")
        // Сейчас 12:02, окно до 12:05 → остаётся 2 свободные минуты (12:03 и 12:04), разрешение — минуты.
        assertEquals(2, fired.size)
        fired.forEach { assertTrue(it < 3 * minute, "срабатывание позже остатка окна: $it") }
    }

    @Test
    fun randomWindowFullyPassedGoesToTomorrow() {
        val session = TimerSession()
        val nowElapsed = 0L
        val nowWall = wall(12, 10, 0) // окно 12:00–12:05 уже прошло
        session.start(
            config(
                alarm(
                    mode = SceneMode.RANDOM,
                    startMinutes = 12 * 60,
                    endMinutes = 12 * 60 + 5,
                    launchCount = 2,
                )
            ),
            nowElapsed,
            nowWall,
        )

        val fired = collect(session, nowElapsed, 5 * minute)
        assertTrue(fired.isEmpty(), "окно прошло — сегодня звуков быть не должно")
        val next = session.nextEventElapsedMs(nowElapsed)
        assertNotNull(next)
        // Следующее срабатывание — уже завтра (не меньше ~23 часов до него).
        assertTrue(next > 23 * 60 * minute, "ожидалось завтрашнее окно, получено nextEvent=$next")
    }

    @Test
    fun randomCountBiggerThanWindowIsAllowedAndDistinct() {
        val session = TimerSession()
        val nowElapsed = 0L
        val nowWall = wall(12, 0, 0)
        session.start(
            config(
                alarm(
                    mode = SceneMode.RANDOM,
                    startMinutes = 12 * 60 + 1,
                    endMinutes = 12 * 60 + 3, // span = 2 минуты
                    launchCount = 5, // больше, чем span
                )
            ),
            nowElapsed,
            nowWall,
        )

        val fired = collect(session, nowElapsed, 10 * minute)
        assertEquals(fired.toSet().size, fired.size, "минуты должны быть различны")
        assertTrue(fired.size in 1..2, "в окне 2 минуты не может быть больше 2 разных моментов, получено ${fired.size}")
    }
}