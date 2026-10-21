package com.timersound.ui

import com.timersound.model.Defaults
import com.timersound.model.FolderEntry
import com.timersound.model.FolderPlayMode
import com.timersound.model.SoundSourceType
import com.timersound.model.VolumeMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Тесты чистых помощников UI для источника «Папка» и плавающей громкости.
 *
 * Это JVM-тесты без Android: проверяются только форматирование сводки свёрнутой
 * карточки ([alarmSummary], [volumeSummary]) и предпросмотр содержимого папки
 * ([folderFilesPreview]). Разметка (FilterChip/Slider) проверяется
 * инструментальными тестами по testTag'ам.
 */
class AlarmFolderUiTest {

    private val base = Defaults.newAlarm(0, 0)

    private fun files(vararg names: String): List<FolderEntry> =
        names.mapIndexed { i, n -> FolderEntry("content://tree/audio/$i", n) }

    // ------------------------------------------------------------------ сводка: громкость

    @Test
    fun alarmSummaryShowsFixedVolume() {
        val alarm = base.copy(volumeMode = VolumeMode.FIXED, volumePercent = 80)
        val summary = alarmSummary(alarm)
        assertTrue(summary.contains("80%"), "в сводке нет фиксированной громкости: $summary")
        assertFalse(summary.contains("–"), "у фикса не должно быть диапазона: $summary")
    }

    @Test
    fun alarmSummaryShowsRandomVolumeRange() {
        val alarm = base.copy(
            volumeMode = VolumeMode.RANDOM_RANGE,
            volumeMin = 50,
            volumeMax = 80,
        )
        val summary = alarmSummary(alarm)
        assertTrue(summary.contains("50–80%"), "в сводке нет диапазона громкости: $summary")
    }

    @Test
    fun alarmSummarySanitizesVolumeRange() {
        // Границы «переехали» местами и вышли за 0..100 — сводка показывает санированные.
        val alarm = base.copy(
            volumeMode = VolumeMode.RANDOM_RANGE,
            volumeMin = 80,
            volumeMax = 50,
        )
        assertTrue(alarmSummary(alarm).contains("50–80%"))

        val outOfBounds = base.copy(
            volumeMode = VolumeMode.RANDOM_RANGE,
            volumeMin = -10,
            volumeMax = 140,
        )
        assertTrue(alarmSummary(outOfBounds).contains("0–100%"))
    }

    @Test
    fun volumeSummaryMatchesMode() {
        assertEquals("80%", volumeSummary(base.copy(volumeMode = VolumeMode.FIXED, volumePercent = 80)))
        assertEquals(
            "50–80%",
            volumeSummary(
                base.copy(volumeMode = VolumeMode.RANDOM_RANGE, volumeMin = 50, volumeMax = 80)
            ),
        )
    }

    // ------------------------------------------------------------------ сводка: источник-папка

    @Test
    fun alarmSummaryMarksFolderSource() {
        val alarm = base.copy(
            sourceType = SoundSourceType.FOLDER,
            folderUri = "content://tree/audio",
            folderName = "Музыка",
            folderFiles = files("a.mp3", "b.mp3"),
        )
        val summary = alarmSummary(alarm)
        assertTrue(summary.contains("папка"), "в сводке нет маркера папки: $summary")
    }

    @Test
    fun alarmSummaryHasNoFolderMarkerForFileSource() {
        val summary = alarmSummary(base.copy(sourceType = SoundSourceType.FILE))
        assertFalse(summary.contains("папка"), "у одиночного файла не должно быть маркера папки: $summary")
    }

    // ------------------------------------------------------------------ предпросмотр файлов папки

    @Test
    fun folderFilesPreviewListsAllFilesWhenFew() {
        val preview = folderFilesPreview(files("a.mp3", "b.mp3", "c.mp3"))
        assertEquals("a.mp3, b.mp3, c.mp3", preview)
    }

    @Test
    fun folderFilesPreviewShowsFirstFiveAndRemainder() {
        val preview = folderFilesPreview(
            files("1.mp3", "2.mp3", "3.mp3", "4.mp3", "5.mp3", "6.mp3", "7.mp3")
        )
        assertEquals("1.mp3, 2.mp3, 3.mp3, 4.mp3, 5.mp3 … и ещё 2", preview)
    }

    @Test
    fun folderFilesPreviewRespectsCustomLimit() {
        val preview = folderFilesPreview(files("1.mp3", "2.mp3", "3.mp3"), limit = 1)
        assertEquals("1.mp3 … и ещё 2", preview)
    }

    @Test
    fun folderFilesPreviewIsEmptyForEmptyFolder() {
        assertEquals("", folderFilesPreview(emptyList()))
    }

    // ------------------------------------------------------------------ режим папки

    @Test
    fun folderPlayModesHaveShortLabels() {
        assertEquals("Случайно", FolderPlayMode.RANDOM_NOREPEAT.label)
        assertEquals("По порядку", FolderPlayMode.SEQUENTIAL.label)
    }
}
