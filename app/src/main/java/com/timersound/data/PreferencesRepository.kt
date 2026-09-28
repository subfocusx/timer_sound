package com.timersound.data

import android.content.Context
import com.timersound.AppLog
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

// internal (не private): тесты миграции должны иметь возможность засеять legacy-ключи
// в тот же DataStore, что читает продакшн-код. Поведение не меняется.
internal val Context.dataStore by preferencesDataStore(
    name = "timer_sound",
    // Битый файл настроек (обрыв записи, повреждённые байты) не должен ронять приложение
    // при старте: подменяем его пустыми настройками, дальше срабатывает обычный фолбэк на дефолты.
    corruptionHandler = ReplaceFileCorruptionHandler { e ->
        AppLog.e("PreferencesRepository: битый файл настроек, откат на пустые: $e")
        emptyPreferences()
    },
)

private val alarmsJsonKey = stringPreferencesKey("alarms_json")
private val schemaVersionKey = intPreferencesKey("schema_version")
private val nextAlarmIdKey = intPreferencesKey("next_alarm_id")
private val autoStopKey = longPreferencesKey("auto_stop_ms")
private val maxFiresKey = intPreferencesKey("max_total_fires")
private val fadeInKey = booleanPreferencesKey("fade_in_enabled")
private val groupsJsonKey = stringPreferencesKey("groups_json")
private val nextGroupIdKey = intPreferencesKey("next_group_id")
private val sessionActiveKey = booleanPreferencesKey("session_active")
/** Множество id активных групп (v3, CSV); legacy boolean-флаг выше не трогаем. */
private val activeGroupsKey = stringPreferencesKey("active_group_ids")

