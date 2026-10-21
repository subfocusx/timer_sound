package com.timersound.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaExtractor
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
import java.util.concurrent.ConcurrentHashMap

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

    /** Уникальный ключ звука: (groupId, alarmId). */
    data class RingKey(val groupId: Int, val alarmId: Int)

    /**
     * Кэш проверки «файл вообще играбелен» по uri.
     *
     * Проверка идёт на каждом срабатывании, а читать и декодировать файл
     * каждый раз дорого. Кэшируем только успех: битый файл пользователь может
     * заменить на рабочий — тогда файл снова проверится.
     */
    private val playableCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Проверка, что файл читается и декодируется (а не просто существует).
     *
     * Зачем: `Ringtone` на битом файле НЕ бросает исключение — он молча
     * переключается на системный рингтон (в logcat: `Open file failed, goto
     * fallback, uri=content://settings/system/ringtone`). Из-за этого сломанный
     * файл из папки играл системным звонком вместо тишины, а счётчик ошибок
     * не рос. Проверяем файл ДО воспроизведения и отдаём false — звонок
     * пропускается, сервис берёт следующий файл папки.
     *
     * Проверка идёт через [MediaExtractor] — тот же разборщик, что и у
     * [android.media.MediaPlayer]: если дорожек нет, файл не декодируется.
     */
    internal fun isPlayable(context: Context, uri: Uri): Boolean {
        val key = uri.toString()
        playableCache[key]?.let { return it }
        val ok = try {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                extractor.trackCount > 0
            } finally {
                extractor.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioEngine.isPlayable: файл не читается uri=$uri: ${e.message}")
            false
        }
        if (ok) playableCache[key] = true
        return ok
    }

    /** Session channel players (key = RingKey). LinkedHashMap для FIFO (стоп самых старых). */
    private val players = LinkedHashMap<RingKey, Ringtone>()
    private val previewPlayers = mutableMapOf<RingKey, Ringtone>()
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
     *
     * @return true, если сигнал запущен; false — файл недоступен/битый (звонок пропускается).
     */
    fun play(context: Context, alarm: AlarmConfig, fadeIn: Boolean = false, groupId: Int = 0): Boolean {
        return playKeyed(context, RingKey(groupId, alarm.id), alarm, fadeIn)
    }

    /** Ключ (groupId, alarmId): стоп одной группы не глушит звук другой. */
    fun playKeyed(context: Context, key: RingKey, alarm: AlarmConfig, fadeIn: Boolean = false): Boolean {
        // Проверка файла — ДО блокировки: это чтение с носителя, держать под ним lock нельзя.
        if (!alarm.isBuiltInBeep && !isPlayable(context, Uri.parse(alarm.fileUri))) {
            Log.w(TAG, "AudioEngine.play: NO playable source key=$key uri=${alarm.fileUri} - aborted")
            return false
        }
        val handle: PlayHandle? = synchronized(lock) {
            try {
                val ring = createRingtone(context, alarm)
                if (ring == null) {
                    Log.w(TAG, "AudioEngine.play: NO ringtone key=$key uri=${alarm.fileUri} - aborted")
                    return@synchronized null
                }
                players.remove(key)?.let { runCatching { it.stop() } }
                val dropped = droppedFor(players.size, incomingNewChannel = true)
                while (players.size >= MAX_CONCURRENT_RINGS) {
                    val oldestId = players.entries.first().key
                    Log.w(TAG, "MAX_CONCURRENT_RINGS=$MAX_CONCURRENT_RINGS: стоп самого старого id=$oldestId")
                    players.remove(oldestId)?.let { runCatching { it.stop() } }
                }
                if (dropped > 0) _droppedRingsCount.value += dropped
                val targetVolume = alarm.volumePercent / 100f
                ring.setVolume(if (fadeIn) 0f else targetVolume)
                players[key] = ring
                Log.i(TAG, "AudioEngine.play: calling ring.play() key=$key uri=${alarm.fileUri}")
                ring.play()
                Log.i(TAG, "AudioEngine.play: returned OK key=$key")
                PlayHandle(ring, targetVolume)
            } catch (e: Exception) {
                Log.e(TAG, "AudioEngine.play: EXCEPTION key=$key uri=${alarm.fileUri}: ${e}", e)
                null
            }
        }
        val started = handle ?: return false
        if (fadeIn) {
            fadeScope.launch {
                repeat(FADE_IN_STEPS) { i ->
                    delay(FADE_IN_STEP_MS)
                    val stillCurrent = synchronized(lock) { players[key] === started.ring }
                    if (!stillCurrent) return@launch
                    runCatching { started.ring.setVolume(started.volume * (i + 1) / FADE_IN_STEPS) }
                }
            }
        }
        return true
    }

    /** Запущенный звонок и его целевая громкость (для плавного нарастания). */
    private data class PlayHandle(val ring: Ringtone, val volume: Float)

    /** One-shot preview of a channel signal (not tied to the timer session). */
    fun preview(context: Context, alarm: AlarmConfig, groupId: Int = 0) {
        // Битый файл Ringtone не отвергает, а молча играет системным рингтоном —
        // проверяем заранее, чтобы превью было тихим (см. [isPlayable]).
        if (!alarm.isBuiltInBeep && !isPlayable(context, Uri.parse(alarm.fileUri))) {
            Log.w(TAG, "AudioEngine.preview: ch=${alarm.id} uri=${alarm.fileUri} - файл не играбелен")
            return
        }
        synchronized(lock) {
            try {
                val key = RingKey(groupId, alarm.id)
                val ring = createRingtone(context, alarm) ?: return
                previewPlayers.remove(key)?.let { runCatching { it.stop() } }
                ring.setVolume(alarm.volumePercent / 100f)
                previewPlayers[key] = ring
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
    fun stopChannel(channelId: Int, groupId: Int = 0) {
        stopKey(RingKey(groupId, channelId))
    }

    /** Стоп по ключу (groupId, alarmId). */
    fun stopKey(key: RingKey) {
        synchronized(lock) {
            players.remove(key)?.let { runCatching { it.stop() } }
            previewPlayers.remove(key)?.let { runCatching { it.stop() } }
        }
    }

    /** Стоп всех звуков одной группы (STOP группы не глушит другие). */
    fun stopGroup(groupId: Int) {
        synchronized(lock) {
            players.keys.filter { it.groupId == groupId }.forEach { k ->
                players.remove(k)?.let { runCatching { it.stop() } }
            }
            previewPlayers.keys.filter { it.groupId == groupId }.forEach { k ->
                previewPlayers.remove(k)?.let { runCatching { it.stop() } }
            }
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
