package com.timersound.timer

import com.timersound.model.AlarmConfig
import com.timersound.model.SceneMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SceneSchedulerTest {

    @Before
    fun useUtcForDeterministicWallClockTests() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @Test
    fun offsetConvertsWallTimeToElapsedTime() {
        assertEquals(123_456L, SceneScheduler.offset(223_456L, 100_000L))
    }

    @Test
    fun localMidnightWallUsesLocalDayBoundary() {
        val nowWall = epochUtc(2026, 9, 21, 23, 50, 0)

        assertEquals(epochUtc(2026, 9, 21, 0, 0, 0), SceneScheduler.localMidnightWall(nowWall))
    }

    @Test
    fun nextClockElapsedUsesTodayWhenTargetIsFuture() {
        val nowElapsed = 5_000_000L
        val nowWall = epochUtc(2026, 9, 21, 10, 0, 0)

        assertEquals(
            nowElapsed + 30 * 60_000L,
            SceneScheduler.nextClockElapsed(nowElapsed, nowWall, 10 * 60 + 30),
        )
    }

    @Test
    fun nextClockElapsedMovesToTomorrowWhenTargetPassed() {
        val nowElapsed = 5_000_000L
        val nowWall = epochUtc(2026, 9, 21, 23, 50, 0)

        assertEquals(
            nowElapsed + 15 * 60_000L,
            SceneScheduler.nextClockElapsed(nowElapsed, nowWall, 5),
        )
    }

    @Test
    fun initialRepeatFireIsImmediateWithoutStartTime() {
        assertEquals(7_000L, SceneScheduler.initialRepeatFire(7_000L, 0L, null))
    }

    @Test
    fun onceTimeProducesSingleFutureClockEvent() {
        val nowElapsed = 1_000_000L
        val nowWall = epochUtc(2026, 9, 21, 11, 0, 0)
        val alarm = alarm(
            mode = SceneMode.ONCE_TIME,
            startMinutes = 11 * 60 + 45,
        )

        assertEquals(
            nowElapsed + 45 * 60_000L,
            SceneScheduler.fireTimesFor(alarm, nowElapsed, nowWall).single(),
        )
    }

    @Test
    fun intervalProducesLaunchCountEventsAtClampedStep() {
        val nowElapsed = 500_000L
        val nowWall = epochUtc(2026, 9, 21, 10, 0, 0)
        val alarm = alarm(
            mode = SceneMode.INTERVAL,
            startMinutes = 10 * 60 + 10,
            intervalMs = 100L,
            launchCount = 3,
        )

        val times = SceneScheduler.fireTimesFor(alarm, nowElapsed, nowWall)
        val first = nowElapsed + 10 * 60_000L

        assertEquals(listOf(first, first + 1_000L, first + 2_000L), times)
    }

    @Test
    fun repeatWithStartTimeUsesClockThenReturnsNoFiniteList() {
        val nowElapsed = 0L
        val nowWall = epochUtc(2026, 9, 21, 12, 0, 0)
        val alarm = alarm(
            mode = SceneMode.REPEAT,
            startMinutes = 12 * 60 + 5,
            intervalMs = 60_000L,
        )

        assertEquals(nowElapsed + 5 * 60_000L, SceneScheduler.initialRepeatFire(nowElapsed, nowWall, alarm.startMinutes))
        assertTrue(SceneScheduler.fireTimesFor(alarm, nowElapsed, nowWall).isEmpty())
    }

    @Test
    fun randomProducesDistinctMinutesInsideWindowAndIsDeterministic() {
        val nowElapsed = 0L
        val nowWall = epochUtc(2026, 9, 21, 9, 0, 0)
        val alarm = alarm(
            mode = SceneMode.RANDOM,
            startMinutes = 600,
            endMinutes = 630,
            launchCount = 5,
        )

        val first = SceneScheduler.fireTimesFor(alarm, nowElapsed, nowWall)
        val second = SceneScheduler.fireTimesFor(alarm, nowElapsed, nowWall)
        val base = SceneScheduler.localMidnightWall(nowWall) + SceneScheduler.offset(nowElapsed, nowWall)
        val windowStart = base + 600 * 60_000L
        val windowEnd = base + 630 * 60_000L

        assertEquals(first, second)
        assertEquals(5, first.size)
        assertEquals(first.toSet().size, first.size)
        first.forEach { assertTrue(it >= windowStart && it < windowEnd) }
    }

    @Test
    fun randomMinutesAreDistinct() {
        val minutes = SceneScheduler.randomMinutes(30, 5, seed = 42L)

        assertEquals(5, minutes.size)
        assertEquals(minutes.toSet().size, minutes.size)
        minutes.forEach { assertTrue(it in 0 until 30) }
    }

    private fun alarm(
        mode: SceneMode,
        startMinutes: Int? = null,
        endMinutes: Int? = null,
        intervalMs: Long = 60_000L,
        launchCount: Int = 0,
    ): AlarmConfig = AlarmConfig(
        id = 1,
        name = "Сценарий",
        fileUri = "file:///tmp/beep.mp3",
        mode = mode,
        intervalMs = intervalMs,
        startMinutes = startMinutes,
        endMinutes = endMinutes,
        launchCount = launchCount,
        volumePercent = 80,
        enabled = true,
    )

    private fun epochUtc(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, second)
        return calendar.timeInMillis
    }
}
