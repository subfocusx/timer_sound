package com.timersound.timer

import com.timersound.model.ChannelConfig
import com.timersound.model.SceneMode
import java.util.Calendar
import java.util.Random

/** Чистая математика планирования: wall-время -> elapsed-время, режимы и RANDOM. */
object SceneScheduler {

    const val MS_PER_MIN = 60_000L
    const val MS_PER_DAY = 86_400_000L

    fun offset(nowElapsedMs: Long, nowWallMs: Long): Long = nowElapsedMs - nowWallMs

    /** Локальная полночь (в тестах — UTC) как wall-мс от эпохи. */
    fun localMidnightWall(nowWallMs: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = nowWallMs
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /** Ближайшее НАСТУПЛЕНИЕ HH:MM: сегодня, если будущее; иначе завтра. */
    fun nextClockElapsed(nowElapsedMs: Long, nowWallMs: Long, targetMinutes: Int): Long {
        val today = localMidnightWall(nowWallMs) + targetMinutes.toLong() * MS_PER_MIN
        val wall = if (today > nowWallMs) today else today + MS_PER_DAY
        return wall + offset(nowElapsedMs, nowWallMs)
    }

    /** Первое срабатывание REPEAT: сразу (null) или в ближайшее HH:MM. */
    fun initialRepeatFire(nowElapsedMs: Long, nowWallMs: Long, startMinutes: Int?): Long =
        if (startMinutes == null) nowElapsedMs else nextClockElapsed(nowElapsedMs, nowWallMs, startMinutes)

    /** Моменты конечных режимов (ONCE/INTERVAL/RANDOM), по возрастанию. REPEAT -> пустой. */
    fun fireTimesFor(channel: ChannelConfig, nowElapsedMs: Long, nowWallMs: Long): List<Long> {
        when (channel.mode) {
            SceneMode.ONCE_TIME -> return listOf(nextClockElapsed(nowElapsedMs, nowWallMs, requireStart(channel)))
            SceneMode.INTERVAL -> {
                val first = nextClockElapsed(nowElapsedMs, nowWallMs, requireStart(channel))
                val count = channel.launchCount.coerceAtLeast(1)
                val step = channel.intervalMs.coerceAtLeast(1_000L)
                return (0 until count).map { first + it.toLong() * step }
            }
            SceneMode.RANDOM -> {
                val start = requireStart(channel)
                val end = requireEnd(channel)
                val span = end - start
                val count = channel.launchCount.coerceIn(1, span)
                val dayStartWall = localMidnightWall(nowWallMs)
                val windowStartWall = dayStartWall + start.toLong() * MS_PER_MIN
                val originWall = if (windowStartWall > nowWallMs) windowStartWall else windowStartWall + MS_PER_DAY
                return randomMinutes(span, count, dayStartWall).sorted()
                    .map { originWall + it.toLong() * MS_PER_MIN + offset(nowElapsedMs, nowWallMs) }
            }
            SceneMode.REPEAT -> return emptyList()
        }
    }

    /** n различных случайных минут в [0, span). Детерминированно от seed. */
    fun randomMinutes(span: Int, n: Int, seed: Long): MutableList<Int> {
        val random = Random(seed)
        val picked = mutableSetOf<Int>()
        while (picked.size < n) picked += random.nextInt(span)
        return picked.toMutableList()
    }

    private fun requireStart(channel: ChannelConfig): Int =
        channel.startMinutes ?: throw IllegalArgumentException("startMinutes required for ${channel.mode}")

    private fun requireEnd(channel: ChannelConfig): Int =
        channel.endMinutes ?: throw IllegalArgumentException("endMinutes required for RANDOM")
}
