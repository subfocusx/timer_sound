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
import com.timersound.audio.PreviewPlayer
import com.timersound.data.PreferencesRepository
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

    private enum class Command { START, PAUSE, RESUME, STOP, RESET, TICK }

    /**
     * Все команды и тики идут СТРОГО последовательно (один поток): у сессии два
     * входа срабатывания — точный алярм (AlarmManager) и тик-цикл, — а
     * [TimerSession] не потокобезопасна. При параллельном входе две нити видели
     * одно и то же «пора звучать», обе жгли звук, а одна из них обнуляла
     * расписание — сессия «N раз» завершалась на первом же срабатывании.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val session = TimerSession()
    private lateinit var prefs: PreferencesRepository
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null
    private var alarmManager: AlarmManager? = null
    private var usingExactAlarm = false
    private var tickJob: Job? = null
    /** Флаг fade-in из конфига на момент старта сессии. */
    private var fadeInEnabled = false

    override fun onCreate() {
        super.onCreate()
        AppLog.init(filesDir)
        prefs = PreferencesRepository(this)
        createNotificationChannel()
        TimerStateHolder.reset()
        alarmManager = runCatching { getSystemService(Context.ALARM_SERVICE) as AlarmManager }.getOrNull()
        registerThermalListener()
        startCommandConsumer()
    }

    /**
     * А3: подписка на термальный статус (API 29+). SEVERE+ — зажимаем тик на
     * редкий и не продлеваем wake lock агрессивно; CRITICAL — предлагаем
     * пользователю приостановить сессию через уведомление.
     */
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
            if (status >= PowerManager.THERMAL_STATUS_CRITICAL && session.isActive) {
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

    // ------------------------------------------------------------------ commands

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> commands.trySend(Command.START)
            ACTION_PAUSE -> commands.trySend(Command.PAUSE)
            ACTION_RESUME -> commands.trySend(Command.RESUME)
            ACTION_STOP -> commands.trySend(Command.STOP)
            ACTION_RESET -> commands.trySend(Command.RESET)
            ACTION_TICK -> commands.trySend(Command.TICK)
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
    private suspend fun handleCommand(command: Command) {
        when (command) {
            Command.START -> handleStart()
            Command.PAUSE -> handlePause()
            Command.RESUME -> handleResume()
            Command.STOP -> handleStop(completed = false)
            Command.RESET -> handleReset()
            Command.TICK -> onAlarmTick()
        }
    }

    private suspend fun handleStart() {
        val config = prefs.config.first()
        if (config.playableAlarms().isEmpty()) {
            handleStop(completed = false)
            return
        }
        AudioEngine.releaseAll()
        PreviewPlayer.stop()
        fadeInEnabled = config.fadeInEnabled
        prefs.setSessionActive(true)
        session.start(config)
        Log.i(TAG, "handleStart: playableTasks=${config.playableAlarms().size}, nextEvent=${session.nextEventElapsedMs()}")
        acquireWakeLock()
        setupMediaSession(playing = true)
        startAsForeground()
        // Первый тик — сразу после старта: здесь важен ТОЛЬКО факт завершения
        // сессии. Срабатывание канала — не повод её останавливать (иначе первый
        // же звук глушился releaseAll() и сессия уходила в COMPLETED).
        if (handleTick()) {
            handleAutoStop(playOutLastRing = true)
            return
        }
        startTickLoop()
        scheduleExactAlarm()
    }

    private fun handlePause() {
        session.pause()
        cancelExactAlarm()
        releaseWakeLock()
        updateMediaSession(playing = false)
        refreshNotification()
        publishUiSnapshot()
    }

    private fun handleResume() {
        session.resume()
        scheduleExactAlarm()
        acquireWakeLock()
        updateMediaSession(playing = true)
        publishUiSnapshot()
    }

    private fun handleReset() {
        cancelExactAlarm()
        session.reset()
        TimerStateHolder.reset()
        if (!session.isActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** STOP / завершение: освобождает аудио, снимает FGS, останавливает сервис. */
    private suspend fun handleStop(completed: Boolean) {
        cancelExactAlarm()
        stopTickLoop()
        AudioEngine.releaseAll()
        session.stop()
        releaseWakeLock()
        releaseMediaSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        prefs.setSessionActive(false)
        if (completed) {
            showCompletedNotification()
            TimerStateHolder.setState(TimerState.COMPLETED)
        } else {
            TimerStateHolder.reset()
        }
        stopSelf()
    }

    /**
     * Завершение сессии: сценарий отыгран целиком либо сработала авто-остановка.
     *
     * [playOutLastRing] = true, когда сессию закрыло её собственное последнее
     * срабатывание (ONCE_TIME / INTERVAL / RANDOM). Такой звук только что
     * стартовал и должен доиграть файл: без этого `handleStop` вызывался тем же
     * тиком, который запустил Ringtone, — `releaseAll()` глушил его мгновенно, и
     * режимы «Один раз»/«N раз»/«Случайно» выглядели как «звука нет».
     *
     * Авто-остановка по таймеру ([playOutLastRing] = false) глушит всё сразу —
     * так задумано: пользователь задал момент остановки.
     */
    private suspend fun handleAutoStop(playOutLastRing: Boolean = false) {
        stopTickLoop()
        cancelExactAlarm()
        publishUiSnapshot(stateOverride = TimerState.COMPLETED)
        if (!playOutLastRing) {
            handleStop(completed = true)
            return
        }
        // Сессия завершена: FGS-уведомление снимаем сразу, но процесс доживает
        // последний звук (Ringtone играет файл один раз) и только потом уходит.
        stopForeground(STOP_FOREGROUND_REMOVE)
        showCompletedNotification()
        TimerStateHolder.setState(TimerState.COMPLETED)
        scope.launch {
            awaitLastRingFinished()
            // Пока звук доигрывал, пользователь мог нажать «Стоп» или «Запустить заново»:
            // тогда teardown уже сделан (или начата новая сессия) — не трогаем.
            if (session.state == TimerState.COMPLETED) handleStop(completed = true)
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
                val autoStopDeadlineHit: Boolean = if (session.isRunning) {
                    val autoStopDeadline = session.countdownToAutoStopMs(now)
                    autoStopDeadline != null && autoStopDeadline <= 0L
                } else {
                    false
                }
                val scenarioCompleted: Boolean = if (session.isRunning && !autoStopDeadlineHit) {
                    // Возврат handleTick означает, что серия отработала/сессия завершена:
                    // его НЕЛЬЗЯ терять, иначе сервис останется висеть FGS со старым уведомлением.
                    handleTick(now)
                } else {
                    false
                }
                if (autoStopDeadlineHit) {
                    handleAutoStop(playOutLastRing = false)
                    return@launch
                }
                if (scenarioCompleted) {
                    // Серия закрылась последним срабатыванием — даём звуку доиграть.
                    handleAutoStop(playOutLastRing = true)
                    return@launch
                }
                if (session.isActive) {
                    // А2: короткие продления wake lock (~2 мин), пока сессия реально активна —
                    // лок не протухает посреди долгой сессии и не висит дольше нужного.
                    // А3: при перегреве не продлеваем агрессивно.
                    val overheatedNow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
                    } else false
                    if (!overheatedNow && now - lastWakeLockRenewMs >= WAKE_LOCK_RENEW_MS) {
                        renewWakeLock()
                        lastWakeLockRenewMs = now
                    }
                    // Уведомление обновляем ~1 раз в секунду, состояние — каждый тик.
                    if (counter % 4 == 0) refreshNotification()
                    publishUiSnapshot()
                } else {
                    // Сессия завершилась/сброшена вне тик-цикла (алярм, авто-остановка, STOP):
                    // публикуем актуальное состояние и выходим, не держа FGS вхолостую.
                    publishUiSnapshot(stateOverride = session.state)
                    if (session.state == TimerState.COMPLETED) handleAutoStop(playOutLastRing = true)
                    return@launch
                }
                counter++
                // А3: при SEVERE+ полагаемся на точный будильник, тик — минимум.
                val overheated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
                } else false
                if (overheated) {
                    delay(5_000L)
                } else {
                    // А1: адаптивный шаг — вдали от события спим дольше, не греем CPU.
                    delay(adaptiveTickDelayMs(now))
                }
            }
        }
    }

    /**
     * А1: шаг тика по расстоянию до ближайшего события. Рядом (< 5 с) — частый
     * тик для точности; дальше — редкий, точное срабатывание страхует
     * scheduleExactAlarm. Чистая функция — тестируется без Android.
     */
    internal fun adaptiveTickDelayMs(
        nowElapsedMs: Long,
        nextEventMs: Long? = session.nextEventElapsedMs(nowElapsedMs),
    ): Long = TickPolicy.delayMs(nextEventMs?.let { it - nowElapsedMs })

    private fun stopTickLoop() {
        tickJob?.cancel()
        tickJob = null
    }

    /**
     * Один тик сессии. Возвращает true, только если сценарий ЗАВЕРШЁН
     * (все конечные серии отыграны или наступила авто-остановка).
     * Само срабатывание канала завершением не является.
     */
    private fun handleTick(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean {
        val nextEvent = session.nextEventElapsedMs(nowElapsedMs)
        val wall = System.currentTimeMillis()
        Log.i(TAG, "tick state=${session.state} nextEventElapsedMs=$nextEvent wall=$wall")
        val completed = session.tick(nowElapsedMs) { channel ->
            Log.i(TAG, "tick alarmFired=true state=${session.state} nextEventElapsedMs=${session.nextEventElapsedMs(nowElapsedMs)} wall=${System.currentTimeMillis()}")
            AudioEngine.play(applicationContext, channel, fadeIn = fadeInEnabled)
        }
        return completed || session.state == TimerState.COMPLETED
    }

    private suspend fun onAlarmTick() {
        if (session.state != TimerState.RUNNING) return
        // Это пробуждение сервиса точным алярмом, а НЕ гарантированное срабатывание канала:
        // о самом срабатывании пишет строка «tick alarmFired=true» из handleTick.
        Log.i(TAG, "alarmWake=true state=${session.state} nextEventElapsedMs=${session.nextEventElapsedMs()} wall=${System.currentTimeMillis()}")
        handleTick()
        if (session.state == TimerState.COMPLETED) {
            handleAutoStop(playOutLastRing = true)
        } else {
            scheduleExactAlarm()
        }
    }

    private fun scheduleExactAlarm() {
        val manager = alarmManager
        if (manager == null || session.state != TimerState.RUNNING || !canScheduleExactAlarm()) {
            usingExactAlarm = false
            return
        }

        val nextEvent = session.nextEventElapsedMs() ?: return
        val delayMs = (nextEvent - SystemClock.elapsedRealtime()).coerceAtLeast(1_000L)
        val triggerAtWallMs = System.currentTimeMillis() + delayMs
        val pending = exactAlarmPendingIntent()

        try {
            manager.cancel(pending)
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtWallMs, null), pending)
            usingExactAlarm = true
            Log.i(TAG, "alarmScheduled=true state=${session.state} nextEventElapsedMs=$nextEvent delayMs=$delayMs wall=$triggerAtWallMs")
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
        // Только AlarmManager.canScheduleExactAlarms(): он учитывает и appop SCHEDULE_EXACT_ALARM,
        // и USE_EXACT_ALARM (обычное разрешение, выдаётся при установке).
        // Отдельная проверка самого SCHEDULE_EXACT_ALARM отсекала объявившие USE_EXACT_ALARM
        // приложения: на API 33+ она возвращает DENIED, хотя капабилити — true, и точный
        // будильник не планировался вообще (проверено на API 35: DENIED + canSchedule=true).
        return alarmManager?.canScheduleExactAlarms() == true
    }

    // ------------------------------------------------------------------ notification

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

    private fun buildNotification(): Notification {
        val state = session.state
        val content = buildString {
            append(if (state == TimerState.RUNNING) "Идет" else "Пауза")
            val cd = session.countdownToAutoStopMs()
            if (cd != null) {
                append(" · До авто-остановки: ").append(TimerSession.formatHms(cd))
            }
            append(" · Следующий звук: ").append(session.nextSoundDescription())
            val dropped = AudioEngine.droppedRingsCount.value
            if (dropped > 0) {
                append(" · Часть сигналов пропущена (много звучит одновременно)")
            }
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle("Timer Sound")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent())
        if (state == TimerState.RUNNING) {
            builder.addAction(0, "Пауза", commandPendingIntent(ACTION_PAUSE, 1))
        } else if (state == TimerState.PAUSED) {
            builder.addAction(0, "Продолжить", commandPendingIntent(ACTION_RESUME, 2))
        }
        builder.addAction(0, "Стоп", commandPendingIntent(ACTION_STOP, 3))
        return builder.build()
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

    /** А3: устройство критически греется — предлагаем приостановить сессию. */
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
            .addAction(0, "Пауза", commandPendingIntent(ACTION_PAUSE, 4))
            .build()
        getSystemService(NotificationManager::class.java).notify(THERMAL_NOTIFICATION_ID, n)
    }

    private fun refreshNotification() {
        if (session.isActive) {
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

    private fun commandPendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode, commandIntent(this, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // ------------------------------------------------------------------ UI snapshot

    private fun publishUiSnapshot(stateOverride: TimerState? = null) {
        val now = SystemClock.elapsedRealtime()
        TimerStateHolder.set(
            TimerStateHolder.Ui(
                state = stateOverride ?: session.state,
                nextSound = session.nextSoundDescription(now),
                autoStop = session.countdownToAutoStopMs(now)?.let { TimerSession.formatHms(it) } ?: "",
                droppedRingsCount = AudioEngine.droppedRingsCount.value,
            )
        )
    }

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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TimerSound"
        const val ACTION_START = "com.timersound.intent.START"
        const val ACTION_PAUSE = "com.timersound.intent.PAUSE"
        const val ACTION_RESUME = "com.timersound.intent.RESUME"
        const val ACTION_STOP = "com.timersound.intent.STOP"
        const val ACTION_RESET = "com.timersound.intent.RESET"
        const val ACTION_TICK = "com.timersound.intent.TICK"
        private const val ALARM_TICK_REQUEST_CODE = 4001

        /** Минимум, который последнее срабатывание должно отзвучать перед teardown. */
        private const val MIN_LAST_RING_MS = 1_500L

        /** Потолок ожидания конца последнего сигнала (защита от «вечного» Ringtone). */
        private const val MAX_LAST_RING_MS = 120_000L
        /** А2: таймаут одного удержания wake lock; продлевается из тик-цикла. */
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60_000L
        /** А2: как часто продлевать wake lock, пока сессия активна. */
        private const val WAKE_LOCK_RENEW_MS = 60_000L
        const val NOTIFICATION_ID = 1001
        const val COMPLETED_NOTIFICATION_ID = 1002
        const val THERMAL_NOTIFICATION_ID = 1003
        const val CHANNEL_ID = "timer_running"

        fun commandIntent(context: Context, action: String): Intent =
            Intent(context, TimerSoundService::class.java).setAction(action)
    }
}