/** Метка «системный запрос POST_NOTIFICATIONS уже показывали» — чтобы не спрашивать каждый запуск. */
private val notificationAskedKey = booleanPreferencesKey("notification_permission_asked")

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
                val parsed = runCatching {
                    alarmsJson.decodeFromString(AlarmListDto.serializer(), jsonStr)
                        .alarms.map { it.toAlarmConfig() }
                }.getOrNull()
                if (parsed == null) {
                    AppLog.e("PreferencesRepository: битый alarms_json (${jsonStr.length} символов), откат на firstRunConfig")
                }
                parsed
            } ?: Defaults.firstRunConfig().alarms
        } else {
            // До миграции: читаем legacy-ключи напрямую.
            (0 until (p[channelCountKey] ?: 5)).map { id -> readLegacyAlarm(p, id) }
        }
        TimerConfig(
            alarms = alarms,
            autoStopMs = (p[autoStopKey] ?: 0L).coerceAtLeast(0L),
            maxTotalFiresPerSession = (p[maxFiresKey] ?: 0).coerceAtLeast(0),
            fadeInEnabled = p[fadeInKey] ?: false,
        )
    }

    suspend fun save(config: TimerConfig) {
        context.dataStore.edit { p ->
            p[alarmsJsonKey] = alarmsJson.encodeToString(
                AlarmListDto.serializer(),
                AlarmListDto(config.alarms.map { it.toDto() }),
            )
            p[autoStopKey] = config.autoStopMs
            p[maxFiresKey] = config.maxTotalFiresPerSession.coerceAtLeast(0)
            p[fadeInKey] = config.fadeInEnabled
        }
    }

    /** Корневой конфиг групп (схема v3). Битый JSON → лог и дефолт, как раньше. */
    val appConfig: Flow<com.timersound.model.AppConfig> = context.dataStore.data.map { p ->
        val decoded = p[groupsJsonKey]?.let { jsonStr ->
            val dto = runCatching {
                groupsJson.decodeFromString(GroupsListDto.serializer(), jsonStr)
            }.getOrNull()
            if (dto == null) {
                AppLog.e("PreferencesRepository: битый groups_json (${jsonStr.length} символов), откат на дефолт")
            }
            dto
        }
        if (decoded != null) {
            com.timersound.model.AppConfig(
                groups = decoded.groups.map { it.toAlarmGroup() },
                nextGroupId = decoded.nextGroupId,
                fadeInEnabled = p[fadeInKey] ?: false,
            )
        } else if ((p[schemaVersionKey] ?: 1) >= 3) {
            com.timersound.model.AppConfig(
                groups = listOf(com.timersound.model.AlarmGroup(id = 0, name = "Основная")),
                nextGroupId = 1,
                fadeInEnabled = p[fadeInKey] ?: false,
            )
        } else {
            com.timersound.model.AppConfig(emptyList(), 0, p[fadeInKey] ?: false)
        }
    }

    suspend fun saveGroups(config: com.timersound.model.AppConfig) {
        context.dataStore.edit { p ->
            p[groupsJsonKey] = groupsJson.encodeToString(
                GroupsListDto.serializer(),
                GroupsListDto(config.groups.map { it.toGroupDto() }, config.nextGroupId),
            )
            p[nextGroupIdKey] = config.nextGroupId
            p[fadeInKey] = config.fadeInEnabled
        }
    }

    /** Активные группы (v3): id сессий, считавшихся активными. */
    suspend fun setActiveGroups(ids: Set<Int>) {
        context.dataStore.edit { p ->
            p[activeGroupsKey] = ids.sorted().joinToString(",")
            p[sessionActiveKey] = ids.isNotEmpty()
        }
    }

    suspend fun activeGroups(): Set<Int> =
        context.dataStore.data.first()[activeGroupsKey]
            ?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()


    /**
     * А4: метка «сессия считалась активной». Ставится при старте сессии,
     * снимается при штатном завершении. Если процесс убит системой, метка
     * остаётся true — при следующем запуске показываем «прервано системой».
     */
    suspend fun setSessionActive(active: Boolean) {
        context.dataStore.edit { p -> p[sessionActiveKey] = active }
    }

    suspend fun wasSessionActive(): Boolean =
        context.dataStore.data.first()[sessionActiveKey] ?: false

    /**
     * Показывали ли уже системный запрос разрешения на уведомления (API 33+).
     * Флаг в DataStore: без него диалог всплывал при каждом запуске, пока пользователь
     * не откажет дважды (дальше платформа глушит его сама, но приложение продолжало просить).
     */
    suspend fun wasNotificationPermissionAsked(): Boolean =
        context.dataStore.data.first()[notificationAskedKey] ?: false

    suspend fun setNotificationPermissionAsked() {
        context.dataStore.edit { it[notificationAskedKey] = true }
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
        ensureMigratedV1toV2()
        ensureMigratedV2toV3()
    }

    private suspend fun ensureMigratedV1toV2() {
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

    /**
     * Идемпотентная миграция v2 → v3 в ОДНОЙ транзакции: alarms_json +
     * глобальные auto_stop_ms/max_total_fires становятся группой «Основная»
     * (id 0, enabled, weekdays = 0), старые ключи удаляются.
     */
    private suspend fun ensureMigratedV2toV3() {
        val current = context.dataStore.data.first()
        if ((current[schemaVersionKey] ?: 1) >= 3) return

        val alarms: List<AlarmConfig> = current[alarmsJsonKey]?.let { jsonStr ->
            runCatching {
                alarmsJson.decodeFromString(AlarmListDto.serializer(), jsonStr)
                    .alarms.map { it.toAlarmConfig() }
            }.getOrNull()
        } ?: Defaults.firstRunConfig().alarms
        val main = com.timersound.model.AlarmGroup(
            id = 0,
            name = "Основная",
            alarms = alarms,
            enabled = true,
            weekdays = 0,
            autoStopMs = (current[autoStopKey] ?: 0L).coerceAtLeast(0L),
            maxTotalFiresPerSession = (current[maxFiresKey] ?: 0).coerceAtLeast(0),
        )
        val nextAlarmId = current[nextAlarmIdKey]
            ?: (alarms.maxOfOrNull { it.id }?.plus(1) ?: alarms.size)

        context.dataStore.edit { edit ->
            edit[groupsJsonKey] = groupsJson.encodeToString(
                GroupsListDto.serializer(),
                GroupsListDto(listOf(main.toGroupDto()), nextGroupId = 1),
            )
            edit[nextGroupIdKey] = 1
            edit.remove(alarmsJsonKey)
            edit.remove(autoStopKey)
            edit.remove(maxFiresKey)
            edit.remove(nextAlarmIdKey)
            edit[intPreferencesKey("next_alarm_id_g0")] = nextAlarmId
            edit[schemaVersionKey] = 3
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
