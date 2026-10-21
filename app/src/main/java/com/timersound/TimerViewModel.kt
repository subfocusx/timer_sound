package com.timersound

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.timersound.audio.AudioEngine
import com.timersound.audio.FolderScanResult
import com.timersound.audio.FolderScanner
import com.timersound.audio.FolderSoundPicker
import com.timersound.audio.PreviewPlayer
import com.timersound.audio.SoundRandom
import com.timersound.audio.SoundVolume
import com.timersound.data.PreferencesRepository
import com.timersound.model.AlarmConfig
import com.timersound.model.AlarmGroup
import com.timersound.model.AppConfig
import com.timersound.model.Defaults
import com.timersound.model.FolderPlayMode
import com.timersound.model.GroupLimits
import com.timersound.model.GroupPresets
import com.timersound.model.SceneMode
import com.timersound.model.SoundSourceType
import com.timersound.model.TimerConfig
import com.timersound.model.VolumeMode
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
        ioScope.launch { repo.setNotificationPermissionAsked() }
    }

    fun dismissInterrupted() {
        ioScope.launch {
            repo.setSessionActive(false)
            repo.setActiveGroups(emptySet())
            _wasInterrupted.value = false
            _interruptedGroups.value = emptySet()
        }
    }

    init {
        viewModelScope.launch {
            // Миграция — до чтения конфига, но на IO-диспетчере: запись в DataStore
            // не должна зависеть от диспетчера вызывающего (см. ioScope).
            withContext(Dispatchers.IO) { repo.ensureMigrated() }
            val interrupted = repo.activeGroups()
            if (interrupted.isNotEmpty()) {
                _interruptedGroups.value = interrupted
                _wasInterrupted.value = true
            } else if (repo.wasSessionActive()) {
                _wasInterrupted.value = true
            }
            _notificationPermissionAsked.value = repo.wasNotificationPermissionAsked()
            var foldersChecked = false
            repo.appConfig.collect { cfg ->
                latestFromRepo = cfg
                applyFromRepo(cfg)
                // При старте приложения права на папку могли быть отозваны,
                // а содержимое — измениться: проверяем один раз.
                if (!foldersChecked) {
                    foldersChecked = true
                    revalidateFolders(cfg)
                }
            }
        }
    }

    /** Последний конфиг, прочитанный из хранилища (может быть «старее» локального). */
    private var latestFromRepo: AppConfig? = null

    /**
     * Применение конфига из хранилища.
     *
     * Пока есть НЕСОХРАНЁННЫЕ локальные правки, значение из хранилища не
     * применяется: иначе «старый» снимок, доехавший после debounce-записи,
     * откатывал бы правки пользователя (терялись бы будильники, добавленные
     * подряд). После сохранения применяется последнее известное значение.
     */
    private fun applyFromRepo(cfg: AppConfig) {
        if (savedRevision < localRevision) return
        _app.value = cfg
        if (_selectedGroupId.value == null) {
            _selectedGroupId.value = cfg.groups.firstOrNull()?.id
        }
        // Legacy-зеркало: первая группа → плоский конфиг.
        val first = cfg.groups.firstOrNull()
        if (first != null) {
            _configMode.value = first.toRunConfig(cfg.fadeInEnabled)
        }
    }

    /**
     * Проверка папок при старте приложения: право могло быть отозвано, папка —
     * удалена, содержимое — измениться. Снимаем флаг «доступ потерян», если
     * доступ снова есть, и обновляем кэш файлов (план §5).
     */
    private fun revalidateFolders(app: AppConfig) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val updated = withContext(Dispatchers.IO) {
                app.groups.map { group ->
                    var groupChanged = false
                    val alarms = group.alarms.map { alarm ->
                        if (!alarm.isFolderSource || !alarm.hasFolder) return@map alarm
                        val scan = FolderScanner.scan(ctx, Uri.parse(alarm.folderUri))
                        val next = when (scan) {
                            is FolderScanResult.Ok -> alarm.copy(
                                folderFiles = scan.files,
                                folderAccessLost = false,
                                lastFolderIndex = alarm.lastFolderIndex
                                    .coerceIn(-1, (scan.files.size - 1).coerceAtLeast(-1)),
                            )
                            is FolderScanResult.AccessLost ->
                                // Право есть, но сканирование не удалось (провайдер занят) —
                                // кэш не трогаем. Права нет — доступ потерян по-настоящему.
                                if (!FolderScanner.hasPersistedAccess(ctx, Uri.parse(alarm.folderUri))) {
                                    alarm.copy(folderFiles = emptyList(), folderAccessLost = true)
                                } else {
                                    alarm
                                }
                        }
                        if (next != alarm) groupChanged = true
                        next
                    }
                    if (groupChanged) group.copy(alarms = alarms) else group
                }
            }
            val changed = updated.zip(app.groups).any { (new, old) -> new != old }
            if (!changed) return@launch
            AppLog.i("revalidateFolders: состояние папок обновлено")
            updateApp { it.copy(groups = updated) }
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

    /** Сброс сессии группы в IDLE: счётчики чистятся (GroupRuntime убирается). */
    fun resetGroup(groupId: Int) {
        TimerStateHolder.removeGroup(groupId)
        send(TimerSoundService.ACTION_RESET, groupId)
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
        val others = _app.value.groups.filter { it.id != groupId }.flatMap { it.alarms }
        val otherFiles = others.map { a -> a.fileUri }.toSet()
        val otherFolders = others.map { a -> a.folderUri }.toSet()
        victim.alarms.forEach { a ->
            if (a.fileUri.isNotEmpty() && a.fileUri != Defaults.BUILT_IN_BEEP && a.fileUri !in otherFiles) {
                releaseUri(a.fileUri)
            }
            // Право на папку отпускаем, только если её больше не использует никто.
            if (a.folderUri.isNotEmpty() && a.folderUri !in otherFolders) {
                releaseUri(a.folderUri)
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
            // Для папки превью играет один случайный файл из неё:
            // подставляем его в «эффективный» будильник-файл.
            val effective = effectiveForPreview(alarm) ?: return
            PreviewPlayer.play(getApplication(), effective, groupId)
        }
    }

    /**
     * Будильник для превью: для источника-папки — один файл, выбранный
     * по режиму папки (случайно без повтора / по порядку).
     * null — играть нечего (пустая папка).
     */
    private fun effectiveForPreview(alarm: AlarmConfig): AlarmConfig? {
        if (!alarm.isFolderSource) return alarm
        val files = alarm.folderFiles
        if (files.isEmpty()) return null
        val index = FolderSoundPicker.nextIndex(
            files.size,
            alarm.folderPlayMode,
            alarm.lastFolderIndex,
            SoundRandom.random,
        )
        if (index < 0) return null
        val picked = files[index]
        return alarm.copy(
            sourceType = SoundSourceType.FILE,
            fileUri = picked.uri,
            fileName = picked.name,
        )
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
        // У будильника-папки «файл» — это папка: чистим её, а не одиночный файл.
        val alarm = groupOf(groupId)?.alarms?.firstOrNull { it.id == id }
        if (alarm != null && alarm.isFolderSource) {
            clearFolder(groupId, id)
            return
        }
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

    // ------------------------------------------------------------------ folder source

    /**
     * Папка выбрана в системном пикере: забираем persistable-право чтения,
     * сканируем содержимое и переключаем будильник на источник-папку.
     *
     * Если доступ закрыт (папка удалена, право отозвано, uri не дерево) —
     * будильник помечается как «доступ потерян», показывается снекбар,
     * приложение не падает.
     */
    fun onFolderPicked(groupId: Int, alarmId: Int, treeUri: Uri) {
        if (isLocked(groupId)) { refuseEdit(); return }
        val ctx = getApplication<Application>()
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { e ->
            AppLog.w("onFolderPicked: не удалось взять persistable-право на $treeUri: $e")
        }
        when (val scan = FolderScanner.scan(ctx, treeUri)) {
            is FolderScanResult.Ok -> {
                val folderName = FolderScanner.queryDisplayName(ctx, treeUri)
                updateApp { app ->
                    app.copy(groups = app.groups.map { g ->
                        if (g.id != groupId) g else g.copy(alarms = g.alarms.map { ch ->
                            if (ch.id != alarmId) ch else ch.copy(
                                sourceType = SoundSourceType.FOLDER,
                                folderUri = treeUri.toString(),
                                folderName = folderName,
                                folderFiles = scan.files,
                                folderAccessLost = false,
                                lastFolderIndex = -1,
                                enabled = true,
                            )
                        })
                    })
                }
                AppLog.i(
                    "onFolderPicked: папка \"$folderName\" — файлов ${scan.files.size}, " +
                        "persistable-доступ=${FolderScanner.hasPersistedAccess(ctx, treeUri)}",
                )
            }

            FolderScanResult.AccessLost -> {
                updateApp { app ->
                    app.copy(groups = app.groups.map { g ->
                        if (g.id != groupId) g else g.copy(alarms = g.alarms.map { ch ->
                            if (ch.id != alarmId) ch else ch.copy(
                                sourceType = SoundSourceType.FOLDER,
                                folderUri = treeUri.toString(),
                                folderName = null,
                                folderFiles = emptyList(),
                                folderAccessLost = true,
                                lastFolderIndex = -1,
                                enabled = true,
                            )
                        })
                    })
                }
                AppLog.w("onFolderPicked: нет доступа к папке $treeUri")
                snack("Не удалось получить доступ к папке — выберите папку заново")
            }
        }
    }

    /**
     * Переключатель источника звука (файл ↔ папка).
     * Данные НЕ теряются: и файл, и папка остаются в модели,
     * меняется только [AlarmConfig.sourceType].
     */
    fun setSourceType(groupId: Int, alarmId: Int, type: SoundSourceType) {
        editAlarm(groupId, alarmId) { it.copy(sourceType = type) }
    }

    /** Режим выбора файла из папки; порядок начинается заново. */
    fun setFolderPlayMode(groupId: Int, alarmId: Int, mode: FolderPlayMode) {
        editAlarm(groupId, alarmId) { it.copy(folderPlayMode = mode, lastFolderIndex = -1) }
    }

    /**
     * Сброс папки: uri, имя, кэш файлов, признак потерянного доступа и индекс.
     * Persistable-право освобождается, только если папку больше не использует
     * ни один будильник ни одной группы.
     */
    fun clearFolder(groupId: Int, alarmId: Int) {
        if (isLocked(groupId)) { refuseEdit(); return }
        val alarm = groupOf(groupId)?.alarms?.firstOrNull { it.id == alarmId } ?: return
        val folderUri = alarm.folderUri
        editAlarm(groupId, alarmId) {
            it.copy(
                folderUri = "",
                folderName = null,
                folderFiles = emptyList(),
                folderAccessLost = false,
                lastFolderIndex = -1,
            )
        }
        if (folderUri.isNotEmpty() && !folderUriUsedElsewhere(groupId, alarmId, folderUri)) {
            AppLog.i("clearFolder: освобождаем доступ к папке $folderUri")
            releaseUri(folderUri)
        }
    }

    /**
     * Режим громкости: фикс или случайный диапазон.
     * При переходе в диапазон границы стартуют с текущей громкости
     * (пока пользователь их не расширит); уже настроенный диапазон не сбрасывается.
     */
    fun setVolumeMode(groupId: Int, alarmId: Int, mode: VolumeMode) {
        editAlarm(groupId, alarmId) { alarm ->
            if (mode == VolumeMode.RANDOM_RANGE && alarm.volumeMode != VolumeMode.RANDOM_RANGE) {
                val current = alarm.volumePercent.coerceIn(0, 100)
                alarm.copy(volumeMode = mode, volumeMin = current, volumeMax = current)
            } else {
                alarm.copy(volumeMode = mode)
            }
        }
    }

    /** Границы диапазона громкости; значения санируются (0 <= min <= max <= 100). */
    fun setVolumeRange(groupId: Int, alarmId: Int, min: Int, max: Int) {
        val (lo, hi) = SoundVolume.sanitizeRange(min, max)
        editAlarm(groupId, alarmId) { it.copy(volumeMin = lo, volumeMax = hi) }
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
        releaseAlarmFolderPermission(groupId, id)
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
        groupOf(groupId)?.alarms?.forEach {
            releaseAlarmPermission(groupId, it.id)
            releaseAlarmFolderPermission(groupId, it.id)
        }
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
    fun setMode(id: Int, mode: SceneMode) {
        val gid = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        setMode(gid, id, mode)
    }

    fun setStartMinutes(groupId: Int, id: Int, minutes: Int?) = editAlarm(groupId, id) { it.copy(startMinutes = minutes) }
    fun setStartMinutes(id: Int, minutes: Int?) {
        val gid = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        setStartMinutes(gid, id, minutes)
    }

    fun setEndMinutes(groupId: Int, id: Int, minutes: Int?) = editAlarm(groupId, id) { it.copy(endMinutes = minutes) }
    fun setEndMinutes(id: Int, minutes: Int?) {
        val gid = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        setEndMinutes(gid, id, minutes)
    }

    fun setLaunchCount(groupId: Int, id: Int, count: Int) = editAlarm(groupId, id) { it.copy(launchCount = count) }
    fun setLaunchCount(id: Int, count: Int) {
        val gid = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        setLaunchCount(gid, id, count)
    }

    fun setInterval(groupId: Int, id: Int, ms: Long) =
        editAlarm(groupId, id) { it.copy(intervalMs = ms.coerceAtLeast(Defaults.MIN_INTERVAL_MS)) }

    fun setInterval(id: Int, ms: Long) {
        val gid = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        setInterval(gid, id, ms)
    }

    fun setVolume(groupId: Int, id: Int, percent: Int) =
        editAlarm(groupId, id) { it.copy(volumePercent = percent.coerceIn(0, 100)) }

    fun setVolume(id: Int, percent: Int) {
        val gid = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        setVolume(gid, id, percent)
    }

    fun setEnabled(groupId: Int, id: Int, enabled: Boolean) = editAlarm(groupId, id) { it.copy(enabled = enabled) }
    fun setEnabled(id: Int, enabled: Boolean) {
        val gid = _selectedGroupId.value ?: _app.value.groups.firstOrNull()?.id ?: return
        setEnabled(gid, id, enabled)
    }

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

    /**
     * Счётчики ревизий: `localRevision` растёт на каждой локальной правке,
     * `savedRevision` — после успешной записи. Пока `savedRevision < localRevision`,
     * значение из хранилища не применяется (см. [applyFromRepo]).
     */
    private var localRevision = 0L
    private var savedRevision = 0L

    /** Записи в DataStore строго по порядку: старый снимок не перебивает новый. */
    private val writeMutex = Mutex()

    /**
     * Отдельная область для записи на диск.
     *
     * Запись в DataStore выполняется НЕ из `viewModelScope` (диспетчер Main):
     * актор DataStore выполняет трансформу записи в контексте вызывающего, и если
     * этот контекст — Main (или тестовый планировщик, который уже завершён),
     * запись «зависает» навсегда и следующие записи тоже. Свой IO-контекст
     * делает запись независимой от диспетчера вызывающего.
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCleared() {
        super.onCleared()
        ioScope.cancel()
    }

    private fun updateApp(transform: (AppConfig) -> AppConfig) {
        val next = transform(_app.value)
        _app.value = next
        val first = next.groups.firstOrNull()
        if (first != null) _configMode.value = first.toRunConfig(next.fadeInEnabled)
        localRevision++
        val revision = localRevision
        saveJob?.cancel()
        saveJob = ioScope.launch {
            delay(300)
            writeMutex.withLock {
                // Пока шла запись, правки могли измениться — тогда пишет более
                // свежий снимок (следующий job), а этот устарел и не нужен.
                if (revision != localRevision) return@withLock
                repo.saveGroups(next)
                savedRevision = revision
                latestFromRepo?.let { applyFromRepo(it) }
            }
            // После любого изменения групп/расписаний — rearm по уже сохранённому
            // конфигу (без второго чтения DataStore и без гонки с очередью).
            runCatching { WakeSchedulerRearm.rearmWith(getApplication(), next) }
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

    /**
     * Освобождение persistable-права на папку будильника.
     * Право отпускается, только если ту же папку не использует
     * ни один другой будильник ни одной группы.
     */
    private fun releaseAlarmFolderPermission(groupId: Int, id: Int) {
        val uri = groupOf(groupId)?.alarms?.firstOrNull { it.id == id }?.folderUri ?: return
        if (uri.isEmpty()) return
        if (!folderUriUsedElsewhere(groupId, id, uri)) {
            AppLog.i("releaseAlarmFolderPermission: освобождаем доступ к папке $uri")
            releaseUri(uri)
        }
    }

    /** Использует ли кто-то (кроме указанного будильника) эту папку. */
    private fun folderUriUsedElsewhere(groupId: Int, alarmId: Int, uri: String): Boolean {
        if (uri.isEmpty()) return false
        return _app.value.groups.flatMap { it.alarms }
            .any {
                it.folderUri == uri &&
                    !(it.id == alarmId && groupOf(groupId)?.alarms?.contains(it) == true)
            }
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
            // Источник-папка: показываем имя папки и число файлов в ней.
            alarm.isFolderSource ->
                "Папка: " + (alarm.folderName ?: "папка") + " (" + alarm.folderFiles.size + ")"
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
