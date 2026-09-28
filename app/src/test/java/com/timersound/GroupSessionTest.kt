package com.timersound

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.timersound.model.AlarmGroup
import com.timersound.model.Defaults
import com.timersound.service.TimerStateHolder
import com.timersound.timer.TimerSession
import com.timersound.timer.TimerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Этап 6: копирование групп, изоляция сессий, гонки на уровне сессий. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupSessionTest {

    private lateinit var viewModel: TimerViewModel

    @Before
    fun setup() {
        TimerStateHolder.reset()
        val app = ApplicationProvider.getApplicationContext<Application>()
        viewModel = TimerViewModel(app)
    }

    @After
    fun tearDown() {
        TimerStateHolder.reset()
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private suspend fun awaitGroups() {
        viewModel.app.first { it.groups.isNotEmpty() }
    }

    @Test
    fun duplicateGroup_uniqueIdsAndCopySuffix() = runTest {
        awaitGroups()
        val gid = viewModel.app.first().groups.first().id
        viewModel.duplicateGroup(gid)
        val groups = viewModel.app.first().groups
        assertEquals(2, groups.size)
        val src = groups.first { it.id == gid }
        val copy = groups.first { it.id != gid }
        assertTrue(copy.name.endsWith("(копия)"))
        val srcIds = src.alarms.map { it.id }.toSet()
        val copyIds = copy.alarms.map { it.id }.toSet()
        assertTrue(srcIds.intersect(copyIds).isEmpty())
        assertEquals(src.alarms.size, copy.alarms.size)
    }

    @Test
    fun pauseOneGroup_doesNotTouchOther() {
        val a = TimerSession()
        val b = TimerSession()
        val cfgA = Defaults.firstRunConfig()
        val cfgB = Defaults.firstRunConfig()
        a.start(cfgA, nowElapsedMs = 0L, nowWallMs = 0L)
        b.start(cfgB, nowElapsedMs = 0L, nowWallMs = 0L)
        a.pause(5_000L)
        assertEquals(TimerState.PAUSED, a.state)
        assertEquals(TimerState.RUNNING, b.state)
        b.stop()
        assertEquals(TimerState.PAUSED, a.state)
    }

    @Test
    fun restartKeepsEpochGuard() {
        val s = TimerSession()
        val cfg = Defaults.firstRunConfig()
        s.start(cfg, nowElapsedMs = 0L, nowWallMs = 0L)
        val e1 = s.epoch
        s.stop()
        s.start(cfg, nowElapsedMs = 1_000L, nowWallMs = 1_000L)
        assertNotEquals(e1, s.epoch)
    }

    @Test
    fun createGroup_fromPresetHasBeep() = runTest {
        awaitGroups()
        viewModel.createGroup(1) // Подъём
        val groups = viewModel.app.first().groups
        val morning = groups.last()
        assertTrue(morning.alarms.isNotEmpty())
        assertTrue(morning.alarms.all { it.fileUri == Defaults.BUILT_IN_BEEP })
    }

    @Test
    fun setGroup_keepsLegacyMirror() = runTest {
        awaitGroups()
        val gid = viewModel.app.first().groups.first().id
        TimerStateHolder.setGroup(
            TimerStateHolder.GroupRuntime(groupId = gid, state = TimerState.RUNNING),
        )
        assertEquals(TimerState.RUNNING, TimerStateHolder.ui.first().state)
        assertEquals(TimerState.RUNNING, viewModel.groupRuntimes.first()[gid]?.state)
        assertTrue(viewModel.isLocked(gid))
    }
}
