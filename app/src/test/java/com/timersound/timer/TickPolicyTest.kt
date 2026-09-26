package com.timersound.timer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * А1: политика адаптивного шага тик-цикла — чистая математика, без Android.
 */
class TickPolicyTest {

    @Test
    fun nearEventTicksFrequently() {
        assertEquals(250L, TickPolicy.delayMs(0L))
        assertEquals(250L, TickPolicy.delayMs(5_000L))
    }

    @Test
    fun midRangeTicksEverySecond() {
        assertEquals(1_000L, TickPolicy.delayMs(5_001L))
        assertEquals(1_000L, TickPolicy.delayMs(30_000L))
    }

    @Test
    fun farEventAndNoEventsSleepLong() {
        assertEquals(5_000L, TickPolicy.delayMs(30_001L))
        assertEquals(5_000L, TickPolicy.delayMs(3 * 3_600_000L))
        assertEquals(5_000L, TickPolicy.delayMs(null))
    }
}
