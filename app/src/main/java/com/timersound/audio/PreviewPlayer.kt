package com.timersound.audio

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.timersound.model.AlarmConfig
import com.timersound.model.Defaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorJob

/**
 * Компонент плеера в карточке (media3 ExoPlayer).
 * Один экземпляр на процесс — реиспользуется по всему UI.
 * Ровно одна активная дорожка: запуск на другом будильнике останавливает предыдущий.
 * Прогресс обновляется каждые 250 мс через внутренний ticker.
 */
object PreviewPlayer {

    private const val TAG = "PreviewPlayer"

    private val scope = CoroutineScope(Dispatchers.Main + supervisorJob())

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    @Volatile
    private var player: ExoPlayer? = null

    @Volatile
    private var currentAlarmId: Int? = null

    private var tickerJob: Job? = null

    data class State(
        val alarmId: Int,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
    )

    // ------------------------------------------------------------------ ticker

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun startTicker() {
        stopTicker()
        tickerJob = scope.launch {
            while (isActive) {
                delay(250)
                val p = player ?: return@launch
                val id = currentAlarmId ?: return@launch
                _state.value = State(
                    alarmId = id,
                    isPlaying = p.isPlaying,
                    positionMs = p.currentPosition,
                    durationMs = p.duration,
                )
            }
        }
    }

    private fun updateState() {
        player?.let { p ->
            val id = currentAlarmId ?: return
            _state.value = State(
                alarmId = id,
                isPlaying = p.isPlaying,
                positionMs = p.currentPosition,
                durationMs = p.duration,
            )
        }
    }

    // ------------------------------------------------------------------ API

    /** Запустить воспроизведение будильника (URI или встроенный beep). */
    fun play(context: Context, alarm: AlarmConfig) {
        stop()
        val uri = alarm.fileUri
        val isBeep = uri == Defaults.BUILT_IN_BEEP
        val mediaItem = when {
            isBeep -> {
                val packageName = context.packageName
                val rawId = context.resources.getIdentifier("beep", "raw", packageName)
                if (rawId == 0) {
                    Log.w(TAG, "Встроенный beep не найден: alarmId=${alarm.id}")
                    return
                }
                val uriString = "android.resource://$packageName/$rawId"
                MediaItem.fromUri(uriString)
            }
            uri.isNotEmpty() -> MediaItem.fromUri(uri)
            else -> {
                Log.w(TAG, "Попытка воспроизвести без файла: alarmId=${alarm.id}")
                return
            }
        }

        val exoPlayer = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
                true,
            )
            .build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                if (playbackState == Player.STATE_ENDED) {
                    stop()
                }
                updateState()
            }
        })
        player = exoPlayer
        currentAlarmId = alarm.id
        updateState()
        startTicker()
        Log.d(TAG, "Запущен превью-плеер для alarmId=${alarm.id}")
    }

    /** Приостановить, если играет. */
    fun pause() {
        if (player?.isPlaying == true) {
            player?.playWhenReady = false
            updateState()
            Log.d(TAG, "Пауза превью-плеера")
        }
    }

    /** Продолжить, если на паузе. */
    fun resume() {
        if (player?.isPlaying != true) {
            player?.playWhenReady = true
            updateState()
            Log.d(TAG, "Продолжение превью-плеера")
        }
    }

    /** Остановить и освободить ресурсы. */
    fun stop() {
        player?.let {
            it.stop()
            it.release()
            Log.d(TAG, "Остановлен и освобожден превью-плеер")
        }
        player = null
        currentAlarmId = null
        stopTicker()
        _state.value = null
    }

    /** Освободить без остановки (использовать при уходе из UI). */
    fun release() {
        stop()
    }

    /** Запустить ticker только если состояние изменилось — оптимизация обновлений StateFlow. */
    private fun startTicker() {
        stopTicker()
        var lastPosition: Long = -1
        var lastIsPlaying: Boolean = false
        tickerJob = scope.launch {
            while (isActive) {
                delay(250)
                val p = player ?: return@launch
                val id = currentAlarmId ?: return@launch
                val newPosition = p.currentPosition
                val newIsPlaying = p.isPlaying
                // Обновляем StateFlow только при изменении состояния
                if (newPosition != lastPosition || newIsPlaying != lastIsPlaying) {
                    _state.value = State(
                        alarmId = id,
                        isPlaying = newIsPlaying,
                        positionMs = newPosition,
                        durationMs = p.duration,
                    )
                    lastPosition = newPosition
                    lastIsPlaying = newIsPlaying
                }
            }
        }
    }
}
