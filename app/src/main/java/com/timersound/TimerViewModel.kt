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
import com.timersound.model.AlarmGroup
import com.timersound.model.AppConfig
import com.timersound.model.Defaults
import com.timersound.model.GroupLimits
import com.timersound.model.GroupPresets
import com.timersound.model.SceneMode
import com.timersound.model.TimerConfig
import com.timersound.model.Weekdays
import com.timersound.service.TimerSoundService
import com.timersound.service.TimerStateHolder
import com.timersound.service.WakeSchedulerRearm
import com.timersound.timer.ScheduleConflicts
import com.timersound.timer.TimerState

/**
 * ViewModel: владелец AppConfig (группы). Все мутации по (groupId, alarmId)
 * с debounce 300 мс. Команды сервису несут EXTRA_GROUP_ID.
 * Блокировка правок теперь ПО ГРУППЕ: isLocked(groupId).
 */
class TimerViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = PreferencesRepository(application)

    private val _app = MutableStateFlow(AppConfig())
    /** Группы и глобальный fadeIn (персист). */
    val app: StateFlow<AppConfig> = _app.asStateFlow()

    /** Совместимость: плоский конфиг первой группы (для старых тестов/UI). */
    private val _configMode = MutableStateFlow(Defaults.firstRunConfig())
    val configMode: StateFlow<TimerConfig> = _configMode.asStateFlow()
    val config: StateFlow<TimerConfig> = configMode

    /** Состояние выполнения групп из сервиса. */
    val groupRuntimes: StateFlow<Map<Int, TimerStateHolder.GroupRuntime>> = TimerStateHolder.groups
    /** Legacy-зеркало первой активной группы. */
    val runtime: StateFlow<TimerStateHolder.Ui> = TimerStateHolder.ui

    /** Выбранная группа для экрана группы (по умолчанию первая). */
    private val _selectedGroupId = MutableStateFlow<Int?>(null)
    val selectedGroupId: StateFlow<Int?> = _selectedGroupId.asStateFlow()

    val canEdit: StateFlow<Boolean> = runtime
        .map { it.state == TimerState.IDLE || it.state == TimerState.COMPLETED }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Правки группы разрешены, кроме RUNNING/PAUSED этой группы. */
    fun isLocked(groupId: Int): Boolean {
        val st = groupRuntimes.value[groupId]?.state ?: TimerState.IDLE
        return st == TimerState.RUNNING || st == TimerState.PAUSED
    }

    fun canEditGroup(groupId: Int): Boolean = !isLocked(groupId)

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvents = _snackbarEvents.asSharedFlow()

    val previewState: StateFlow<PreviewPlayer.State?> = PreviewPlayer.state

    /** Прерванные системой группы (метка осталась, сервис не поднят). */
    private val _interruptedGroups = MutableStateFlow<Set<Int>>(emptySet())
    val interruptedGroups: StateFlow<Set<Int>> = _interruptedGroups.asStateFlow()
    private val _wasInterrupted = MutableStateFlow(false)
    val wasInterrupted: StateFlow<Boolean> = _wasInterrupted.asStateFlow()

    private val _notificationPermissionAsked = MutableStateFlow(true)
    val notificationPermissionAsked: StateFlow<Boolean> = _notificationPermissionAsked.asStateFlow()

    /** Предупреждения о пересечениях (чистая функция от групп). */
    val conflicts: StateFlow<List<ScheduleConflicts.Conflict>> = _app
        .map { ScheduleConflicts.find(it.groups) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun markNotificationPermissionAsked() {
        _notificationPermissionAsked.value = true
        viewModelScope.launch { repo.setNotificationPermissionAsked() }
    }

    fun dismissInterrupted() {
        viewModelScope.launch {
            repo.setSessionActive(false)
            repo.setActiveGroups(emptySet())
            _wasInterrupted.value = false
            _interruptedGroups.value = emptySet()
        }
    }

    init {
        viewModelScope.launch {
            repo.ensureMigrated()
            val interrupted = repo.activeGroups()
            if (interrupted.isNotEmpty()) {
                _interruptedGroups.value = interrupted
                _wasInterrupted.value = true
            } else if (repo.wasSessionActive()) {
                _wasInterrupted.value = true
            }
            _notificationPermissionAsked.value = repo.wasNotificationPermissionAsked()
            repo.appConfig.collect {
                _app.value = it
                if (_selectedGroupId.value == null) {
                    _selectedGroupId.value = it.groups.firstOrNull()?.id
                }
                // Legacy-зеркало: первая группа → плоский конфиг.
                val first = it.groups.firstOrNull()
                if (first != null) {
                    _configMode.value = first.toRunConfig(it.fadeInEnabled)
                }
            }
        }
    }

    // ------------------------------------------------------------------ group commands

    fun startGroup(groupId: Int) {
        val g = _app.value.groups.firstOrNull { it.id == groupId } ?: return
        val run = g.toRunConfig(_app.value.fadeInEnabled)
        if (run.missingFileAlarms().isNotEmpty() || run.playableAlarms().isEmpty()) return
        PreviewPlayer.stop()
        send(TimerSoundService.ACTION_START, groupId)
    }

    fun pauseGroup(groupId: Int) = send(TimerSoundService.ACTION_PAUSE, groupId)
    fun resumeGroup(groupId: Int) = send(TimerSoundService.ACTION_RESUME, groupId)
    fun stopGroup(groupId: Int) = send(TimerSoundService.ACTION_STOP, groupId)
    fun restartGroup(groupId: Int) = send(TimerSoundService.ACTION_RESTART, groupId)
    fun pauseAll() = send(TimerSoundService.ACTION_PAUSE_ALL, -1)
    fun stopAll() = send(TimerSoundService.ACTION_STOP_ALL, -1)

    /** Legacy: старт первой группы. */
    fun start() {
        val first = _app.value.groups.firstOrNull() ?: return
        startGroup(first.id)
    }

    fun pause() {
        val id = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        pauseGroup(id)
    }

    fun resume() {
        val id = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        resumeGroup(id)
    }

    fun stop() {
        val id = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        stopGroup(id)
    }

    fun reset() {
        TimerStateHolder.reset()
        val id = _selectedGroupId.value ?: return
        send(TimerSoundService.ACTION_RESET, id)
    }

    fun selectGroup(groupId: Int) {
        _selectedGroupId.value = groupId
    }

    // ------------------------------------------------------------------ groups CRUD

    /** null — пустая; иначе индекс в GroupPresets.all()/presetNames() (0=пустая, 1=«Подъём», …). */
    fun createGroup(fromPreset: Int? = null) {
        val cur = _app.value
        if (cur.groups.size >= GroupLimits.MAX_GROUPS) {
            snack("Достигнут лимит ${GroupLimits.MAX_GROUPS} групп")
            return
        }
        val id = cur.nextGroupId
        val base = nextAlarmId(cur)
        val group = if (fromPreset == null) {
            GroupPresets.empty(id, base)
        } else {
            GroupPresets.all(id, base).getOrElse(fromPreset) { GroupPresets.empty(id, base) }
        }
        // Новые id будильников — монотонные, без коллизий.
        val remapped = remapAlarmIds(group, base)
        updateApp { it.copy(groups = it.groups + remapped, nextGroupId = id + 1) }
        _selectedGroupId.value = id
    }

    fun duplicateGroup(groupId: Int) {
        val cur = _app.value
        if (cur.groups.size >= GroupLimits.MAX_GROUPS) {
            snack("Достигнут лимит ${GroupLimits.MAX_GROUPS} групп")
            return
        }
        val src = cur.groups.firstOrNull { it.id == groupId } ?: return
        val id = cur.nextGroupId
        val base = nextAlarmId(cur)
        val copy = remapAlarmIds(
            src.copy(id = id, name = "${src.name} (копия)"),
            base,
        )
        // SAF-права URI не освобождаем: файл использует другая группа (копия делит URI).
        updateApp { it.copy(groups = it.groups + copy, nextGroupId = id + 1) }
    }

    fun deleteGroup(groupId: Int) {
        if (isLocked(groupId)) { refuseEdit(); return }
        AudioEngine.stopGroup(groupId)
        // SAF-права освобождаем только если URI не занят другими группами.
        val victim = _app.value.groups.firstOrNull { it.id == groupId } ?: return
        val others = _app.value.groups.filter { it.id != groupId }.flatMap { it.alarms.map { a -> a.fileUri } }.toSet()
        victim.alarms.forEach { a ->
            if (a.fileUri.isNotEmpty() && a.fileUri != Defaults.BUILT_IN_BEEP && a.fileUri !in others) {
                releaseUri(a.fileUri)
            }
        }
        updateApp { it.copy(groups = it.groups.filter { g -> g.id != groupId }) }
        if (_selectedGroupId.value == groupId) {
            _selectedGroupId.value = _app.value.groups.firstOrNull()?.id
        }
        send(TimerSoundService.ACTION_STOP, groupId)
    }

    fun renameGroup(groupId: Int, name: String) {
        if (isLocked(groupId)) { refuseEdit(); return }
        val trimmed = name.trim().let { if (it.length > Defaults.MAX_NAME_LENGTH) it.take(Defaults.MAX_NAME_LENGTH) else it }
        updateApp { app ->
            app.copy(groups = app.groups.map { g ->
                if (g.id == groupId) g.copy(name = trimmed.ifEmpty { "Группа ${groupId + 1}" }) else g
            })
        }
    }

    fun setGroupEnabled(groupId: Int, enabled: Boolean) {
        updateApp { app ->
            app.copy(groups = app.groups.map { g -> if (g.id == groupId) g.copy(enabled = enabled) else g })
        }
    }

    fun setWeekdays(groupId: Int, mask: Int) {
        if (isLocked(groupId)) { refuseEdit(); return }
        updateApp { app ->
            app.copy(groups = app.groups.map { g -> if (g.id == groupId) g.copy(weekdays = mask and Weekdays.ALL) else g })
        }
    }

    fun setGroupAutoStop(groupId: Int, ms: Long) {
        if (isLocked(groupId)) { refuseEdit(); return }
        updateApp { app ->
            app.copy(groups = app.groups.map { g ->
                if (g.id == groupId) g.copy(autoStopMs = ms.coerceAtLeast(0L)) else g
            })
        }
    }

    fun setGroupMaxFires(groupId: Int, count: Int) {
        if (isLocked(groupId)) { refuseEdit(); return }
        updateApp { app ->
            app.copy(groups = app.groups.map { g ->
                if (g.id == groupId) g.copy(maxTotalFiresPerSession = count.coerceAtLeast(0)) else g
            })
        }
    }

    fun setFadeInEnabled(enabled: Boolean) {
        updateApp { it.copy(fadeInEnabled = enabled) }
    }

    // ------------------------------------------------------------------ alarms in group

    private fun groupOf(groupId: Int): AlarmGroup? = _app.value.groups.firstOrNull { it.id == groupId }

    fun playerToggle(groupId: Int, id: Int) {
        val alarm = groupOf(groupId)?.alarms?.firstOrNull { it.id == id } ?: return
        if (!alarm.hasFile) return
        if (previewState.value?.alarmId == id && previewState.value?.groupId == groupId &&
            previewState.value?.isPlaying == true
        ) {
            PreviewPlayer.pause()
        } else {
            PreviewPlayer.play(getApplication(), alarm, groupId)
        }
    }

    /** Legacy-сигнатура для старых вызовов (первая группа). */
    fun playerToggle(id: Int) {
        val gid = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        playerToggle(gid, id)
    }

    fun playerStop() = PreviewPlayer.stop()

    private fun editAlarm(groupId: Int, id: Int, transform: (AlarmConfig) -> AlarmConfig) {
        if (isLocked(groupId)) { refuseEdit(); return }
        updateApp { app ->
            app.copy(groups = app.groups.map { g ->
                if (g.id != groupId) g else g.copy(alarms = g.alarms.map { ch ->
                    if (ch.id == id) transform(ch) else ch
                })
            })
        }
    }

    fun onFilePicked(groupId: Int, id: Int, uri: Uri) {
        if (isLocked(groupId)) { refuseEdit(); return }
        val ctx = getApplication<Application>()
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val displayName = queryDisplayName(uri)
        updateApp { app ->
            app.copy(groups = app.groups.map { g ->
                if (g.id != groupId) g else g.copy(alarms = g.alarms.map { ch ->
                    if (ch.id == id) ch.copy(fileUri = uri.toString(), fileName = displayName, enabled = true) else ch
                })
            })
        }
    }

    fun onFilePicked(id: Int, uri: Uri) {
        val gid = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        onFilePicked(gid, id, uri)
    }

    fun removeFile(groupId: Int, id: Int) {
        if (isLocked(groupId)) { refuseEdit(); return }
        AudioEngine.stopChannel(id, groupId)
        releaseAlarmPermission(groupId, id)
        updateApp { app ->
            app.copy(groups = app.groups.map { g ->
                if (g.id != groupId) g else g.copy(alarms = g.alarms.map { ch ->
                    if (ch.id == id) ch.copy(fileUri = "", fileName = null) else ch
                })
            })
        }
    }

    fun removeFile(id: Int) {
        val gid = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        removeFile(gid, id)
    }

    fun addAlarm(groupId: Int) {
        if (isLocked(groupId)) { refuseEdit(); return }
        val g = groupOf(groupId) ?: return
        if (g.alarms.size >= Defaults.MAX_ALARMS) {
            snack("Достигнут лимит ${Defaults.MAX_ALARMS} будильников")
            return
        }
        val newId = (g.alarms.maxOfOrNull { it.id }?.plus(1)) ?: (groupId * 1000)
        updateApp { app ->
            app.copy(groups = app.groups.map { grp ->
                if (grp.id != groupId) grp else grp.copy(alarms = grp.alarms + Defaults.newAlarm(newId, grp.alarms.size))
            })
        }
    }

    fun addAlarm() {
        val gid = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        addAlarm(gid)
    }

    fun deleteAlarm(groupId: Int, id: Int) {
        if (isLocked(groupId)) { refuseEdit(); return }
        AudioEngine.stopChannel(id, groupId)
        releaseAlarmPermission(groupId, id)
        updateApp { app ->
            app.copy(groups = app.groups.map { g ->
                if (g.id != groupId) g else g.copy(alarms = g.alarms.filter { it.id != id })
            })
        }
    }

    fun deleteAlarm(id: Int) {
        val gid = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        deleteAlarm(gid, id)
    }

    fun deleteAllAlarms(groupId: Int) {
        if (isLocked(groupId)) { refuseEdit(); return }
        AudioEngine.stopGroup(groupId)
        groupOf(groupId)?.alarms?.forEach { releaseAlarmPermission(groupId, it.id) }
        updateApp { app ->
            app.copy(groups = app.groups.map { g -> if (g.id != groupId) g else g.copy(alarms = emptyList()) })
        }
    }

    fun deleteAllAlarms() {
        val gid = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        deleteAllAlarms(gid)
    }

    fun renameAlarm(groupId: Int, id: Int, name: String) {
        val trimmed = name.trim().let { if (it.length > Defaults.MAX_NAME_LENGTH) it.take(Defaults.MAX_NAME_LENGTH) else it }
        editAlarm(groupId, id) { it.copy(name = trimmed.ifEmpty { "Будильник ${id + 1}" }) }
    }

    fun renameAlarm(id: Int, name: String) {
        val gid = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        renameAlarm(gid, id, name)
    }

    fun setBuiltInBeep(groupId: Int, id: Int) {
        editAlarm(groupId, id) { it.copy(fileUri = Defaults.BUILT_IN_BEEP, fileName = null) }
    }

    fun setBuiltInBeep(id: Int) {
        val gid = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        setBuiltInBeep(gid, id)
    }

    fun setMode(groupId: Int, id: Int, mode: SceneMode) = editAlarm(groupId, id) { it.copy(mode = mode) }
    fun setMode(id: Int, mode: SceneMode) = setMode(
        _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return, id, mode,
    )

    fun setStartMinutes(groupId: Int, id: Int, minutes: Int?) = editAlarm(groupId, id) { it.copy(startMinutes = minutes) }
    fun setStartMinutes(id: Int, minutes: Int?) = setStartMinutes(
        _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return, id, minutes,
    )

    fun setEndMinutes(groupId: Int, id: Int, minutes: Int?) = editAlarm(groupId, id) { it.copy(endMinutes = minutes) }
    fun setEndMinutes(id: Int, minutes: Int?) = setEndMinutes(
        _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return, id, minutes,
    )

    fun setLaunchCount(groupId: Int, id: Int, count: Int) = editAlarm(groupId, id) { it.copy(launchCount = count) }
    fun setLaunchCount(id: Int, count: Int) = setLaunchCount(
        _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return, id, count,
    )

    fun setInterval(groupId: Int, id: Int, ms: Long) =
        editAlarm(groupId, id) { it.copy(intervalMs = ms.coerceAtLeast(Defaults.MIN_INTERVAL_MS)) }

    fun setInterval(id: Int, ms: Long) = setInterval(
        _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return, id, ms,
    )

    fun setVolume(groupId: Int, id: Int, percent: Int) =
        editAlarm(groupId, id) { it.copy(volumePercent = percent.coerceIn(0, 100)) }

    fun setVolume(id: Int, percent: Int) = setVolume(
        _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return, id, percent,
    )

    fun setEnabled(groupId: Int, id: Int, enabled: Boolean) = editAlarm(groupId, id) { it.copy(enabled = enabled) }
    fun setEnabled(id: Int, enabled: Boolean) = setEnabled(
        _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return, id, enabled,
    )

    /** Legacy: автостоп первой выбранной группы. */
    fun setAutoStop(ms: Long) {
        val gid = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        setGroupAutoStop(gid, ms)
    }

    fun setMaxTotalFires(count: Int) {
        val gid = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        setGroupMaxFires(gid, count)
    }

    // ------------------------------------------------------------------ persistence

    private var saveJob: Job? = null

    private fun updateApp(transform: (AppConfig) -> AppConfig) {
        val next = transform(_app.value)
        _app.value = next
        val first = next.groups.firstOrNull()
        if (first != null) _configMode.value = first.toRunConfig(next.fadeInEnabled)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(300)
            repo.saveGroups(next)
            // После любого изменения групп/расписаний — rearm.
            runCatching { WakeSchedulerRearm.rearm(getApplication()) }
        }
    }

    /** Legacy update плоского конфига → пишет в выбранную группу. */
    private fun update(transform: (TimerConfig) -> TimerConfig) {
        val gid = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        val cur = _app.value
        val g = cur.groups.firstOrNull { it.id == gid } ?: return
        val flat = transform(g.toRunConfig(cur.fadeInEnabled))
        updateApp { app ->
            app.copy(groups = app.groups.map { grp ->
                if (grp.id != gid) grp else grp.copy(
                    alarms = flat.alarms,
                    autoStopMs = flat.autoStopMs,
                    maxTotalFiresPerSession = flat.maxTotalFiresPerSession,
                )
            }, fadeInEnabled = flat.fadeInEnabled)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun nextAlarmId(cur: AppConfig): Int =
        cur.groups.flatMap { it.alarms }.maxOfOrNull { it.id }?.plus(1) ?: (cur.nextGroupId * 1000)

    private fun remapAlarmIds(group: AlarmGroup, base: Int): AlarmGroup =
        group.copy(alarms = group.alarms.mapIndexed { i, a -> a.copy(id = base + i) })

    private fun snack(msg: String) {
        viewModelScope.launch { _snackbarEvents.emit(msg) }
    }

    private fun refuseEdit() {
        snack("Остановите группу, чтобы менять её будильники")
    }

    private fun releaseAlarmPermission(groupId: Int, id: Int) {
        val uri = groupOf(groupId)?.alarms?.firstOrNull { it.id == id }?.fileUri ?: return
        if (uri.isEmpty() || uri == Defaults.BUILT_IN_BEEP) return
        val usedElsewhere = _app.value.groups.flatMap { it.alarms }
            .any { it.fileUri == uri && !(it.id == id && groupOf(groupId)?.alarms?.contains(it) == true) }
        if (!usedElsewhere) releaseUri(uri)
    }

    private fun releaseUri(uri: String) {
        val ctx = getApplication<Application>()
        runCatching {
            ctx.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

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

    private fun send(action: String, groupId: Int) {
        val ctx = getApplication<Application>()
        ctx.startService(TimerSoundService.commandIntent(ctx, action, groupId))
    }

    /** Legacy send без группы. */
    private fun send(action: String) {
        val gid = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: -1
        send(action, gid)
    }

    companion object {
        fun formatInterval(ms: Long): String = com.timersound.timer.TimerSession.formatHms(ms)
    }
}
