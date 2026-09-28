package com.timersound.ui

import android.app.Application
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.timersound.TimerViewModel
import com.timersound.data.PreferencesRepository
import com.timersound.model.AlarmConfig
import com.timersound.model.AlarmGroup
import com.timersound.model.AppConfig
import com.timersound.model.Defaults
import com.timersound.service.TimerStateHolder
import com.timersound.timer.TimerState
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI-тесты списка групп (устройство/эмулятор).
 * Сервис не запускается: проверяются структура, теги и блокировки правок.
 */
@RunWith(AndroidJUnit4::class)
class AppUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private companion object {
        var warmedUp = false
    }

    private lateinit var app: Application
    private lateinit var repo: PreferencesRepository

    @Before
    fun setUp() {
        TimerStateHolder.reset()
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        warmUpAppProcess()
        grantNotificationPermissionIfNeeded()
        repo = PreferencesRepository(app)
        runBlocking {
            repo.ensureMigrated()
            repo.setSessionActive(false)
            repo.setActiveGroups(emptySet())
        }
    }

    private fun grantNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant " + app.packageName + " android.permission.POST_NOTIFICATIONS")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        composeRule.waitForIdle()
    }

    private fun warmUpAppProcess() {
        if (warmedUp) return
        warmedUp = true
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = android.content.Intent(ctx, com.timersound.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { InstrumentationRegistry.getInstrumentation().startActivitySync(intent)?.finish() }
        composeRule.waitForIdle()
    }

    private fun seedApp(config: AppConfig) = runBlocking { repo.saveGroups(config) }

    private fun seed(vararg alarms: AlarmConfig) = seedApp(
        AppConfig(
            groups = listOf(
                AlarmGroup(id = 0, name = "Основная", alarms = alarms.toList(), enabled = true),
            ),
            nextGroupId = 1,
        ),
    )

    private fun showApp() {
        composeRule.setContent {
            val vm = remember { TimerViewModel(app) }
            TimerSoundTheme { App(vm = vm) }
        }
    }

    private fun scrollTo(tag: String) {
        composeRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag(tag))
    }

    private fun textOf(tag: String): String? =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun awaitText(tag: String, expected: String) {
        try {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching { scrollTo(tag) }.isSuccess && textOf(tag) == expected
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(
                "не дождались \"$expected\" для $tag: фактически \"${textOf(tag)}\"",
                timeout,
            )
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun groupListShowsSeededGroup() {
        seed(Defaults.newAlarm(0, 0))
        showApp()

        awaitText("group_name_0", "Основная")
        composeRule.onNodeWithTag("group_card_0").assertIsDisplayed()
    }

    @Test
    fun groupStartStopButtonsExist() {
        seed(Defaults.newAlarm(0, 0))
        showApp()

        awaitText("group_name_0", "Основная")
        scrollTo("group_start_0")
        composeRule.onNodeWithTag("group_start_0").assertIsDisplayed()
    }

    @Test
    fun runningGroupShowsStateBadge() {
        seed(Defaults.newAlarm(0, 0))
        TimerStateHolder.setGroup(
            TimerStateHolder.GroupRuntime(groupId = 0, state = TimerState.RUNNING),
        )
        showApp()

        awaitText("group_state_0", "Идёт")
    }

    @Test
    fun pauseAllStopAllVisibleWhenActive() {
        seed(Defaults.newAlarm(0, 0))
        TimerStateHolder.setGroup(
            TimerStateHolder.GroupRuntime(groupId = 0, state = TimerState.RUNNING),
        )
        showApp()

        scrollTo("btn_pause_all")
        composeRule.onNodeWithTag("btn_pause_all").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_stop_all").assertIsDisplayed()
    }

    @Test
    fun addGroupFabOpensPresetPicker() {
        seed(Defaults.newAlarm(0, 0))
        showApp()

        awaitText("group_name_0", "Основная")
        composeRule.onNodeWithTag("fab_add_group").performClick()
        composeRule.onNodeWithTag("preset_0").assertIsDisplayed()
    }
}
