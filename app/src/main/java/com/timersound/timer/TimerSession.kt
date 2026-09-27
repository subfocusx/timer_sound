package com.timersound.timer

import android.os.SystemClock
import com.timersound.AppLog
import com.timersound.model.AlarmConfig
import com.timersound.model.SceneMode
import com.timersound.model.TimerConfig

/** Состояние машины состояний сессии таймера (I->R->P<->R->STOP/RESET/COMPLETED). */
enum class TimerState { IDLE, RUNNING, PAUSED, COMPLETED }

/**
 * А1: политика адаптивного шага тик-цикла. Чистая математика — тестируется без Android:
 * рядом с событием частый тик для точности, вдали — редкий (точное срабатывание
 * страхует scheduleExactAlarm в сервисе).
 */
object TickPolicy {
    /** Шаг тика по расстоянию до ближайшего события (null — событий нет). */
    fun delayMs(untilNextMs: Long?): Long {
        val untilNext = untilNextMs ?: Long.MAX_VALUE
        return when {
            untilNext <= 5_000L -> 250L
            untilNext <= 30_000L -> 1_000L
            else -> 5_000L
        }
    }
}

/**
 * Движок сценарной сессии. Единственный источник «что и когда звучит» —
 * монотонное elapsed-время; wall-время используется только при старте для
 * привязки локальных HH:MM к elapsed-шкале.
 *
 * Машина состояний:
 *  IDLE --start--> RUNNING
 *  RUNNING --pause--> PAUSED --resume--> RUNNING
 *  RUNNING/PAUSED --stop--> IDLE
 *  RUNNING --tick(дедлайн авто-остановки или последний звук сценария)--> COMPLETED
 *  COMPLETED --reset--> IDLE
 */
class TimerSession {

    private data class Scheduled(
        val config: AlarmConfig,
        var nextFireElapsedMs: Long,
        /** Остаток до ближайшего события на момент паузы. */
        var remainingOnPauseMs: Long = 0L,
        /** Будущие события конечного режима: абсолютные во время работы, относительные в паузе. */
        var finiteRemaining: List<Long> = emptyList(),
    )

    var state: TimerState = TimerState.IDLE
        private set
    var startedElapsedMs: Long = 0L
        private set

    /** Wall-время старта: нужно, чтобы переводить elapsed-моменты в «человеческое» время. */
    private var startedWallMs: Long = 0L

    private var autoStopDeadlineElapsedMs = Long.MAX_VALUE
    private var remainingAutoStopOnPauseMs = Long.MAX_VALUE
    private val scheduled = mutableListOf<Scheduled>()
    /** Суммарное число trigger() за сессию; лимит — maxTotalFiresPerSession. */
    private var totalFiresCount = 0
    private var maxTotalFires = 0
    /**
     * Поколение сессии: +1 на каждый start(). Отложенный teardown (Баг 6)
     * сверяет его перед сносом — старая сессия не глушит новую.
     */
    var epoch: Long = 0L
        private set

    val isRunning: Boolean get() = state == TimerState.RUNNING
    val isActive: Boolean get() = state == TimerState.RUNNING || state == TimerState.PAUSED

