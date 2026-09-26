package com.timersound.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import com.timersound.model.AlarmConfig
import com.timersound.model.SceneMode
import com.timersound.model.TimerConfig

/**
 * Юнит-тесты машины состояний и планирования [TimerSession].
 *
 * Движок не зависит от Android: время передаётся явным параметром
 * nowElapsedMs, поэтому всё тестируется на обычной JVM (JUnit 4).
 */
class TimerSessionTest {

    private val tickMs = 250L

    private fun alarm(
        id: Int,
        intervalMs: Long = 60_000L,
        enabled: Boolean = true,
        fileUri: String = "file:///tmp/ch${id}.mp3",
        mode: SceneMode = SceneMode.REPEAT,
        startMinutes: Int? = null,
        endMinutes: Int? = null,
        launchCount: Int = 0,
    ): AlarmConfig = AlarmConfig(
        id = id,
        name = "Канал ${id + 1}",
        fileUri = fileUri,
        mode = mode,
        intervalMs = intervalMs,
        startMinutes = startMinutes,
        endMinutes = endMinutes,
        launchCount = launchCount,
        volumePercent = 80,
        enabled = enabled,
    )

    private fun config(
        alarms: List<AlarmConfig>,
        autoStopMs: Long = 0L,
        maxTotalFiresPerSession: Int = 0,
    ): TimerConfig = TimerConfig(
        alarms = alarms,
        autoStopMs = autoStopMs,
        maxTotalFiresPerSession = maxTotalFiresPerSession,
    )

    // ---------------------------------------------------------------- start / tick

    @Test
    fun startSetsRunningAndSchedulesFirstFire() {
        val session = TimerSession()
        val now = 10_000L
        session.start(config(listOf(alarm(0, intervalMs = 20_000L))), now)
        assertEquals(TimerState.RUNNING, session.state)
        assertTrue(session.isRunning)
        assertTrue(session.isActive)

        // REPEAT без окна старта ждёт один интервал — звука в момент старта нет.
        val fired = mutableListOf<Int>()
        session.tick(now) { fired += it.id }
        assertTrue(fired.isEmpty())

        // Первое срабатывание — ровно через интервал.
        assertFalse(session.tick(now + 19_999L) { fired += it.id })
        assertTrue(fired.isEmpty())
        session.tick(now + 20_000L) { fired += it.id }
        assertEquals(listOf(0), fired)

        // Следующее — ещё через интервал.
        assertFalse(session.tick(now + 39_999L) { fired += it.id })
        assertEquals(listOf(0), fired)
        session.tick(now + 40_000L) { fired += it.id }
        assertEquals(listOf(0, 0), fired)
    }

    @Test
    fun tickFiresOnExactBoundaryThenReschedules() {
        val session = TimerSession()
        val now = 0L
        session.start(config(listOf(alarm(0, intervalMs = 10_000L))), now)

        val fired = mutableListOf<Long>()
        session.tick(now + 10_000L) { fired += it.id.toLong() }
        assertFalse(fired.isEmpty())

        // Сразу после срабатывания следующий fire — ещё через интервал.
        val fired2 = mutableListOf<Long>()
        session.tick(now + 19_999L) { fired2 += it.id.toLong() }
        assertTrue(fired2.isEmpty())
        session.tick(now + 20_000L) { fired2 += it.id.toLong() }
        assertEquals(1, fired2.size)
    }

    @Test
    fun tickCatchesUpMissedIntervals() {
        val session = TimerSession()
        val now = 0L
        session.start(config(listOf(alarm(0, intervalMs = 5_000L))), now)

        // Пропуск тиков: без немедленного старта за 25 с проходит 5 срабатываний (5, 10, 15, 20, 25).
        val fired = mutableListOf<Int>()
        session.tick(now + 25_000L) { fired += it.id }
        assertEquals(5, fired.size)
    }

    @Test
    fun intervalIsClampedToMinimumSecond() {
        val session = TimerSession()
        val now = 0L
        // 200 мс < минимума 1000 мс — интервал принудительно 1 с.
        session.start(config(listOf(alarm(0, intervalMs = 200L))), now)

        val fired = mutableListOf<Int>()
        session.tick(now) { fired += it.id }
        assertTrue(fired.isEmpty())
        session.tick(now + 999L) { fired += it.id }
        assertTrue(fired.isEmpty())
        session.tick(now + 1_000L) { fired += it.id }
        assertEquals(1, fired.size)
    }

