package com.timersound.data

import com.timersound.model.Defaults
import com.timersound.model.SceneMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты чистого JSON-кодека и дефолтов (без Android Context).
 *
 * Миграция legacy v1 → v2 здесь НЕ тестируется: она работает с реальным DataStore
 * и покрыта [PreferencesRepositoryMigrationTest]. Раньше миграция проверялась
 * через копию продакшн-логики (`PreferencesRepositoryTestHelper`), из-за чего
 * `ensureMigrated()` не исполнялся ни одним тестом — копия удалена.
 */
class PreferencesRepositoryTest {

    // ------------------------------------------------------------------ JSON codec

    @Test
    fun jsonRoundTripSingleAlarm() {
        val original = Defaults.newAlarm(0, 0)
        val json = alarmsJson.encodeToString(AlarmListDto.serializer(), AlarmListDto(listOf(original.toDto())))
        val decoded = alarmsJson.decodeFromString(AlarmListDto.serializer(), json)
        assertEquals(1, decoded.alarms.size)
        assertEquals(original.toDto(), decoded.alarms[0])
    }

    @Test
    fun jsonRoundTrip100Alarms() {
        val alarms = (0 until 100).map { id -> Defaults.newAlarm(id, id) }
        val original = AlarmListDto(alarms.map { it.toDto() })
        val json = alarmsJson.encodeToString(AlarmListDto.serializer(), original)
        val decoded = alarmsJson.decodeFromString(AlarmListDto.serializer(), json)
        assertEquals(100, decoded.alarms.size)
        assertEquals(original, decoded)
    }

    @Test
    fun jsonIgnoresUnknownKeys() {
        val json = """{"alarms":[{"id":0,"name":"Test","fileUri":"","mode":"REPEAT","intervalMs":300000,"volumePercent":80,"enabled":false,"UNKNOWN_KEY":"value"}],"extra":"data"}"""
        val decoded = alarmsJson.decodeFromString(AlarmListDto.serializer(), json)
        assertEquals(1, decoded.alarms.size)
        assertEquals(0, decoded.alarms[0].id)
        assertEquals("Test", decoded.alarms[0].name)
        // Явно заданные поля читаются как есть: UNKNOWN_KEY не ломает разбор.
        assertEquals(80, decoded.alarms[0].volumePercent)
        assertEquals(300_000L, decoded.alarms[0].intervalMs)
        assertFalse(decoded.alarms[0].enabled)
    }

    @Test
    fun jsonUsesDefaultsForAbsentOptionalFields() {
        val json = """{"alarms":[{"id":7,"name":"Only required","fileUri":"","intervalMs":1000,"volumePercent":50,"enabled":true}]}"""
        val decoded = alarmsJson.decodeFromString(AlarmListDto.serializer(), json)
        val alarm = decoded.alarms.single()
        assertEquals(SceneMode.REPEAT, alarm.mode)
        assertNull(alarm.fileName)
        assertNull(alarm.startMinutes)
        assertNull(alarm.endMinutes)
        assertEquals(0, alarm.launchCount)
    }

    // ------------------------------------------------------------------ defaults

    @Test
    fun newAlarmFirstIsBuiltInBeep() {
        val alarm = Defaults.newAlarm(0, 0)
        assertEquals(0, alarm.id)
        assertEquals(Defaults.BUILT_IN_BEEP, alarm.fileUri)
        assertTrue(alarm.enabled)
        assertEquals(SceneMode.REPEAT, alarm.mode)
        assertEquals(Defaults.DEFAULT_INTERVAL_MS, alarm.intervalMs)
        assertEquals(Defaults.DEFAULT_VOLUME_PERCENT, alarm.volumePercent)
    }

    @Test
    fun newAlarmOthersAreEmptyDisabled() {
        val alarm = Defaults.newAlarm(3, 4)
        assertEquals(3, alarm.id)
        assertEquals("", alarm.fileUri)
        assertFalse(alarm.enabled)
    }
}