    /** Старт сессии: фиксируем сценарные моменты и переводим их в elapsed-шкалу. */
    @Synchronized
    fun start(
        config: TimerConfig,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        nowWallMs: Long = System.currentTimeMillis(),
    ) {
        scheduled.clear()
        config.playableAlarms()
            .filter { it.scheduleValid }
            .forEach { ch ->
                val fires = SceneScheduler.fireTimesFor(ch, nowElapsedMs, nowWallMs)
                if (ch.mode == SceneMode.REPEAT) {
                    scheduled += Scheduled(
                        config = ch,
                        nextFireElapsedMs = SceneScheduler.initialRepeatFire(
                            nowElapsedMs,
                            nowWallMs,
                            ch.startMinutes,
                            ch.intervalMs,
                        ),
                    )
                } else if (fires.isNotEmpty()) {
                    scheduled += Scheduled(
                        config = ch,
                        nextFireElapsedMs = fires.first(),
                        finiteRemaining = fires.drop(1),
                    )
                }
            }
        startedElapsedMs = nowElapsedMs
        startedWallMs = nowWallMs
        epoch++
        autoStopDeadlineElapsedMs =
            if (config.autoStopMs > 0) nowElapsedMs + config.autoStopMs else Long.MAX_VALUE
        totalFiresCount = 0
        maxTotalFires = config.maxTotalFiresPerSession.coerceAtLeast(0)
        AppLog.i("TimerSession.start: wall=${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(nowWallMs))} alarms=${scheduled.size} autoStopMs=${config.autoStopMs} maxFires=$maxTotalFires")
        scheduled.forEach { s ->
            val offsets = buildList {
                add(s.nextFireElapsedMs - nowElapsedMs)
                addAll(s.finiteRemaining.map { it - nowElapsedMs })
            }
            AppLog.i("TimerSession.plan: ch=${s.config.id} name=\"${s.config.name}\" mode=${s.config.mode} intervalMs=${s.config.intervalMs} startMin=${s.config.startMinutes} launches=${s.config.launchCount} offsetsMs=$offsets vol=${s.config.volumePercent}")
        }
        transitionTo(TimerState.RUNNING, "start")
    }

    /** Пауза: запоминаем остатки до следующих срабатываний и до авто-остановки. */
    @Synchronized
    fun pause(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        if (state != TimerState.RUNNING) return
        scheduled.forEach { s ->
            s.remainingOnPauseMs = (s.nextFireElapsedMs - nowElapsedMs).coerceAtLeast(0L)
            s.finiteRemaining = s.finiteRemaining.map { (it - nowElapsedMs).coerceAtLeast(0L) }
        }
        remainingAutoStopOnPauseMs =
            if (autoStopDeadlineElapsedMs == Long.MAX_VALUE) Long.MAX_VALUE
            else (autoStopDeadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)
        transitionTo(TimerState.PAUSED, "pause")
    }

    /** Возобновление: переносим сохранённые остатки от текущего момента. */
    @Synchronized
    fun resume(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        if (state != TimerState.PAUSED) return
        scheduled.forEach { s ->
            s.nextFireElapsedMs = nowElapsedMs + s.remainingOnPauseMs
            s.finiteRemaining = s.finiteRemaining.map { nowElapsedMs + it }
        }
        autoStopDeadlineElapsedMs =
            if (remainingAutoStopOnPauseMs == Long.MAX_VALUE) Long.MAX_VALUE
            else nowElapsedMs + remainingAutoStopOnPauseMs
        transitionTo(TimerState.RUNNING, "resume")
    }

    /** Полная остановка (STOP) -> IDLE. */
    @Synchronized
    fun stop() {
        scheduled.clear()
        autoStopDeadlineElapsedMs = Long.MAX_VALUE
        remainingAutoStopOnPauseMs = Long.MAX_VALUE
        totalFiresCount = 0
        transitionTo(TimerState.IDLE, "stop")
    }

    /** Принудительно отметить сессию завершённой (авто-остановка). */
    @Synchronized
    fun markCompleted() {
        scheduled.clear()
        transitionTo(TimerState.COMPLETED, "markCompleted")
    }

    /** Сбросить пометкy «Завершено» -> IDLE. */
    @Synchronized
    fun reset() {
        scheduled.clear()
        autoStopDeadlineElapsedMs = Long.MAX_VALUE
        remainingAutoStopOnPauseMs = Long.MAX_VALUE
        totalFiresCount = 0
        transitionTo(TimerState.IDLE, "reset")
    }

