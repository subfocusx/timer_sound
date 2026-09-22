package com.timersound.timer

import android.os.SystemClock
import com.timersound.model.AlarmConfig
import com.timersound.model.SceneMode
import com.timersound.model.TimerConfig

/** Состояние машины состояний сессии таймера (I->R->P<->R->STOP/RESET/COMPLETED). */
enum class TimerState { IDLE, RUNNING, PAUSED, COMPLETED }

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

    private var autoStopDeadlineElapsedMs = Long.MAX_VALUE
    private var remainingAutoStopOnPauseMs = Long.MAX_VALUE
    private val scheduled = mutableListOf<Scheduled>()

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
        autoStopDeadlineElapsedMs =
            if (config.autoStopMs > 0) nowElapsedMs + config.autoStopMs else Long.MAX_VALUE
        state = TimerState.RUNNING
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
        state = TimerState.PAUSED
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
        state = TimerState.RUNNING
    }

    /** Полная остановка (STOP) -> IDLE. */
    @Synchronized
    fun stop() {
        scheduled.clear()
        autoStopDeadlineElapsedMs = Long.MAX_VALUE
        remainingAutoStopOnPauseMs = Long.MAX_VALUE
        state = TimerState.IDLE
    }

    /** Принудительно отметить сессию завершённой (авто-остановка). */
    @Synchronized
    fun markCompleted() {
        scheduled.clear()
        state = TimerState.COMPLETED
    }

    /** Сбросить пометкy «Завершено» -> IDLE. */
    @Synchronized
    fun reset() {
        scheduled.clear()
        autoStopDeadlineElapsedMs = Long.MAX_VALUE
        remainingAutoStopOnPauseMs = Long.MAX_VALUE
        state = TimerState.IDLE
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
        scheduled.toList().forEach { s ->
            while (nowElapsedMs >= s.nextFireElapsedMs) {
                trigger(s.config)
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
        val next = nextEventElapsedMs(nowElapsedMs) ?: return "—"
        val remain = (next - nowElapsedMs).coerceAtLeast(0L)
        val channel = scheduled.minByOrNull { it.nextFireElapsedMs }?.config ?: return "—"
        return "${channel.name}: через ${formatHms(remain)}"
    }

    companion object {
        fun formatHms(ms: Long): String {
            val totalSec = (ms / 1000).coerceAtLeast(0L)
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return "%02d:%02d:%02d".format(h, m, s)
        }
    }
}
