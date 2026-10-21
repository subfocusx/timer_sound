package com.timersound.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.timersound.model.AlarmConfig
import com.timersound.model.AlarmGroup
import com.timersound.model.AppConfig
import com.timersound.model.FolderEntry
import com.timersound.model.FolderPlayMode
import com.timersound.model.SceneMode
import com.timersound.model.SoundSourceType
import com.timersound.model.VolumeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Миграция схемы v3 → v4: источник звука (файл/папка) и режим громкости (фикс/диапазон).
 *
 * v4 ничего не перестраивает: новые поля будильника появляются с дефолтами
 * (FILE / FIXED), старый JSON обязан читаться как есть, а данные (группы,
 * будильники, имена, uri) не должны потеряться ни при миграции, ни при
 * точечном патче [PreferencesRepository.patchFolderState].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchemaV4MigrationTest {

    private lateinit var context: Context
    private lateinit var repo: PreferencesRepository

    private val schemaVersionKey = intPreferencesKey("schema_version")
    private val groupsJsonKey = stringPreferencesKey("groups_json")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repo = PreferencesRepository(context)
    }

    // ------------------------------------------------------------------ v3 -> v4

    @Test
    fun v3toV4_setsVersionToFourAndKeepsGroupsAndAlarms() = runTest {
        context.dataStore.edit {
            it.clear()
            it[schemaVersionKey] = 3
            it[groupsJsonKey] = oldV3GroupsJson
        }

        repo.ensureMigrated()

        assertEquals(4, context.dataStore.data.first()[schemaVersionKey])

        val cfg = repo.appConfig.first()
        assertEquals(2, cfg.groups.size)
        assertEquals(2, cfg.nextGroupId)

        val main = cfg.groups[0]
        assertEquals(0, main.id)
        assertEquals("Основная", main.name)
        assertTrue(main.enabled)
        assertEquals(0, main.weekdays)
        assertEquals(60_000L, main.autoStopMs)
        assertEquals(5, main.maxTotalFiresPerSession)
        assertEquals(1, main.alarms.size)
        assertEquals(0, main.alarms[0].id)
        assertEquals("Будильник 1", main.alarms[0].name)
        assertEquals("@beep", main.alarms[0].fileUri)
        assertTrue(main.alarms[0].enabled)

        val second = cfg.groups[1]
        assertEquals(1, second.id)
        assertEquals("Вторая", second.name)
        assertFalse(second.enabled)
        assertEquals(62, second.weekdays)
        assertEquals(1, second.alarms.size)
        assertEquals(3, second.alarms[0].id)
        assertEquals("Файл", second.alarms[0].name)
        assertEquals("content://media/1.mp3", second.alarms[0].fileUri)
        assertEquals("1.mp3", second.alarms[0].fileName)
        assertEquals(55, second.alarms[0].volumePercent)
        assertFalse(second.alarms[0].enabled)
    }

    @Test
    fun v3toV4_persistsNewFieldsWithDefaults() = runTest {
        context.dataStore.edit {
            it.clear()
            it[schemaVersionKey] = 3
            it[groupsJsonKey] = oldV3GroupsJson
        }

        repo.ensureMigrated()

        // Новые поля записаны в JSON — при следующем чтении дефолты не «откатываются».
        val raw = context.dataStore.data.first()[groupsJsonKey].orEmpty()
        assertTrue(raw.contains("\"sourceType\""), "в groups_json нет sourceType: $raw")
        assertTrue(raw.contains("\"folderFiles\""), "в groups_json нет folderFiles: $raw")
        assertTrue(raw.contains("\"volumeMode\""), "в groups_json нет volumeMode: $raw")

        val alarm = repo.appConfig.first().groups[0].alarms[0]
        assertEquals(SoundSourceType.FILE, alarm.sourceType)
        assertEquals(VolumeMode.FIXED, alarm.volumeMode)
        assertEquals(emptyList<FolderEntry>(), alarm.folderFiles)
        assertEquals(-1, alarm.lastFolderIndex)
        assertFalse(alarm.folderAccessLost)
    }

    @Test
    fun v3toV4_isIdempotent() = runTest {
        context.dataStore.edit {
            it.clear()
            it[schemaVersionKey] = 3
            it[groupsJsonKey] = oldV3GroupsJson
        }

        repo.ensureMigrated()
        val afterFirst = repo.appConfig.first()
        val rawAfterFirst = context.dataStore.data.first()[groupsJsonKey]

        repo.ensureMigrated()

        assertEquals(4, context.dataStore.data.first()[schemaVersionKey])
        assertEquals(afterFirst, repo.appConfig.first())
        assertEquals(rawAfterFirst, context.dataStore.data.first()[groupsJsonKey])
    }

    @Test
    fun v3toV4_brokenGroupsJsonKeepsDataAndBumpsVersion() = runTest {
        context.dataStore.edit {
            it.clear()
            it[schemaVersionKey] = 3
            it[groupsJsonKey] = "{broken"
        }

        repo.ensureMigrated()

        // Версия поднята, но битые данные НЕ удалены и не заменены дефолтами.
        assertEquals(4, context.dataStore.data.first()[schemaVersionKey])
        assertEquals("{broken", context.dataStore.data.first()[groupsJsonKey])
    }

    @Test
    fun v3toV4_missingGroupsJsonKeepsDataAndBumpsVersion() = runTest {
        context.dataStore.edit {
            it.clear()
            it[schemaVersionKey] = 3
        }

        repo.ensureMigrated()

        assertEquals(4, context.dataStore.data.first()[schemaVersionKey])
        assertNull(context.dataStore.data.first()[groupsJsonKey])
    }

    // ------------------------------------------------------------------ старый JSON без новых полей

    @Test
    fun oldJsonWithoutNewFields_decodesWithDefaults() {
        val dto = groupsJson.decodeFromString(GroupsListDto.serializer(), oldV3GroupsJson)

        val alarm = dto.groups[0].alarms[0]
        assertEquals(SoundSourceType.FILE, alarm.sourceType)
        assertEquals(VolumeMode.FIXED, alarm.volumeMode)
        assertEquals(emptyList<FolderEntry>(), alarm.folderFiles)
        assertEquals(-1, alarm.lastFolderIndex)
        assertFalse(alarm.folderAccessLost)
        assertEquals("", alarm.folderUri)
        assertNull(alarm.folderName)
        assertEquals(FolderPlayMode.RANDOM_NOREPEAT, alarm.folderPlayMode)
        assertEquals(0, alarm.volumeMin)
        assertEquals(100, alarm.volumeMax)

        // То же самое после конвертации в доменную модель.
        val config = dto.groups[0].alarms[0].toAlarmConfig()
        assertEquals(SoundSourceType.FILE, config.sourceType)
        assertEquals(VolumeMode.FIXED, config.volumeMode)
        assertEquals(emptyList<FolderEntry>(), config.folderFiles)
        assertEquals(-1, config.lastFolderIndex)
        assertFalse(config.folderAccessLost)
    }

    // ------------------------------------------------------------------ round-trip

    @Test
    fun folderAlarm_survivesSaveAndRead() = runTest {
        val alarm = folderAlarm()
        repo.saveGroups(
            AppConfig(
                groups = listOf(AlarmGroup(id = 3, name = "Папки", alarms = listOf(alarm))),
                nextGroupId = 4,
            ),
        )

        val read = repo.appConfig.first().groups.single().alarms.single()
        assertEquals(alarm, read)
        assertEquals(SoundSourceType.FOLDER, read.sourceType)
        assertEquals(2, read.folderFiles.size)
        assertEquals(FolderPlayMode.RANDOM_NOREPEAT, read.folderPlayMode)
        assertEquals(VolumeMode.RANDOM_RANGE, read.volumeMode)
        assertEquals(20, read.volumeMin)
        assertEquals(90, read.volumeMax)
        assertEquals(1, read.lastFolderIndex)
    }

    // ------------------------------------------------------------------ patchFolderState

    @Test
    fun patchFolderState_changesOnlyFolderStateFields() = runTest {
        val alarm = folderAlarm()
        repo.saveGroups(
            AppConfig(
                groups = listOf(AlarmGroup(id = 3, name = "Папки", alarms = listOf(alarm))),
                nextGroupId = 4,
            ),
        )

        repo.patchFolderState(
            groupId = 3,
            patches = listOf(FolderStatePatch(alarmId = alarm.id, lastFolderIndex = 0, folderAccessLost = true)),
        )

        val patched = repo.appConfig.first().groups.single().alarms.single()
        // Изменились только состояние папки...
        assertEquals(0, patched.lastFolderIndex)
        assertTrue(patched.folderAccessLost)
        // ...остальное не тронуто.
        assertEquals(alarm.name, patched.name)
        assertEquals(alarm.volumePercent, patched.volumePercent)
        assertEquals(alarm.fileUri, patched.fileUri)
        assertEquals(alarm.fileName, patched.fileName)
        assertEquals(alarm.mode, patched.mode)
        assertEquals(alarm.intervalMs, patched.intervalMs)
        assertEquals(alarm.sourceType, patched.sourceType)
        assertEquals(alarm.folderUri, patched.folderUri)
        assertEquals(alarm.folderName, patched.folderName)
        assertEquals(alarm.folderFiles, patched.folderFiles)
        assertEquals(alarm.folderPlayMode, patched.folderPlayMode)
        assertEquals(alarm.volumeMode, patched.volumeMode)
        assertEquals(alarm.volumeMin, patched.volumeMin)
        assertEquals(alarm.volumeMax, patched.volumeMax)
        assertEquals(alarm.enabled, patched.enabled)
    }

    @Test
    fun patchFolderState_updatesFolderFilesOnlyForTargetAlarm() = runTest {
        val first = folderAlarm()
        val second = folderAlarm().copy(id = 8, name = "Второй")
        repo.saveGroups(
            AppConfig(
                groups = listOf(AlarmGroup(id = 3, name = "Папки", alarms = listOf(first, second))),
                nextGroupId = 4,
            ),
        )

        val newFiles = listOf(FolderEntry("content://media/new.mp3", "new.mp3"))
        repo.patchFolderState(
            groupId = 3,
            patches = listOf(FolderStatePatch(alarmId = first.id, folderFiles = newFiles)),
        )

        val alarms = repo.appConfig.first().groups.single().alarms
        assertEquals(newFiles, alarms[0].folderFiles)
        assertEquals(first.folderUri, alarms[0].folderUri)
        // Соседний будильник не задет.
        assertEquals(second, alarms[1])
    }

    @Test
    fun patchFolderState_doesNotTouchOtherGroups() = runTest {
        val target = folderAlarm()
        val otherGroupAlarm = folderAlarm().copy(id = 9, name = "Чужая группа")
        repo.saveGroups(
            AppConfig(
                groups = listOf(
                    AlarmGroup(id = 3, name = "Папки", alarms = listOf(target)),
                    AlarmGroup(id = 4, name = "Другая", alarms = listOf(otherGroupAlarm)),
                ),
                nextGroupId = 5,
            ),
        )

        repo.patchFolderState(
            groupId = 3,
            patches = listOf(FolderStatePatch(alarmId = target.id, lastFolderIndex = 2)),
        )

        val groups = repo.appConfig.first().groups
        assertEquals(2, groups[0].alarms.single().lastFolderIndex)
        assertEquals(otherGroupAlarm, groups[1].alarms.single())
    }

    @Test
    fun patchFolderState_unknownGroupId_doesNotThrowAndKeepsData() = runTest {
        val alarm = folderAlarm()
        repo.saveGroups(
            AppConfig(
                groups = listOf(AlarmGroup(id = 3, name = "Папки", alarms = listOf(alarm))),
                nextGroupId = 4,
            ),
        )
        val before = repo.appConfig.first()

        repo.patchFolderState(
            groupId = 999,
            patches = listOf(FolderStatePatch(alarmId = alarm.id, lastFolderIndex = 5)),
        )

        assertEquals(before, repo.appConfig.first())
    }

    @Test
    fun patchFolderState_unknownAlarmId_doesNotThrowAndKeepsData() = runTest {
        val alarm = folderAlarm()
        repo.saveGroups(
            AppConfig(
                groups = listOf(AlarmGroup(id = 3, name = "Папки", alarms = listOf(alarm))),
                nextGroupId = 4,
            ),
        )
        val before = repo.appConfig.first()

        repo.patchFolderState(
            groupId = 3,
            patches = listOf(FolderStatePatch(alarmId = 12345, lastFolderIndex = 5)),
        )

        assertEquals(before, repo.appConfig.first())
    }

    @Test
    fun patchFolderState_emptyPatchList_isNoOp() = runTest {
        val alarm = folderAlarm()
        repo.saveGroups(
            AppConfig(
                groups = listOf(AlarmGroup(id = 3, name = "Папки", alarms = listOf(alarm))),
                nextGroupId = 4,
            ),
        )
        val before = repo.appConfig.first()
        val rawBefore = context.dataStore.data.first()[groupsJsonKey]

        repo.patchFolderState(groupId = 3, patches = emptyList())

        assertEquals(before, repo.appConfig.first())
        assertEquals(rawBefore, context.dataStore.data.first()[groupsJsonKey])
    }

    @Test
    fun patchFolderState_sameValues_isNoOp() = runTest {
        val alarm = folderAlarm()
        repo.saveGroups(
            AppConfig(
                groups = listOf(AlarmGroup(id = 3, name = "Папки", alarms = listOf(alarm))),
                nextGroupId = 4,
            ),
        )
        val rawBefore = context.dataStore.data.first()[groupsJsonKey]

        repo.patchFolderState(
            groupId = 3,
            patches = listOf(
                FolderStatePatch(
                    alarmId = alarm.id,
                    lastFolderIndex = alarm.lastFolderIndex,
                    folderAccessLost = alarm.folderAccessLost,
                    folderFiles = alarm.folderFiles,
                ),
            ),
        )

        assertEquals(rawBefore, context.dataStore.data.first()[groupsJsonKey])
    }

    // ------------------------------------------------------------------ helpers

    /** Будильник-папка: два файла, режим «случайно», громкость в диапазоне. */
    private fun folderAlarm(): AlarmConfig = AlarmConfig(
        id = 7,
        name = "Папка",
        fileUri = "",
        mode = SceneMode.REPEAT,
        intervalMs = 60_000L,
        volumePercent = 70,
        enabled = true,
        sourceType = SoundSourceType.FOLDER,
        folderUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic",
        folderName = "Music",
        folderFiles = listOf(
            FolderEntry("content://media/external/audio/media/1.mp3", "one.mp3"),
            FolderEntry("content://media/external/audio/media/2.mp3", "two.mp3"),
        ),
        folderPlayMode = FolderPlayMode.RANDOM_NOREPEAT,
        lastFolderIndex = 1,
        folderAccessLost = false,
        volumeMode = VolumeMode.RANDOM_RANGE,
        volumeMin = 20,
        volumeMax = 90,
    )

    /** JSON схемы v3: групп две, полей папки и режима громкости нет. */
    private val oldV3GroupsJson = """
        {
          "groups": [
            {
              "id": 0,
              "name": "Основная",
              "alarms": [
                {
                  "id": 0,
                  "name": "Будильник 1",
                  "fileUri": "@beep",
                  "mode": "REPEAT",
                  "intervalMs": 300000,
                  "volumePercent": 80,
                  "enabled": true
                }
              ],
              "enabled": true,
              "weekdays": 0,
              "autoStopMs": 60000,
              "maxTotalFires": 5
            },
            {
              "id": 1,
              "name": "Вторая",
              "alarms": [
                {
                  "id": 3,
                  "name": "Файл",
                  "fileUri": "content://media/1.mp3",
                  "fileName": "1.mp3",
                  "mode": "REPEAT",
                  "intervalMs": 1000,
                  "volumePercent": 55,
                  "enabled": false
                }
              ],
              "enabled": false,
              "weekdays": 62,
              "autoStopMs": 0,
              "maxTotalFires": 0
            }
          ],
          "nextGroupId": 2
        }
    """.trimIndent()
}
