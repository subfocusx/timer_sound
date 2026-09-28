package com.timersound.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.timersound.model.Defaults
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Миграция v2 → v3: плоский конфиг становится группой «Основная». */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupsMigrationTest {

    private lateinit var context: Context
    private lateinit var repo: PreferencesRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repo = PreferencesRepository(context)
    }

    @Test
    fun v2toV3_movesFlatConfigIntoMainGroup() = runTest {
        context.dataStore.edit {
            it.clear()
            it[intPreferencesKey("schema_version")] = 2
            it[stringPreferencesKey("alarms_json")] = alarmsJson.encodeToString(
                AlarmListDto.serializer(),
                AlarmListDto(
                    listOf(
                        Defaults.newAlarm(0, 0).copy(fileUri = Defaults.BUILT_IN_BEEP, enabled = true).toDto(),
                        Defaults.newAlarm(1, 1).toDto(),
                    ),
                ),
            )
            it[intPreferencesKey("next_alarm_id")] = 2
            it[longPreferencesKey("auto_stop_ms")] = 60_000L
            it[intPreferencesKey("max_total_fires")] = 5
        }

        repo.ensureMigrated()

        val raw = context.dataStore.data.first()
        assertEquals(3, raw[intPreferencesKey("schema_version")])
        assertNull(raw[stringPreferencesKey("alarms_json")])
        assertNull(raw[longPreferencesKey("auto_stop_ms")])
        assertNull(raw[intPreferencesKey("max_total_fires")])

        val cfg = repo.appConfig.first()
        assertEquals(1, cfg.groups.size)
        val main = cfg.groups[0]
        assertEquals(0, main.id)
        assertEquals("Основная", main.name)
        assertTrue(main.enabled)
        assertEquals(0, main.weekdays)
        assertEquals(2, main.alarms.size)
        assertEquals(60_000L, main.autoStopMs)
        assertEquals(5, main.maxTotalFiresPerSession)
        assertEquals(1, cfg.nextGroupId)
    }

    @Test
    fun v2toV3_isIdempotent() = runTest {
        context.dataStore.edit {
            it.clear()
            it[intPreferencesKey("schema_version")] = 2
            it[stringPreferencesKey("alarms_json")] = alarmsJson.encodeToString(
                AlarmListDto.serializer(),
                AlarmListDto(listOf(Defaults.newAlarm(0, 0).toDto())),
            )
        }
        repo.ensureMigrated()
        val first = repo.appConfig.first()
        repo.ensureMigrated()
        assertEquals(first, repo.appConfig.first())
        assertEquals(3, context.dataStore.data.first()[intPreferencesKey("schema_version")])
    }

    @Test
    fun v2toV3_brokenJsonFallsBackToDefaults() = runTest {
        context.dataStore.edit {
            it.clear()
            it[intPreferencesKey("schema_version")] = 2
            it[stringPreferencesKey("alarms_json")] = "{broken"
        }
        repo.ensureMigrated()
        val cfg = repo.appConfig.first()
        assertEquals(1, cfg.groups.size)
        assertEquals("Основная", cfg.groups[0].name)
        assertEquals(Defaults.firstRunConfig().alarms.size, cfg.groups[0].alarms.size)
    }
}
