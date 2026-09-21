package com.timersound.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import com.timersound.R
import com.timersound.model.ChannelConfig

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

    /** Session channel players (key = channel id) and preview players. */
    private val players = mutableMapOf<Int, Ringtone>()
    private val previewPlayers = mutableMapOf<Int, Ringtone>()
    private val lock = Any()

    /**
     * Ring channel signal once (interval fire). If the channel is still sounding
     * from the previous interval - the ringtone is stopped and re-created.
     */
    fun play(context: Context, channel: ChannelConfig) {
        synchronized(lock) {
            try {
                val ring = createRingtone(context, channel)
                if (ring == null) {
                    Log.w(TAG, "AudioEngine.play: NO ringtone ch=${channel.id} uri=${channel.fileUri} - aborted")
                    return
                }
                players.remove(channel.id)?.let { runCatching { it.stop() } }
                ring.setVolume(channel.volumePercent / 100f)
                players[channel.id] = ring
                Log.i(TAG, "AudioEngine.play: calling ring.play() ch=${channel.id} uri=${channel.fileUri}")
                ring.play()
                Log.i(TAG, "AudioEngine.play: returned OK ch=${channel.id}")
            } catch (e: Exception) {
                Log.e(TAG, "AudioEngine.play: EXCEPTION ch=${channel.id} uri=${channel.fileUri}: ${e}", e)
            }
        }
    }

    /** One-shot preview of a channel signal (not tied to the timer session). */
    fun preview(context: Context, channel: ChannelConfig) {
        synchronized(lock) {
            try {
                val ring = createRingtone(context, channel) ?: return
                previewPlayers.remove(channel.id)?.let { runCatching { it.stop() } }
                ring.setVolume(channel.volumePercent / 100f)
                previewPlayers[channel.id] = ring
                ring.play()
            } catch (_: Exception) {
            }
        }
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
        }
    }

    private fun createRingtone(context: Context, channel: ChannelConfig): Ringtone? {
        val uriStr = if (channel.isBuiltInBeep) {
            "android.resource://${context.packageName}/${R.raw.beep}"
        } else {
            channel.fileUri
        }
        Log.i(TAG, "AudioEngine.createRingtone: ch=${channel.id} uri=$uriStr")
        return try {
            val uri = Uri.parse(uriStr)
            val ring = RingtoneManager.getRingtone(context, uri)
            Log.i(TAG, "AudioEngine.getRingtone: OK ch=${channel.id} ring=$ring")
            ring.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            ring
        } catch (e: Exception) {
            Log.e(TAG, "AudioEngine.getRingtone: FAILED ch=${channel.id} uri=$uriStr: ${e}", e)
            null
        }
    }
}