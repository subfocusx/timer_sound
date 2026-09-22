package com.timersound.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerConfigTest {

    private fun alarm(
        id: Int = 0,
        mode: SceneMode = SceneMode.REPEAT,
        startMinutes: Int? = null,
        endMinutes: Int? = null,
        launchCount: Int = 0,
        enabled: Boolean = true,
        fileUri: String = "file:///tmp/alarm$id.mp3",
    ): AlarmConfig = AlarmConfig(
        id = id,
        name = "Будильник ${id + 1}",
        fileUri = fileUri,
        mode = mode,
        intervalMs = Defaults.MIN_INTERVAL_MS,
        startMinutes = startMinutes,
        endMinutes = endMinutes,
        launchCount = launchCount,
        volumePercent = Defaults.DEFAULT_VOLUME_PERCENT,
        enabled = enabled,
    )

    @Test
    fun repeatDefaultsKeepLegacyBehavior() {
        val alarm = alarm()

        assertEquals(SceneMode.REPEAT, alarm.mode)
        assertNull(alarm.startMinutes)
        assertNull(alarm.endMinutes)
        assertEquals(0, alarm.launchCount)
        assertTrue(alarm.scheduleValid)
    }

    @Test
    fun invalidScheduleAlarmsFindsEnabledAlarmsWithFiles() {
        val cfg = TimerConfig(
            alarms = listOf(
                alarm(mode = SceneMode.ONCE_TIME),
                alarm(id = 1, mode = SceneMode.INTERVAL, startMinutes = 600),
                alarm(id = 2, mode = SceneMode.RANDOM, startMinutes = 600, endMinutes = 630, launchCount = 0),
                alarm(id = 3, mode = SceneMode.RANDOM, startMinutes = 630, endMinutes = 600, launchCount = 1),
                alarm(id = 4, mode = SceneMode.RANDOM, startMinutes = 1400, endMinutes = 1500, launchCount = 1),
            ),
            autoStopMs = 0L,
        )

        assertEquals(listOf(0, 1, 2, 3, 4), cfg.invalidScheduleAlarms().map { it.id })
    }

    @Test
    fun validFiniteSchedulesAreAccepted() {
        val cfg = TimerConfig(
            alarms = listOf(
                alarm(mode = SceneMode.ONCE_TIME, startMinutes = 600),
                alarm(id = 1, mode = SceneMode.INTERVAL, startMinutes = 600, launchCount = 1),
                alarm(id = 2, mode = SceneMode.RANDOM, startMinutes = 600, endMinutes = 630, launchCount = 5),
            ),
            autoStopMs = 0L,
        )

        assertTrue(cfg.invalidScheduleAlarms().isEmpty())
    }

    @Test
    fun disabledAndMissingFileAlarmsDoNotBlockStart() {
        val cfg = TimerConfig(
            alarms = listOf(
                alarm(mode = SceneMode.ONCE_TIME, enabled = false),
                alarm(id = 1, mode = SceneMode.ONCE_TIME, fileUri = ""),
            ),
            autoStopMs = 0L,
        )

        assertTrue(cfg.invalidScheduleAlarms().isEmpty())
        assertEquals(1, cfg.missingFileAlarms().size)
        assertTrue(cfg.playableAlarms().isEmpty())
    }
}
