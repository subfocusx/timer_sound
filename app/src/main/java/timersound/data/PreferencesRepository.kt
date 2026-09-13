package timersound.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timersound.model.ChannelConfig
import timersound.model.Defaults
import timersound.model.TimerConfig

private val Context.dataStore by preferencesDataStore(name = "timer_sound")

/**
 * Локальное хранилище настроек (DataStore Preferences, без сети и Room).
 * Единственный источник истины для конфигурации каналов; ТЗ: без авто-возобновления
 * после перезапуска приложения — здесь хранится только конфигурация, не состояние сессии.
 */
class PreferencesRepository(private val context: Context) {

    private val uriKeys = (0 until Defaults.CHANNEL_COUNT).map { stringPreferencesKey("ch${it}_uri") }
    private val fileNameKeys = (0 until Defaults.CHANNEL_COUNT).map { stringPreferencesKey("ch${it}_file_name") }
    private val intervalKeys = (0 until Defaults.CHANNEL_COUNT).map { longPreferencesKey("ch${it}_interval") }
    private val volumeKeys = (0 until Defaults.CHANNEL_COUNT).map { intPreferencesKey("ch${it}_volume") }
    private val enabledKeys = (0 until Defaults.CHANNEL_COUNT).map { booleanPreferencesKey("ch${it}_enabled") }
    private val autoStopKey = longPreferencesKey("auto_stop_ms")

    val config: Flow<TimerConfig> = context.dataStore.data.map { p ->
        val defaults = Defaults.defaultConfig()
        TimerConfig(
            channels = (0 until Defaults.CHANNEL_COUNT).map { i ->
                val d = defaults.channels[i]
                ChannelConfig(
                    id = d.id,
                    name = d.name,
                    fileUri = p[uriKeys[i]] ?: d.fileUri,
                    fileName = p[fileNameKeys[i]]?.takeIf { it.isNotEmpty() },
                    intervalMs = (p[intervalKeys[i]] ?: d.intervalMs).coerceAtLeast(Defaults.MIN_INTERVAL_MS),
                    volumePercent = (p[volumeKeys[i]] ?: d.volumePercent).coerceIn(0, 100),
                    enabled = p[enabledKeys[i]] ?: d.enabled,
                )
            },
            autoStopMs = (p[autoStopKey] ?: defaults.autoStopMs).coerceAtLeast(0L),
        )
    }

    suspend fun save(config: TimerConfig) {
        context.dataStore.edit { p ->
            config.channels.forEachIndexed { i, ch ->
                p[uriKeys[i]] = ch.fileUri
                p[fileNameKeys[i]] = ch.fileName.orEmpty()
                p[intervalKeys[i]] = ch.intervalMs
                p[volumeKeys[i]] = ch.volumePercent
                p[enabledKeys[i]] = ch.enabled
            }
            p[autoStopKey] = config.autoStopMs
        }
    }
}