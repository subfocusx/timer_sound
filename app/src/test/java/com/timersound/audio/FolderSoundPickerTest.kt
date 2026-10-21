package com.timersound.audio

import com.timersound.model.FolderPlayMode
import org.junit.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Выбор файла из папки — чистая математика, Android не нужен.
 * Все генераторы случайности с явным seed: тесты детерминированы.
 */
class FolderSoundPickerTest {

    @Test
    fun sequentialStartsFromFirstFile() {
        val random = Random(1L)
        assertEquals(0, FolderSoundPicker.nextIndex(3, FolderPlayMode.SEQUENTIAL, -1, random))
    }

    @Test
    fun sequentialWalksThroughFilesInOrder() {
        val random = Random(1L)
        assertEquals(1, FolderSoundPicker.nextIndex(3, FolderPlayMode.SEQUENTIAL, 0, random))
        assertEquals(2, FolderSoundPicker.nextIndex(3, FolderPlayMode.SEQUENTIAL, 1, random))
    }

    @Test
    fun sequentialWrapsToFirstAfterLastFile() {
        val random = Random(1L)
        assertEquals(0, FolderSoundPicker.nextIndex(3, FolderPlayMode.SEQUENTIAL, 2, random))
    }

    @Test
    fun sequentialWrapsOnOutOfRangeLastIndex() {
        // lastIndex вне диапазона (например, кэш папки изменился) — начинаем сначала.
        val random = Random(1L)
        assertEquals(0, FolderSoundPicker.nextIndex(3, FolderPlayMode.SEQUENTIAL, 10, random))
    }

    @Test
    fun emptyFolderHasNoIndex() {
        val random = Random(1L)
        assertEquals(-1, FolderSoundPicker.nextIndex(0, FolderPlayMode.SEQUENTIAL, -1, random))
        assertEquals(-1, FolderSoundPicker.nextIndex(0, FolderPlayMode.RANDOM_NOREPEAT, -1, random))
        assertEquals(-1, FolderSoundPicker.nextIndex(-5, FolderPlayMode.SEQUENTIAL, -1, random))
        assertEquals(-1, FolderSoundPicker.nextIndex(-5, FolderPlayMode.RANDOM_NOREPEAT, -1, random))
    }

    @Test
    fun singleFileFolderAlwaysPlaysThatFile() {
        // При одном файле повтор неизбежен — индекс всегда 0.
        val random = Random(1L)
        assertEquals(0, FolderSoundPicker.nextIndex(1, FolderPlayMode.SEQUENTIAL, -1, random))
        assertEquals(0, FolderSoundPicker.nextIndex(1, FolderPlayMode.SEQUENTIAL, 0, random))
        assertEquals(0, FolderSoundPicker.nextIndex(1, FolderPlayMode.RANDOM_NOREPEAT, -1, random))
        assertEquals(0, FolderSoundPicker.nextIndex(1, FolderPlayMode.RANDOM_NOREPEAT, 0, random))
    }

    @Test
    fun randomNoRepeatNeverReturnsForbiddenIndex() {
        val random = Random(42L)
        repeat(2000) {
            val next = FolderSoundPicker.nextIndex(5, FolderPlayMode.RANDOM_NOREPEAT, 3, random)
            assertTrue(next in 0..4, "индекс вне диапазона: $next")
            assertTrue(next != 3, "выпал запрещённый индекс: $next")
        }
    }

    @Test
    fun randomNoRepeatSequenceHasNoImmediateRepeat() {
        val random = Random(7L)
        var last = -1
        repeat(500) {
            val next = FolderSoundPicker.nextIndex(4, FolderPlayMode.RANDOM_NOREPEAT, last, random)
            assertTrue(next in 0..3, "индекс вне диапазона: $next")
            assertTrue(next != last, "два раза подряд один и тот же индекс: $next")
            last = next
        }
    }

    @Test
    fun randomNoRepeatEventuallyCoversAllFiles() {
        // За 2000 выборов должны встретиться все индексы папки из трёх файлов.
        val random = Random(2024L)
        val seen = mutableSetOf<Int>()
        var last = -1
        repeat(2000) {
            val next = FolderSoundPicker.nextIndex(3, FolderPlayMode.RANDOM_NOREPEAT, last, random)
            seen += next
            last = next
        }
        assertEquals(setOf(0, 1, 2), seen)
    }

    @Test
    fun randomNoRepeatWithoutHistoryPicksAnyFile() {
        // lastIndex = -1 (ещё не играли) — запрещённого индекса нет, любой файл допустим.
        val random = Random(3L)
        repeat(500) {
            val next = FolderSoundPicker.nextIndex(3, FolderPlayMode.RANDOM_NOREPEAT, -1, random)
            assertTrue(next in 0..2, "индекс вне диапазона: $next")
        }
    }

    @Test
    fun excludedIndexIsNeverPicked() {
        // Битый файл исключается — следующий файл папки должен играть.
        val random = Random(11L)
        repeat(500) {
            val next = FolderSoundPicker.nextIndex(4, FolderPlayMode.RANDOM_NOREPEAT, -1, random, exclude = setOf(2))
            assertTrue(next in 0..3, "индекс вне диапазона: $next")
            assertTrue(next != 2, "выпал исключённый индекс: $next")
        }
    }

    @Test
    fun sequentialSkipsExcludedIndex() {
        // По порядку: после 0 идёт 1, но если 1 исключён — берём 2.
        val random = Random(5L)
        assertEquals(
            2,
            FolderSoundPicker.nextIndex(4, FolderPlayMode.SEQUENTIAL, 0, random, exclude = setOf(1)),
        )
    }

    @Test
    fun sequentialWrapsAroundExcludedIndexes() {
        // Последний файл исключён — по кругу доходим до первого.
        val random = Random(5L)
        assertEquals(
            0,
            FolderSoundPicker.nextIndex(3, FolderPlayMode.SEQUENTIAL, 2, random, exclude = setOf(2)),
        )
    }

    @Test
    fun allIndexesExcludedMeansNothingToPlay() {
        val random = Random(5L)
        assertEquals(
            FolderSoundPicker.NO_INDEX,
            FolderSoundPicker.nextIndex(3, FolderPlayMode.SEQUENTIAL, -1, random, exclude = setOf(0, 1, 2)),
        )
        assertEquals(
            FolderSoundPicker.NO_INDEX,
            FolderSoundPicker.nextIndex(3, FolderPlayMode.RANDOM_NOREPEAT, -1, random, exclude = setOf(0, 1, 2)),
        )
    }

    @Test
    fun singleFileFolderExcludedMeansNothingToPlay() {
        val random = Random(5L)
        assertEquals(
            FolderSoundPicker.NO_INDEX,
            FolderSoundPicker.nextIndex(1, FolderPlayMode.SEQUENTIAL, -1, random, exclude = setOf(0)),
        )
    }
}
