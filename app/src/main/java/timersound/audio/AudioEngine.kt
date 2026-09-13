package timersound.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import com.timersound.R
import timersound.model.ChannelConfig

/**
 * Audio engine: one independent [MediaPlayer] per channel - sounds play
 * simultaneously (no sequential queue), with per-channel volume.
 *
 * Single in-process instance: used by the service (session) and the UI layer
 * (preview). All players are always released on stop / service destroy
 * ([releaseAll]) - no leaks.
 *
 * Only async preparation ([MediaPlayer.prepareAsync]): [preview] is called from
 * the main thread (onClick), a synchronous [MediaPlayer.prepare] on large SAF
 * files would block the UI; the session tick loop also never blocks on prepare.
 */
object AudioEngine {

    /** Session channel players (key = channel id) and preview players. */
    private val players = mutableMapOf<Int, MediaPlayer>()
    private val previewPlayers = mutableMapOf<Int, MediaPlayer>()
    private val lock = Any()

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var audioManager: AudioManager? = null
    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    private fun requestAudioFocusIfNeeded(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audioFocusListener == null) {
            val listener = AudioManager.OnAudioFocusChangeListener {}
            if (am.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            ) {
                audioManager = am
                audioFocusListener = listener
            }
        }
    }

    /**
     * Play channel file once (interval fire). If the channel is still sounding
     * from the previous interval - the player is recreated.
     */
    fun play(context: Context, channel: ChannelConfig) {
        synchronized(lock) {
            try {
                requestAudioFocusIfNeeded(context)
                val player = createPlayer(context, channel) ?: return
                players.remove(channel.id)?.let { runCatching { it.release() } }
                players[channel.id] = player
                attachLifecycle(player, channel.id) { releasePlayer(players, channel.id, it) }
                player.startWhenPrepared(channel.id)
            } catch (_: Exception) {
                // Failure of one channel must not crash the whole session.
            }
        }
    }

    /** One-shot preview of a channel file (not tied to the timer session). */
    fun preview(context: Context, channel: ChannelConfig) {
        synchronized(lock) {
            try {
                val player = createPlayer(context, channel) ?: return
                previewPlayers.remove(channel.id)?.let { runCatching { it.release() } }
                previewPlayers[channel.id] = player
                attachLifecycle(player, channel.id) { releasePlayer(previewPlayers, channel.id, it) }
                player.startWhenPrepared(channel.id)
            } catch (_: Exception) {
            }
        }
    }

    /** Stop and release a specific channel. */
    fun stopChannel(channelId: Int) {
        synchronized(lock) {
            players.remove(channelId)?.let { runCatching { it.release() } }
            previewPlayers.remove(channelId)?.let { runCatching { it.release() } }
        }
    }

    /** Stop everything and release all players (STOP, auto-stop, service destroy). */
    fun releaseAll() {
        synchronized(lock) {
            players.values.forEach { runCatching { it.release() } }
            players.clear()
            previewPlayers.values.forEach { runCatching { it.release() } }
            previewPlayers.clear()
            audioFocusListener?.let { audioManager?.abandonAudioFocus(it) }
            audioFocusListener = null
            audioManager = null
        }
    }

    private fun releasePlayer(map: MutableMap<Int, MediaPlayer>, channelId: Int, player: MediaPlayer) {
        synchronized(lock) {
            // Only if it is still the same player (it may have been recreated).
            if (map[channelId] === player) map.remove(channelId)
            runCatching { player.release() }
        }
    }

    /**
     * Async start: preparation (prepareAsync) does not block the calling thread
     * (main for preview); the player starts in onPrepared. A player recreated
     * before preparation does not start - checked by identity.
     */
    private fun MediaPlayer.startWhenPrepared(channelId: Int) {
        setOnPreparedListener { p ->
            synchronized(lock) {
                if (players[channelId] !== p && previewPlayers[channelId] !== p) {
                    runCatching { p.release() }
                } else {
                    runCatching { p.start() }
                }
            }
        }
        prepareAsync()
    }

    private fun attachLifecycle(
        player: MediaPlayer,
        channelId: Int,
        onFinished: (MediaPlayer) -> Unit,
    ) {
        player.setOnCompletionListener { p -> synchronized(lock) { onFinished(p) } }
        player.setOnErrorListener { p, _, _ -> synchronized(lock) { onFinished(p) }; true }
        // onPrepared is added in startWhenPrepared (prepareAsync is there too).
    }

    private fun createPlayer(context: Context, channel: ChannelConfig): MediaPlayer? {
        val mp = MediaPlayer(context)
        mp.setAudioAttributes(attributes)
        val uri = if (channel.isBuiltInBeep) {
            // Built-in beep also plays via a Uri source (SDK 37 has no
            // MediaPlayer.create(Context, resid, ...) overloads):
            // android.resource://<package>/<raw-id>
            Uri.parse("android.resource://${context.packageName}/${R.raw.beep}")
        } else {
            Uri.parse(channel.fileUri)
        }
        // SAF: persistable read permission was taken when the file was picked.
        mp.setDataSource(context, uri)
        mp.setVolume(channel.volumePercent / 100f, channel.volumePercent / 100f)
        return mp
    }
}