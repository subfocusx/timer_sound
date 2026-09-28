package com.timersound.timer

import com.timersound.model.AlarmConfig
import com.timersound.model.AlarmGroup
import com.timersound.model.SceneMode
import java.util.Calendar

/**
 * Пересечения расписаний групп. Чистая функция без Android-зависимостей
 * (календарь — java.util, время передаётся параметрами).
 *
 * Два типа:
 * - SPAN_OVERLAP — интервалы [первое срабатывание, последнее/конец окна] пересекаются;
 * - NEAR_FIRE — отдельные срабатывания ближе [nearThresholdMs] (по умолчанию 60 с).
 *
 * Только предупреждения, никогда не блокируют.
 */
object ScheduleConflicts {

    const val DEFAULT_NEAR_MS = 60_000L
    /** Ограничение числа срабатываний, разворачиваемых для NEAR_FIRE (защита от огромных launchCount). */
    const val MAX_FIRES_PER_ALARM = 64

    enum class Kind { SPAN_OVERLAP, NEAR_FIRE }

    data class Conflict(
        val kind: Kind,
        val groupA: Int,
        val groupB: Int,
        /** ISO-дни (1..7), в которых пересечение наблюдается. */
        val days: List<Int>,
        val detail: String = "",
    )

    /**
     * @param groups включённые группы проверяются; выключенные игнорируются.
     * @param minutesPerDay 1440; минуты — целые HH:MM.
     */
    fun find(
        groups: List<AlarmGroup>,
        nearThresholdMs: Long = DEFAULT_NEAR_MS,
    ): List<Conflict> {
        val active = groups.filter { it.enabled && it.weekdays != 0 }
        val result = mutableListOf<Conflict>()
        for (i in active.indices) {
            for (j in i + 1 until active.size) {
                val a = active[i]
                val b = active[j]
                val commonDays = (1..7).filter { day ->
                    a.weekdays and (1 shl (day - 1)) != 0 && b.weekdays and (1 shl (day - 1)) != 0
                }
                if (commonDays.isEmpty()) continue
                val spanA = spanOf(a) ?: continue
                val spanB = spanOf(b) ?: continue
                val overlapDays = commonDays.filter { day ->
                    val sa = shiftSpanForDay(spanA, a.weekdays, day)
                    val sb = shiftSpanForDay(spanB, b.weekdays, day)
                    sa != null && sb != null && spansOverlapMinutes(sa, sb)
                }
                if (overlapDays.isNotEmpty()) {
                    result += Conflict(Kind.SPAN_OVERLAP, a.id, b.id, overlapDays)
                }
                val nearDays = commonDays.filter { day ->
                    val fa = firesOnDay(a, day)
                    val fb = firesOnDay(b, day)
                    hasNearPair(fa, fb, nearThresholdMs)
                }
                if (nearDays.isNotEmpty()) {
                    result += Conflict(Kind.NEAR_FIRE, a.id, b.id, nearDays)
                }
            }
        }
        return result
    }

    /** Спан группы в минутах суток [start, end]; end может превышать 1440 (переход через полночь). */
    internal fun spanOf(group: AlarmGroup): Pair<Int, Int>? {
        var start: Int? = null
        var end: Int? = null
        for (alarm in group.alarms) {
            if (!alarm.enabled || !alarm.hasFile || !alarm.scheduleValid) continue
            val s = alarm.startMinutes ?: continue
            val e = when (alarm.mode) {
                SceneMode.ONCE_TIME -> s
                SceneMode.INTERVAL -> {
                    val count = alarm.launchCount.coerceAtLeast(1)
                    val stepMin = (alarm.intervalMs.coerceAtLeast(1_000L) / 60_000L).toInt()
                    s + stepMin * (count - 1)
                }
                SceneMode.RANDOM -> alarm.endMinutes ?: continue
                SceneMode.REPEAT -> s
            }
            start = minOf(start ?: s, s)
            end = maxOf(end ?: e, e)
        }
        return if (start != null && end != null) start to end else null
    }

