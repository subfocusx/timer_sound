package com.timersound.service

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
import com.timersound.audio.AudioEngine
import com.timersound.data.PreferencesRepository
import com.timersound.timer.TimerSession
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

    private enum class Command { START, PAUSE, RESUME, STOP, RESET }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val session = TimerSession()
    private lateinit var prefs: PreferencesRepository
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null
    private var tickJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesRepository(this)
        createNotificationChannel()
        TimerStateHolder.reset()
        startCommandConsumer()
    }

    // ------------------------------------------------------------------ commands

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> commands.trySend(Command.START)
            ACTION_PAUSE -> commands.trySend(Command.PAUSE)
            ACTION_RESUME -> commands.trySend(Command.RESUME)
            ACTION_STOP -> commands.trySend(Command.STOP)
            ACTION_RESET -> commands.trySend(Command.RESET)
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

    private fun handleCommand(command: Command) {
        when (command) {
            Command.START -> handleStart()
            Command.PAUSE -> handlePause()
            Command.RESUME -> handleResume()
            Command.STOP -> handleStop(completed = false)
            Command.RESET -> handleReset()
        }
    }

    private fun handleStart() {
        scope.launch {
            val config = prefs.config.first()
            if (config.playableChannels().isEmpty()) {
                handleStop(completed = false)
                return@launch
            }
            AudioEngine.releaseAll()
            session.start(config)
            acquireWakeLock()
            setupMediaSession(playing = true)
            startAsForeground()
            startTickLoop()
        }
    }

    private fun handlePause() {
        session.pause()
        releaseWakeLock()
        updateMediaSession(playing = false)
        refreshNotification()
        publishUiSnapshot()
    }

    private fun handleResume() {
        session.resume()
        acquireWakeLock()
        updateMediaSession(playing = true)
        publishUiSnapshot()
    }

    private fun handleReset() {
        session.reset()
        TimerStateHolder.reset()
        if (!session.isActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** STOP / завершение: освобождает аудио, снимает FGS, останавливает сервис. */
    private fun handleStop(completed: Boolean) {
        stopTickLoop()
        AudioEngine.releaseAll()
        session.stop()
        releaseWakeLock()
        releaseMediaSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (completed) {
            showCompletedNotification()
            TimerStateHolder.setState(TimerState.COMPLETED)
        } else {
            TimerStateHolder.reset()
        }
        stopSelf()
    }

    /** Авто-остановка из тик-цикла: вызывается после того, как старт завершён. */
    private fun handleAutoStop() {
        publishUiSnapshot(stateOverride = TimerState.COMPLETED)
        handleStop(completed = true)
    }

    // ------------------------------------------------------------------ tick loop

    private fun startTickLoop() {
        stopTickLoop()
        tickJob = scope.launch {
            var counter = 0
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val autoStopped: Boolean = if (session.isRunning) {
                    session.tick(now) { channel -> AudioEngine.play(applicationContext, channel) }
                } else {
                    false
                }
                if (autoStopped) {
                    handleAutoStop()
                    return@launch
                }
                if (session.isActive) {
                    // Уведомление обновляем ~1 раз в секунду, состояние — каждый тик.
                    if (counter % 4 == 0) refreshNotification()
                    publishUiSnapshot()
                }
                counter++
                delay(250)
            }
        }
    }

    private fun stopTickLoop() {
        tickJob?.cancel()
        tickJob = null
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
        val text = "Авто-остановка: все каналы остановлены."
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
            )
        )
    }

    // ------------------------------------------------------------------ wake lock / media session

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.timersound:timer")
            .apply { acquire() }
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
        stopTickLoop()
        AudioEngine.releaseAll()
        releaseWakeLock()
        releaseMediaSession()
        TimerStateHolder.reset()
        commands.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.timersound.intent.START"
        const val ACTION_PAUSE = "com.timersound.intent.PAUSE"
        const val ACTION_RESUME = "com.timersound.intent.RESUME"
        const val ACTION_STOP = "com.timersound.intent.STOP"
        const val ACTION_RESET = "com.timersound.intent.RESET"
        const val NOTIFICATION_ID = 1001
        const val COMPLETED_NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "timer_running"

        fun commandIntent(context: Context, action: String): Intent =
            Intent(context, TimerSoundService::class.java).setAction(action)
    }
}