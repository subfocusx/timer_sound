package com.timersound

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.timersound.model.Defaults
import com.timersound.model.SceneMode
import com.timersound.service.TimerStateHolder
import com.timersound.timer.TimerState
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ViewModel layer tests (Этап 2 DoD).
 *
 * Требует Android runtime (Robolectric или instrumentation).
 * Добавить: `testImplementation("androidx.test:core:junit4")` и
 * `testImplementation("org.robolectric:robolectric:...")` для JVM-запуска.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimerViewModelTest {

    private lateinit var viewModel: TimerViewModel

    @Before
    fun setup() {
        TimerStateHolder.reset()
        val app = ApplicationProvider.getApplicationContext<Application>()
        viewModel = TimerViewModel(app)
    }

    /** Группы грузятся из DataStore асинхронно в init — ждём перед действиями. */
    private suspend fun awaitGroups() {
        viewModel.app.first { it.groups.isNotEmpty() }
    }

    @After
    fun tearDown() {
        TimerStateHolder.reset()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ add/delete id uniqueness

    @Test
    fun addAlarm_reservesUniqueMonotonicIds() = runTest {
        awaitGroups();
        viewModel.addAlarm()
        viewModel.addAlarm()
        viewModel.addAlarm()

        val config = viewModel.configMode.first()
        assertEquals(4, config.alarms.size)
        assertEquals(0, config.alarms[0].id)
        assertEquals(1, config.alarms[1].id)
        assertEquals(2, config.alarms[2].id)
        assertEquals(3, config.alarms[3].id)
    }

    @Test
    fun deleteAlarm_keepsRemainingIds() = runTest {
        awaitGroups();
        viewModel.addAlarm()
        viewModel.addAlarm()
        viewModel.addAlarm() // ids 0,1,2,3

        viewModel.deleteAlarm(1)

        val config = viewModel.configMode.first()
        assertEquals(3, config.alarms.size)
        assertEquals(0, config.alarms[0].id)
        assertEquals(2, config.alarms[1].id)
        assertEquals(3, config.alarms[2].id)
    }

    @Test
    fun deleteAllAlarms_clearsList() = runTest {
        awaitGroups();
        viewModel.addAlarm()
        viewModel.addAlarm()

        viewModel.deleteAllAlarms()

        val config = viewModel.configMode.first()
        assertTrue(config.alarms.isEmpty())
    }

    // ------------------------------------------------------------------ add limit

    @Test
    fun addAlarmRespectsMaxAlarmsLimit() = runTest {
        awaitGroups();
        repeat(Defaults.MAX_ALARMS) { viewModel.addAlarm() }
        var config = viewModel.configMode.first()
        assertEquals(Defaults.MAX_ALARMS, config.alarms.size)

        viewModel.addAlarm() // should be no-op
        config = viewModel.configMode.first()
        assertEquals(Defaults.MAX_ALARMS, config.alarms.size)
    }

    // ------------------------------------------------------------------ rename

    @Test
    fun renameAlarm_trimsAndClamps() = runTest {
        awaitGroups();
        viewModel.renameAlarm(0, "  Имя с пробелами  ")
        assertEquals("Имя с пробелами", viewModel.configMode.first().alarms[0].name)

        // Обрезка проверяется на имени, которое ПОСЛЕ trim длиннее лимита (60 > 40).
        viewModel.renameAlarm(0, "  " + "x".repeat(60) + "  ")
        val name = viewModel.configMode.first().alarms[0].name
        assertEquals(Defaults.MAX_NAME_LENGTH, name.length)
        assertEquals("x".repeat(Defaults.MAX_NAME_LENGTH), name)
    }

    @Test
    fun renameAlarm_emptyFallsBackToAutoName() = runTest {
        awaitGroups();
        viewModel.renameAlarm(0, "   ") // whitespace only
        val config = viewModel.configMode.first()
        assertEquals("Будильник 1", config.alarms[0].name)
    }

    // ------------------------------------------------------------------ edit lock

    @Test
    fun editsRejectedDuringRunningSession() = runTest {
        awaitGroups();
        val gid = viewModel.app.first().groups.first().id
        TimerStateHolder.setGroup(
            TimerStateHolder.GroupRuntime(groupId = gid, state = TimerState.RUNNING),
        )

        viewModel.addAlarm()
        viewModel.deleteAlarm(0)
        viewModel.renameAlarm(1, "Should Fail")
        viewModel.setEnabled(0, false)
        viewModel.setVolume(0, 50)
        viewModel.setMode(0, SceneMode.INTERVAL)

        val config = viewModel.configMode.first()
        assertEquals(1, config.alarms.size)
        assertTrue(config.alarms[0].enabled) // default first alarm unchanged
        assertEquals(SceneMode.REPEAT, config.alarms[0].mode)
    }

    @Test
    fun editsAllowedAfterSessionCompleted() = runTest {
        awaitGroups();
        val gid = viewModel.app.first().groups.first().id
        TimerStateHolder.setGroup(
            TimerStateHolder.GroupRuntime(groupId = gid, state = TimerState.RUNNING),
        )
        viewModel.addAlarm() // отклонено: правки запрещены при RUNNING
        assertEquals(1, viewModel.configMode.first().alarms.size)

        TimerStateHolder.setGroup(
            TimerStateHolder.GroupRuntime(groupId = gid, state = TimerState.COMPLETED),
        )

        viewModel.addAlarm() // разрешено: сессия завершена
        viewModel.renameAlarm(1, "Should Succeed")
        val config = viewModel.configMode.first()
        assertEquals("Should Succeed", config.alarms[1].name)
    }

    // ------------------------------------------------------------------ canEdit

    @Test
    fun canEditIsTrueInIdle() = runTest {
        awaitGroups();
        TimerStateHolder.set(TimerStateHolder.Ui(state = TimerState.IDLE))
        assertTrue(viewModel.canEdit.first())
    }

    @Test
    fun canEditIsFalseDuringSession() = runTest {
        awaitGroups();
        TimerStateHolder.set(TimerStateHolder.Ui(state = TimerState.RUNNING))
        assertFalse(viewModel.canEdit.first())
        TimerStateHolder.set(TimerStateHolder.Ui(state = TimerState.PAUSED))
        assertFalse(viewModel.canEdit.first())
    }

    @Test
    fun canEditIsTrueAfterCompletion() = runTest {
        awaitGroups();
        TimerStateHolder.set(TimerStateHolder.Ui(state = TimerState.COMPLETED))
        assertTrue(viewModel.canEdit.first())
    }

    // ------------------------------------------------------------------ file operations

    @Test
    fun removeFileMakesUriEmpty() = runTest {
        awaitGroups();
        viewModel.removeFile(0) // built-in beep → empty per spec (no special id==0 case)
        val config = viewModel.configMode.first()
        assertEquals("", config.alarms[0].fileUri)
        assertNull(config.alarms[0].fileName)
    }
}
