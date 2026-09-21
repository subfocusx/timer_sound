package com.timersound.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.timersound.model.ChannelConfig
import com.timersound.model.Defaults
import com.timersound.model.SceneMode
import com.timersound.model.TimerConfig

private val Context.dataStore by preferencesDataStore(name = "timer_sound")
private val channelCountKey = intPreferencesKey("channel_count")
private val autoStopKey = longPreferencesKey("auto_stop_ms")

private fun uriKey(id: Int) = stringPreferencesKey("ch${id}_uri")
private fun fileNameKey(id: Int) = stringPreferencesKey("ch${id}_file_name")
private fun intervalKey(id: Int) = longPreferencesKey("ch${id}_interval")
private fun volumeKey(id: Int) = intPreferencesKey("ch${id}_volume")
private fun enabledKey(id: Int) = booleanPreferencesKey("ch${id}_enabled")
private fun modeKey(id: Int) = stringPreferencesKey("ch${id}_mode")
private fun startMinutesKey(id: Int) = longPreferencesKey("ch${id}_start_minutes")
private fun endMinutesKey(id: Int) = longPreferencesKey("ch${id}_end_minutes")
private fun launchCountKey(id: Int) = longPreferencesKey("ch${id}_launch_count")

/**
 * Локальное хранилище настроек (DataStore Preferences, без сети и Room).
 * Единственный источник истины для конфигурации каналов; ТЗ: без авто-возобновления
 * после перезапуска приложения — здесь хранится только конфигурация, не состояние сессии.
 */
class PreferencesRepository(private val context: Context) {

    val config: Flow<TimerConfig> = context.dataStore.data.map { p ->
        TimerConfig(
            channels = (0 until channelCountFrom(p)).map { id -> readChannelConfig(p, id) },
            autoStopMs = (p[autoStopKey] ?: Defaults.defaultConfig().autoStopMs).coerceAtLeast(0L),
        )
    }

    suspend fun save(config: TimerConfig) {
        context.dataStore.edit { p ->
            config.channels.forEach { channel -> p.writeChannelConfig(channel) }
            p.setChannelCount(config.channels.size)
            p[autoStopKey] = config.autoStopMs
        }
    }
}

internal fun channelCountFrom(prefs: Preferences): Int =
    (prefs[channelCountKey] ?: Defaults.CHANNEL_COUNT).coerceAtLeast(0)

internal fun readChannelConfig(prefs: Preferences, id: Int): ChannelConfig {
    val default = Defaults.defaultChannel(id)
    val mode = prefs[modeKey(id)]
        ?.let { runCatching { SceneMode.valueOf(it) }.getOrNull() }
        ?: SceneMode.REPEAT
    val startMinutes = prefs[startMinutesKey(id)]
        ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
        ?.toInt()
    val endMinutes = prefs[endMinutesKey(id)]
        ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
        ?.toInt()
    val launchCount = prefs[launchCountKey(id)]
        ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
        ?.toInt()
        ?: 0

    return ChannelConfig(
        id = id,
        name = default.name,
        fileUri = prefs[uriKey(id)] ?: default.fileUri,
        fileName = prefs[fileNameKey(id)]?.takeIf { it.isNotEmpty() },
        mode = mode,
        intervalMs = (prefs[intervalKey(id)] ?: default.intervalMs).coerceAtLeast(Defaults.MIN_INTERVAL_MS),
        startMinutes = startMinutes,
        endMinutes = endMinutes,
        launchCount = launchCount,
        volumePercent = (prefs[volumeKey(id)] ?: default.volumePercent).coerceIn(0, 100),
        enabled = prefs[enabledKey(id)] ?: default.enabled,
    )
}

internal fun MutablePreferences.writeChannelConfig(channel: ChannelConfig) {
    this[uriKey(channel.id)] = channel.fileUri
    this[fileNameKey(channel.id)] = channel.fileName.orEmpty()
    this[intervalKey(channel.id)] = channel.intervalMs
    this[volumeKey(channel.id)] = channel.volumePercent
    this[enabledKey(channel.id)] = channel.enabled
    this[modeKey(channel.id)] = channel.mode.name
    this[startMinutesKey(channel.id)] = channel.startMinutes?.toLong() ?: -1L
    this[endMinutesKey(channel.id)] = channel.endMinutes?.toLong() ?: -1L
    this[launchCountKey(channel.id)] = channel.launchCount.toLong()
}

internal fun MutablePreferences.setChannelCount(count: Int) {
    this[channelCountKey] = count.coerceAtLeast(0)
}
