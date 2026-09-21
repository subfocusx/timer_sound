package com.timersound.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerConfigTest {

    private fun channel(
        id: Int = 0,
        mode: SceneMode = SceneMode.REPEAT,
        startMinutes: Int? = null,
        endMinutes: Int? = null,
        launchCount: Int = 0,
        enabled: Boolean = true,
        fileUri: String = "file:///tmp/ch$id.mp3",
    ): ChannelConfig = ChannelConfig(
        id = id,
        name = "Канал ${id + 1}",
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
        val ch = channel()

        assertEquals(SceneMode.REPEAT, ch.mode)
        assertNull(ch.startMinutes)
        assertNull(ch.endMinutes)
        assertEquals(0, ch.launchCount)
        assertTrue(ch.scheduleValid)
    }

    @Test
    fun invalidScheduleChannelsFindsEnabledChannelsWithFiles() {
        val cfg = TimerConfig(
            channels = listOf(
                channel(mode = SceneMode.ONCE_TIME),
                channel(id = 1, mode = SceneMode.INTERVAL, startMinutes = 600),
                channel(id = 2, mode = SceneMode.RANDOM, startMinutes = 600, endMinutes = 630, launchCount = 0),
                channel(id = 3, mode = SceneMode.RANDOM, startMinutes = 630, endMinutes = 600, launchCount = 1),
                channel(id = 4, mode = SceneMode.RANDOM, startMinutes = 1400, endMinutes = 1500, launchCount = 1),
            ),
            autoStopMs = 0L,
        )

        assertEquals(listOf(0, 1, 2, 3, 4), cfg.invalidScheduleChannels().map { it.id })
    }

    @Test
    fun validFiniteSchedulesAreAccepted() {
        val cfg = TimerConfig(
            channels = listOf(
                channel(mode = SceneMode.ONCE_TIME, startMinutes = 600),
                channel(id = 1, mode = SceneMode.INTERVAL, startMinutes = 600, launchCount = 1),
                channel(id = 2, mode = SceneMode.RANDOM, startMinutes = 600, endMinutes = 630, launchCount = 5),
            ),
            autoStopMs = 0L,
        )

        assertTrue(cfg.invalidScheduleChannels().isEmpty())
    }

    @Test
    fun disabledAndMissingFileChannelsDoNotBlockStart() {
        val cfg = TimerConfig(
            channels = listOf(
                channel(mode = SceneMode.ONCE_TIME, enabled = false),
                channel(id = 1, mode = SceneMode.ONCE_TIME, fileUri = ""),
            ),
            autoStopMs = 0L,
        )

        assertTrue(cfg.invalidScheduleChannels().isEmpty())
        assertEquals(1, cfg.missingFileChannels().size)
        assertTrue(cfg.playableChannels().isEmpty())
    }
}
