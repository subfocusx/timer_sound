package com.timersound.timer

import com.timersound.model.AlarmConfig
import com.timersound.model.AlarmGroup
import com.timersound.model.Defaults
import com.timersound.model.SceneMode
import com.timersound.model.TimerConfig
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun alarm(
    id: Int,
    mode: SceneMode = SceneMode.ONCE_TIME,
    startMin: Int? = 8 * 60,
    endMin: Int? = null,
    launches: Int = 1,
    intervalMs: Long = Defaults.DEFAULT_INTERVAL_MS,
): AlarmConfig = AlarmConfig(
    id = id, name = "A$id", fileUri = Defaults.BUILT_IN_BEEP,
    mode = mode, intervalMs = intervalMs,
    startMinutes = startMin, endMinutes = endMin,
    launchCount = launches, volumePercent = 80, enabled = true,
)

private fun group(id: Int, days: Int, vararg alarms: AlarmConfig) = AlarmGroup(
    id = id, name = "G$id", alarms = alarms.toList(), enabled = true, weekdays = days,
)

/** Этап 2: planAnchor, snapshot, ScheduleConflicts, WakeScheduler, дни недели. */
class GroupLogicTest {

    private fun wall(year: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(year, mo - 1, d, h, mi, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // --- planAnchor: алярм на мс позже плана не уезжает на завтра ---

    @Test
    fun planAnchor_keepsScheduleStartToday() {
        val nowWall = wall(2026, 9, 28, 8, 0) // Пн 08:00
        val planWall = nowWall // план ровно на 08:00
        val lateElapsed = 1_000L
        val lateWall = planWall + 37L // алярм опоздал на 37 мс
        val cfg = TimerConfig(listOf(alarm(0, startMin = 8 * 60)), autoStopMs = 0L)

        val anchored = TimerSession()
        anchored.start(cfg, lateElapsed, lateWall, planAnchorWallMs = planWall)
        val plain = TimerSession()
        plain.start(cfg, lateElapsed, lateWall)

        val aNext = anchored.snapshot(lateElapsed).untilNextMs
        val pNext = plain.snapshot(lateElapsed).untilNextMs
        assertNotNull(aNext)
        assertNotNull(pNext)
        // С якорем — почти сутки меньше (сегодня уже отзвучало → 0..несколько суток? нет: старт в плане).
        // Ключевое: якорь даёт запуск «сейчас» (остаток ~0), без якоря — почти сутки.
        assertTrue(aNext < 60_000L, "anchored untilNext=$aNext")
        assertTrue(pNext > 20 * 3_600_000L, "plain untilNext=$pNext")
    }

    /** Задержка wake-алярма 50–500 мс с якорем-планом: запуск остаётся сегодня. */
    @Test
    fun planAnchor_wakeDelaysUpTo500ms() {
        val planWall = wall(2026, 9, 28, 16, 6)
        val cfg = TimerConfig(listOf(alarm(0, startMin = 16 * 60 + 6)), autoStopMs = 0L)
        for (delayMs in listOf(50L, 150L, 300L, 500L)) {
            val s = TimerSession()
            s.start(cfg, 1_000L, planWall + delayMs, planAnchorWallMs = planWall)
            val untilNext = s.snapshot(1_000L).untilNextMs
            assertNotNull(untilNext, "delay=$delayMs")
            assertTrue(untilNext < 60_000L, "delay=$delayMs untilNext=$untilNext")
        }
    }

    /** Якорь — плановое время, а не момент пробуждения: nextAutoStart wall. */
    @Test
    fun wakeAnchor_isPlanTime() {
        val g = group(0, 0b000_0001, alarm(0, startMin = 16 * 60 + 6))
        val before = wall(2026, 9, 28, 16, 5) // Пн 16:05
        val anchor = WakeScheduler.nextAutoStartWall(g, before)
        assertNotNull(anchor)
        assertEquals(wall(2026, 9, 28, 16, 6), anchor)
        val s = TimerSession()
        s.start(TimerConfig(listOf(alarm(0, startMin = 16 * 60 + 6)), 0L), 1_000L, anchor + 800L, planAnchorWallMs = anchor)
        assertTrue((s.snapshot(1_000L).untilNextMs ?: Long.MAX_VALUE) < 60_000L)
    }

    /** Незапущенная группа только на Пн: следующий запуск — через неделю, а не «завтра». */
    @Test
    fun idleMondayGroup_nextRunIsNextWeek() {
        val g = group(0, 0b000_0001, alarm(0, startMin = 16 * 60 + 6))
        val mondayEvening = wall(2026, 9, 28, 16, 8) // Пн 16:08, время прошло
        val next = WakeScheduler.nextAutoStartWall(g, mondayEvening)
        assertNotNull(next)
        assertEquals(wall(2026, 10, 5, 16, 6), next) // следующий Пн
    }


    // --- snapshot: остаток до конца, пауза, бесконечность ---

    @Test
    fun snapshot_finiteInterval_untilEndIsLastFire() {
        val s = TimerSession()
        s.start(
            TimerConfig(listOf(alarm(0, SceneMode.INTERVAL, 8 * 60, launches = 3, intervalMs = 600_000L)), 0L),
            nowElapsedMs = 0L, nowWallMs = wall(2026, 9, 28, 7, 0),
        )
        val snap = s.snapshot(0L)
        assertEquals(3, snap.remainingFires)
        // Последнее срабатывание: 08:00 + 2×10мин = 08:20, старт в 07:00 → 80 мин.
        assertEquals(80 * 60_000L, snap.untilEndMs)
        assertEquals(60 * 60_000L, snap.untilNextMs)
    }

    @Test
    fun snapshot_pause_freezesRemainders() {
        val s = TimerSession()
        s.start(
            TimerConfig(listOf(alarm(0, SceneMode.INTERVAL, 8 * 60, launches = 2, intervalMs = 600_000L)), 0L),
            nowElapsedMs = 0L, nowWallMs = wall(2026, 9, 28, 7, 0),
        )
        s.pause(30 * 60_000L) // пауза в 07:30
        val a = s.snapshot(30 * 60_000L)
        val b = s.snapshot(90 * 60_000L) // время идёт, пауза заморожена
        assertEquals(a.untilNextMs, b.untilNextMs)
        assertEquals(a.untilEndMs, b.untilEndMs)
    }

    @Test
    fun snapshot_infiniteRepeat_isInfinity() {
        val s = TimerSession()
        s.start(
            TimerConfig(listOf(alarm(0, SceneMode.REPEAT, startMin = null)), 0L),
            nowElapsedMs = 0L, nowWallMs = wall(2026, 9, 28, 7, 0),
        )
        val snap = s.snapshot(0L)
        assertNull(snap.untilEndMs)
        assertNull(snap.remainingFires)
    }

    @Test
    fun snapshot_infiniteRepeatWithAutoStop_endIsDeadline() {
        val s = TimerSession()
        s.start(
            TimerConfig(listOf(alarm(0, SceneMode.REPEAT, startMin = null)), autoStopMs = 10 * 60_000L),
            nowElapsedMs = 0L, nowWallMs = wall(2026, 9, 28, 7, 0),
        )
        val snap = s.snapshot(0L)
        assertEquals(10 * 60_000L, snap.untilEndMs)
    }

    // --- ScheduleConflicts ---

    @Test
    fun conflicts_overlapOnCommonDay() {
        val a = group(0, 0b000_0001, alarm(0, startMin = 480)) // Пн 08:00
        val b = group(1, 0b000_0001, alarm(1, startMin = 480)) // Пн 08:00
        val found = ScheduleConflicts.find(listOf(a, b))
        assertTrue(found.any { it.kind == ScheduleConflicts.Kind.SPAN_OVERLAP && it.days == listOf(1) })
        assertTrue(found.any { it.kind == ScheduleConflicts.Kind.NEAR_FIRE })
    }

    @Test
    fun conflicts_noOverlapOnDifferentDays() {
        val a = group(0, 0b000_0001, alarm(0, startMin = 480))
        val b = group(1, 0b000_0010, alarm(1, startMin = 480))
        assertTrue(ScheduleConflicts.find(listOf(a, b)).isEmpty())
    }

    @Test
    fun conflicts_spanAcrossMidnight() {
        // Группа A: INTERVAL 23:30 + 3×60мин → спан [1410, 1530] (хвост за полночь).
        val a = group(0, 0b000_0001, alarm(0, SceneMode.INTERVAL, 23 * 60 + 30, launches = 3, intervalMs = 3_600_000L))
        // Группа B: Пн+Вт 00:30 → пересечение с хвостом A во Вт.
        val b = group(1, 0b000_0011, alarm(1, startMin = 30))
        val found = ScheduleConflicts.find(listOf(a, b))
        val spans = found.filter { it.kind == ScheduleConflicts.Kind.SPAN_OVERLAP }
        assertTrue(spans.any { 2 in it.days }, "spans=$spans")
    }

    @Test
    fun conflicts_randomWindowSpan() {
        val a = group(0, 0b000_0001, alarm(0, SceneMode.RANDOM, 540, 600, launches = 3))
        val b = group(1, 0b000_0001, alarm(1, startMin = 570))
        val found = ScheduleConflicts.find(listOf(a, b))
        assertTrue(found.any { it.kind == ScheduleConflicts.Kind.SPAN_OVERLAP })
    }

    // --- WakeScheduler ---

    @Test
    fun wakeScheduler_picksMinOfSessionsAndAutoStarts() {
        val nowWall = wall(2026, 9, 28, 7, 0) // Пн
        val nowElapsed = 5_000L
        val g = group(3, 0b000_0001, alarm(0, startMin = 8 * 60))
        val auto = WakeScheduler.nextAutoStartWall(g, nowWall)
        assertNotNull(auto)
        val wake = WakeScheduler.nextWakeUp(
            sessionNextElapsed = listOf(nowElapsed + 10 * 60_000L),
            autoStartWalls = listOf(auto),
            nowElapsedMs = nowElapsed, nowWallMs = nowWall,
        )
        val min = WakeScheduler.nextWakeElapsed(wake, nowElapsed, nowWall)
        // Автозапуск в 08:00 (60 мин) раньше сессии (+10 мин)? Нет — сессия раньше.
        assertEquals(nowElapsed + 10 * 60_000L, min)
    }

    @Test
    fun wakeScheduler_nextWeekdaySkips() {
        // Сегодня Пн 09:00, группа только на Вт 08:00 → следующий автозапуск завтра.
        val nowWall = wall(2026, 9, 28, 9, 0)
        val g = group(0, 0b000_0010, alarm(0, startMin = 8 * 60))
        val auto = WakeScheduler.nextAutoStartWall(g, nowWall)
        assertNotNull(auto)
        assertEquals(wall(2026, 9, 29, 8, 0), auto)
    }

    // --- SceneScheduler с днями недели ---

    @Test
    fun nextClockForWeekdays_skipsToArmedDay() {
        val nowWall = wall(2026, 9, 28, 9, 0) // Пн 09:00
        val t = SceneScheduler.nextClockElapsedForWeekdays(0L, nowWall, 8 * 60, 0b000_0010)
        // Вт 08:00.
        val cal = Calendar.getInstance()
        cal.timeInMillis = t // offset=0L-nowWall → t = wall - nowWall... проверяем через wallOfDayPlusK
        val expected = SceneScheduler.wallOfDayPlusK(nowWall, 8 * 60, 1) + (0L - nowWall)
        assertEquals(expected, t)
    }

    @Test
    fun autofStartMinutes_minimumOfAlarms() {
        val g = group(0, 0, alarm(0, startMin = 500), alarm(1, startMin = 480))
        assertEquals(480, g.autoStartMinutes())
    }

    @Test
    fun scheduleValid_blocksRepeatWithoutStart() {
        val g = group(0, 0b000_0001, alarm(0, SceneMode.REPEAT, startMin = null))
        assertEquals(false, g.scheduleValid())
    }
}
