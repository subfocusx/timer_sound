package com.timersound.model

import org.junit.Before
import org.junit.Test
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Будильник с источником «папка»: играбельность, URI воспроизведения и поиск пересечений
 * с одиночными файлами.
 */
class FolderAlarmConfigTest {

    @Before
    fun useUtcForDeterministicWallClock() {
        // REPEAT со startMinutes считается по wall-времени — нужен фиксированный часовой пояс.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun fileAlarm(
        id: Int,
        fileUri: String,
        enabled: Boolean = true,
    ): AlarmConfig = Defaults.newAlarm(id, id).copy(
        fileUri = fileUri,
        enabled = enabled,
    )

    private fun folderAlarm(
        id: Int,
        uris: List<String>,
        fileUri: String = "",
        enabled: Boolean = true,
    ): AlarmConfig = Defaults.newAlarm(id, id).copy(
        sourceType = SoundSourceType.FOLDER,
        folderUri = "content://com.android.externalstorage.documents/tree/folder$id",
        folderName = "Папка $id",
        folderFiles = uris.map { FolderEntry(uri = it, name = it.substringAfterLast('/')) },
        fileUri = fileUri,
        enabled = enabled,
    )

    /** REPEAT с одинаковым стартом и шагом: первые срабатывания совпадают по времени. */
    private fun AlarmConfig.repeatingAt(startMinutes: Int): AlarmConfig = copy(
        mode = SceneMode.REPEAT,
        startMinutes = startMinutes,
        intervalMs = 60_000L,
    )

    @Test
    fun hasFileDependsOnSourceType() {
        assertTrue(fileAlarm(id = 0, fileUri = "content://audio/1").hasFile)
        assertFalse(fileAlarm(id = 1, fileUri = "").hasFile)
        assertTrue(folderAlarm(id = 2, uris = listOf("content://audio/1")).hasFile)
        assertFalse(folderAlarm(id = 3, uris = emptyList()).hasFile)
    }

    @Test
    fun isBuiltInBeepOnlyForFileSource() {
        assertTrue(fileAlarm(id = 0, fileUri = Defaults.BUILT_IN_BEEP).isBuiltInBeep)
        assertFalse(fileAlarm(id = 1, fileUri = "content://audio/1").isBuiltInBeep)
        // Папка, у которой остался старый fileUri встроенного сигнала, — не встроенный сигнал.
        assertFalse(
            folderAlarm(
                id = 2,
                uris = listOf("content://audio/1"),
                fileUri = Defaults.BUILT_IN_BEEP,
            ).isBuiltInBeep,
        )
    }

    @Test
    fun playbackUrisCoverAllSources() {
        assertEquals(
            setOf("content://audio/1"),
            fileAlarm(id = 0, fileUri = "content://audio/1").playbackUris(),
        )
        assertEquals(emptySet<String>(), fileAlarm(id = 1, fileUri = "").playbackUris())
        assertEquals(
            setOf("content://audio/1", "content://audio/2"),
            folderAlarm(id = 2, uris = listOf("content://audio/1", "content://audio/2")).playbackUris(),
        )
    }

    @Test
    fun playableAlarmsIncludeFolderWithFilesAndExcludeEmptyFolder() {
        val cfg = TimerConfig(
            alarms = listOf(
                fileAlarm(id = 0, fileUri = "content://audio/1"),
                folderAlarm(id = 1, uris = listOf("content://audio/2", "content://audio/3")),
                folderAlarm(id = 2, uris = emptyList()),
            ),
            autoStopMs = 0L,
        )

        assertEquals(listOf(0, 1), cfg.playableAlarms().map { it.id })
        // Пустая папка блокирует старт так же, как пустой файл.
        assertEquals(listOf(2), cfg.missingFileAlarms().map { it.id })
    }

    @Test
    fun overlappingAlarmsFindsTwoFilesWithSameUri() {
        val cfg = TimerConfig(
            alarms = listOf(
                fileAlarm(id = 0, fileUri = "content://audio/1").repeatingAt(startMinutes = 10),
                fileAlarm(id = 1, fileUri = "content://audio/1").repeatingAt(startMinutes = 10),
            ),
            autoStopMs = 0L,
        )

        // now = полночь UTC, старт в 00:10 — первое срабатывание попадает в горизонт часа.
        val pairs = cfg.overlappingAlarms(nowElapsedMs = 0L, nowWallMs = 0L)
        assertEquals(1, pairs.size)
        assertEquals(setOf(0, 1), setOf(pairs[0].first.id, pairs[0].second.id))
    }

    @Test
    fun overlappingAlarmsFindsFileThatIsInsideFolder() {
        val cfg = TimerConfig(
            alarms = listOf(
                fileAlarm(id = 0, fileUri = "content://audio/1").repeatingAt(startMinutes = 10),
                folderAlarm(
                    id = 1,
                    uris = listOf("content://audio/1", "content://audio/2"),
                ).repeatingAt(startMinutes = 10),
            ),
            autoStopMs = 0L,
        )

        // Одиночный файл совпадает с одним из файлов папки — пересечение по playbackUris.
        val pairs = cfg.overlappingAlarms(nowElapsedMs = 0L, nowWallMs = 0L)
        assertEquals(1, pairs.size)
        assertEquals(setOf(0, 1), setOf(pairs[0].first.id, pairs[0].second.id))
    }

    @Test
    fun overlappingAlarmsIgnoresFileAndFolderWithDisjointUris() {
        val cfg = TimerConfig(
            alarms = listOf(
                fileAlarm(id = 0, fileUri = "content://audio/1").repeatingAt(startMinutes = 10),
                folderAlarm(
                    id = 1,
                    uris = listOf("content://audio/2", "content://audio/3"),
                ).repeatingAt(startMinutes = 10),
            ),
            autoStopMs = 0L,
        )

        assertTrue(cfg.overlappingAlarms(nowElapsedMs = 0L, nowWallMs = 0L).isEmpty())
    }

    @Test
    fun overlappingAlarmsFindsFilePickedFromSameFolder() {
        // Реальные формы uri с устройства: файл из папки адресован через дерево,
        // тот же файл из пикера файлов — напрямую. Документ один, строки разные.
        val treeFileUri =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FAlarmSounds" +
                "/document/primary%3AMusic%2FAlarmSounds%2F01_tone_440.wav"
        val pickedFileUri =
            "content://com.android.externalstorage.documents/document/primary%3AMusic%2FAlarmSounds%2F01_tone_440.wav"
        val cfg = TimerConfig(
            alarms = listOf(
                fileAlarm(id = 0, fileUri = pickedFileUri).repeatingAt(startMinutes = 10),
                folderAlarm(id = 1, uris = listOf(treeFileUri)).repeatingAt(startMinutes = 10),
            ),
            autoStopMs = 0L,
        )

        val pairs = cfg.overlappingAlarms(nowElapsedMs = 0L, nowWallMs = 0L)
        assertEquals(1, pairs.size)
        assertEquals(setOf(0, 1), setOf(pairs[0].first.id, pairs[0].second.id))
    }
}
