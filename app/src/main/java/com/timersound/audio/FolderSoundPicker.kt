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
     * @param exclude   индексы файлов, которые нельзя брать (только что не воспроизвелись).
     * @return индекс файла или [NO_INDEX], если брать нечего.
     */
    fun nextIndex(
        size: Int,
        mode: FolderPlayMode,
        lastIndex: Int,
        random: Random,
        exclude: Set<Int> = emptySet(),
    ): Int {
        if (size <= 0) return NO_INDEX
        if (size == 1) return if (0 in exclude) NO_INDEX else 0
        val forbidden = lastIndex.takeIf { it in 0 until size }
        val allowed = (0 until size).filter { it != forbidden && it !in exclude }
        if (allowed.isEmpty()) return NO_INDEX
        return when (mode) {
            FolderPlayMode.SEQUENTIAL -> {
                // Идём по кругу от следующего после lastIndex, пропуская исключённые.
                var idx = ((forbidden ?: -1) + 1) % size
                var guard = 0
                while (idx in exclude && guard < size) {
                    idx = (idx + 1) % size
                    guard++
                }
                if (idx in exclude) NO_INDEX else idx
            }
            FolderPlayMode.RANDOM_NOREPEAT -> {
                // Равномерный выбор среди допустимых (без только что сыгранного).
                allowed[random.nextInt(allowed.size)]
            }
        }
    }
}
