package com.timersound.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.timersound.timer.TimerSession
import com.timersound.timer.TimerState

/**
 * In-process держатель состояния выполнения групп: сервис пишет, UI читает
 * (Coroutines StateFlow, без Binder/LiveData). Живёт, пока жив процесс.
 *
 * Сервис публикует АБСОЛЮТНЫЕ elapsed-цели и состояние только по событиям
 * (старт, пауза, срабатывание, стоп). Секундный отсчёт считает UI
 * (тикер в Compose на elapsedRealtime). В паузе отсчёт заморожен —
 * для этого храним остатки [pausedRemainingMs].
 */
object TimerStateHolder {

    /**
     * Рантайм одной группы.
     *
     * @param groupId идентификатор группы.
     * @param state состояние сессии группы.
     * @param nextFireElapsedMs абсолютная elapsed-цель ближайшего срабатывания
     *   (в PAUSED — now + остаток на момент публикации).
     * @param untilNextMs остаток до ближайшего события на момент публикации.
     * @param untilEndMs остаток до конца последовательности (null = «∞»).
     * @param remainingFires оставшиеся срабатывания (null = бесконечно).
     * @param pausedRemainingMs остатки в паузе: следующие срабатывания как
     *   остатки от now (UI прибавляет их к своему тикеру, не к стене).
     * @param publishedElapsedMs elapsed-метка публикации (точка отсчёта UI).
     */
    data class GroupRuntime(
        val groupId: Int,
        val state: TimerState = TimerState.IDLE,
        val nextFireElapsedMs: Long? = null,
        val untilNextMs: Long? = null,
        val untilEndMs: Long? = null,
        val remainingFires: Int? = null,
        val pausedRemainingMs: List<Long> = emptyList(),
        val publishedElapsedMs: Long = 0L,
        val droppedRingsCount: Int = 0,
    )

    /** @deprecated старый одиночный UI; оставлен для совместимости тестов. */
    data class Ui(
        val state: TimerState = TimerState.IDLE,
        val nextSound: String = "",
        val autoStop: String = "",
        val droppedRingsCount: Int = 0,
    )

    private val _groups = MutableStateFlow<Map<Int, GroupRuntime>>(emptyMap())
    val groups: StateFlow<Map<Int, GroupRuntime>> = _groups

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    fun setGroup(rt: GroupRuntime) {
        _groups.value = _groups.value + (rt.groupId to rt)
        mirrorLegacy(rt)
    }

    fun removeGroup(groupId: Int) {
        _groups.value = _groups.value - groupId
        if (_groups.value.isEmpty()) reset()
    }

    fun group(groupId: Int): GroupRuntime? = _groups.value[groupId]

    fun set(ui: Ui) {
        _ui.value = ui
    }

    fun setState(state: TimerState) {
        _ui.value = _ui.value.copy(state = state)
    }

    fun reset() {
        _ui.value = Ui()
        _groups.value = emptyMap()
    }

    /** Публикация снимка сессии группы (вызывает сервис по событиям). */
    fun publishSnapshot(
        groupId: Int,
        snap: TimerSession.SessionSnapshot,
        nowElapsedMs: Long,
        droppedRings: Int = 0,
    ) {
        val nextAbs = snap.fires.minByOrNull {
            (it.nextFireElapsedMs - nowElapsedMs).coerceAtLeast(0L)
        }?.nextFireElapsedMs
        val pausedRemain = if (snap.state == TimerState.PAUSED) {
            snap.fires.map { (it.nextFireElapsedMs - nowElapsedMs).coerceAtLeast(0L) }
        } else emptyList()
        setGroup(
            GroupRuntime(
                groupId = groupId,
                state = snap.state,
                nextFireElapsedMs = nextAbs,
                untilNextMs = snap.untilNextMs,
                untilEndMs = snap.untilEndMs,
                remainingFires = snap.remainingFires,
                pausedRemainingMs = pausedRemain,
                publishedElapsedMs = nowElapsedMs,
                droppedRingsCount = droppedRings,
            )
        )
    }

    /** Старый Ui зеркалит первую активную группу (для совместимости UI/тестов). */
    private fun mirrorLegacy(rt: GroupRuntime) {
        if (rt.state == TimerState.IDLE) {
            val anyActive = _groups.value.values.any { it.state == TimerState.RUNNING || it.state == TimerState.PAUSED }
            if (!anyActive) _ui.value = Ui()
            return
        }
        _ui.value = Ui(
            state = rt.state,
            nextSound = rt.untilNextMs?.let { "через ${TimerSession.formatHms(it)}" } ?: "",
            autoStop = rt.untilEndMs?.let { TimerSession.formatHms(it) } ?: "",
            droppedRingsCount = rt.droppedRingsCount,
        )
    }
}
