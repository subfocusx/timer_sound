package com.timersound.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import com.timersound.R
import com.timersound.model.AlarmConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Audio engine: one independent [Ringtone] per channel - timers ring through the
 * system clock/alarm stream, so several channels can sound independently (no
 * sequential queue), with per-channel volume ([Ringtone.setVolume]).
 *
 * SINGLE source of truth: [MediaPlayer] silently drops on this device (no audio
 * on Android 9 / EMUI 29 - even the bundled beep doesn't play; the media service
 * releases the player immediately). [Ringtone] is the system alarm path that
 * actually rings the phone, and it does not need foreground media focus.
 *
 * Single in-process instance: used by the service (session) and the UI layer
 * (preview). All rings are always stopped on stop / service destroy ([releaseAll])
 * - no leaks.
 */
object AudioEngine {

    private const val TAG = "TimerSound"

    /** Максимум одновременных Ringtone (D-1). При превышении — стоп самых старых. */
    const val MAX_CONCURRENT_RINGS = 10
    /** Длительность плавного нарастания: 20 шагов по 100 мс ≈ 2 секунды. */
    const val FADE_IN_STEP_MS = 100L
    const val FADE_IN_STEPS = 20

    /** Session channel players (key = channel id). LinkedHashMap для FIFO (стоп самых старых). */
    private val players = LinkedHashMap<Int, Ringtone>()
    private val previewPlayers = mutableMapOf<Int, Ringtone>()
    private val lock = Any()
    private val fadeScope = CoroutineScope(Dispatchers.Default)
    /**
     * Сколько раз за сессию сработал лимит одновременных звуков (старые глушились).
     * Сбрасывается в [releaseAll]. Читается сервисом для видимого предупреждения.
     */
    private val _droppedRingsCount = MutableStateFlow(0)
    val droppedRingsCount: StateFlow<Int> = _droppedRingsCount

    /** Чистая функция: сколько каналов будет заглушено при данном заполнении. */
    internal fun droppedFor(activeCount: Int, incomingNewChannel: Boolean, limit: Int = MAX_CONCURRENT_RINGS): Int {
        if (!incomingNewChannel) return 0
        return (activeCount + 1 - limit).coerceAtLeast(0)
    }
    /**
     * Ring channel signal once (interval fire). If the channel is still sounding
     * from the previous interval - the ringtone is stopped and re-created.
     */
    fun play(context: Context, alarm: AlarmConfig, fadeIn: Boolean = false) {
        val ringRef: Ringtone?
        val targetVolume: Float
        synchronized(lock) {
            try {
                val ring = createRingtone(context, alarm)
                if (ring == null) {
                    Log.w(TAG, "AudioEngine.play: NO ringtone ch=${alarm.id} uri=${alarm.fileUri} - aborted")
                    return
                }
                players.remove(alarm.id)?.let { runCatching { it.stop() } }
                val dropped = droppedFor(players.size, incomingNewChannel = true)
                // MAX_CONCURRENT_RINGS: стоп самых старых если лимит достигнут.
                while (players.size >= MAX_CONCURRENT_RINGS) {
                    val oldestId = players.entries.first().key
                    Log.w(TAG, "MAX_CONCURRENT_RINGS=$MAX_CONCURRENT_RINGS: стоп самого старого id=$oldestId")
                    players.remove(oldestId)?.let { runCatching { it.stop() } }
                }
                if (dropped > 0) _droppedRingsCount.value += dropped
                targetVolume = alarm.volumePercent / 100f
                ring.setVolume(if (fadeIn) 0f else targetVolume)
                players[alarm.id] = ring
                Log.i(TAG, "AudioEngine.play: calling ring.play() ch=${alarm.id} uri=${alarm.fileUri}")
                ring.play()
                Log.i(TAG, "AudioEngine.play: returned OK ch=${alarm.id}")
                ringRef = ring
            } catch (e: Exception) {
                Log.e(TAG, "AudioEngine.play: EXCEPTION ch=${alarm.id} uri=${alarm.fileUri}: ${e}", e)
                return
            }
        }
        // Плавное нарастание — вне lock, чтобы не держать монитор ~2 секунды.
        if (fadeIn && ringRef != null) {
            fadeScope.launch {
                repeat(FADE_IN_STEPS) { i ->
                    delay(FADE_IN_STEP_MS)
                    val stillCurrent = synchronized(lock) { players[alarm.id] === ringRef }
                    if (!stillCurrent) return@launch
                    runCatching { ringRef.setVolume(targetVolume * (i + 1) / FADE_IN_STEPS) }
                }
            }
        }
    }

    /** One-shot preview of a channel signal (not tied to the timer session). */
    fun preview(context: Context, alarm: AlarmConfig) {
        synchronized(lock) {
            try {
                val ring = createRingtone(context, alarm) ?: return
                previewPlayers.remove(alarm.id)?.let { runCatching { it.stop() } }
                ring.setVolume(alarm.volumePercent / 100f)
                previewPlayers[alarm.id] = ring
                ring.play()
            } catch (e: Exception) {
                Log.w(TAG, "AudioEngine.preview: ch=${alarm.id} uri=${alarm.fileUri}: $e")
            }
        }
    }
    /**
     * true, пока звучит хотя бы один сценарный сигнал (не preview).
     * Нужен сервису, чтобы дать последнему срабатыванию конечного режима
     * (Один раз / N раз / Случайно) доиграть файл, а не глушить его тем же
     * тиком, который его запустил.
     */
    fun isAnyPlaying(): Boolean = synchronized(lock) {
        players.values.any { runCatching { it.isPlaying() }.getOrDefault(false) }
    }

    /** Stop and release a specific channel. */
    fun stopChannel(channelId: Int) {
        synchronized(lock) {
            players.remove(channelId)?.let { runCatching { it.stop() } }
            previewPlayers.remove(channelId)?.let { runCatching { it.stop() } }
        }
    }

    /** Stop everything and release all rings (STOP, auto-stop, service destroy). */
    fun releaseAll() {
        synchronized(lock) {
            players.values.forEach { runCatching { it.stop() } }
            players.clear()
            previewPlayers.values.forEach { runCatching { it.stop() } }
            previewPlayers.clear()
            _droppedRingsCount.value = 0
        }
    }

    private fun createRingtone(context: Context, alarm: AlarmConfig): Ringtone? {
        val uriStr = if (alarm.isBuiltInBeep) {
            "android.resource://${context.packageName}/${R.raw.beep}"
        } else {
            alarm.fileUri
        }
        Log.i(TAG, "AudioEngine.createRingtone: ch=${alarm.id} uri=$uriStr")
        return try {
            val uri = Uri.parse(uriStr)
            val ring = RingtoneManager.getRingtone(context, uri)
            Log.i(TAG, "AudioEngine.getRingtone: OK ch=${alarm.id} ring=$ring")
            ring.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            ring
        } catch (e: Exception) {
            Log.e(TAG, "AudioEngine.getRingtone: FAILED ch=${alarm.id} uri=$uriStr: ${e}", e)
            null
        }
    }
}
