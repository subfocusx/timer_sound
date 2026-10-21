package com.timersound.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.timersound.R
import com.timersound.AppLog
import com.timersound.audio.AudioEngine
import com.timersound.audio.FolderScanResult
import com.timersound.audio.FolderScanner
import com.timersound.audio.FolderSoundPicker
import com.timersound.audio.PreviewPlayer
import com.timersound.audio.SoundRandom
import com.timersound.audio.SoundVolume
import com.timersound.data.FolderStatePatch
import com.timersound.data.PreferencesRepository
import com.timersound.model.AlarmConfig
import com.timersound.model.SoundSourceType
import com.timersound.model.TimerConfig
import com.timersound.timer.TimerSession
import com.timersound.timer.TickPolicy
import com.timersound.timer.TimerState
/**
 * Foreground-сервис: единственный хозяин сессии таймера во время выполнения.
 * Работает поверх блокировки экрана и при свёрнутом приложении, держит
 * персистентное уведомление с кнопками PAUSE/RESUME и STOP.
 *
 * Все команды (START/PAUSE/RESUME/STOP/RESET) попадают в очередь [commands]
 * и исполняются одним consumer-процессом последовательно. Это исключает гонку,
 * когда асинхронный START (чтение конфига) догоняет и перекрывает синхронный
 * STOP: порядок команд всегда совпадает с порядком их отправки.
 *
 * Планирование — на реальном времени (SystemClock.elapsedRealtime), см. [TimerSession].
 */
class TimerSoundService : Service() {

    private data class QueuedCommand(val kind: Command, val groupId: Int = -1)
    private enum class Command { START, PAUSE, RESUME, STOP, RESET, RESTART, TICK, PAUSE_ALL, STOP_ALL, AUTO_START }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    /** Отдельный поток для фоновых записей (курсоры папок) — не блокирует очередь команд. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<QueuedCommand>(Channel.UNLIMITED)
    /** Один TimerSession на каждую активную группу. */
    private val sessions = mutableMapOf<Int, TimerSession>()
    private lateinit var prefs: PreferencesRepository
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null
    private var alarmManager: AlarmManager? = null
    private var usingExactAlarm = false
    private var tickJob: Job? = null
    /** Флаг fade-in глобальный (fadeIn — поле AppConfig). */
    private var fadeInEnabled = false

    /**
     * Курсор «последний сыгранный файл папки» по ключу (groupId, alarmId).
     * Живёт в памяти сервиса (переживает тики без записи в DataStore), сохраняется
     * в конфиг отложенным патчем — без перезапуска сессии и без второго таймера.
     */
    private val folderCursors = mutableMapOf<AudioEngine.RingKey, Int>()

    /** Счётчик подряд идущих неудачных воспроизведений (битый/удалённый файл). */
    private val playErrorStreak = mutableMapOf<AudioEngine.RingKey, Int>()