    @Test
    fun multipleChannelsFireIndependently() {
        val session = TimerSession()
        val now = 0L
        session.start(
            config(
                listOf(
                    alarm(0, intervalMs = 10_000L),
                    alarm(1, intervalMs = 20_000L),
                )
            ),
            now,
        )

        val fired = mutableMapOf<Int, Int>()
        session.tick(now + 10_000L) { fired[it.id] = (fired[it.id] ?: 0) + 1 }
        session.tick(now + 20_000L) { fired[it.id] = (fired[it.id] ?: 0) + 1 }

        // ch0 (10 с): срабатывания на 10 и 20 с; ch1 (20 с): только на 20 с.
        assertEquals(2, fired[0])
        assertEquals(1, fired[1])
    }

    @Test
    fun disabledOrMissngFileChannelsAreNotScheduled() {
        val c0 = alarm(0)
        val c1 = alarm(1).copy(enabled = false)
        val c2 = alarm(2).copy(fileUri = "")
        val cfg = config(listOf(c0, c1, c2), autoStopMs = 0L)

        assertEquals(1, cfg.playableAlarms().size)
        // missingFileAlarms — только включённые, но без файла.
        assertEquals(1, cfg.missingFileAlarms().size)

        val session = TimerSession()
        session.start(cfg, 0L)
        val fired = mutableListOf<Int>()
        session.tick(60_000L) { fired += it.id }
        // disabled (1) и без файла (2) не звучат; REPEAT срабатывает через свой интервал (60 с).
        assertEquals(listOf(0), fired)
    }

    // ---------------------------------------------------------------- pause / resume

    @Test
    fun pauseKeepsRemainingAndResumeKeepsCadence() {
        val session = TimerSession()
        val now = 0L
        session.start(config(listOf(alarm(0, intervalMs = 10_000L))), now)

        // Первый REPEAT-звук — через интервал (10 с); пауза на 3-й секунде.
        val fired = mutableListOf<Int>()
        session.tick(now) { fired += it.id }
        assertTrue(fired.isEmpty())
        session.pause(now + 3_000L)
        assertEquals(TimerState.PAUSED, session.state)
        assertFalse(session.isRunning)
        assertTrue(session.isActive)

        // Во время паузы тики не срабатывают.
        session.tick(now + 50_000L) { fired += it.id }
        assertTrue(fired.isEmpty())

        // Резюме в момент T=10 с: звук наступает через сохранённые 7 с.
        session.resume(now + 10_000L)
        assertEquals(TimerState.RUNNING, session.state)
        assertFalse(session.tick(now + 16_999L) { fired += it.id })
        assertTrue(fired.isEmpty())
        session.tick(now + 17_000L) { fired += it.id }
        assertEquals(1, fired.size)
    }

    @Test
    fun pauseAndResumeFromIdleAreNoOps() {
        val session = TimerSession()
        session.pause(0L)
        assertEquals(TimerState.IDLE, session.state)
        session.resume(0L)
        assertEquals(TimerState.IDLE, session.state)
    }

    // ---------------------------------------------------------------- stop / reset

    @Test
    fun stopReturnsToIdle() {
        val session = TimerSession()
        session.start(config(listOf(alarm(0))), 0L)
        session.stop()
        assertEquals(TimerState.IDLE, session.state)
        assertFalse(session.isActive)

        // После стопа тик не триггерит.
        val fired = mutableListOf<Int>()
        assertFalse(session.tick(100_000L) { fired += it.id })
        assertTrue(fired.isEmpty())
    }

    @Test
    fun markCompletedAndReset() {
        val session = TimerSession()
        session.start(config(listOf(alarm(0))), 0L)
        session.markCompleted()
        assertEquals(TimerState.COMPLETED, session.state)
        assertFalse(session.isActive)

        session.reset()
        assertEquals(TimerState.IDLE, session.state)
    }

    // ---------------------------------------------------------------- auto-stop

