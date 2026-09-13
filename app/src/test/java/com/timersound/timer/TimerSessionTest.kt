package com.timersound.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.timersound.model.ChannelConfig
import com.timersound.model.TimerConfig

/**
 * Юнит-тесты машины состояний и планирования [TimerSession].
 *
 * Движок не зависит от Android: время передаётся явным параметром
 * nowElapsedMs, поэтому всё тестируется на обычной JVM (JUnit 4).
 */
class TimerSessionTest {

    private val tickMs = 250L

    private fun channel(
        id: Int,
        intervalMs: Long = 60_000L,
        enabled: Boolean = true,
        fileUri: String = "file:///tmp/ch${id}.mp3",
    ): ChannelConfig = ChannelConfig(
        id = id,
        name = "Канал ${id + 1}",
        fileUri = fileUri,
        intervalMs = intervalMs,
        volumePercent = 80,
        enabled = enabled,
    )

    private fun config(
        channels: List<ChannelConfig>,
        autoStopMs: Long = 0L,
    ): TimerConfig = TimerConfig(channels = channels, autoStopMs = autoStopMs)

    // ---------------------------------------------------------------- start / tick

    @Test
    fun startSetsRunningAndSchedulesFirstFire() {
        val session = TimerSession()
        val now = 10_000L
        session.start(config(listOf(channel(0, intervalMs = 20_000L))), now)
        assertEquals(TimerState.RUNNING, session.state)
        assertTrue(session.isRunning)
        assertTrue(session.isActive)

        // До первого срабатывания триггер не вызывается.
        val fired = mutableListOf<Int>()
        assertFalse(session.tick(now + 19_999L) { fired += it.id })
        assertTrue(fired.isEmpty())

        // Ровно на границе — срабатывает.
        assertFalse(session.tick(now + 20_000L) { fired += it.id })
        assertEquals(listOf(0), fired)
    }

    @Test
    fun tickFiresOnExactBoundaryThenReschedules() {
        val session = TimerSession()
        val now = 0L
        session.start(config(listOf(channel(0, intervalMs = 10_000L))), now)

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
        session.start(config(listOf(channel(0, intervalMs = 5_000L))), now)

        // Пропуск тиков: за 25 с должно накопиться 5 срабатываний.
        val fired = mutableListOf<Int>()
        session.tick(now + 25_000L) { fired += it.id }
        assertEquals(5, fired.size)
    }

    @Test
    fun intervalIsClampedToMinimumSecond() {
        val session = TimerSession()
        val now = 0L
        // 200 мс < минимума 1000 мс — интервал принудительно 1 с.
        session.start(config(listOf(channel(0, intervalMs = 200L))), now)

        val fired = mutableListOf<Int>()
        session.tick(now + 999L) { fired += it.id }
        assertTrue(fired.isEmpty())
        session.tick(now + 1_000L) { fired += it.id }
        assertFalse(fired.isEmpty())
    }

    @Test
    fun multipleChannelsFireIndependently() {
        val session = TimerSession()
        val now = 0L
        session.start(
            config(
                listOf(
                    channel(0, intervalMs = 10_000L),
                    channel(1, intervalMs = 20_000L),
                )
            ),
            now,
        )

        val fired = mutableMapOf<Int, Int>()
        session.tick(now + 10_000L) { fired[it.id] = (fired[it.id] ?: 0) + 1 }
        session.tick(now + 20_000L) { fired[it.id] = (fired[it.id] ?: 0) + 1 }

        assertEquals(2, fired[0])
        assertEquals(1, fired[1])
    }

    @Test
    fun disabledOrMissngFileChannelsAreNotScheduled() {
        val c0 = channel(0)
        val c1 = channel(1).copy(enabled = false)
        val c2 = channel(2).copy(fileUri = "")
        val cfg = config(listOf(c0, c1, c2), autoStopMs = 0L)

        assertEquals(1, cfg.playableChannels().size)
        // missingFileChannels — только включённые, но без файла.
        assertEquals(1, cfg.missingFileChannels().size)

        val session = TimerSession()
        session.start(cfg, 0L)
        val fired = mutableListOf<Int>()
        session.tick(60_000L) { fired += it.id }
        // disabled (1) и без файла (2) не звучат.
        assertEquals(listOf(0), fired)
    }

    // ---------------------------------------------------------------- pause / resume

    @Test
    fun pauseKeepsRemainingAndResumeKeepsCadence() {
        val session = TimerSession()
        val now = 0L
        session.start(config(listOf(channel(0, intervalMs = 10_000L))), now)

        // Пауза на 3-й секунде: остаток до звука 7 с.
        session.pause(now + 3_000L)
        assertEquals(TimerState.PAUSED, session.state)
        assertFalse(session.isRunning)
        assertTrue(session.isActive)

        // Во время паузы тики не срабатывают.
        val fired = mutableListOf<Int>()
        session.tick(now + 50_000L) { fired += it.id }
        assertTrue(fired.isEmpty())

        // Резюме в момент T=10 с: звук должен наступить через оставшиеся 7 с.
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
        session.start(config(listOf(channel(0))), 0L)
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
        session.start(config(listOf(channel(0))), 0L)
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
        session.start(config(listOf(channel(0, intervalMs = 10_000L)), autoStopMs = 30_000L), now)

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
        session.start(config(listOf(channel(0)), autoStopMs = 30_000L), now)

        assertEquals(30_000L, session.countdownToAutoStopMs(now))
        assertEquals(17_500L, session.countdownToAutoStopMs(now + 12_500L))
        assertEquals(0L, session.countdownToAutoStopMs(now + 99_000L))
    }

    @Test
    fun noAutoStopWithoutLimit() {
        val session = TimerSession()
        session.start(config(listOf(channel(0)), autoStopMs = 0L), 0L)
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
                    channel(0, intervalMs = 30_000L),
                    channel(1, intervalMs = 10_000L),
                )
            ),
            now,
        )
        val desc = session.nextSoundDescription(now + 3_000L)
        assertNotNull(desc)
        assertTrue("ожидается «Канал 2» в описании: $desc", desc.contains("Канал 2"))
        assertTrue(desc.contains("через"))
    }

    @Test
    fun nextFireForChannel() {
        val session = TimerSession()
        val now = 0L
        session.start(config(listOf(channel(0, intervalMs = 10_000L))), now)

        assertEquals(10_000L, session.nextFireForChannel(0, now))
        assertEquals(2_000L, session.nextFireForChannel(0, now + 8_000L))
        assertEquals(0L, session.nextFireForChannel(0, now + 20_000L))
        assertNull(session.nextFireForChannel(3, now))
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
}