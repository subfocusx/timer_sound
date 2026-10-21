package com.timersound.audio

import com.timersound.model.FolderPlayMode
import java.util.Random

/**
 * Выбор файла из папки — чистая функция, без Android (юнит-тесты запускаются на JVM).
 *
 * Семантика:
 *  - [FolderPlayMode.SEQUENTIAL] — по порядку: после последнего файла снова первый;
 *  - [FolderPlayMode.RANDOM_NOREPEAT] — случайный файл, кроме только что игравшего
 *    (при количестве файлов > 1; при одном файле повтор неизбежен).
 */
object FolderSoundPicker {

    /** Нет файлов — индекс -1. */
    const val NO_INDEX = -1

    /**
     * Индекс следующего файла папки.
     *
     * @param size      количество файлов в папке.
     * @param mode      режим выбора.
     * @param lastIndex индекс только что сыгранного файла (-1 — ещё не играли).
     * @param random    источник случайности (в приложении — один на процесс).
     */
    fun nextIndex(size: Int, mode: FolderPlayMode, lastIndex: Int, random: Random): Int {
        if (size <= 0) return NO_INDEX
        if (size == 1) return 0
        return when (mode) {
            FolderPlayMode.SEQUENTIAL -> {
                val next = lastIndex + 1
                if (next >= size) 0 else next.coerceAtLeast(0)
            }
            FolderPlayMode.RANDOM_NOREPEAT -> {
                val forbidden = lastIndex.takeIf { it in 0 until size }
                if (forbidden == null) {
                    random.nextInt(size)
                } else {
                    // Равномерный выбор из (size - 1) вариантов без запрещённого индекса.
                    val pick = random.nextInt(size - 1)
                    if (pick >= forbidden) pick + 1 else pick
                }
            }
        }
    }
}