    /** Отложенное сохранение курсоров папок в DataStore. */
    private var cursorSaveJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.init(filesDir)
        prefs = PreferencesRepository(this)
        createNotificationChannel()
        TimerStateHolder.reset()
        alarmManager = runCatching { getSystemService(Context.ALARM_SERVICE) as AlarmManager }.getOrNull()
        registerThermalListener()
        startCommandConsumer()
        // Автозапуски после перезагрузки/обновления пересчитывает WakeSchedulerRearm.
    }

    private var thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = runCatching { getSystemService(Context.POWER_SERVICE) as PowerManager }.getOrNull()
            ?: return
        thermalStatus = runCatching { pm.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            thermalStatus = status
            AppLog.i("TimerSoundService: thermal status -> $status")
            if (status >= PowerManager.THERMAL_STATUS_CRITICAL && sessions.values.any { it.isActive }) {
                showThermalNotification()
            }
        }
        thermalListener = listener
        runCatching { pm.addThermalStatusListener(mainExecutor, listener) }
    }

    private fun unregisterThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = runCatching { getSystemService(Context.POWER_SERVICE) as PowerManager }.getOrNull()
        thermalListener?.let { runCatching { pm?.removeThermalStatusListener(it) } }
        thermalListener = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val gid = intent?.getIntExtra(EXTRA_GROUP_ID, -1) ?: -1
        when (intent?.action) {
            ACTION_START -> commands.trySend(QueuedCommand(Command.START, gid))
            ACTION_AUTO_START -> {
                intentCache[gid] = intent.getLongExtra(EXTRA_PLAN_ANCHOR, -1L).takeIf { it >= 0 }
                commands.trySend(QueuedCommand(Command.AUTO_START, gid))
            }
            ACTION_PAUSE -> commands.trySend(QueuedCommand(Command.PAUSE, gid))
            ACTION_RESUME -> commands.trySend(QueuedCommand(Command.RESUME, gid))
            ACTION_STOP -> commands.trySend(QueuedCommand(Command.STOP, gid))
            ACTION_RESET -> commands.trySend(QueuedCommand(Command.RESET, gid))
            ACTION_RESTART -> commands.trySend(QueuedCommand(Command.RESTART, gid))
            ACTION_PAUSE_ALL -> commands.trySend(QueuedCommand(Command.PAUSE_ALL))
            ACTION_STOP_ALL -> commands.trySend(QueuedCommand(Command.STOP_ALL))
            ACTION_TICK -> commands.trySend(QueuedCommand(Command.TICK))
        }
        return START_NOT_STICKY
    }

    /**
     * Единственный потребитель очереди команд: обрабатывает команды строго
     * по очереди, в одном процессе. Все handleX вызываются только отсюда
     * (кроме авто-остановки из тик-цикла — она идёт после полного старта).
     */
    private fun startCommandConsumer() {
        scope.launch {
            commands.consumeEach { command -> handleCommand(command) }
        }
    }

    /**
     * Гонки START/STOP больше нет: вся цепочка suspend и выполняется строго
     * последовательно внутри consumeEach — handleStart дожидается чтения
     * конфига до того, как consumer заберёт STOP. Отдельного fire-and-forget
     * scope.launch здесь было источником подъёма сессии после остановки.
     */
    private suspend fun handleCommand(cmd: QueuedCommand) {
        when (cmd.kind) {
            Command.START -> handleStart(cmd.groupId)
            Command.AUTO_START -> handleAutoStart(cmd.groupId)
            Command.PAUSE -> handlePause(cmd.groupId)
            Command.RESUME -> handleResume(cmd.groupId)
            Command.STOP -> handleStop(cmd.groupId, completed = false)
            Command.RESET -> handleReset(cmd.groupId)
            Command.RESTART -> handleRestart(cmd.groupId)
            Command.TICK -> onAlarmTick()
            Command.PAUSE_ALL -> handlePauseAll()
            Command.STOP_ALL -> handleStopAll()
        }
    }

    private fun sessionOf(groupId: Int): TimerSession =
        sessions.getOrPut(groupId) { TimerSession() }

    private suspend fun handleStart(groupId: Int, planAnchorWallMs: Long? = null) {
        val app = prefs.appConfig.first()
        fadeInEnabled = app.fadeInEnabled
        val group = app.groups.firstOrNull { it.id == groupId } ?: return
        val runConfig = group.toRunConfig(fadeInEnabled)
        // Папки: проверка доступа + пересканирование (файлы могли добавиться/удалиться).
        val prepared = prepareFolderAlarms(groupId, runConfig)
        if (prepared.playableAlarms().isEmpty()) return
        AudioEngine.stopGroup(groupId)
        PreviewPlayer.stop()
        val session = sessionOf(groupId)
        // Курсор папки стартует с сохранённого lastFolderIndex (порядок продолжается).
        prepared.alarms.filter { it.isFolderSource && it.hasFolder }.forEach { a ->
            folderCursors[AudioEngine.RingKey(groupId, a.id)] = a.lastFolderIndex
        }
        playErrorStreak.keys.removeIf { it.groupId == groupId }
        session.start(prepared, planAnchorWallMs = planAnchorWallMs)
        prefs.setActiveGroups(sessions.keys.filter { sessions[it]?.isActive == true }.toSet())
        Log.i(TAG, "handleStart: group=$groupId playable=${prepared.playableAlarms().size}")
        acquireWakeLock()
        setupMediaSession(playing = true)
        startAsForeground()
        publishGroup(groupId)
        if (handleTickGroup(groupId)) {
            handleAutoStop(groupId, playOutLastRing = true)
            return
        }
        startTickLoop()
        scheduleExactAlarm()
        rearmAsync()
    }

    /**
     * Подготовка будильников-папок перед стартом сессии:
     *  - пересканирование папки (файлы могли измениться);
     *  - проверка persistable-доступа: при отзыве доступа будильник выпадает из
     *    сессии (остальные будильники группы продолжают работать), а в конфиге
     *    фиксируется флаг «доступ потерян» — UI покажет понятное сообщение.
     *
     * Возвращает конфиг запуска с обновлёнными данными папок.
     */
    private suspend fun prepareFolderAlarms(groupId: Int, config: TimerConfig): TimerConfig {
        val hasFolders = config.alarms.any { it.isFolderSource && it.hasFolder }
        if (!hasFolders) return config
        val patches = mutableListOf<FolderStatePatch>()
        val updated: List<AlarmConfig> = config.alarms.map { alarm ->
            if (!alarm.isFolderSource || !alarm.hasFolder) return@map alarm
            when (val scan = FolderScanner.scan(applicationContext, Uri.parse(alarm.folderUri))) {
                is FolderScanResult.Ok -> {
                    val files = scan.files
                    // Имя папки могло остаться пустым (например, провайдер не отдал его
                    // при выборе) — восстанавливаем его из документа дерева.
                    val name = alarm.folderName
                        ?: FolderScanner.queryDisplayName(applicationContext, Uri.parse(alarm.folderUri))
                    if (files != alarm.folderFiles || name != alarm.folderName) {
                        patches.add(
                            FolderStatePatch(
                                alarmId = alarm.id,
                                folderFiles = files,
                                folderAccessLost = false,
                                folderName = name,
                            ),
                        )
                    } else if (alarm.folderAccessLost) {
                        patches.add(
                            FolderStatePatch(alarmId = alarm.id, folderAccessLost = false),
                        )
                    }
                    alarm.copy(
                        folderFiles = files,
                        folderName = name,
                        folderAccessLost = false,
                        lastFolderIndex = alarm.lastFolderIndex.coerceIn(-1, files.size - 1),
                    )
                }
                is FolderScanResult.AccessLost -> {
                    AppLog.w(
                        "TimerSoundService: доступ к папке потерян, ch=${alarm.id} uri=${alarm.folderUri}",
                    )
                    if (alarm.folderAccessLost && alarm.folderFiles.isEmpty()) {
                        alarm
                    } else {
                        patches.add(
                            FolderStatePatch(
                                alarmId = alarm.id,
                                folderFiles = emptyList(),
                                folderAccessLost = true,
                            ),
                        )
                        alarm.copy(folderFiles = emptyList(), folderAccessLost = true)
                    }
                }
            }
        }
        if (patches.isNotEmpty()) {
            runCatching { prefs.patchFolderState(groupId, patches) }
                .onFailure { AppLog.e("TimerSoundService: не удалось сохранить состояние папок: ${it.message}") }
        }
        return config.copy(alarms = updated)
    }

    /** Автозапуск по расписанию: пропуск, если группа уже RUNNING/PAUSED. */
    private suspend fun handleAutoStart(groupId: Int) {
        val existing = sessions[groupId]
        if (existing != null && existing.isActive) {
            AppLog.i("TimerSoundService: автозапуск группы $groupId пропущен (уже активна)")
            return
        }
        val intent = intentCache.remove(groupId)
        handleStart(groupId, planAnchorWallMs = intent)
    }

    private suspend fun handlePause(groupId: Int) {
        sessions[groupId]?.pause()
        releaseWakeLockIfIdle()
        updateMediaSession(playing = sessions.values.any { it.isRunning })
        refreshNotification()
        publishGroup(groupId)
        rearmAsync()
    }

    private suspend fun handleResume(groupId: Int) {
        sessions[groupId]?.resume()
        acquireWakeLock()
        updateMediaSession(playing = true)
        publishGroup(groupId)
        scheduleExactAlarm()
        rearmAsync()
    }

    /** Restart = атомарно Stop + Start в очереди команд, с новым epoch. */
    private suspend fun handleRestart(groupId: Int) {
        stopGroupInternal(groupId)
        handleStart(groupId)
    }

    private suspend fun handleReset(groupId: Int) {
        sessions[groupId]?.reset()
        TimerStateHolder.removeGroup(groupId)
        sessions.remove(groupId)
        AudioEngine.stopGroup(groupId)
        afterGroupGone()
    }

    private fun handlePauseAll() {
        sessions.keys.toList().forEach { sessions[it]?.pause() }
        releaseWakeLockIfIdle()
        updateMediaSession(playing = false)
        refreshNotification()
        publishAll()
    }

    private suspend fun handleStopAll() {
        val ids = sessions.keys.toList()
        for (id in ids) stopGroupInternal(id)
        afterGroupGone()
    }

    /** STOP группы: чистим её сессию и звук; следующий автозапуск остаётся по расписанию. */
    private suspend fun handleStop(groupId: Int, completed: Boolean) {
        stopGroupInternal(groupId)
        if (completed) showCompletedNotification()
        afterGroupGone()
    }

    private suspend fun stopGroupInternal(groupId: Int) {
        cancelExactAlarm()
        sessions[groupId]?.stop()
        AudioEngine.stopGroup(groupId)
        TimerStateHolder.removeGroup(groupId)
        sessions.remove(groupId)
        // Курсор папки сохраняем — последовательный порядок продолжится в следующей сессии.
        persistFolderCursors()
        playErrorStreak.keys.removeIf { it.groupId == groupId }
        prefs.setActiveGroups(sessions.keys.filter { sessions[it]?.isActive == true }.toSet())
    }

    /** Сервис останавливается только когда не осталось активных сессий. */
    private suspend fun afterGroupGone() {
        publishAll()
        if (sessions.values.any { it.isActive }) {
            scheduleExactAlarm()
            refreshNotification()
            rearmAsync()
            return
        }
        stopTickLoop()
        cancelExactAlarm()
        releaseWakeLock()
        releaseMediaSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        prefs.setActiveGroups(emptySet())
        prefs.setSessionActive(false)
        refreshNotification()
        stopSelf()
    }

    private fun releaseWakeLockIfIdle() {
        if (sessions.values.none { it.isRunning }) releaseWakeLock()
    }

    private val intentCache = mutableMapOf<Int, Long?>()

    private suspend fun handleAutoStop(groupId: Int, playOutLastRing: Boolean = false) {
        val session = sessions[groupId] ?: return
        publishGroup(groupId, stateOverride = TimerState.COMPLETED)
        if (!playOutLastRing) {
            AudioEngine.stopGroup(groupId)
            handleStop(groupId, completed = true)
            return
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        showCompletedNotification()
        val completedEpoch = session.epoch
        scope.launch {
            awaitLastRingFinished()
            if (sessions[groupId]?.epoch != completedEpoch) {
                AppLog.i("TimerSoundService: teardown пропущен, сессия уже новая (epoch $completedEpoch)")
                return@launch
            }
            if (sessions[groupId]?.state == TimerState.COMPLETED) {
                AudioEngine.stopGroup(groupId)
                handleStop(groupId, completed = true)
            }
        }
    }

    /**
     * Ждём естественного конца последнего срабатывания. Потолок [MAX_LAST_RING_MS]
     * страхует от «вечного» Ringtone (битый/очень длинный файл), пол [MIN_LAST_RING_MS] —
     * от устройств, где `Ringtone.isPlaying()` не успевает/не умеет отвечать true.
     */
    private suspend fun awaitLastRingFinished() {
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed >= MAX_LAST_RING_MS) return
            if (elapsed >= MIN_LAST_RING_MS && !AudioEngine.isAnyPlaying()) return
            delay(100)
        }
    }

    // ------------------------------------------------------------------ tick loop

    private fun startTickLoop() {
        stopTickLoop()
        tickJob = scope.launch {
            var counter = 0
            var lastWakeLockRenewMs = 0L
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                var workLeft = false
                for ((gid, session) in sessions.toMap()) {
                    if (!session.isRunning) continue
                    workLeft = true
                    val autoHit = session.countdownToAutoStopMs(now)?.let { it <= 0L } == true
                    if (autoHit) {
                        handleAutoStop(gid, playOutLastRing = false)
                        continue
                    }
                    if (handleTickGroup(gid, now)) {
                        handleAutoStop(gid, playOutLastRing = true)
                    }
                }
                if (sessions.values.any { it.isActive }) {
                    workLeft = true
                    val overheatedNow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
                    } else false
                    if (!overheatedNow && now - lastWakeLockRenewMs >= WAKE_LOCK_RENEW_MS) {
                        renewWakeLock()
                        lastWakeLockRenewMs = now
                    }
                    if (counter % 4 == 0) refreshNotification()
                    publishAll()
                }
                if (!workLeft && sessions.isEmpty()) return@launch
                counter++
                val overheated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
                } else false
                if (overheated) delay(5_000L) else delay(adaptiveTickDelayMs(now))
            }
        }
    }

    internal fun adaptiveTickDelayMs(nowElapsedMs: Long): Long {
        val next = sessions.values.mapNotNull { it.nextEventElapsedMs(nowElapsedMs) }.minOrNull()
        return TickPolicy.delayMs(next?.let { it - nowElapsedMs })
    }

    private fun stopTickLoop() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun handleTickGroup(groupId: Int, nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean {
        val session = sessions[groupId] ?: return false
        val completed = session.tick(nowElapsedMs) { channel ->
            playChannel(groupId, channel)
        }
        publishGroup(groupId)
        return completed || session.state == TimerState.COMPLETED
    }

    /**
     * Одно срабатывание канала: резолв источника (файл/папка) и громкости, затем звук.
     *
     * Один тик = один play на будильник: соседние будильники не делят плеер
     * (ключ звука — (groupId, alarmId)), фаза интервалов не сдвигается.
     *
     * Битый файл в папке не блокирует будильник: неудачные индексы исключаются,
     * и следующий файл папки играет в том же срабатывании.
     */
    private fun playChannel(groupId: Int, alarm: AlarmConfig) {
        val key = AudioEngine.RingKey(groupId, alarm.id)
        val streak = playErrorStreak[key] ?: 0
        if (streak >= MAX_CONSECUTIVE_PLAY_ERRORS) {
            // Битый файл: не крутимся на нём — будильник пропускается до конца сессии.
            AppLog.w(
                "TimerSoundService: ch=${alarm.id} пропущен ($streak ошибок подряд), " +
                    "источник=${alarm.sourceType}",
            )
            return
        }
        // Индексы файлов папки, которые не удалось воспроизвести в этом срабатывании.
        val failed = mutableSetOf<Int>()
        var attempt = 0
        while (attempt < MAX_PLAY_ATTEMPTS) {
            val resolved = resolveForPlay(groupId, alarm, failed)
            if (resolved == null) {
                // Играть нечего: папка пуста или все файлы не воспроизвелись.
                if (failed.isEmpty()) return
                break
            }
            val ok = AudioEngine.play(
                applicationContext,
                resolved.alarm,
                fadeIn = fadeInEnabled,
                groupId = groupId,
            )
            if (ok) {
                playErrorStreak.remove(key)
                return
            }
            attempt++
            if (!alarm.isFolderSource) break
            failed += resolved.folderIndex
        }
        val newStreak = (playErrorStreak[key] ?: 0) + 1
        playErrorStreak[key] = newStreak
        AppLog.w(
            "TimerSoundService: ch=${alarm.id} не воспроизведён ($newStreak ошибок подряд), " +
                "источник=${alarm.sourceType}",
        )
    }

    /**
     * Резолв источника и громкости для очередного срабатывания.
     *
     * @param exclude индексы файлов папки, которые уже не воспроизвелись.
     */
    private fun resolveForPlay(
        groupId: Int,
        alarm: AlarmConfig,
        exclude: Set<Int> = emptySet(),
    ): ResolvedPlay? {
        val volume = SoundVolume.resolveFor(alarm, SoundRandom.random)
        if (!alarm.isFolderSource) {
            return ResolvedPlay(alarm.copy(volumePercent = volume), -1)
        }
        val files = alarm.folderFiles
        if (files.isEmpty()) {
            AppLog.w("TimerSoundService: ch=${alarm.id} — папка пуста, сигнал пропущен")
            return null
        }
        val key = AudioEngine.RingKey(groupId, alarm.id)
        val cursor = folderCursors[key] ?: alarm.lastFolderIndex
        val index = FolderSoundPicker.nextIndex(
            size = files.size,
            mode = alarm.folderPlayMode,
            lastIndex = cursor,
            random = SoundRandom.random,
            exclude = exclude,
        )
        if (index < 0) return null
        folderCursors[key] = index
        scheduleCursorSave()
        val picked = files[index]
        AppLog.i(
            "TimerSoundService: папка ch=${alarm.id} idx=$index/${files.size} " +
                "file=\"${picked.name}\" vol=$volume mode=${alarm.folderPlayMode}",
        )
        return ResolvedPlay(
            alarm = alarm.copy(
                sourceType = SoundSourceType.FILE,
                fileUri = picked.uri,
                fileName = picked.name,
                volumePercent = volume,
            ),
            folderIndex = index,
        )
    }

    /** Готовый к воспроизведению будильник + индекс выбранного файла папки (-1 — не папка). */
    private data class ResolvedPlay(val alarm: AlarmConfig, val folderIndex: Int)

    /** Отложенное сохранение курсоров папок: без записи в DataStore на каждом тике. */
    private fun scheduleCursorSave() {
        if (cursorSaveJob?.isActive == true) return
        cursorSaveJob = ioScope.launch {
            delay(CURSOR_SAVE_DEBOUNCE_MS)
            persistFolderCursors()
        }
    }
    private suspend fun persistFolderCursors() {
        val byGroup = folderCursors.entries.groupBy { it.key.groupId }
        for ((groupId, entries) in byGroup) {
            val patches = entries.map { (key, index) ->
                FolderStatePatch(alarmId = key.alarmId, lastFolderIndex = index)
            }
            runCatching { prefs.patchFolderState(groupId, patches) }
                .onFailure { AppLog.e("TimerSoundService: не удалось сохранить курсор папки: ${it.message}") }
        }
    }

    private suspend fun onAlarmTick() {
        var anyRunning = false
        for ((gid, session) in sessions.toMap()) {
            if (session.state != TimerState.RUNNING) continue
            anyRunning = true
            handleTickGroup(gid)
            if (session.state == TimerState.COMPLETED) handleAutoStop(gid, playOutLastRing = true)
        }
        if (anyRunning) scheduleExactAlarm() else rearmAsync()
    }


    /**
     * Один общий точный алярм: минимум из следующих событий активных сессий.
     * Автозапуски групп держит WakeSchedulerRearm (отдельный PendingIntent).
     */
    private fun scheduleExactAlarm() {
        val manager = alarmManager
        val anyRunning = sessions.values.any { it.state == TimerState.RUNNING }
        if (manager == null || !anyRunning || !canScheduleExactAlarm()) {
            usingExactAlarm = false
            return
        }
        val now = SystemClock.elapsedRealtime()
        val nextEvent = sessions.values.mapNotNull { it.nextEventElapsedMs(now) }.minOrNull() ?: return
        val delayMs = (nextEvent - now).coerceAtLeast(1_000L)
        val triggerAtWallMs = System.currentTimeMillis() + delayMs
        val pending = exactAlarmPendingIntent()
        try {
            manager.cancel(pending)
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtWallMs, null), pending)
            usingExactAlarm = true
        } catch (error: SecurityException) {
            usingExactAlarm = false
            Log.w(TAG, "exact alarm denied: ${error.message}")
        } catch (error: Exception) {
            usingExactAlarm = false
            Log.w(TAG, "exact alarm scheduling failed: ${error.message}")
        }
    }

    private fun exactAlarmPendingIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            ALARM_TICK_REQUEST_CODE,
            commandIntent(this, ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun cancelExactAlarm() {
        if (!usingExactAlarm) return
        alarmManager?.cancel(exactAlarmPendingIntent())
        usingExactAlarm = false
        Log.i(TAG, "exactAlarmCancelled=true")
    }

    private fun canScheduleExactAlarm(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager?.canScheduleExactAlarms() == true
    }

    /** Перевооружение по свежему конфигу: без гонки с очередью команд. */
    private suspend fun rearmAsync() {
        rearmAsync(prefs.appConfig.first())
    }

    private fun rearmAsync(app: com.timersound.model.AppConfig) {
        WakeSchedulerRearm.rearmWith(this, app)
    }

    private fun publishGroup(groupId: Int, stateOverride: TimerState? = null) {
        val session = sessions[groupId] ?: return
        val now = SystemClock.elapsedRealtime()
        val snap = session.snapshot(now)
        val forced = stateOverride?.let {
            TimerSession.SessionSnapshot(it, snap.fires, snap.untilNextMs, snap.untilEndMs, snap.remainingFires, snap.totalFiresCount)
        } ?: snap
        TimerStateHolder.publishSnapshot(groupId, forced, now, AudioEngine.droppedRingsCount.value)
    }

    private fun publishAll() {
        val now = SystemClock.elapsedRealtime()
        sessions.toMap().forEach { (gid, s) ->
            TimerStateHolder.publishSnapshot(gid, s.snapshot(now), now, AudioEngine.droppedRingsCount.value)
        }
    }

    private fun showCompletedNotification() {
        val title = "Timer Sound: завершено"
        val text = "Серия срабатываний завершена, все каналы остановлены."
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .setWhen(System.currentTimeMillis())
            .build()
        getSystemService(NotificationManager::class.java).notify(COMPLETED_NOTIFICATION_ID, n)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Работа таймера",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Состояние интервального таймера"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startAsForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    /** А3: устройство критически греется — предлагаем приостановить всё. */
    private fun showThermalNotification() {
        val text = "Устройство перегревается — рассмотрите паузу таймера."
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle("Timer Sound: перегрев")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .setWhen(System.currentTimeMillis())
            .addAction(0, "Пауза всех", commandPendingIntent(ACTION_PAUSE_ALL, 4, -1))
            .build()
        getSystemService(NotificationManager::class.java).notify(THERMAL_NOTIFICATION_ID, n)
    }
    /** Агрегат: «N групп активно; ближайшее: …». */
    private fun buildNotification(): Notification {
        val now = SystemClock.elapsedRealtime()
        val active = sessions.toMap().filter { it.value.isActive }
        val nearest = active.mapNotNull { (gid, s) ->
            s.snapshot(now).untilNextMs?.let { gid to it }
        }.minByOrNull { it.second }
        val content = buildString {
            append("${active.size} групп активно")
            if (nearest != null) {
                append(" · ближайшее: группа ${nearest.first} через ${TimerSession.formatHms(nearest.second)}")
            }
            val dropped = AudioEngine.droppedRingsCount.value
            if (dropped > 0) append(" · Часть сигналов пропущена")
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle("Timer Sound")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent())
        val firstRunning = active.entries.firstOrNull { it.value.isRunning }?.key
        val firstPaused = active.entries.firstOrNull { it.value.state == TimerState.PAUSED }?.key
        if (firstRunning != null) {
            builder.addAction(0, "Пауза всех", commandPendingIntent(ACTION_PAUSE_ALL, 1, -1))
        }
        if (firstPaused != null) {
            builder.addAction(0, "Продолжить ${firstPaused}", commandPendingIntent(ACTION_RESUME, 2, firstPaused))
        }
        builder.addAction(0, "Стоп всех", commandPendingIntent(ACTION_STOP_ALL, 3, -1))
        return builder.build()
    }

    private fun refreshNotification() {
        if (sessions.values.any { it.isActive }) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, com.timersound.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun commandPendingIntent(action: String, requestCode: Int, groupId: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode, commandIntent(this, action, groupId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // ------------------------------------------------------------------ wake lock / media session

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.timersound:timer")
            .apply {
                setReferenceCounted(false) // Защита от утечки при повторных acquire/release
                acquire(WAKE_LOCK_TIMEOUT_MS) // Короткий таймаут + продления из тик-цикла
            }
    }

    /** А2: продлить wake lock, пока сессия реально активна. */
    private fun renewWakeLock() {
        val lock = wakeLock
        if (lock != null && lock.isHeld) {
            runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) }
        } else {
            acquireWakeLock()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * MediaSession обязателен для FGS типа mediaPlayback на Android 15+ (targetSdk 37):
     * без активной сессии система остановит сервис. Кнопки медиа не используем —
     * управление через уведомление.
     */
    private fun setupMediaSession(playing: Boolean) {
        if (mediaSession == null) {
            mediaSession = MediaSession(this, "TimerSound").apply {
                isActive = true
            }
        }
        updateMediaSession(playing)
    }

    private fun updateMediaSession(playing: Boolean) {
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    0L,
                    1f,
                )
                .build()
        )
    }

    private fun releaseMediaSession() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onDestroy() {
        unregisterThermalListener()
        cancelExactAlarm()
        stopTickLoop()
        AudioEngine.releaseAll()
        releaseWakeLock()
        releaseMediaSession()
        // НЕ стираем статус «Завершено»: handleStop(completed = true) ставит COMPLETED
        // прямо перед stopSelf(), а onDestroy() здесь не должен возвращать UI в IDLE —
        // иначе пользователь видит «сброс» вместо «Завершено / Запустить заново».
        // Стираем только активную (RUNNING/PAUSED) сессию — например, при убийстве
        // процесса посреди работы, когда UI не должен залипать на «Идёт».
        if (TimerStateHolder.ui.value.state != TimerState.COMPLETED) {
            TimerStateHolder.reset()
        }
        commands.close()
        scope.cancel()
        ioScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TimerSound"
        const val ACTION_START = "com.timersound.intent.START"
        const val ACTION_AUTO_START = "com.timersound.intent.AUTO_START"
        const val ACTION_PAUSE = "com.timersound.intent.PAUSE"
        const val ACTION_RESUME = "com.timersound.intent.RESUME"
        const val ACTION_STOP = "com.timersound.intent.STOP"
        const val ACTION_RESET = "com.timersound.intent.RESET"
        const val ACTION_RESTART = "com.timersound.intent.RESTART"
        const val ACTION_PAUSE_ALL = "com.timersound.intent.PAUSE_ALL"
        const val ACTION_STOP_ALL = "com.timersound.intent.STOP_ALL"
        const val ACTION_TICK = "com.timersound.intent.TICK"
        const val EXTRA_GROUP_ID = "extra_group_id"
        const val EXTRA_PLAN_ANCHOR = "extra_plan_anchor"
        private const val ALARM_TICK_REQUEST_CODE = 4001

        /** Минимум, который последнее срабатывание должно отзвучать перед teardown. */
        private const val MIN_LAST_RING_MS = 1_500L

        /** Потолок ожидания конца последнего сигнала (защита от «вечного» Ringtone). */
        private const val MAX_LAST_RING_MS = 120_000L
        /** А2: таймаут одного удержания wake lock; продлевается из тик-цикла. */
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60_000L
        /** А2: как часто продлевать wake lock, пока сессия активна. */
        private const val WAKE_LOCK_RENEW_MS = 60_000L
        /** Сколько попыток воспроизведения на один тик (битый файл в папке — пробуем следующий). */
        private const val MAX_PLAY_ATTEMPTS = 3
        /** После скольких ошибок подряд будильник перестаёт пытаться играть. */
        private const val MAX_CONSECUTIVE_PLAY_ERRORS = 3
        /** Пауза перед отложенным сохранением курсоров папок в DataStore, мс. */
        private const val CURSOR_SAVE_DEBOUNCE_MS = 3_000L
        const val NOTIFICATION_ID = 1001
        const val COMPLETED_NOTIFICATION_ID = 1002
        const val THERMAL_NOTIFICATION_ID = 1003
        const val CHANNEL_ID = "timer_running"

        fun commandIntent(context: Context, action: String, groupId: Int = -1): Intent =
            Intent(context, TimerSoundService::class.java).setAction(action)
                .putExtra(EXTRA_GROUP_ID, groupId)

        /** Request code уникален на группу и действие. */
        fun requestCodeFor(action: String, groupId: Int): Int =
            (action.hashCode() * 31 + groupId) and 0x0FFF_FFFF
    }
}