    /** Б2: переход с логом причины — состояние восстанавливается напрямую, не косвенно. */
    private fun transitionTo(next: TimerState, reason: String) {
        val prev = state
        state = next
        AppLog.i("TimerSession: $prev -> $next ($reason)")
    }

    /**
     * Тик сессии, вызывается сервисом ~4 раза в секунду, пока RUNNING.
     * Возвращает true, когда сценарий завершён или сработала авто-остановка.
     */
    @Synchronized
    fun tick(
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        trigger: (AlarmConfig) -> Unit,
    ): Boolean {
        if (state != TimerState.RUNNING) return false

        // Авто-остановка: глобальная, останавливает всё.
        if (autoStopDeadlineElapsedMs != Long.MAX_VALUE && nowElapsedMs >= autoStopDeadlineElapsedMs) {
            markCompleted()
            return true
        }

        // Срабатывания каналов: каждый канал воспроизводится по своему сценарию.
        // REPEAT: catch-up ограничен MAX_CATCHUP_FIRES_PER_TICK — задержанный тик
        // жжёт не более 3 пропусков зараз, остаток «докапывает» следующими тиками
        // (фаза интервалов сдвигается от факта, а не бьёт залпом + не глушится
        // лимитом AudioEngine.MAX_CONCURRENT_RINGS). Конечные режимы (INTERVAL/
        // RANDOM/ONCE) досрабатывают полностью: там каждое срабатывание уникально.
        scheduled.toList().forEach { s ->
            var fired = 0
            while (nowElapsedMs >= s.nextFireElapsedMs) {
                if (s.config.mode == SceneMode.REPEAT && fired >= MAX_CATCHUP_FIRES_PER_TICK) {
                    AppLog.w("TimerSession: tick отстал, ch=${s.config.id}: пропуск сжат до $MAX_CATCHUP_FIRES_PER_TICK, фаза сдвинута к $nowElapsedMs")
                    s.nextFireElapsedMs = nowElapsedMs + s.config.intervalMs.coerceAtLeast(1_000L)
                    break
                }
                val planned = s.nextFireElapsedMs
                AppLog.evt(s.config.id, s.config.name, planned, nowElapsedMs, s.config.fileUri.ifEmpty { "beep" })
                trigger(s.config)
                fired++
                totalFiresCount++
                // Глобальный предохранитель: лимит суммарных срабатываний за сессию.
                if (maxTotalFires > 0 && totalFiresCount >= maxTotalFires) {
                    markCompleted()
                    return true
                }
                if (s.finiteRemaining.isNotEmpty()) {
                    s.nextFireElapsedMs = s.finiteRemaining.first()
                    s.finiteRemaining = s.finiteRemaining.drop(1)
                } else if (s.config.mode == SceneMode.REPEAT) {
                    s.nextFireElapsedMs += s.config.intervalMs.coerceAtLeast(1_000L)
                } else {
                    s.nextFireElapsedMs = Long.MAX_VALUE
                    break
                }
            }
        }
        scheduled.removeAll { it.nextFireElapsedMs == Long.MAX_VALUE && it.finiteRemaining.isEmpty() }

        if (scheduled.isEmpty()) {
            markCompleted()
            return true
        }
        return false
    }

    /** Сколько осталось до авто-остановки (null — без ограничения). */
    @Synchronized
    fun countdownToAutoStopMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long? {
        // В паузе дедлайн тоже заморожен: берём сохранённый остаток, иначе отсчёт падал в 0.
        if (state == TimerState.PAUSED) {
            return if (remainingAutoStopOnPauseMs == Long.MAX_VALUE) null
            else remainingAutoStopOnPauseMs.coerceAtLeast(0L)
        }
        if (autoStopDeadlineElapsedMs == Long.MAX_VALUE) return null
        return (autoStopDeadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)
    }

