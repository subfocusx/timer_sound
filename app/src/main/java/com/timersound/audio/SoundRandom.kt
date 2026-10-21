package com.timersound.audio

import java.util.Random

/**
 * Единственный источник случайности для звука на процесс.
 *
 * Выбор файла из папки и случайная громкость берут случайность отсюда:
 * новый `Random` на каждый тик давал бы коррелированные значения
 * (java.util.Random засевается временем) и лишнюю нагрузку.
 */
object SoundRandom {
    /** Общий генератор. Потокобезопасен (java.util.Random). */
    val random: Random = Random()
}
