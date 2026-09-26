package com.timersound.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Задача 3: математика лимита одновременных звуков — без Android SDK.
 * Сам `play()` с реальным Ringtone покрывается только ручной проверкой.
 */
class AudioEngineDroppedTest {

    @Test
    fun noDropBelowLimit() {
        assertEquals(0, AudioEngine.droppedFor(activeCount = 9, incomingNewChannel = true))
    }

    @Test
    fun oneDropAtLimit() {
        // 10 активных + 1 новый при лимите 10 → глушится 1 старый.
        assertEquals(1, AudioEngine.droppedFor(activeCount = 10, incomingNewChannel = true))
    }

    @Test
    fun noDropWhenSameChannelReplays() {
        // Повтор уже звучащего канала не вытесняет других.
        assertEquals(0, AudioEngine.droppedFor(activeCount = 10, incomingNewChannel = false))
    }

    @Test
    fun customLimitRespected() {
        assertEquals(2, AudioEngine.droppedFor(activeCount = 3, incomingNewChannel = true, limit = 2))
    }
}
