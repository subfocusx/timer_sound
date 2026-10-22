package com.timersound.ui

import android.app.Application
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
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
 *
 * Три особенности дерева семантики Compose, из-за которых тесты написаны так:
 *
 * 1. Кликабельная `Card` (карточка группы) помечает узел
 *    `MergeDescendants = true`, поэтому вложенные в неё `Text` (название
 *    группы, бейдж состояния) в merged-дереве НЕ существуют — их теги
 *    поглощаются слиянием. Поиск узлов и чтение текста идут по unmerged-дереву.
 * 2. У `Text` с `Modifier.clickable` текст лежит в ПОТОМКЕ, а не в самом узле
 *    с тегом, — поэтому текст берём обходом поддерева.
 * 3. `performClick` по карточке кликает в её ЦЕНТР, а там чип дня недели:
 *    переключался день вместо открытия группы. Поэтому кликаем касанием по
 *    конкретному вложенному узлу — событие всплывает до кликабельной карточки.
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

    // ------------------------------------------------------------------ поиск узлов

    /** Узлы с тегом в unmerged-дереве: там есть и поглощённые слиянием. */
    private fun nodes(tag: String): List<SemanticsNode> =
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()

    /** Текст узла с тегом: свой + текст потомков (у кликабельного Text текст в потомке). */
    private fun textOf(tag: String): String? {
        val node = nodes(tag).firstOrNull() ?: return null
        val sb = StringBuilder()
        fun collect(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { sb.append(it.text) }
            n.children.forEach { collect(it) }
        }
        collect(node)
        return sb.toString().ifEmpty { null }
    }

    // ------------------------------------------------------------------ скролл

    /** Вертикальный скролл-контейнер (LazyColumn), а не горизонтальные ряды чипов. */
    private fun verticalScrollable() =
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).onFirst()

    /**
     * Прокручивает список так, чтобы узел с тегом стал виден.
     *
     * Для узла merged-дерева — штатный `performScrollToNode`. Для узла,
     * поглощённого слиянием (`Card(onClick)`), скроллим к ближайшему
     * предку с тегом (сама карточка).
     */
    private fun scrollTo(tag: String) {
        if (composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) {
            runCatching { verticalScrollable().performScrollToNode(hasTestTag(tag)) }
            return
        }
        val anchor = nearestTaggedAncestor(tag)
        if (anchor != null) {
            runCatching { verticalScrollable().performScrollToNode(hasTestTag(anchor)) }
            composeRule.waitForIdle()
        }
    }

    /** Ближайший предок узла с тегом (карточка-родитель поглощённого узла). */
    private fun nearestTaggedAncestor(tag: String): String? {
        var parent = nodes(tag).firstOrNull()?.parent
        while (parent != null) {
            val parentTag = parent.config.getOrNull(SemanticsProperties.TestTag)
            if (parentTag != null) return parentTag
            parent = parent.parent
        }
        return null
    }

    private fun awaitText(tag: String, expected: String) {
        try {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching { scrollTo(tag) }
                textOf(tag) == expected
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(
                "не дождались \"$expected\" для $tag: фактически \"${textOf(tag)}\"",
                timeout,
            )
        }
    }

    /** Касание по узлу с тегом (событие всплывает до кликабельного родителя). */
    private fun click(tag: String) {
        scrollTo(tag)
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst()
            .performTouchInput { click() }
        composeRule.waitForIdle()
    }

    private fun assertDisplayed(tag: String) {
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst().assertIsDisplayed()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun groupListShowsSeededGroup() {
        seed(Defaults.newAlarm(0, 0))
        showApp()

        awaitText("group_name_0", "Основная")
        assertDisplayed("group_card_0")
    }

    @Test
    fun groupStartStopButtonsExist() {
        seed(Defaults.newAlarm(0, 0))
        showApp()

        awaitText("group_name_0", "Основная")
        scrollTo("group_start_0")
        assertDisplayed("group_start_0")
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
        assertDisplayed("btn_pause_all")
        assertDisplayed("btn_stop_all")
    }

    @Test
    fun addGroupFabOpensPresetPicker() {
        seed(Defaults.newAlarm(0, 0))
        showApp()

        awaitText("group_name_0", "Основная")
        click("fab_add_group")
        assertDisplayed("preset_0")
    }

    /**
     * Ряд действий источника звука: все четыре кнопки должны быть видимы.
     *
     * Раньше это были подписи-кнопки, и четвёртая («Удалить») не влезала в ряд —
     * она обрезалась и становилась недоступной. Теперь это иконки, но регресс
     * всё равно возможен, поэтому проверяем явно.
     */
    @Test
    fun alarmSourceActionsAllVisible() {
        seed(Defaults.newAlarm(0, 0))
        showApp()

        awaitText("group_name_0", "Основная")
        click("group_name_0")
        awaitText("alarm_name_0", "Будильник 1")
        click("alarm_expand_0")

        scrollTo("alarm_file_pick_0")
        assertDisplayed("alarm_file_pick_0")
        assertDisplayed("alarm_folder_pick_0")
        assertDisplayed("alarm_beep_0")
        assertDisplayed("alarm_remove_0")
    }
}