    @Test
    fun autoStopAfterDeadlineReturnsTrueAndStateCompleted() {
        val session = TimerSession()
        val now = 0L
        // Канал звучит каждые 10 с, авто-остановка через 30 с.
        session.start(config(listOf(alarm(0, intervalMs = 10_000L)), autoStopMs = 30_000L), now)

        val fired = mutableListOf<Int>()
        // До дедлайна — обычные срабатывания.
        assertFalse(session.tick(now + 10_000L) { fired += it.id })
        assertFalse(fired.isEmpty())

        // В момент дедлайна — авто-остановка.
        val autoStopped = session.tick(now + 30_000L) { fired += it.id }
        assertTrue(autoStopped)
        assertEquals(TimerState.COMPLETED, session.state)
    }

    @Test
    fun countdownToAutoStop() {
        val session = TimerSession()
        val now = 0L
        session.start(config(listOf(alarm(0)), autoStopMs = 30_000L), now)

        assertEquals(30_000L, session.countdownToAutoStopMs(now))
        assertEquals(17_500L, session.countdownToAutoStopMs(now + 12_500L))
        assertEquals(0L, session.countdownToAutoStopMs(now + 99_000L))
    }

    @Test
    fun noAutoStopWithoutLimit() {
        val session = TimerSession()
        session.start(config(listOf(alarm(0)), autoStopMs = 0L), 0L)
        assertNull(session.countdownToAutoStopMs(50_000L))
    }

    // ---------------------------------------------------------------- description

    @Test
    fun nextSoundDescriptionShowsClosestChannel() {
        val session = TimerSession()
        val now = 0L
        session.start(
            config(
                listOf(
                    alarm(0, intervalMs = 30_000L),
                    alarm(1, intervalMs = 10_000L),
                )
            ),
            now,
        )
        val desc = session.nextSoundDescription(now + 3_000L)
        assertNotNull(desc)
        // alarm(1) отображается как «Канал 2» (id + 1) и сейчас ближе: 10 с против 30 с.
        assertTrue("ожидается «Канал 2» в описании: $desc", desc.contains("Канал 2"))
        assertTrue(desc.contains("через"))
    }
    @Test
    fun nextSoundDescriptionInPauseShowsRemainingNotZero() {
        val session = TimerSession()
        session.start(config(listOf(alarm(0, intervalMs = 10_000L))), 0L)
        session.pause(4_000L) // до срабатывания оставалось 6 с

        // now заметно позже паузы: абсолютная метка уже в прошлом,
        // поэтому показывать надо сохранённый остаток, а не «через 0 с».
        val desc = session.nextSoundDescription(30_000L)
        assertTrue("ожидается «через 6 с», получено: $desc", desc.contains("через 6 с"))

        session.resume(30_000L)
        assertEquals("Канал 1: через 6 с", session.nextSoundDescription(30_000L))
    }

    @Test
    fun countdownToAutoStopInPauseKeepsRemaining() {
        val session = TimerSession()
        session.start(config(listOf(alarm(0, intervalMs = 10_000L)), autoStopMs = 60_000L), 0L)
        session.pause(20_000L) // до авто-остановки оставалось 40 с

        assertEquals(40_000L, session.countdownToAutoStopMs(50_000L))

        session.resume(50_000L)
        assertEquals(40_000L, session.countdownToAutoStopMs(50_000L))
    }

    @Test
    fun nextFireForChannel() {
        val session = TimerSession()
        val now = 0L
        session.start(config(listOf(alarm(0, intervalMs = 10_000L))), now)
        session.tick(now) { }

        assertEquals(10_000L, session.nextFireForChannel(0, now))
        assertEquals(2_000L, session.nextFireForChannel(0, now + 8_000L))
        assertEquals(0L, session.nextFireForChannel(0, now + 20_000L))
        assertNull(session.nextFireForChannel(3, now))
    }

    // ---------------------------------------------------------------- schedule modes

    @Test
    fun repeatWithoutStartTimeWaitsOneInterval() {
        val session = TimerSession()
        session.start(config(listOf(alarm(0, intervalMs = 10_000L))), 0L)

        val fired = mutableListOf<Int>()
        session.tick(0L) { fired += it.id }
        assertTrue(fired.isEmpty())

        session.tick(9_999L) { fired += it.id }
        assertTrue(fired.isEmpty())

        session.tick(10_000L) { fired += it.id }
        assertEquals(listOf(0), fired)
    }

