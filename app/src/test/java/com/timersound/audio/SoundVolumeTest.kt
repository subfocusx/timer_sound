package com.timersound.audio

import com.timersound.model.AlarmConfig
import com.timersound.model.Defaults
import com.timersound.model.VolumeMode
import org.junit.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Громкость будильника: фиксированная или случайная в диапазоне — чистая математика, без Android.
 */
class SoundVolumeTest {

    private fun alarm(
        volumeMode: VolumeMode,
        volumePercent: Int = 50,
        volumeMin: Int = 0,
        volumeMax: Int = 100,
    ): AlarmConfig = Defaults.newAlarm(0, 0).copy(
        volumeMode = volumeMode,
        volumePercent = volumePercent,
        volumeMin = volumeMin,
        volumeMax = volumeMax,
    )

    @Test
    fun fixedVolumeIsClampedToPercentRange() {
        assertEquals(80, SoundVolume.resolve(VolumeMode.FIXED, 80, 10, 90, Random(1L)))
        assertEquals(100, SoundVolume.resolve(VolumeMode.FIXED, 140, 10, 90, Random(1L)))
        assertEquals(0, SoundVolume.resolve(VolumeMode.FIXED, -20, 10, 90, Random(1L)))
    }

    @Test
    fun fixedVolumeIgnoresMinMax() {
        // Границы диапазона не влияют на фиксированную громкость.
        assertEquals(40, SoundVolume.resolve(VolumeMode.FIXED, 40, 0, 10, Random(1L)))
        assertEquals(40, SoundVolume.resolve(VolumeMode.FIXED, 40, 90, 100, Random(1L)))
    }

    @Test
    fun randomVolumeStaysInsideRange() {
        val random = Random(11L)
        repeat(5000) {
            val volume = SoundVolume.resolve(VolumeMode.RANDOM_RANGE, 50, 30, 70, random)
            assertTrue(volume in 30..70, "громкость вне диапазона: $volume")
        }
    }

    @Test
    fun randomVolumeWithEqualBoundsReturnsExactlyThatValue() {
        val random = Random(13L)
        repeat(100) {
            assertEquals(55, SoundVolume.resolve(VolumeMode.RANDOM_RANGE, 50, 55, 55, random))
        }
    }

    @Test
    fun randomVolumeOverFullRangeHitsBothEnds() {
        val random = Random(17L)
        val seen = mutableSetOf<Int>()
        repeat(5000) {
            val volume = SoundVolume.resolve(VolumeMode.RANDOM_RANGE, 50, 0, 100, random)
            assertTrue(volume in 0..100, "громкость вне 0..100: $volume")
            seen += volume
        }
        assertTrue(seen.contains(0), "нижний край не выпал: $seen")
        assertTrue(seen.contains(100), "верхний край не выпал: $seen")
    }

    @Test
    fun randomVolumeSanitizesReversedRange() {
        // Границы «переехали» местами — resolve обязан привести их к валидному виду.
        val random = Random(19L)
        repeat(1000) {
            val volume = SoundVolume.resolve(VolumeMode.RANDOM_RANGE, 50, 90, 30, random)
            assertTrue(volume in 30..90, "громкость вне диапазона: $volume")
        }
    }

    @Test
    fun sanitizeRangeClampsAndSwapsReversedBounds() {
        assertEquals(30 to 90, SoundVolume.sanitizeRange(90, 30))
        assertEquals(0 to 100, SoundVolume.sanitizeRange(0, 100))
        assertEquals(0 to 100, SoundVolume.sanitizeRange(-10, 140))
        assertEquals(0 to 50, SoundVolume.sanitizeRange(-5, 50))
        assertEquals(50 to 100, SoundVolume.sanitizeRange(50, 140))
    }

    @Test
    fun midpointUsesSanitizedRange() {
        assertEquals(65, SoundVolume.midpoint(50, 80))
        assertEquals(65, SoundVolume.midpoint(80, 50))
    }

    @Test
    fun resolveForUsesAlarmVolumeSettings() {
        val fixed = alarm(VolumeMode.FIXED, volumePercent = 42, volumeMin = 10, volumeMax = 20)
        assertEquals(42, SoundVolume.resolveFor(fixed, Random(1L)))

        val ranged = alarm(VolumeMode.RANDOM_RANGE, volumePercent = 42, volumeMin = 10, volumeMax = 20)
        val random = Random(23L)
        repeat(2000) {
            val volume = SoundVolume.resolveFor(ranged, random)
            assertTrue(volume in 10..20, "громкость вне диапазона: $volume")
        }
    }

    @Test
    fun resolveForClampsFixedVolumeOfAlarm() {
        val loud = alarm(VolumeMode.FIXED, volumePercent = 150)
        assertEquals(100, SoundVolume.resolveFor(loud, Random(1L)))
    }
}
