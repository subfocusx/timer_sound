package com.timersound.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.timersound.model.Defaults
import com.timersound.model.SceneMode
import com.timersound.model.TimerConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Интеграционные тесты хранилища на РЕАЛЬНОМ DataStore (Robolectric), а не на копии логики:
 * покрывают `ensureMigrated()` (v1 → v2), чтение legacy-ключей в [PreferencesRepository.config],
 * фолбэк на firstRunConfig при битом JSON, round-trip `save()/config` и метку A4
 * `session_active`.
 *
 * Каждый тест сам записывает нужное исходное состояние (включая `schema_version`),
 * поэтому порядок тестов и кэш синглтона DataStore на результат не влияют.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferencesRepositoryMigrationTest {

    private lateinit var context: Context
    private lateinit var repo: PreferencesRepository

    private val channelCountKey = intPreferencesKey("channel_count")
    private val schemaVersionKey = intPreferencesKey("schema_version")
    private val nextAlarmIdKey = intPreferencesKey("next_alarm_id")
    private val alarmsJsonKey = stringPreferencesKey("alarms_json")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repo = PreferencesRepository(context)
    }

    // ------------------------------------------------------------------ v1 -> v2

    @Test
    fun migrationCreatesAlarmsFromLegacyKeys() = runTest {
        context.dataStore.edit { it.seedLegacyV1(channels = 3) }

        repo.ensureMigrated()

        val config = repo.config.first()
        assertEquals(3, config.alarms.size)

        // Канал 0 — встроенный бип, включён.
        assertEquals(Defaults.BUILT_IN_BEEP, config.alarms[0].fileUri)
        assertTrue(config.alarms[0].enabled)

        // Канал 1 — RANDOM со всеми параметрами.
        assertEquals(1, config.alarms[1].id)
        assertEquals("file:///alarm1.mp3", config.alarms[1].fileUri)
        assertEquals("alarm1.mp3", config.alarms[1].fileName)
        assertEquals(SceneMode.RANDOM, config.alarms[1].mode)
        assertEquals(30_000L, config.alarms[1].intervalMs)
        assertEquals(600, config.alarms[1].startMinutes)
        assertEquals(630, config.alarms[1].endMinutes)
        assertEquals(3, config.alarms[1].launchCount)
        assertEquals(55, config.alarms[1].volumePercent)

        // Канал 2 — выключен, ONCE_TIME.
        assertEquals(SceneMode.ONCE_TIME, config.alarms[2].mode)
        assertFalse(config.alarms[2].enabled)
        assertEquals(900, config.alarms[2].startMinutes)
        assertEquals(42, config.alarms[2].volumePercent)
    }

    @Test
    fun migrationWritesSchemaVersionAndNextAlarmId() = runTest {
        context.dataStore.edit { it.seedLegacyV1(channels = 3) }

        repo.ensureMigrated()

        val raw = context.dataStore.data.first()
        assertEquals(2, raw[schemaVersionKey])
        assertEquals(3, raw[nextAlarmIdKey])
        assertTrue(raw[alarmsJsonKey]?.isNotEmpty() == true)
    }

    @Test
    fun migrationRemovesLegacyKeys() = runTest {
        context.dataStore.edit { it.seedLegacyV1(channels = 3) }

        repo.ensureMigrated()

        val raw = context.dataStore.data.first()
        assertNull(raw[channelCountKey])
        assertNull(raw[stringPreferencesKey("ch0_uri")])
        assertNull(raw[booleanPreferencesKey("ch1_enabled")])
        assertNull(raw[stringPreferencesKey("ch1_mode")])
        assertNull(raw[longPreferencesKey("ch1_launch_count")])
        assertNull(raw[intPreferencesKey("ch2_volume")])
        assertNull(raw[longPreferencesKey("ch2_start_minutes")])
    }

    @Test
    fun migrationIsIdempotentAndDoesNotOverwriteLaterEdits() = runTest {
        context.dataStore.edit { it.seedLegacyV1(channels = 3) }
        repo.ensureMigrated()

        // Пользователь изменил конфиг после миграции.
        val edited = repo.config.first().let { it.copy(alarms = it.alarms.take(1), autoStopMs = 12_345L) }
        repo.save(edited)

        repo.ensureMigrated() // повторный вызов не должен ничего перезаписать

        assertEquals(edited, repo.config.first())
        assertEquals(2, context.dataStore.data.first()[schemaVersionKey])
    }

    @Test
    fun migrationOfEmptyPrefsCreatesFiveLegacyDefaults() = runTest {
        context.dataStore.edit { it.clear() }

        repo.ensureMigrated()

        val config = repo.config.first()
        assertEquals(5, config.alarms.size)
        assertEquals(listOf(0, 1, 2, 3, 4), config.alarms.map { it.id })
        assertEquals(Defaults.BUILT_IN_BEEP, config.alarms[0].fileUri)
        assertTrue(config.alarms[0].enabled)
        assertTrue(config.alarms.drop(1).all { it.fileUri.isEmpty() && !it.enabled })
        assertEquals(5, context.dataStore.data.first()[nextAlarmIdKey])
    }

    @Test
    fun migrationWithChannelCountZeroProducesEmptyList() = runTest {
        context.dataStore.edit { prefs ->
            prefs.clear()
            prefs[channelCountKey] = 0
        }

        repo.ensureMigrated()

        assertTrue(repo.config.first().alarms.isEmpty())
    }

    @Test
    fun legacyValuesAreSanitisedDuringMigration() = runTest {
        context.dataStore.edit { prefs ->
            prefs.seedLegacyV1(channels = 2)
            prefs[stringPreferencesKey("ch1_mode")] = "NOT_A_MODE"
            prefs[longPreferencesKey("ch1_interval")] = 200L // ниже MIN_INTERVAL_MS
            prefs[intPreferencesKey("ch1_volume")] = 500   // вне 0..100
        }

        repo.ensureMigrated()

        val alarm = repo.config.first().alarms[1]
        assertEquals(SceneMode.REPEAT, alarm.mode)
        assertEquals(Defaults.MIN_INTERVAL_MS, alarm.intervalMs)
        assertEquals(100, alarm.volumePercent)
    }

    // ------------------------------------------------------------------ config flow

    @Test
    fun configReadsLegacyKeysBeforeMigration() = runTest {
        context.dataStore.edit { it.seedLegacyV1(channels = 3) }

        val config = repo.config.first() // ensureMigrated НЕ вызывался

        assertEquals(3, config.alarms.size)
        assertEquals("file:///alarm1.mp3", config.alarms[1].fileUri)
        assertEquals(SceneMode.RANDOM, config.alarms[1].mode)
        // Чтение не должно выполнять миграцию само по себе: версия остаётся v1.
        assertEquals(1, context.dataStore.data.first()[schemaVersionKey])
    }

    @Test
    fun corruptAlarmsJsonFallsBackToFirstRunConfig() = runTest {
        context.dataStore.edit { prefs ->
            prefs.clear()
            prefs[channelCountKey] = 3 // legacy-мусор не должен использоваться при version >= 2
            prefs[schemaVersionKey] = 2
            prefs[alarmsJsonKey] = "{not a json"
        }

        assertEquals(Defaults.firstRunConfig().alarms, repo.config.first().alarms)
    }

    @Test
    fun saveAndLoadRoundTripKeepsAllSettings() = runTest {
        context.dataStore.edit { it.clear() }
        repo.ensureMigrated()

        val config = TimerConfig(
            alarms = listOf(
                Defaults.newAlarm(0, 0),
                Defaults.newAlarm(1, 1).copy(fileUri = "content://media/1.mp3", fileName = "1.mp3", enabled = true),
            ),
            autoStopMs = 90_000L,
            maxTotalFiresPerSession = 7,
            fadeInEnabled = true,
        )

        repo.save(config)

        assertEquals(config, repo.config.first())
    }

    @Test
    fun notificationPermissionAskedFlagRoundTrip() = runTest {
        context.dataStore.edit { it.clear() }

        // Пока не спрашивали — можно показать системный запрос.
        assertFalse(repo.wasNotificationPermissionAsked())
        repo.setNotificationPermissionAsked()
        // Флаг переживает перезапуск приложения: читаем новым экземпляром репозитория.
        assertTrue(PreferencesRepository(context).wasNotificationPermissionAsked())
    }

    @Test
    fun sessionActiveFlagRoundTrip() = runTest {
        context.dataStore.edit { it.clear() }

        assertFalse(repo.wasSessionActive())
        repo.setSessionActive(true)
        assertTrue(repo.wasSessionActive())
        repo.setSessionActive(false)
        assertFalse(repo.wasSessionActive())
    }

    // ------------------------------------------------------------------ helpers

    /** Засевает legacy-состояние v1 теми же ключами/типами, что читает продакшн-код. */
    private fun MutablePreferences.seedLegacyV1(channels: Int) {
        clear()
        this[channelCountKey] = channels
        this[schemaVersionKey] = 1
        if (channels > 0) {
            this[booleanPreferencesKey("ch0_enabled")] = true
            this[stringPreferencesKey("ch0_uri")] = Defaults.BUILT_IN_BEEP
            this[longPreferencesKey("ch0_interval")] = 300_000L
            this[intPreferencesKey("ch0_volume")] = 80
        }
        if (channels > 1) {
            this[booleanPreferencesKey("ch1_enabled")] = true
            this[stringPreferencesKey("ch1_uri")] = "file:///alarm1.mp3"
            this[stringPreferencesKey("ch1_file_name")] = "alarm1.mp3"
            this[stringPreferencesKey("ch1_mode")] = SceneMode.RANDOM.name
            this[longPreferencesKey("ch1_interval")] = 30_000L
            this[longPreferencesKey("ch1_start_minutes")] = 600L
            this[longPreferencesKey("ch1_end_minutes")] = 630L
            this[longPreferencesKey("ch1_launch_count")] = 3L
            this[intPreferencesKey("ch1_volume")] = 55
        }
        if (channels > 2) {
            this[booleanPreferencesKey("ch2_enabled")] = false
            this[stringPreferencesKey("ch2_uri")] = "file:///alarm2.mp3"
            this[stringPreferencesKey("ch2_mode")] = SceneMode.ONCE_TIME.name
            this[longPreferencesKey("ch2_start_minutes")] = 900L
            this[intPreferencesKey("ch2_volume")] = 42
        }
    }
}
