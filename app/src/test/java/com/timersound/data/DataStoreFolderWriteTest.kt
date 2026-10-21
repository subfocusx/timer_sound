package com.timersound.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.timersound.model.AlarmConfig
import com.timersound.model.AlarmGroup
import com.timersound.model.AppConfig
import com.timersound.model.Defaults
import com.timersound.model.FolderEntry
import com.timersound.model.SoundSourceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Диагностика: две последовательные записи в DataStore с данными папки.
 *
 * Если запись папки роняет актор DataStore, вторая запись «зависает» (ack
 * не завершается) — здесь это ловится по таймауту, а не hangs на 15 минут.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataStoreFolderWriteTest {

    private lateinit var context: Context
    private lateinit var repo: PreferencesRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repo = PreferencesRepository(context)
    }

    private fun folderAlarm(id: Int = 0) = Defaults.newAlarm(id, id).copy(
        sourceType = SoundSourceType.FOLDER,
        folderUri = "content://docs/tree/$id",
        folderName = "Папка",
        folderFiles = listOf(FolderEntry("content://docs/document/1", "a.mp3")),
        enabled = true,
    )

    private fun config(alarms: List<AlarmConfig>) = AppConfig(
        groups = listOf(AlarmGroup(id = 0, name = "Основная", alarms = alarms)),
        nextGroupId = 1,
    )

    @Test(timeout = 60_000)
    fun twoConsecutiveWritesWithFolderDataSucceed() = runBlocking {
        repo.saveGroups(config(listOf(folderAlarm(0))))
        withTimeout(20_000) { repo.saveGroups(config(listOf(folderAlarm(0), folderAlarm(1)))) }
        val cfg = repo.appConfig.first()
        assertEquals(2, cfg.groups.first().alarms.size)
        assertTrue(cfg.groups.first().alarms.all { it.sourceType == SoundSourceType.FOLDER })
    }
}
