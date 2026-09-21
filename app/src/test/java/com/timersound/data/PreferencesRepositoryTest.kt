package com.timersound.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import com.timersound.model.Defaults
import com.timersound.model.SceneMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PreferencesRepositoryTest {

    private fun mutablePreferences(): MutablePreferences {
        val emptyPairs = emptyArray<androidx.datastore.preferences.core.Preferences.Pair<*>>()
        val factory = Class.forName("androidx.datastore.preferences.core.PreferencesFactory")
        val createMutable = factory.getMethod("createMutable", emptyPairs.javaClass)
        @Suppress("UNCHECKED_CAST")
        return createMutable.invoke(null, emptyPairs) as MutablePreferences
    }

    @Test
    fun legacyChannelFieldsMigrateToRepeatDefaults() {
        val prefs = mutablePreferences().apply {
            this[stringPreferencesKey("ch1_uri")] = "file:///legacy.mp3"
            this[longPreferencesKey("ch1_interval")] = 42_000L
            this[booleanPreferencesKey("ch1_enabled")] = true
        }

        val channel = readChannelConfig(prefs, 1)

        assertEquals("file:///legacy.mp3", channel.fileUri)
        assertEquals(42_000L, channel.intervalMs)
        assertEquals(true, channel.enabled)
        assertEquals(SceneMode.REPEAT, channel.mode)
        assertNull(channel.startMinutes)
        assertNull(channel.endMinutes)
        assertEquals(0, channel.launchCount)
    }

    @Test
    fun sceneFieldsRoundTripAndChannelCountIsPersisted() {
        val source = Defaults.defaultChannel(2).copy(
            fileName = "interval.ogg",
            mode = SceneMode.RANDOM,
            intervalMs = 15_000L,
            startMinutes = 600,
            endMinutes = 630,
            launchCount = 5,
            volumePercent = 42,
            enabled = true,
        )
        val prefs = mutablePreferences()

        prefs.writeChannelConfig(source)
        prefs.setChannelCount(3)

        assertEquals(3, channelCountFrom(prefs))
        assertEquals(source, readChannelConfig(prefs, 2))
    }

    @Test
    fun absentChannelCountKeepsFiveLegacyChannels() {
        assertEquals(Defaults.CHANNEL_COUNT, channelCountFrom(mutablePreferences()))
    }

    @Test
    fun invalidStoredModeFallsBackToRepeat() {
        val prefs = mutablePreferences().apply {
            this[stringPreferencesKey("ch0_mode")] = "UNKNOWN"
        }

        assertEquals(SceneMode.REPEAT, readChannelConfig(prefs, 0).mode)
    }

    @Test
    fun negativeStoredLaunchCountRemainsInvalid() {
        val prefs = mutablePreferences().apply {
            this[stringPreferencesKey("ch0_mode")] = SceneMode.INTERVAL.name
            this[longPreferencesKey("ch0_start_minutes")] = 600L
            this[longPreferencesKey("ch0_launch_count")] = -1L
        }

        val channel = readChannelConfig(prefs, 0)

        assertEquals(-1, channel.launchCount)
        assertFalse(channel.scheduleValid)
    }

    @Test
    fun negativeStoredChannelCountMeansNoChannels() {
        val prefs = mutablePreferences().apply {
            this[intPreferencesKey("channel_count")] = -3
        }

        assertEquals(0, channelCountFrom(prefs))
    }
}
