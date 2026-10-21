package com.timersound

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.timersound.data.PreferencesRepository
import com.timersound.data.dataStore
import com.timersound.model.AlarmConfig
import com.timersound.model.AlarmGroup
import com.timersound.model.AppConfig
import com.timersound.model.Defaults
import com.timersound.model.FolderEntry
import com.timersound.model.FolderPlayMode
import com.timersound.model.SoundSourceType
import com.timersound.model.VolumeMode
import com.timersound.service.TimerStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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
 * Логика ViewModel для источника «папка» и режима громкости (диапазон).
 *
 * Настоящий SAF-пикер не проверяем: ему нужен документ-провайдер.
 * Здесь — только состояние модели: переключение источника, режим папки,
 * диапазон громкости, сброс папки и отображаемое имя.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderViewModelTest {

    private lateinit var viewModel: TimerViewModel

    private val groupId = 0
    private val alarmId = 0

    /** Uri одиночного файла и uri дерева-папки — как их отдаёт SAF. */
    private val fileUri = "content://com.android.providers.media.documents/document/audio1"
    private val folderUri = "content://com.android.externalstorage.documents/tree/folder1"

    /**
     * Версия схемы хранилища. 4 — добавлены источник-папка и режим громкости
     * (миграция v3→v4 только перекодирует JSON и ничего не теряет).
     */
    private val schemaVersion = 4

    @Before
    fun setup() {
        TimerStateHolder.reset()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Изоляция: DataStore-синглтон общий на процесс — сеем известную группу
        // с одним будильником (файл + папка) до создания VM.
        runBlocking {
            val repo = PreferencesRepository(app)
            repo.saveGroups(
                AppConfig(
                    groups = listOf(
                        AlarmGroup(
                            id = groupId,
                            name = "Основная",
                            alarms = listOf(seedAlarm()),
                            enabled = true,
                        ),
                    ),
                    nextGroupId = 1,
                ),
            )
            app.dataStore.edit {
                it[intPreferencesKey("schema_version")] = schemaVersion
            }
        }
        viewModel = TimerViewModel(app)
    }

    /**
     * Будильник с ОДНИМ файлом и ОДНОЙ настроенной папкой.
     *
     * Источник в сиде — FILE: при старте приложения папки пересканируются
     * (проверка доступа), а документ-провайдера в тесте нет — кэш файлов папки
     * был бы пустым. Переключение источника проверяется отдельно.
     */
    private fun seedAlarm(): AlarmConfig = Defaults.newAlarm(alarmId, 0).copy(
        sourceType = SoundSourceType.FILE,
        folderUri = folderUri,
        folderName = "X",
        folderFiles = listOf(
            FolderEntry(uri = "content://docs/document/1", name = "a.mp3"),
            FolderEntry(uri = "content://docs/document/2", name = "b.wav"),
        ),
        lastFolderIndex = 2,
        fileUri = fileUri,
        fileName = "audio1.mp3",
        enabled = true,
    )

    /** Группы грузятся из DataStore асинхронно в init — ждём перед действиями. */
    private suspend fun awaitGroups() {
        viewModel.app.first { it.groups.isNotEmpty() }
    }

    private suspend fun currentAlarm(): AlarmConfig =
        viewModel.app.first().groups.first { it.id == groupId }.alarms.first { it.id == alarmId }

    /** Настроенная папка должна была пережить round-trip через DataStore. */
    private fun assertSeededFolder(alarm: AlarmConfig) {
        assertEquals(folderUri, alarm.folderUri, "seed: uri папки не сохранился")
        assertEquals("X", alarm.folderName, "seed: имя папки не сохранилось")
        assertEquals(2, alarm.folderFiles.size, "seed: файлы папки не сохранились")
    }

    @After
    fun tearDown() {
        TimerStateHolder.reset()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ громкость: режим и диапазон

    @Test
    fun setVolumeModeRandomRangeStartsFromCurrentVolume() = runTest {
        awaitGroups()
        assertEquals(80, currentAlarm().volumePercent)

        viewModel.setVolumeMode(groupId, alarmId, VolumeMode.RANDOM_RANGE)

        val alarm = currentAlarm()
        assertEquals(VolumeMode.RANDOM_RANGE, alarm.volumeMode)
        // Диапазон стартует как фикс — пользователь расширит его сам.
        assertEquals(alarm.volumePercent, alarm.volumeMin)
        assertEquals(alarm.volumePercent, alarm.volumeMax)
        assertEquals(80, alarm.volumeMin)
        assertEquals(80, alarm.volumeMax)
    }

    @Test
    fun setVolumeRangeStoresBounds() = runTest {
        awaitGroups()

        viewModel.setVolumeRange(groupId, alarmId, 30, 90)

        val alarm = currentAlarm()
        assertEquals(30, alarm.volumeMin)
        assertEquals(90, alarm.volumeMax)
    }

    @Test
    fun setVolumeRangeSanitizesReversedBounds() = runTest {
        awaitGroups()

        viewModel.setVolumeRange(groupId, alarmId, 90, 30)

        val alarm = currentAlarm()
        assertTrue(alarm.volumeMin <= alarm.volumeMax, "min должен быть <= max")
        assertEquals(30, alarm.volumeMin)
        assertEquals(90, alarm.volumeMax)
    }

    @Test
    fun setVolumeModeFixedKeepsVolumePercent() = runTest {
        awaitGroups()
        viewModel.setVolume(groupId, alarmId, 55)

        viewModel.setVolumeMode(groupId, alarmId, VolumeMode.FIXED)

        val alarm = currentAlarm()
        assertEquals(VolumeMode.FIXED, alarm.volumeMode)
        assertEquals(55, alarm.volumePercent)
    }

    // ------------------------------------------------------------------ переключение источника

    @Test
    fun setSourceTypeKeepsFileAndFolderData() = runTest {
        awaitGroups()
        assertSeededFolder(currentAlarm())

        viewModel.setSourceType(groupId, alarmId, SoundSourceType.FOLDER)
        var alarm = currentAlarm()
        assertEquals(SoundSourceType.FOLDER, alarm.sourceType)
        // Одиночный файл при переключении не теряется.
        assertEquals(fileUri, alarm.fileUri)
        assertEquals(folderUri, alarm.folderUri)
        assertEquals(2, alarm.folderFiles.size)

        viewModel.setSourceType(groupId, alarmId, SoundSourceType.FILE)
        alarm = currentAlarm()
        assertEquals(SoundSourceType.FILE, alarm.sourceType)
        // Настроенная папка при переключении обратно тоже остаётся.
        assertEquals(fileUri, alarm.fileUri)
        assertEquals(folderUri, alarm.folderUri)
        assertEquals(2, alarm.folderFiles.size)
    }

    // ------------------------------------------------------------------ режим папки

    @Test
    fun setFolderPlayModeResetsLastFolderIndex() = runTest {
        awaitGroups()
        assertSeededFolder(currentAlarm())
        assertEquals(2, currentAlarm().lastFolderIndex, "seed: индекс последнего файла не сохранился")

        viewModel.setFolderPlayMode(groupId, alarmId, FolderPlayMode.SEQUENTIAL)

        val alarm = currentAlarm()
        assertEquals(FolderPlayMode.SEQUENTIAL, alarm.folderPlayMode)
        // Порядок начинается заново.
        assertEquals(-1, alarm.lastFolderIndex)
    }

    // ------------------------------------------------------------------ сброс папки

    @Test
    fun clearFolderClearsFolderState() = runTest {
        awaitGroups()
        assertSeededFolder(currentAlarm())

        viewModel.clearFolder(groupId, alarmId)

        val alarm = currentAlarm()
        assertEquals("", alarm.folderUri)
        assertNull(alarm.folderName)
        assertTrue(alarm.folderFiles.isEmpty())
        assertFalse(alarm.folderAccessLost)
        assertEquals(-1, alarm.lastFolderIndex)
        // Одиночный файл будильника не трогаем.
        assertEquals(fileUri, alarm.fileUri)
    }

    // ------------------------------------------------------------------ отображаемое имя

    @Test
    fun fileDisplayNameForFolderSourceShowsNameAndCount() = runTest {
        awaitGroups()
        viewModel.setSourceType(groupId, alarmId, SoundSourceType.FOLDER)

        assertEquals("Папка: X (2)", viewModel.fileDisplayName(currentAlarm()))
    }

    // ------------------------------------------------------------------ недоступная папка

    @Test
    fun onFolderPickedWithBrokenUriMarksAccessLost() = runTest {
        awaitGroups()

        // Не-дерево: сканирование падает — будильник помечается «доступ потерян».
        viewModel.onFolderPicked(groupId, alarmId, Uri.parse("content://com.example.broken/broken"))

        val alarm = currentAlarm()
        assertTrue(alarm.folderAccessLost, "доступ к папке должен быть помечен как потерянный")
        assertTrue(alarm.folderFiles.isEmpty())
        assertEquals(SoundSourceType.FOLDER, alarm.sourceType)
    }
}
