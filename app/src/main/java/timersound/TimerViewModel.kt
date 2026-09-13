package timersound

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timersound.audio.AudioEngine
import timersound.data.PreferencesRepository
import timersound.model.ChannelConfig
import timersound.model.Defaults
import timersound.model.TimerConfig
import timersound.service.TimerSoundService
import timersound.service.TimerStateHolder

/**
 * ViewModel: единственный владелец конфигурации (читает/пишет DataStore,
 * конфигурация — источник истины для UI), отправляет команды сервису и
 * транслирует его состояние (TimerStateHolder) в UI.
 */
class TimerViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = PreferencesRepository(application)

    /** Текущая конфигурация каналов и авто-остановки (персист, без сети). */
    val config: StateFlow<TimerConfig> = repo.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Defaults.defaultConfig())

    /** Состояние выполнения сессии из сервиса. */
    val runtime: StateFlow<TimerStateHolder.Ui> = TimerStateHolder.ui

    // ------------------------------------------------------------------ commands

    fun start() {
        val c = config.value
        if (c.missingFileChannels().isNotEmpty() || c.playableChannels().isEmpty()) return
        viewModelScope.launch {
            repo.save(c)
            ContextCompat.startForegroundService(
                getApplication(), TimerSoundService.commandIntent(getApplication(), TimerSoundService.ACTION_START),
            )
        }
    }

    fun pause() = send(TimerSoundService.ACTION_PAUSE)

    fun resume() = send(TimerSoundService.ACTION_RESUME)

    fun stop() = send(TimerSoundService.ACTION_STOP)

    fun reset() {
        TimerStateHolder.reset()
        send(TimerSoundService.ACTION_RESET)
    }

    fun preview(channelId: Int) {
        val ch = config.value.channels.firstOrNull { it.id == channelId } ?: return
        if (!ch.hasFile) return
        AudioEngine.preview(getApplication(), ch)
    }

    private fun send(action: String) {
        val ctx = getApplication<Application>()
        ctx.startService(TimerSoundService.commandIntent(ctx, action))
    }

    // ------------------------------------------------------------------ config edits

    /** Выбран файл для канала: фиксируем persistable read-права, сохраняем URI и имя файла. */
    fun onFilePicked(channelId: Int, uri: Uri) {
        val ctx = getApplication<Application>()
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val displayName = queryDisplayName(uri)
        update { cfg ->
            cfg.copy(channels = cfg.channels.map { ch ->
                if (ch.id == channelId) ch.copy(fileUri = uri.toString(), fileName = displayName, enabled = ch.enabled) else ch
            })
        }
    }

    /** Удаление файла: канал 1 возвращается ко встроенному сигналу, остальные — пусто. */
    fun removeFile(channelId: Int) {
        update { cfg ->
            cfg.copy(channels = cfg.channels.map { ch ->
                if (ch.id == channelId) ch.copy(
                    fileUri = if (ch.id == 0) Defaults.BUILT_IN_BEEP else "",
                    fileName = null,
                )
                else ch
            })
        }
    }

    fun setInterval(channelId: Int, ms: Long) {
        update { cfg ->
            cfg.copy(channels = cfg.channels.map { ch ->
                if (ch.id == channelId) ch.copy(intervalMs = ms.coerceAtLeast(Defaults.MIN_INTERVAL_MS)) else ch
            })
        }
    }

    fun setVolume(channelId: Int, percent: Int) {
        update { cfg ->
            cfg.copy(channels = cfg.channels.map { ch ->
                if (ch.id == channelId) ch.copy(volumePercent = percent.coerceIn(0, 100)) else ch
            })
        }
    }

    fun setEnabled(channelId: Int, enabled: Boolean) {
        update { cfg ->
            cfg.copy(channels = cfg.channels.map { ch ->
                if (ch.id == channelId) ch.copy(enabled = enabled) else ch
            })
        }
    }

    fun setAutoStop(ms: Long) {
        update { it.copy(autoStopMs = ms.coerceAtLeast(0L)) }
    }

    private fun update(transform: (TimerConfig) -> TimerConfig) {
        viewModelScope.launch {
            val next = transform(config.value)
            repo.save(next)
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Отображаемое имя файла канала (для UI), либо null. */
    fun fileDisplayName(channel: ChannelConfig): String? {
        return when {
            channel.isBuiltInBeep -> "Встроенный сигнал (бип)"
            !channel.hasFile -> null
            else -> {
                // Приоритет — сохранённое в DataStore имя (ТЗ §11), fallback — live-запрос.
                channel.fileName ?: queryDisplayName(Uri.parse(channel.fileUri))
                    ?: channel.fileUri.substringAfterLast('/').ifEmpty { channel.fileUri }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cr = getApplication<Application>().contentResolver
        return runCatching {
            cr.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
    }

    /** Строковое представление интервала HH:MM:SS. */
    companion object {
        fun formatInterval(ms: Long): String = timersound.timer.TimerSession.formatHms(ms)
    }
}