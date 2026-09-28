package com.timersound.timer

import com.timersound.model.AlarmGroup
import java.util.Calendar

/**
 * Чистая логика вычисления следующего пробуждения (без Android API).
 *
 * Один общий будильник = минимум из:
 * - следующее событие каждой активной сессии (абсолютные elapsed-цели);
 * - следующий автозапуск каждой армированной группы (включена, weekdays != 0,
 *   валидное расписание) как wall-момент.
 *
 * Android-обвязка (AlarmManager.setAlarmClock + PendingIntent) — на стороне
 * сервиса; сюда приходят готовые числа.
 */
object WakeScheduler {

    data class WakeUp(
        /** Минимум в elapsed-шкале (для сессий) — null, если все кандидаты wall. */
        val nextElapsedMs: Long?,
        /** Минимум в wall-шкале (для автозапусков) — null, если кандидатов нет. */
        val nextWallMs: Long?,
    )

    /**
     * Следующий автозапуск группы как wall-мс. Null — группа не армирована
     * (выключена, weekdays == 0, нет startMinutes).
     */
    fun nextAutoStartWall(group: AlarmGroup, nowWallMs: Long): Long? {
        if (!group.enabled || group.weekdays == 0) return null
        val startMin = group.autoStartMinutes() ?: return null
        val todayIso = ScheduleConflicts.isoDayOf(nowWallMs)
        for (k in 0 until 8) {
            val day = (todayIso - 1 + k) % 7 + 1
            if (group.weekdays and (1 shl (day - 1)) == 0) continue
            val candidate = wallOfDayPlus(startMin, nowWallMs, k)
            if (candidate > nowWallMs) return candidate
        }
        return null
    }

    /**
     * Общий минимум: сессии (elapsed) + автозапуски (wall, переводятся в elapsed
     * через offset). Возвращает null, если будить нечего.
     */
    fun nextWakeUp(
        sessionNextElapsed: List<Long>,
        autoStartWalls: List<Long>,
        nowElapsedMs: Long,
        nowWallMs: Long,
    ): WakeUp {
        val nextSession = sessionNextElapsed.filter { it > nowElapsedMs }.minOrNull()
        val offset = nowElapsedMs - nowWallMs
        val nextAuto = autoStartWalls.filter { it > nowWallMs }.minOrNull()
        return WakeUp(nextSession, nextAuto)
    }

    /** Абсолютный минимум в elapsed-шкале (для одного PendingIntent). */
    fun nextWakeElapsed(wake: WakeUp, nowElapsedMs: Long, nowWallMs: Long): Long? {
        val offset = nowElapsedMs - nowWallMs
        val autoElapsed = wake.nextWallMs?.let { it + offset }
        return listOfNotNull(wake.nextElapsedMs, autoElapsed).minOrNull()
    }

    /**
     * Wall-момент «день nowWallMs + k дней, HH:MM = startMin». Полем Calendar
     * (не +24ч): в день DST-перехода сутки 23/25ч.
     */
    internal fun wallOfDayPlus(startMin: Int, nowWallMs: Long, plusDays: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowWallMs
        cal.set(Calendar.HOUR_OF_DAY, startMin / 60)
        cal.set(Calendar.MINUTE, startMin % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, plusDays)
        return cal.timeInMillis
    }
}
