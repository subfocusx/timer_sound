package com.timersound.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Регрессия гонки START/STOP (БАГ 4): handleStart() читал конфиг в отдельном
 * fire-and-forget scope.launch, а consumer тем временем обрабатывал STOP —
 * отложенное продолжение поднимало сессию после остановки.
 *
 * Инвариант фикса: команды обрабатываются строго последовательно suspend-цепочкой
 * внутри consumeEach — STOP не может «проскочить» раньше завершения START.
 * Тест моделирует ту же топологию (канал + висящий config.first() + два обработчика)
 * в обеих конфигурациях: старой (launch внутри) и новой (suspend подряд).
 */
class StartStopRaceTest {

    private enum class Command { START, STOP }

    @Test
    fun sequentialHandlingKeepsStopLast() = runTest {
        val commands = Channel<Command>(Channel.UNLIMITED)
        val configGate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        suspend fun handleStart() {
            configGate.await() // prefs.config.first(): висит, пока конфиг грузится
            events += "started"
        }

        fun handleStop() {
            events += "stopped"
        }

        suspend fun handleCommand(command: Command) {
            when (command) {
                Command.START -> handleStart()
                Command.STOP -> handleStop()
            }
        }

        val consumer = launch { commands.consumeEach { handleCommand(it) } }
        commands.send(Command.START)
        commands.send(Command.STOP)
        testScheduler.advanceUntilIdle() // STOP ждёт в очереди, START висит на config
        assertEquals(emptyList<String>(), events)
        configGate.complete(Unit) // конфиг загрузился — START завершается, затем STOP
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("started", "stopped"), events)
        consumer.cancel()
    }

    @Test
    fun fireAndForgetHandlingLetsStopSlipFirst() = runTest {
        // Демонстрация старого поведения: вложенный launch отпускает consumer дальше,
        // STOP обрабатывается раньше завершения START.
        val commands = Channel<Command>(Channel.UNLIMITED)
        val configGate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        fun handleStartOld() {
            launch {
                configGate.await()
                events += "started"
            }
        }

        suspend fun handleCommandOld(command: Command) {
            when (command) {
                Command.START -> handleStartOld()
                Command.STOP -> events += "stopped"
            }
        }

        val consumer = launch { commands.consumeEach { handleCommandOld(it) } }
        commands.send(Command.START)
        commands.send(Command.STOP)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("stopped"), events) // гонка: стоп раньше старта
        configGate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("stopped", "started"), events) // старт воскрес после стопа
        consumer.cancel()
    }
}
