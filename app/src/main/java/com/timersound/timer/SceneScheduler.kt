package com.timersound.timer

import com.timersound.model.AlarmConfig
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
        if (startMinutes == null) {
            // Режим "Повтор без времени" начинает играть немедленно — это ожидаемое поведение.
            // Добавляем минимальную задержку 500ms, чтобы пользователь успел осознать старт.
            nowElapsedMs + 500L
        } else nextClockElapsed(nowElapsedMs, nowWallMs, startMinutes)

    /** Моменты конечных режимов (ONCE/INTERVAL/RANDOM), по возрастанию. REPEAT -> пустой. */
    fun fireTimesFor(alarm: AlarmConfig, nowElapsedMs: Long, nowWallMs: Long): List<Long> {
        when (alarm.mode) {
            SceneMode.ONCE_TIME -> return listOf(nextClockElapsed(nowElapsedMs, nowWallMs, requireStart(alarm)))
            SceneMode.INTERVAL -> {
                val first = nextClockElapsed(nowElapsedMs, nowWallMs, requireStart(alarm))
                val count = alarm.launchCount.coerceAtLeast(1)
                val step = alarm.intervalMs.coerceAtLeast(1_000L)
                return (0 until count).map { first + it.toLong() * step }
            }
            SceneMode.RANDOM -> {
                val start = requireStart(alarm)
                val end = requireEnd(alarm)
                val span = end - start
                val count = alarm.launchCount.coerceIn(1, span)
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
        val maxAttempts = span * 10 // Защита от бесконечного цикла при невозможных параметрах
        var attempts = 0
        while (picked.size < n && attempts < maxAttempts) {
            picked += random.nextInt(span.coerceAtLeast(1))
            attempts++
        }
        if (attempts >= maxAttempts) {
            throw IllegalStateException("randomMinutes: не удалось сгенерировать $n уникальных значений в диапазоне $span за $maxAttempts попыток")
        }
        return picked.toMutableList()
    }

    private fun requireStart(alarm: AlarmConfig): Int =
        alarm.startMinutes ?: throw IllegalArgumentException("startMinutes required for ${alarm.mode}")

    private fun requireEnd(alarm: AlarmConfig): Int =
        alarm.endMinutes ?: throw IllegalArgumentException("endMinutes required for RANDOM")
}
