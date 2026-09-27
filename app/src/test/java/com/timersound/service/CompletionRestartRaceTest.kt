package com.timersound.service

import com.timersound.model.TimerConfig
import com.timersound.timer.TimerSession
import com.timersound.timer.TimerState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Регрессия Бага 6: отложенный teardown завершённой сессии
 * (awaitLastRingFinished + handleStop вне commands-канала) сносил новую сессию,
 * поднятую повторным START, пока старый звук доигрывал.
 *
 * Модель топологии сервиса: единственный consumer suspend-цепочки + отдельный
 * launch дожития звука. Фикс — сверка epoch сессии: teardown старой эпохи
 * после рестарта запрещён.
 */
class CompletionRestartRaceTest {

    private sealed interface Command {
        data object Start : Command
        data object CompleteOld : Command
    }

    @Test
    fun restartDuringRingPlayoutSurvivesStaleTeardown() = runTest {
        val commands = Channel<Command>(Channel.UNLIMITED)
        val ringGate = CompletableDeferred<Unit>() // звук ещё доигрывает
        val session = TimerSession()
        val empty = TimerConfig(alarms = emptyList(), autoStopMs = 0L)

        suspend fun handleStart() {
            session.start(empty, nowElapsedMs = 0L, nowWallMs = 0L)
        }

        suspend fun handleStop() {
            session.stop()
        }

        // Отложенный teardown старой сессии — как в handleAutoStop(playOutLastRing=true).
        fun scheduleStaleTeardown(completedEpoch: Long) {
            launch {
                ringGate.await()
                if (session.epoch != completedEpoch) return@launch // фикс Бага 6
                if (session.state == TimerState.COMPLETED) handleStop()
            }
        }

        val consumer = launch {
            commands.consumeEach { command ->
                when (command) {
                    is Command.Start -> handleStart()
                    is Command.CompleteOld -> {
                        session.markCompleted()
                        scheduleStaleTeardown(session.epoch)
                    }
                }
            }
        }

        commands.send(Command.Start)
        testScheduler.advanceUntilIdle()
        assertEquals(TimerState.RUNNING, session.state)

        commands.send(Command.CompleteOld) // старая сессия завершилась, звук доигрывает
        testScheduler.advanceUntilIdle()
        assertEquals(TimerState.COMPLETED, session.state)

        commands.send(Command.Start) // «Запустить заново», пока звук ещё играет
        testScheduler.advanceUntilIdle()
        assertEquals(TimerState.RUNNING, session.state)

        ringGate.complete(Unit) // старый звук доиграл — прилетает запоздалый teardown
        testScheduler.advanceUntilIdle()
        assertEquals(TimerState.RUNNING, session.state) // новая сессия жива
        consumer.cancel()
    }

    @Test
    fun epochIncrementsOnEveryStart() {
        val session = TimerSession()
        val empty = TimerConfig(alarms = emptyList(), autoStopMs = 0L)
        assertEquals(0L, session.epoch)
        session.start(empty, nowElapsedMs = 0L, nowWallMs = 0L)
        assertEquals(1L, session.epoch)
        session.start(empty, nowElapsedMs = 0L, nowWallMs = 0L)
        assertEquals(2L, session.epoch)
    }
}
