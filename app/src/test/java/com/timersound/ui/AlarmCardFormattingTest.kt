package com.timersound.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Тесты для функций форматирования времени в AlarmCard.kt
 */
class AlarmCardFormattingTest {

    @Test
    fun parseHm_validInput_withColon() {
        assertEquals(60, parseHm("01:00"))  // 1 час
        assertEquals(90, parseHm("01:30"))  // 1 час 30 минут
        assertEquals(0, parseHm("00:00"))
        assertEquals(24 * 60, parseHm("24:00"))
        assertEquals(59, parseHm("00:59"))
    }

    @Test
    fun parseHm_invalidInput_returnsNull() {
        assertNull(parseHm(""))
        assertNull(parseHm("1:00"))   // слишком короткое
        assertNull(parseHm("123:45")) // слишком длинное
        assertNull(parseHm("ab:cd"))
        assertNull(parseHm("25:00"))  // часы > 24
        assertNull(parseHm("12:60"))  // минуты > 59
    }

    @Test
    fun formatMinutes_correctOutput() {
        assertEquals("00:00", formatMinutes(0))
        assertEquals("01:00", formatMinutes(60))
        assertEquals("01:30", formatMinutes(90))
        assertEquals("24:00", formatMinutes(24 * 60))
        assertEquals("00:59", formatMinutes(59))
    }

    @Test
    fun parseHms_validInput_withColons() {
        assertEquals(3600_000L, parseHms("01:00:00"))  // 1 час
        assertEquals(5400_000L, parseHms("01:30:00"))  // 1 час 30 минут
        assertEquals(0L, parseHms("00:00:00"))
        assertEquals(60_000L, parseHms("00:01:00"))    // 1 минута
        assertEquals(1_000L, parseHms("00:00:01"))     // 1 секунда
    }

    @Test
    fun parseHms_invalidInput_returnsNull() {
        assertNull(parseHms(""))
        assertNull(parseHms("1:00:00"))    // слишком короткое
        assertNull(parseHms("12:34:567"))  // слишком длинное
        assertNull(parseHms("ab:cd:ef"))
        assertNull(parseHms("01:60:00"))   // минуты > 59
        assertNull(parseHms("01:00:60"))   // секунды > 59
    }

    @Test
    fun `mask formats input correctly - 1 digit`() {
        val input = "9"
        val expected = "9"
        assertEquals(expected, input.filter { it.isDigit() }.take(4).let { 
            if (it.length > 2) "${it.take(2)}:${it.drop(2)}" else it 
        })
    }

    @Test
    fun `mask formats input correctly - 2 digits`() {
        val input = "93"
        val expected = "93"
        assertEquals(expected, input.filter { it.isDigit() }.take(4).let { 
            if (it.length > 2) "${it.take(2)}:${it.drop(2)}" else it 
        })
    }

    @Test
    fun `mask formats input correctly - 3 digits`() {
        val input = "930"
        val expected = "93:0"
        assertEquals(expected, input.filter { it.isDigit() }.take(4).let { 
            if (it.length > 2) "${it.take(2)}:${it.drop(2)}" else it 
        })
    }

    @Test
    fun `mask formats input correctly - 4 digits`() {
        val input = "9300"
        val expected = "93:00"
        assertEquals(expected, input.filter { it.isDigit() }.take(4).let { 
            if (it.length > 2) "${it.take(2)}:${it.drop(2)}" else it 
        })
    }

    @Test
    fun `mask formats input correctly - standard time 0930`() {
        val input = "0930"
        val formatted = input.filter { it.isDigit() }.take(4).let { 
            if (it.length > 2) "${it.take(2)}:${it.drop(2)}" else it 
        }
        assertEquals("09:30", formatted)
        assertEquals(9 * 60 + 30, parseHm(formatted))
    }

    @Test
    fun `mask filters non-digit characters`() {
        val input = "9a3b0"
        val expected = "93:0"
        assertEquals(expected, input.filter { it.isDigit() }.take(4).let { 
            if (it.length > 2) "${it.take(2)}:${it.drop(2)}" else it 
        })
    }

    @Test
    fun `mask limits to 4 digits`() {
        val input = "123456"
        val expected = "12:34"
        assertEquals(expected, input.filter { it.isDigit() }.take(4).let { 
            if (it.length > 2) "${it.take(2)}:${it.drop(2)}" else it 
        })
    }
}
