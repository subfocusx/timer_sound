package com.timersound.data

import com.timersound.model.Defaults
import com.timersound.model.FolderEntry
import com.timersound.model.FolderPlayMode
import com.timersound.model.SoundSourceType
import com.timersound.model.VolumeMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Чистая сериализация будильника-папки (без DataStore).
 *
 * Отдельный файл нужен, чтобы поймать ошибку кодирования ДТО быстро и без
 * Robolectric: падение внутри актора DataStore выглядит как «зависший» тест
 * (ack никогда не завершается), а не как исключение.
 */
class AlarmJsonFolderTest {

    private fun folderAlarm() = Defaults.newAlarm(0, 0).copy(
        sourceType = SoundSourceType.FOLDER,
        folderUri = "content://docs/tree/1",
        folderName = "Будильники",
        folderFiles = listOf(
            FolderEntry(uri = "content://docs/document/1", name = "a.mp3"),
            FolderEntry(uri = "content://docs/document/2", name = "b.wav"),
        ),
        folderPlayMode = FolderPlayMode.SEQUENTIAL,
        lastFolderIndex = 1,
        volumeMode = VolumeMode.RANDOM_RANGE,
        volumeMin = 40,
        volumeMax = 90,
        enabled = true,
    )

    @Test
    fun folderAlarmEncodesAndDecodesWithoutLoss() {
        val original = folderAlarm()
        val json = alarmsJson.encodeToString(
            AlarmListDto.serializer(),
            AlarmListDto(listOf(original.toDto())),
        )
        assertTrue(json.contains("folderFiles"), "в JSON нет folderFiles: $json")
        assertTrue(json.contains("FOLDER"), "в JSON нет sourceType: $json")

        val decoded = alarmsJson.decodeFromString(AlarmListDto.serializer(), json)
            .alarms
            .first()
            .toAlarmConfig()

        assertEquals(original, decoded)
    }

    @Test
    fun groupsWithFolderAlarmRoundTrip() {
        val group = com.timersound.model.AlarmGroup(
            id = 0,
            name = "Основная",
            alarms = listOf(folderAlarm()),
        )
        val json = groupsJson.encodeToString(
            GroupsListDto.serializer(),
            GroupsListDto(listOf(group.toGroupDto()), nextGroupId = 1),
        )
        val decoded = groupsJson.decodeFromString(GroupsListDto.serializer(), json)
        assertEquals(group, decoded.groups.first().toAlarmGroup())
    }
}