    @Test
    fun onceTimeFiresOnceAndNotAgain() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val nowWall = epochUtc(2026, 9, 21, 11, 0, 0)
        val nowElapsed = 1_000_000L
        val ch = alarm(
            id = 1,
            mode = SceneMode.ONCE_TIME,
            startMinutes = 11 * 60 + 45,
        )
        val session = TimerSession()
        session.start(config(listOf(ch)), nowElapsed, nowWall)
        val fired = mutableListOf<Int>()

        session.tick(nowElapsed + 45 * 60_000L - 1L) { fired += it.id }
        assertTrue(fired.isEmpty())
        session.tick(nowElapsed + 45 * 60_000L) { fired += it.id }
        session.tick(nowElapsed + 45 * 60_000L + 1_000L) { fired += it.id }

        assertEquals(listOf(1), fired)
        assertNull(session.nextEventElapsedMs(nowElapsed + 45 * 60_000L + 1_000L))
    }

    @Test
    fun intervalFiresLaunchCountTimesAtInterval() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val nowWall = epochUtc(2026, 9, 21, 10, 0, 0)
        val nowElapsed = 500_000L
        val ch = alarm(
            id = 2,
            mode = SceneMode.INTERVAL,
            startMinutes = 10 * 60 + 10,
            intervalMs = 10_000L,
            launchCount = 3,
        )
        val session = TimerSession()
        session.start(config(listOf(ch)), nowElapsed, nowWall)
        val fired = mutableListOf<Int>()
        val first = nowElapsed + 10 * 60_000L

        session.tick(first) { fired += it.id }
        session.tick(first + 10_000L) { fired += it.id }
        session.tick(first + 20_000L) { fired += it.id }
        session.tick(first + 60_000L) { fired += it.id }

        assertEquals(3, fired.size)
        assertNull(session.nextEventElapsedMs(first + 60_000L))
    }

    @Test
    fun repeatWithStartTimeFiresAtClockThenEveryInterval() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val nowWall = epochUtc(2026, 9, 21, 12, 0, 0)
        val ch = alarm(
            id = 3,
            mode = SceneMode.REPEAT,
            startMinutes = 12 * 60 + 5,
            intervalMs = 60_000L,
        )
        val session = TimerSession()
        session.start(config(listOf(ch)), 0L, nowWall)
        val fired = mutableListOf<Int>()

        session.tick(4 * 60_000L) { fired += it.id }
        session.tick(5 * 60_000L) { fired += it.id }
        session.tick(6 * 60_000L) { fired += it.id }

        assertEquals(listOf(3, 3), fired)
        assertNotNull(session.nextEventElapsedMs(6 * 60_000L))
    }

    @Test
    fun randomFixedAtStartSurvivesPauseResume() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val nowWall = epochUtc(2026, 9, 21, 9, 0, 0)
        val ch = alarm(
            id = 4,
            mode = SceneMode.RANDOM,
            startMinutes = 600,
            endMinutes = 610,
            launchCount = 4,
        )
        val session = TimerSession()
        session.start(config(listOf(ch)), 0L, nowWall)
        val snapshot = session.nextEventElapsedMs(0L)

        session.pause(1L)
        session.resume(5_000L)

        assertEquals(snapshot!! + (5_000L - 1L), session.nextEventElapsedMs(5_000L))
    }

    private fun epochUtc(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, second)
        return calendar.timeInMillis
    }

    // ---------------------------------------------------------------- formatHms

    @Test
    fun formatHms() {
        assertEquals("00:00:00", TimerSession.formatHms(0L))
        assertEquals("00:00:01", TimerSession.formatHms(1_000L))
        assertEquals("00:01:30", TimerSession.formatHms(90_000L))
        assertEquals("01:00:00", TimerSession.formatHms(3_600_000L))
        assertEquals("12:34:56", TimerSession.formatHms((12L * 3600 + 34L * 60 + 56L) * 1000L))
        // Округление вниз и отрицательные значения.
        assertEquals("00:00:01", TimerSession.formatHms(1_999L))
        assertEquals("00:00:00", TimerSession.formatHms(-5_000L))
    }

    // ------------------------------------------------- человеческое описание следующего звука

    @Test
    fun describeWallMomentUsesRelativePhrasesForNearFires() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val now = epochUtc(2026, 9, 21, 10, 0, 0)

        assertEquals("через 30 с", TimerSession.describeWallMoment(now + 30_000L, now))
        assertEquals("через 12 мин", TimerSession.describeWallMoment(now + 12 * 60_000L, now))
        assertEquals("через 59 мин", TimerSession.describeWallMoment(now + 59 * 60_000L, now))
    }

    @Test
    fun describeWallMomentNamesDayForDistantFires() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val now = epochUtc(2026, 9, 21, 10, 0, 0)

        assertEquals("сегодня в 14:05", TimerSession.describeWallMoment(epochUtc(2026, 9, 21, 14, 5, 0), now))
        assertEquals("завтра в 01:19", TimerSession.describeWallMoment(epochUtc(2026, 9, 22, 1, 19, 0), now))
        assertEquals("25.09 в 07:00", TimerSession.describeWallMoment(epochUtc(2026, 9, 25, 7, 0, 0), now))
    }

    @Test
    fun descriptionSaysTomorrowWhenOnceTimeAlreadyPassed() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val session = TimerSession()
        val nowElapsed = 0L
        // 01:30 на часах, цель 01:19 уже прошла → сегодня не сработает.
        val nowWall = epochUtc(2026, 9, 21, 1, 30, 0)
        session.start(
            config(listOf(alarm(0, mode = SceneMode.ONCE_TIME, startMinutes = 79))),
            nowElapsed,
            nowWall,
        )

        val desc = session.nextSoundDescription(nowElapsed)

        assertTrue("ожидается «завтра в 01:19», получено: $desc", desc.contains("завтра в 01:19"))
    }

    @Test
    fun descriptionSaysSecondsWhenOnceTimeIsImminent() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val session = TimerSession()
        val nowElapsed = 0L
        val nowWall = epochUtc(2026, 9, 21, 1, 19, 30)
        session.start(
            config(listOf(alarm(0, mode = SceneMode.ONCE_TIME, startMinutes = 80))),
            nowElapsed,
            nowWall,
        )

        assertEquals("Канал 1: через 30 с", session.nextSoundDescription(nowElapsed))
    }

    @Test
    fun maxTotalFiresCompletesBeforeLaunchCountExhausted() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val session = TimerSession()
        val nowElapsed = 0L
        val nowWall = epochUtc(2026, 9, 21, 9, 0, 0)
        session.start(
            config(
                listOf(
                    alarm(
                        0,
                        mode = SceneMode.INTERVAL,
                        startMinutes = 600,
                        launchCount = 10,
                        intervalMs = 60_000L,
                    ),
                ),
                maxTotalFiresPerSession = 3,
            ),
            nowElapsed,
            nowWall,
        )
        val fired = mutableListOf<Int>()
        // Каждое срабатывание — на минуту позже; 3-й тик должен завершить сессию.
        assertFalse(session.tick(nowElapsed + 3_600_000L) { fired += it.id })
        assertFalse(session.tick(nowElapsed + 3_660_000L) { fired += it.id })
        assertTrue(session.tick(nowElapsed + 3_720_000L) { fired += it.id })
        assertEquals(listOf(0, 0, 0), fired)
        assertEquals(TimerState.COMPLETED, session.state)
    }

    @Test
    fun zeroMaxTotalFiresMeansNoLimit() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val session = TimerSession()
        val nowElapsed = 0L
        val nowWall = epochUtc(2026, 9, 21, 9, 0, 0)
        session.start(
            config(
                listOf(
                    alarm(
                        0,
                        mode = SceneMode.INTERVAL,
                        startMinutes = 600,
                        launchCount = 2,
                        intervalMs = 60_000L,
                    ),
                ),
            ),
            nowElapsed,
            nowWall,
        )
        val fired = mutableListOf<Int>()
        assertFalse(session.tick(nowElapsed + 3_600_000L) { fired += it.id })
        assertTrue(session.tick(nowElapsed + 3_660_000L) { fired += it.id })
        assertEquals(listOf(0, 0), fired)
    }
}
