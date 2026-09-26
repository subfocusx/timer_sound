package com.timersound

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import com.timersound.audio.AudioEngine
import com.timersound.audio.PreviewPlayer
import com.timersound.data.PreferencesRepository
import com.timersound.model.AlarmConfig
import com.timersound.model.Defaults
import com.timersound.model.SceneMode
import com.timersound.model.TimerConfig
import com.timersound.service.TimerSoundService
import com.timersound.service.TimerStateHolder
import com.timersound.timer.TimerState

/**
 * ViewModel: единственный владелец конфигурации (читает/пишет DataStore,
 * конфигурация — источник истины для UI), отправляет команды сервису и
 * транслирует его состояние (TimerStateHolder) в UI.
 *
 * Все мутации — по стабильному id. Блокировка правок при RUNNING/PAUSED
 * (Р-5): ранний выход + UI-сигнал в snackbarEvents.
 */
class TimerViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = PreferencesRepository(application)

    private val _configMode = MutableStateFlow(Defaults.firstRunConfig())

    /** Текущая конфигурация будильников и авто-остановки (персист, без сети). */
    val configMode: StateFlow<TimerConfig> = _configMode.asStateFlow()
    val config: StateFlow<TimerConfig> = configMode

    /** Состояние выполнения сессии из сервиса. */
    val runtime: StateFlow<TimerStateHolder.Ui> = TimerStateHolder.ui

    /**
     * Правки разрешены только в IDLE/COMPLETED.
     * UI дизейбит контролы на основе этого флоу (защита в глубину).
     */
    val canEdit: StateFlow<Boolean> = runtime
        .map { it.state == TimerState.IDLE || it.state == TimerState.COMPLETED }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Разовые UI-события (Snackbar-сообщения). */
    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvents = _snackbarEvents.asSharedFlow()

    /** Состояние превью-плеера (одна активная дорожка). */
    val previewState: StateFlow<PreviewPlayer.State?> = PreviewPlayer.state

    // ------------------------------------------------------------------ init

    init {
        viewModelScope.launch {
            repo.ensureMigrated()
            repo.config.collect { _configMode.value = it }
        }
    }

    // ------------------------------------------------------------------ commands

    fun start() {
        val c = config.value
        if (c.missingFileAlarms().isNotEmpty() || c.playableAlarms().isEmpty()) return
        PreviewPlayer.stop()
        viewModelScope.launch {
            repo.save(c)
            ContextCompat.startForegroundService(
                getApplication(),
                TimerSoundService.commandIntent(getApplication(), TimerSoundService.ACTION_START),
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

    // ------------------------------------------------------------------ player

    /** Play/pause toggle для карточки: ровно один preview на процесс. */
    fun playerToggle(id: Int) {
        val alarm = config.value.alarms.firstOrNull { it.id == id } ?: return
        if (!alarm.hasFile) return
        if (previewState.value?.alarmId == id && previewState.value?.isPlaying == true) {
            PreviewPlayer.pause()
        } else {
            PreviewPlayer.play(getApplication(), alarm)
        }
    }

    fun playerStop() = PreviewPlayer.stop()

    // ------------------------------------------------------------------ config edits (all by id, locked during session)

    private val editingLocked get() =
        runtime.value.state == TimerState.RUNNING || runtime.value.state == TimerState.PAUSED

    private fun refuseEdit() {
        viewModelScope.launch {
            _snackbarEvents.emit("Остановите таймер, чтобы менять будильники")
        }
    }

    /** Выбран файл для будильника: фиксируем persistable read-права, сохраняем URI и имя. */
    fun onFilePicked(id: Int, uri: Uri) {
        if (editingLocked) { refuseEdit(); return }
        val ctx = getApplication<Application>()
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val displayName = queryDisplayName(uri)
        update { cfg ->
            cfg.copy(alarms = cfg.alarms.map { ch ->
                if (ch.id == id) ch.copy(
                    fileUri = uri.toString(), fileName = displayName, enabled = true,
                ) else ch
            })
        }
    }

    /** Удаление файла: просто пустой fileUri, без спецслучая id == 0. */
    fun removeFile(id: Int) {
        if (editingLocked) { refuseEdit(); return }
        AudioEngine.stopChannel(id)
        releaseAlarmPermission(id)
        update { cfg ->
            cfg.copy(alarms = cfg.alarms.map { ch ->
                if (ch.id == id) ch.copy(fileUri = "", fileName = null) else ch
            })
        }
    }

    /** Добавить будильник (лимит MAX_ALARMS). */
    fun addAlarm() {
        if (editingLocked) { refuseEdit(); return }
        val current = _configMode.value
        if (current.alarms.size >= Defaults.MAX_ALARMS) {
            viewModelScope.launch {
                _snackbarEvents.emit("Достигнут лимит ${Defaults.MAX_ALARMS} будильников")
            }
            return
        }
        val newId = current.alarms.maxOfOrNull { it.id }?.let { it + 1 } ?: 0
        val newAlarm = Defaults.newAlarm(newId, current.alarms.size)
        update { cfg -> cfg.copy(alarms = cfg.alarms + newAlarm) }
    }

    /** Удалить будильник по id + стоп preview/Ringtone + release SAF. */
    fun deleteAlarm(id: Int) {
        if (editingLocked) { refuseEdit(); return }
        AudioEngine.stopChannel(id)
        releaseAlarmPermission(id)
        update { cfg -> cfg.copy(alarms = cfg.alarms.filter { it.id != id }) }
    }

    /** Удалить все будильники + releaseAll(). */
    fun deleteAllAlarms() {
        if (editingLocked) { refuseEdit(); return }
        AudioEngine.releaseAll()
        _configMode.value.alarms.forEach { releaseAlarmPermission(it.id) }
        update { cfg -> cfg.copy(alarms = emptyList()) }
    }

    /** Переименовать: trim, обрезка до MAX_NAME_LENGTH, пустое → автоимя. */
    fun renameAlarm(id: Int, name: String) {
        if (editingLocked) { refuseEdit(); return }
        val trimmed = name.trim().let { if (it.length > Defaults.MAX_NAME_LENGTH) it.take(Defaults.MAX_NAME_LENGTH) else it }
        val fallback = "Будильник ${id + 1}"
        update { cfg ->
            cfg.copy(alarms = cfg.alarms.map { ch ->
                if (ch.id == id) ch.copy(name = trimmed.ifEmpty { fallback }) else ch
            })
        }
    }

    /** Взять встроенный сигнал для любого будильника. */
    fun setBuiltInBeep(id: Int) {
        if (editingLocked) { refuseEdit(); return }
        update { cfg ->
            cfg.copy(alarms = cfg.alarms.map { ch ->
                if (ch.id == id) ch.copy(fileUri = Defaults.BUILT_IN_BEEP, fileName = null) else ch
            })
        }
    }

    // --- все сеттеры по id через общий метод editAlarm ---

    private fun editAlarm(id: Int, transform: (AlarmConfig) -> AlarmConfig) {
        if (editingLocked) { refuseEdit(); return }
        update { cfg ->
            cfg.copy(alarms = cfg.alarms.map { ch ->
                if (ch.id == id) transform(ch) else ch
            })
        }
    }

    fun setMode(id: Int, mode: SceneMode) {
        editAlarm(id) { it.copy(mode = mode) }
    }

    fun setStartMinutes(id: Int, minutes: Int?) {
        editAlarm(id) { it.copy(startMinutes = minutes) }
    }

    fun setEndMinutes(id: Int, minutes: Int?) {
        editAlarm(id) { it.copy(endMinutes = minutes) }
    }

    fun setLaunchCount(id: Int, count: Int) {
        editAlarm(id) { it.copy(launchCount = count) }
    }

    /** Изменение интервала сценария по id (одна функция вместо setInterval+setIntervalAt). */
    fun setInterval(id: Int, ms: Long) {
        editAlarm(id) { it.copy(intervalMs = ms.coerceAtLeast(Defaults.MIN_INTERVAL_MS)) }
    }

    fun setVolume(id: Int, percent: Int) {
        editAlarm(id) { it.copy(volumePercent = percent.coerceIn(0, 100)) }
    }

    fun setEnabled(id: Int, enabled: Boolean) {
        editAlarm(id) { it.copy(enabled = enabled) }
    }

    fun setAutoStop(ms: Long) {
        if (editingLocked) { refuseEdit(); return }
        update { it.copy(autoStopMs = ms.coerceAtLeast(0L)) }
    }

    // ------------------------------------------------------------------ persistence

    /** Debounce на запись (≈300 мс) — живёт в ViewModel. */
    private var saveJob: Job? = null

    private fun update(transform: (TimerConfig) -> TimerConfig) {
        val next = transform(_configMode.value)
        _configMode.value = next
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(300)
            repo.save(next)
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Release SAF-права для будильника, только если URI не занят другим будильником. */
    private fun releaseAlarmPermission(id: Int) {
        val uri = _configMode.value.alarms.firstOrNull { it.id == id }?.fileUri ?: return
        if (uri.isEmpty() || uri == Defaults.BUILT_IN_BEEP) return
        val usedByOthers = _configMode.value.alarms.any { it.id != id && it.fileUri == uri }
        if (!usedByOthers) {
            val ctx = getApplication<Application>()
            runCatching {
                ctx.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    /** Отображаемое имя файла будильника (для UI), либо null. */
    fun fileDisplayName(alarm: AlarmConfig): String? {
        return when {
            alarm.isBuiltInBeep -> "Встроенный сигнал (бип)"
            !alarm.hasFile -> null
            else -> {
                alarm.fileName ?: queryDisplayName(Uri.parse(alarm.fileUri))
                    ?: alarm.fileUri.substringAfterLast('/').ifEmpty { alarm.fileUri }
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

    private fun send(action: String) {
        val ctx = getApplication<Application>()
        ctx.startService(TimerSoundService.commandIntent(ctx, action))
    }

    companion object {
        fun formatInterval(ms: Long): String = com.timersound.timer.TimerSession.formatHms(ms)
    }
}
