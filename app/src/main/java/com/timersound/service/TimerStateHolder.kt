package com.timersound.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.timersound.timer.TimerState

/**
 * In-process держатель состояния выполнения сессии: сервис пишет, UI читает
 * (Coroutines StateFlow, без Binder/LiveData). Живёт, пока жив процесс приложения —
 * поэтому пометка «Завершено» остаётся видимой и после остановки сервиса.
 */
object TimerStateHolder {

    data class Ui(
        val state: TimerState = TimerState.IDLE,
        /** Строка «Следующий звук: …» из сессии. */
        val nextSound: String = "",
        /** Строка «До авто-остановки: …» (пусто — без ограничения). */
        val autoStop: String = "",
        /** Сколько сигналов заглушено лимитом одновременных звуков за сессию. */
        val droppedRingsCount: Int = 0,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    fun set(ui: Ui) {
        _ui.value = ui
    }

    fun setState(state: TimerState) {
        _ui.value = _ui.value.copy(state = state)
    }

    fun reset() {
        _ui.value = Ui()
    }
}