    /** Дата следующего звука для уведомления; null, если канал не запланирован. */
    @Synchronized
    fun nextFireForChannel(channelId: Int, nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long? {
        if (!isActive) return null
        return scheduled.firstOrNull { it.config.id == channelId }
            ?.let { s -> if (s.nextFireElapsedMs > nowElapsedMs) s.nextFireElapsedMs - nowElapsedMs else 0L }
    }

    /** Общий следующий звук (ближайший по времени) для строки уведомления. */
    @Synchronized
    fun nextEventElapsedMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long? {
        if (!isActive) return null
        return scheduled.minByOrNull { it.nextFireElapsedMs }?.nextFireElapsedMs
    }

    /** Общий следующий звук (ближайший по времени) для строки уведомления. */
    @Synchronized
    fun nextSoundDescription(nowElapsedMs: Long = SystemClock.elapsedRealtime()): String {
        val s = scheduled.minByOrNull { it.nextFireElapsedMs } ?: return "—"
        // В паузе абсолютная метка nextFireElapsedMs заморожена и уже в прошлом, а истинный
        // остаток лежит в remainingOnPauseMs — иначе описание показывало «через 0 с».
        val remainMs = if (state == TimerState.PAUSED) {
            s.remainingOnPauseMs.coerceAtLeast(0L)
        } else {
            (s.nextFireElapsedMs - nowElapsedMs).coerceAtLeast(0L)
        }
        val phrase = if (startedWallMs == 0L) {
            "через ${formatHms(remainMs)}"
        } else {
            // elapsed → wall по шкале сессии (не по реальным часам: они совпадают только вне тестов).
            val nowWallHere = startedWallMs + (nowElapsedMs - startedElapsedMs)
            describeWallMoment(nowWallHere + remainMs, nowWallHere)
        }
        return "${s.config.name}: $phrase"
    }

    companion object {
        /** Максимум пропущенных REPEAT-интервалов, досрабатываемых за один tick. */
        const val MAX_CATCHUP_FIRES_PER_TICK = 3

        fun formatHms(ms: Long): String {
            val totalSec = (ms / 1000).coerceAtLeast(0L)
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return "%02d:%02d:%02d".format(h, m, s)
        }

        /**
         * Человеческое описание момента: «через 30 с», «через 12 мин»,
         * «сегодня в 14:05», «завтра в 01:19», «25.09 в 07:00».
         * Без этого строка «через 23:53:32» читается как «через 23 минуты».
         */
        fun describeWallMoment(targetWallMs: Long, nowWallMs: Long = System.currentTimeMillis()): String {
            val remain = targetWallMs - nowWallMs
            if (remain < 60_000L) return "через ${(remain / 1000).coerceAtLeast(0L)} с"
            if (remain < 60 * 60_000L) return "через ${remain / 60_000L} мин"
            val zone = java.util.TimeZone.getDefault()
            fun cal(ms: Long, plusDays: Int = 0) = java.util.Calendar.getInstance(zone).apply {
                timeInMillis = ms
                add(java.util.Calendar.DAY_OF_YEAR, plusDays)
            }
            val target = cal(targetWallMs)
            val today = cal(nowWallMs)
            val time = "%02d:%02d".format(
                target.get(java.util.Calendar.HOUR_OF_DAY),
                target.get(java.util.Calendar.MINUTE),
            )
            val sameDay = target.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
                target.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)
            if (sameDay) return "сегодня в $time"
            val tomorrow = cal(nowWallMs, 1)
            val isTomorrow = target.get(java.util.Calendar.YEAR) == tomorrow.get(java.util.Calendar.YEAR) &&
                target.get(java.util.Calendar.DAY_OF_YEAR) == tomorrow.get(java.util.Calendar.DAY_OF_YEAR)
            if (isTomorrow) return "завтра в $time"
            return "%02d.%02d в %s".format(
                target.get(java.util.Calendar.DAY_OF_MONTH),
                target.get(java.util.Calendar.MONTH) + 1,
                time,
            )
        }
    }
}