    /**
     * Спан, действующий в конкретный ISO-день: собственный спан дня + хвост
     * спана предыдущего дня, перешедший через полночь. Возвращает пару в
     * минутах «этого дня» (может начинаться с отрицательных).
     */
    private fun shiftSpanForDay(span: Pair<Int, Int>, weekdays: Int, isoDay: Int): Pair<Int, Int>? {
        val (s, e) = span
        val own = if (weekdays and (1 shl (isoDay - 1)) != 0) s to e else null
        val prevDay = if (isoDay == 1) 7 else isoDay - 1
        val prevTail =
            if (weekdays and (1 shl (prevDay - 1)) != 0 && e >= 1440) s - 1440 to e - 1440 else null
        return when {
            own != null && prevTail != null ->
                minOf(own.first, prevTail.first) to maxOf(own.second, prevTail.second)
            own != null -> own
            else -> prevTail
        }
    }

    private fun spansOverlapMinutes(a: Pair<Int, Int>, b: Pair<Int, Int>): Boolean =
        a.first <= b.second && b.first <= a.second

    /** Минутные метки срабатываний группы в конкретный ISO-день (с учётом хвоста прошлого дня). */
    internal fun firesOnDay(group: AlarmGroup, isoDay: Int): List<Int> {
        val out = mutableListOf<Int>()
        for (alarm in group.alarms) {
            if (!alarm.enabled || !alarm.hasFile || !alarm.scheduleValid) continue
            out += firesOfAlarmOnDay(alarm, group.weekdays, isoDay)
        }
        return out
    }

    private fun firesOfAlarmOnDay(alarm: AlarmConfig, weekdays: Int, isoDay: Int): List<Int> {
        val own = if (weekdays and (1 shl (isoDay - 1)) != 0) firesOfAlarm(alarm) else emptyList()
        val prevDay = if (isoDay == 1) 7 else isoDay - 1
        val tail = if (weekdays and (1 shl (prevDay - 1)) != 0) {
            firesOfAlarm(alarm).filter { it >= 1440 }.map { it - 1440 }
        } else emptyList()
        return own.filter { it < 1440 } + tail
    }

    private fun firesOfAlarm(alarm: AlarmConfig): List<Int> {
        val s = alarm.startMinutes ?: return emptyList()
        return when (alarm.mode) {
            SceneMode.ONCE_TIME -> listOf(s)
            SceneMode.REPEAT -> listOf(s)
            SceneMode.INTERVAL -> {
                val count = alarm.launchCount.coerceAtLeast(1).coerceAtMost(MAX_FIRES_PER_ALARM)
                val stepMin = (alarm.intervalMs.coerceAtLeast(1_000L) / 60_000L).toInt().coerceAtLeast(1)
                (0 until count).map { s + it * stepMin }
            }
            SceneMode.RANDOM -> listOf(s, alarm.endMinutes ?: s)
        }
    }

    private fun hasNearPair(a: List<Int>, b: List<Int>, thresholdMs: Long): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val thresholdMin = thresholdMs / 60_000.0
        val sb = b.sorted()
        for (m in a) {
            val pos = sb.binarySearch(m)
            if (pos >= 0) return true
            val ins = -pos - 1
            if (ins < sb.size && kotlin.math.abs(sb[ins] - m) * 60_000L < thresholdMs) return true
            if (ins > 0 && kotlin.math.abs(sb[ins - 1] - m) * 60_000L < thresholdMs) return true
            if (thresholdMin >= 1) continue
        }
        return false
    }

    /** Ближайший день недели из маски (включая сегодня), ISO 1..7. Null — маска пуста. */
    fun nextArmedDay(mask: Int, todayIso: Int): Int? {
        if (mask == 0) return null
        for (k in 0 until 7) {
            val day = (todayIso - 1 + k) % 7 + 1
            if (mask and (1 shl (day - 1)) != 0) return day
        }
        return null
    }

    /** Текущий ISO-день недели для wall-времени. */
    fun isoDayOf(wallMs: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = wallMs
        // Calendar.SUNDAY=1 … SATURDAY=7 → ISO Пн=1 … Вс=7.
        return ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
    }
}
