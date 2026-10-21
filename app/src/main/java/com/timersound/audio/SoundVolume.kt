package com.timersound.audio

import com.timersound.model.AlarmConfig
import com.timersound.model.VolumeMode
import java.util.Random

/**
 * Громкость будильника: фиксированная или случайная внутри диапазона.
 *
 * Чистая функция, без Android (юнит-тесты на JVM).
 * Инвариант диапазона: `0 <= min <= max <= 100`.
 */
object SoundVolume {

    const val MIN_PERCENT = 0
    const val MAX_PERCENT = 100

    /**
     * Громкость для очередного срабатывания.
     *
     * @param mode          режим громкости будильника.
     * @param volumePercent фиксированная громкость (используется в [VolumeMode.FIXED]).
     * @param min           нижняя граница диапазона.
     * @param max           верхняя граница диапазона.
     * @param random        источник случайности (в приложении — один на процесс).
     */
    fun resolve(
        mode: VolumeMode,
        volumePercent: Int,
        min: Int,
        max: Int,
        random: Random,
    ): Int {
        val fixed = volumePercent.coerceIn(MIN_PERCENT, MAX_PERCENT)
        if (mode == VolumeMode.FIXED) return fixed
        val (lo, hi) = sanitizeRange(min, max)
        if (hi <= lo) return lo
        // nextInt(bound) — верхняя граница исключается, поэтому +1.
        return lo + random.nextInt(hi - lo + 1)
    }

    /** Громкость для будильника по его полям. */
    fun resolveFor(alarm: AlarmConfig, random: Random): Int =
        resolve(alarm.volumeMode, alarm.volumePercent, alarm.volumeMin, alarm.volumeMax, random)

    /**
     * Приводит пару границ к валидному виду: обе в 0..100, min <= max.
     * Если границы «переехали» местами — меняем их местами, а не отбрасываем.
     */
    fun sanitizeRange(min: Int, max: Int): Pair<Int, Int> {
        val lo = min.coerceIn(MIN_PERCENT, MAX_PERCENT)
        val hi = max.coerceIn(MIN_PERCENT, MAX_PERCENT)
        return if (lo <= hi) lo to hi else hi to lo
    }

    /** Среднее значение диапазона — для превью (без рандома на каждый ползунок). */
    fun midpoint(min: Int, max: Int): Int {
        val (lo, hi) = sanitizeRange(min, max)
        return (lo + hi) / 2
    }
}
