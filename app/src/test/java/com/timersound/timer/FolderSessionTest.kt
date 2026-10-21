package com.timersound.timer

import com.timersound.audio.FolderSoundPicker
import com.timersound.model.AlarmConfig
import com.timersound.model.FolderEntry
import com.timersound.model.FolderPlayMode
import com.timersound.model.SceneMode
import com.timersound.model.SoundSourceType
import com.timersound.model.TimerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Сессия с будильником-папкой.
 *
 * Главное свойство: выбор файла из папки НЕ сдвигает фазу интервалов —
 * движок планирования работает по (groupId, alarmId) и не знает про папки.
 * Сравниваем с идентичным будильником-файлом: расписание срабатываний должно
 * совпасть до миллисекунды.
 */
class FolderSessionTest {

    private fun folderAlarm(
        id: Int,
        files: List<String>,
        intervalMs: Long = 20_000L,
        mode: FolderPlayMode = FolderPlayMode.RANDOM_NOREPEAT,
    ): AlarmConfig = AlarmConfig(
        id = id,
        name = "Папка ${id + 1}",
        fileUri = "",
        mode = SceneMode.REPEAT,
        intervalMs = intervalMs,
        volumePercent = 80,
        enabled = true,
        sourceType = SoundSourceType.FOLDER,
        folderUri = "content://tree/alarms",
        folderName = "alarms",
        folderFiles = files.mapIndexed { i, uri -> FolderEntry(uri, "sound$i.mp3") },
        folderPlayMode = mode,
    )

    private fun fileAlarm(id: Int, intervalMs: Long = 20_000L): AlarmConfig = AlarmConfig(
        id = id,
        name = "Файл ${id + 1}",
        fileUri = "file:///tmp/ch$id.mp3",
        mode = SceneMode.REPEAT,
        intervalMs = intervalMs,
        volumePercent = 80,
        enabled = true,
    )

    private fun config(alarms: List<AlarmConfig>): TimerConfig = TimerConfig(
        alarms = alarms,
        autoStopMs = 0L,
        maxTotalFiresPerSession = 0,
    )

    @Test
    fun folderAlarmFiresOnTheSameScheduleAsFileAlarm() {
        val now = 10_000L
        val files = listOf("content://a/1", "content://a/2", "content://a/3")

        val fileSession = TimerSession()
        fileSession.start(config(listOf(fileAlarm(0))), now)
        val fileFires = mutableListOf<Long>()
        fileSession.tick(now) { }

        val folderSession = TimerSession()
        folderSession.start(config(listOf(folderAlarm(0, files))), now)
        val folderFires = mutableListOf<Long>()

        var t = now
        while (t < now + 200_000L) {
            fileSession.tick(t) { fileFires += t }
            folderSession.tick(t) { folderFires += t }
            t += 250L
        }

        assertTrue(fileFires.isNotEmpty())
        assertEquals(fileFires, folderFires)
    }

    @Test
    fun pickingFolderFileDoesNotShiftPhase() {
        val now = 10_000L
        val interval = 20_000L
        val files = listOf("content://a/1", "content://a/2", "content://a/3")
        val session = TimerSession()
        session.start(config(listOf(folderAlarm(0, files, intervalMs = interval))), now)

        val firedAt = mutableListOf<Long>()
        val random = Random(42)
        var cursor = -1
        var t = now
        while (t < now + 120_000L) {
            session.tick(t) { alarm ->
                // Ровно то, что делает сервис: выбираем файл и «играем» его.
                val index = FolderSoundPicker.nextIndex(
                    size = alarm.folderFiles.size,
                    mode = alarm.folderPlayMode,
                    lastIndex = cursor,
                    random = random,
                )
                cursor = index
                firedAt += t
            }
            t += 250L
        }

        // Ожидаем срабатывания ровно на интервалах 20с, 40с, 60с, 80с, 100с, 120с.
        assertEquals(5, firedAt.size)
        assertEquals(
            listOf(now + interval, now + 2 * interval, now + 3 * interval, now + 4 * interval, now + 5 * interval),
            firedAt,
        )
    }

    @Test
    fun sequentialFolderPlaysFilesInOrder() {
        val files = listOf("content://a/1", "content://a/2", "content://a/3")
        val alarm = folderAlarm(0, files, mode = FolderPlayMode.SEQUENTIAL)
        val random = Random(7)
        var cursor = -1
        val played = mutableListOf<String>()
        repeat(5) {
            val index = FolderSoundPicker.nextIndex(files.size, alarm.folderPlayMode, cursor, random)
            cursor = index
            played += alarm.folderFiles[index].name
        }
        assertEquals(
            listOf("sound0.mp3", "sound1.mp3", "sound2.mp3", "sound0.mp3", "sound1.mp3"),
            played,
        )
    }

    @Test
    fun folderAlarmWithEmptyFolderIsNotPlayable() {
        val empty = folderAlarm(0, emptyList())
        assertFalse(empty.hasFile)
        val cfg = config(listOf(empty))
        assertTrue(cfg.playableAlarms().isEmpty())
        assertEquals(1, cfg.missingFileAlarms().size)
    }
}
