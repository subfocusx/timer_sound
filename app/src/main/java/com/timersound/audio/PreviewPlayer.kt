package com.timersound.audio

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.timersound.model.ChannelConfig

/**
 * Компонент плеера в карточке (media3 ExoPlayer) с API play/pause/stop/release.
 * Один экземпляр на приложение — реиспользуется по всему UI.
 */
object PreviewPlayer {

    private const val TAG = "PreviewPlayer"

    @Volatile
    private var player: ExoPlayer? = null

    @Volatile
    private var currentAlarmId: Int? = null

    /** Параметры состояния для UI — позиция и продолжительность в мс. */
    data class State(
        val alarmId: Int,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
    )

    /** Свойство для чтения из UI — null, если никакой preview не активен. */
    val state: State?
        get() = player?.let { p ->
            currentAlarmId?.let { id ->
                State(
                    alarmId = id,
                    isPlaying = p.isPlaying,
                    positionMs = p.currentPosition,
                    durationMs = p.duration,
                )
            }
        }

    /** Запустить воспроизведение channelConfig (URI или встроенный beep). */
    fun play(context: Context, channelConfig: ChannelConfig) {
        stop()
        val uri = alarmConfig.fileUri
        val isBeep = uri == "@beep"
        val mediaItem = when {
            isBeep -> {
                val packageName = context.packageName
                val rawId = context.resources.getIdentifier("beep", "raw", packageName)
                if (rawId == 0) {
                    Log.w(TAG, "Встроенный beep не найден: alarmId=${alarmConfig.id}")
                    return
                }
                val uriString = "android.resource://$packageName/$rawId"
                MediaItem.fromUri(uriString)
            }
            uri.isNotEmpty() -> MediaItem.fromUri(uri)
            else -> {
                Log.w(TAG, "Попытка воспроизвести без файла: alarmId=${alarmConfig.id}")
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
            }
        })
        player = exoPlayer
        currentAlarmId = alarmConfig.id
        Log.d(TAG, "Запущен превью-плеер для alarmId=${alarmConfig.id}")
    }

    /** Приостановить, если играет. */
    fun pause() {
        if (player?.isPlaying == true) {
            player?.playWhenReady = false
            Log.d(TAG, "Пауза превью-плеера")
        }
    }

    /** Продолжить, если на паузе. */
    fun resume() {
        if (player?.isPlaying != true) {
            player?.playWhenReady = true
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
    }

    /** Освободить без остановки (использовать при уходе из UI). */
    fun release() {
        stop()
    }
}
