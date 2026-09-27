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

    /**
     * Первое срабатывание REPEAT: через один интервал (если время старта не задано) или в ближайшее HH:MM.
     * Без времени старта звук НЕ играет в момент нажатия «Старт» — первое воспроизведение
     * происходит спустя intervalMs, дальше по тому же шагу.
     */
    fun initialRepeatFire(
        nowElapsedMs: Long,
        nowWallMs: Long,
        startMinutes: Int?,
        intervalMs: Long,
    ): Long = if (startMinutes == null) {
        nowElapsedMs + intervalMs.coerceAtLeast(1_000L)
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
                val dayStartWall = localMidnightWall(nowWallMs)
                val minuteSlots = (start until end).toList()
                // Сегодня доступны только минуты окна, которые ещё не наступили (если сейчас внутри окна — остаток).
                val windowEndToday = dayStartWall + end.toLong() * MS_PER_MIN
                val todayCandidates = if (windowEndToday > nowWallMs) {
                    minuteSlots.filter { dayStartWall + it.toLong() * MS_PER_MIN > nowWallMs }
                } else {
                    emptyList()
                }
                // Слотов сегодня не осталось (окно прошло или истекло) — планируем на завтра:
                // пустое расписание было бы тихим отказом, когда звука нет вообще.
                val originWall = if (todayCandidates.isEmpty()) dayStartWall + MS_PER_DAY else dayStartWall
                val candidates = todayCandidates.ifEmpty { minuteSlots }
                val count = alarm.launchCount.coerceIn(1, candidates.size)
                return randomMinuteSlots(candidates, count, seed = randomSeed(originWall, alarm.id)).sorted()
                    .map { originWall + it.toLong() * MS_PER_MIN + offset(nowElapsedMs, nowWallMs) }
            }
            SceneMode.REPEAT -> return emptyList()
        }
    }

    /**
     * Seed для RANDOM-тасовки: начало суток xor свёртка alarm.id (splitmix64).
     * Без id все каналы дня делили один seed и тасовались одинаково.
     */
    fun randomSeed(originWall: Long, alarmId: Int): Long =
        originWall xor splitmix64(alarmId.toLong() + GOLDEN_GAMMA)

    internal val GOLDEN_GAMMA = 0x9E3779B97F4A7C15uL.toLong()

    private fun splitmix64(z: Long): Long {
        var x = z + GOLDEN_GAMMA
        x = (x xor (x ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        x = (x xor (x ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return x xor (x ushr 31)
    }

    fun randomMinuteSlots(candidates: List<Int>, n: Int, seed: Long): List<Int> {
        val random = Random(seed)
        val pool = candidates.toMutableList()
        // Перемешивание Фишера—Йетса: детерминированно, без повторов и без риска зацикливания.
        for (i in pool.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = pool[i]
            pool[i] = pool[j]
            pool[j] = tmp
        }
        return pool.take(n.coerceIn(1, pool.size))
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
