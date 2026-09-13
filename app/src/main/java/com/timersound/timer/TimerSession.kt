package com.timersound.timer

import android.os.SystemClock
import com.timersound.model.ChannelConfig
import com.timersound.model.TimerConfig

/** Состояние машины состояний сессии таймера (I->R->P<->R->STOP/RESET/COMPLETED). */
enum class TimerState { IDLE, RUNNING, PAUSED, COMPLETED }

/**
 * Движок интервальной сессии. Единственный источник «что и когда звучит» —
 * реальное время (SystemClock.elapsedRealtime(), монотонное, переживает режимы сна);
 * UI лишь отображает состояние и шлёт команды.
 *
 * Машина состояний:
 *  IDLE --start--> RUNNING
 *  RUNNING --pause--> PAUSED --resume--> RUNNING
 *  RUNNING/PAUSED --stop--> IDLE
 *  RUNNING --tick(дедлайн авто-остановки)--> COMPLETED (всё остановлено)
 *  COMPLETED --reset--> IDLE
 */
class TimerSession {

    private data class Scheduled(
        val config: ChannelConfig,
        var nextFireElapsedMs: Long,
        var remainingOnPauseMs: Long = 0L,
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

    /** Старт сессии: планируем первое срабатывание каждого канала через его интервал. */
    fun start(config: TimerConfig, nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        scheduled.clear()
        config.playableChannels().forEach { ch ->
            scheduled += Scheduled(ch, nowElapsedMs + ch.intervalMs.coerceAtLeast(1_000L))
        }
        startedElapsedMs = nowElapsedMs
        autoStopDeadlineElapsedMs =
            if (config.autoStopMs > 0) nowElapsedMs + config.autoStopMs else Long.MAX_VALUE
        state = TimerState.RUNNING
    }

    /** Пауза: запоминаем остатки до следующих срабатываний и до авто-остановки. */
    fun pause(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        if (state != TimerState.RUNNING) return
        scheduled.forEach { it.remainingOnPauseMs = (it.nextFireElapsedMs - nowElapsedMs).coerceAtLeast(0L) }
        remainingAutoStopOnPauseMs =
            if (autoStopDeadlineElapsedMs == Long.MAX_VALUE) Long.MAX_VALUE
            else (autoStopDeadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)
        state = TimerState.PAUSED
    }

    /** Возобновление: перепланируем от текущего момента. */
    fun resume(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        if (state != TimerState.PAUSED) return
        scheduled.forEach { it.nextFireElapsedMs = nowElapsedMs + it.remainingOnPauseMs.coerceAtLeast(0L) }
        autoStopDeadlineElapsedMs =
            if (remainingAutoStopOnPauseMs == Long.MAX_VALUE) Long.MAX_VALUE
            else nowElapsedMs + remainingAutoStopOnPauseMs
        state = TimerState.RUNNING
    }

    /** Полная остановка (STOP) -> IDLE. */
    fun stop() {
        scheduled.clear()
        autoStopDeadlineElapsedMs = Long.MAX_VALUE
        remainingAutoStopOnPauseMs = Long.MAX_VALUE
        state = TimerState.IDLE
    }

    /** Принудительно отметить сессию завершённой (авто-остановка). */
    fun markCompleted() {
        state = TimerState.COMPLETED
    }

    /** Сбросить пометкy «Завершено» -> IDLE. */
    fun reset() {
        scheduled.clear()
        autoStopDeadlineElapsedMs = Long.MAX_VALUE
        remainingAutoStopOnPauseMs = Long.MAX_VALUE
        state = TimerState.IDLE
    }

    /**
     * Тик сессии, вызывается сервисом ~4 раза в секунду, пока RUNNING.
     * Возвращает true, когда сработала авто-остановка (всё остановлено; вызыватель
     * должен остановить аудио, обновить уведомление и остановить сервис).
     */
    fun tick(
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        trigger: (ChannelConfig) -> Unit,
    ): Boolean {
        if (state != TimerState.RUNNING) return false

        // Авто-остановка: глобальная, останавливает всё.
        if (autoStopDeadlineElapsedMs != Long.MAX_VALUE && nowElapsedMs >= autoStopDeadlineElapsedMs) {
            state = TimerState.COMPLETED
            return true
        }

        // Срабатывания каналов: каждый канал воспроизводится каждые свои интервалы.
        scheduled.forEach { s ->
            while (nowElapsedMs >= s.nextFireElapsedMs) {
                trigger(s.config)
                s.nextFireElapsedMs += s.config.intervalMs.coerceAtLeast(1_000L)
            }
        }
        return false
    }

    /** Сколько осталось до авто-остановки (null — без ограничения). */
    fun countdownToAutoStopMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long? {
        if (autoStopDeadlineElapsedMs == Long.MAX_VALUE) return null
        return (autoStopDeadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)
    }

    /** Дата следующего звука для уведомления; null, если канал не запланирован. */
    fun nextFireForChannel(channelId: Int, nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long? {
        return scheduled.firstOrNull { it.config.id == channelId }
            ?.let { s -> if (s.nextFireElapsedMs > nowElapsedMs) s.nextFireElapsedMs - nowElapsedMs else 0L }
    }

    /** Общий следующий звук (ближайший по времени) для строки уведомления. */
    fun nextSoundDescription(nowElapsedMs: Long = SystemClock.elapsedRealtime()): String {
        val next = scheduled.minByOrNull { it.nextFireElapsedMs } ?: return "—"
        val remain = (next.nextFireElapsedMs - nowElapsedMs).coerceAtLeast(0L)
        return "${next.config.name}: через ${formatHms(remain)}"
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