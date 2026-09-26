package com.timersound.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.timersound.model.AlarmConfig
import com.timersound.model.Defaults
import com.timersound.model.SceneMode
import com.timersound.model.TimerConfig

private val Context.dataStore by preferencesDataStore(
    name = "timer_sound",
    // Битый файл настроек (обрыв записи, повреждённые байты) не должен ронять приложение
    // при старте: подменяем его пустыми настройками, дальше срабатывает обычный фолбэк на дефолты.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

private val alarmsJsonKey = stringPreferencesKey("alarms_json")
private val schemaVersionKey = intPreferencesKey("schema_version")
private val nextAlarmIdKey = intPreferencesKey("next_alarm_id")
private val autoStopKey = longPreferencesKey("auto_stop_ms")

/**
 * Локальное хранилище настроек (DataStore + JSON).
 * Единственный источник истины для конфигурации будильников — список в `alarms_json`.
 * Миграция legacy-формата (ch{i}_* ключи) выполняется один раз через [ensureMigrated].
 */
class PreferencesRepository(private val context: Context) {

    val config: Flow<TimerConfig> = context.dataStore.data.map { p ->
        val version = p[schemaVersionKey] ?: 1
        val alarms = if (version >= 2) {
            p[alarmsJsonKey]?.let { jsonStr ->
                runCatching {
                    alarmsJson.decodeFromString(AlarmListDto.serializer(), jsonStr)
                        .alarms.map { it.toAlarmConfig() }
                }.getOrNull()
            } ?: Defaults.firstRunConfig().alarms
        } else {
            // До миграции: читаем legacy-ключи напрямую.
            (0 until (p[channelCountKey] ?: 5)).map { id -> readLegacyAlarm(p, id) }
        }
        TimerConfig(
            alarms = alarms,
            autoStopMs = (p[autoStopKey] ?: 0L).coerceAtLeast(0L),
        )
    }

    suspend fun save(config: TimerConfig) {
        context.dataStore.edit { p ->
            p[alarmsJsonKey] = alarmsJson.encodeToString(
                AlarmListDto.serializer(),
                AlarmListDto(config.alarms.map { it.toDto() }),
            )
            p[autoStopKey] = config.autoStopMs
        }
    }

    /**
     * Идемпотентная миграция v1 → v2.
     * Вызывается один раз из [TimerViewModel.init] до подписки на [config].
     *
     * Правила безопасности:
     * - schema_version >= 2 → выход (идемпотентность)
     * - legacy-ключи удаляются В ТОЙ ЖЕ транзакции, что и alarms_json
     * - битый/нечитаемый alarms_json → логирование + firstRunConfig()
     */
    suspend fun ensureMigrated() {
        val current = context.dataStore.data.first()
        if ((current[schemaVersionKey] ?: 1) >= 2) return

        val legacyAlarms = (0 until (current[channelCountKey] ?: 5)).map { id ->
            readLegacyAlarm(current, id).toDto()
        }
        val nextId = legacyAlarms.maxOfOrNull { it.id }?.let { it + 1 } ?: legacyAlarms.size

        context.dataStore.edit { edit ->
            edit[alarmsJsonKey] = alarmsJson.encodeToString(
                AlarmListDto.serializer(),
                AlarmListDto(legacyAlarms),
            )
            edit[nextAlarmIdKey] = nextId
            edit[schemaVersionKey] = 2
            // Удаляем все legacy-ключи в той же транзакции.
            (0 until 5).forEach { i ->
                edit.remove(legacyUriKey(i))
                edit.remove(legacyFileNameKey(i))
                edit.remove(legacyIntervalKey(i))
                edit.remove(legacyVolumeKey(i))
                edit.remove(legacyEnabledKey(i))
                edit.remove(legacyModeKey(i))
                edit.remove(legacyStartMinutesKey(i))
                edit.remove(legacyEndMinutesKey(i))
                edit.remove(legacyLaunchCountKey(i))
            }
            edit.remove(channelCountKey)
        }
    }

    // ------------------------------------------------------------------ legacy helpers

    private fun readLegacyAlarm(p: Preferences, id: Int): AlarmConfig {
        val default = Defaults.newAlarm(id, id)
        val mode = p[legacyModeKey(id)]
            ?.let { runCatching { SceneMode.valueOf(it) }.getOrNull() }
            ?: SceneMode.REPEAT
        val startMinutes = p[legacyStartMinutesKey(id)]
            ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
            ?.toInt()
        val endMinutes = p[legacyEndMinutesKey(id)]
            ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
            ?.toInt()
        val launchCount = p[legacyLaunchCountKey(id)]
            ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
            ?.toInt()
            ?: 0

        return AlarmConfig(
            id = id,
            name = default.name,
            fileUri = p[legacyUriKey(id)] ?: default.fileUri,
            fileName = p[legacyFileNameKey(id)]?.takeIf { it.isNotEmpty() },
            mode = mode,
            intervalMs = (p[legacyIntervalKey(id)] ?: default.intervalMs).coerceAtLeast(Defaults.MIN_INTERVAL_MS),
            startMinutes = startMinutes,
            endMinutes = endMinutes,
            launchCount = launchCount,
            volumePercent = (p[legacyVolumeKey(id)] ?: default.volumePercent).coerceIn(0, 100),
            enabled = p[legacyEnabledKey(id)] ?: default.enabled,
        )
    }
}

// ------------------------------------------------------------------ legacy key helpers

internal val channelCountKey = intPreferencesKey("channel_count")

private fun legacyUriKey(id: Int) = stringPreferencesKey("ch${id}_uri")
private fun legacyFileNameKey(id: Int) = stringPreferencesKey("ch${id}_file_name")
private fun legacyIntervalKey(id: Int) = longPreferencesKey("ch${id}_interval")
private fun legacyVolumeKey(id: Int) = intPreferencesKey("ch${id}_volume")
private fun legacyEnabledKey(id: Int) = booleanPreferencesKey("ch${id}_enabled")
private fun legacyModeKey(id: Int) = stringPreferencesKey("ch${id}_mode")
private fun legacyStartMinutesKey(id: Int) = longPreferencesKey("ch${id}_start_minutes")
private fun legacyEndMinutesKey(id: Int) = longPreferencesKey("ch${id}_end_minutes")
private fun legacyLaunchCountKey(id: Int) = longPreferencesKey("ch${id}_launch_count")
