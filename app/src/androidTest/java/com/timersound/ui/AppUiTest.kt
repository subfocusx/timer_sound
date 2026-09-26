package com.timersound.ui

import android.app.Application
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.timersound.TimerViewModel
import com.timersound.data.PreferencesRepository
import com.timersound.model.AlarmConfig
import com.timersound.model.Defaults
import com.timersound.model.SceneMode
import com.timersound.model.TimerConfig
import com.timersound.service.TimerStateHolder
import com.timersound.timer.TimerState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI-тесты главного экрана (устройство/эмулятор).
 *
 * Состояние задаётся детерминированно: хранилище чистится в [setUp], затем каждый тест
 * пишет свой конфиг через [PreferencesRepository.save], состояние сессии — через
 * [TimerStateHolder]. Сервис не запускается: кнопка «Старт» проверяется только на
 * доступность (клик поднял бы реальную foreground-сессию со звуком).
 *
 * Покрывает регресс на дефекты: недостижимый диалог «Удалить все», активную кнопку
 * «Старт» без файла и недостижимую подсказку о причине.
 */
@RunWith(AndroidJUnit4::class)
class AppUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private companion object {
        /** Прогрев делается один раз на класс, а не перед каждым тестом. */
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
            // Приводим хранилище к состоянию «первый запуск» через публичный API.
            // Удалять файл нельзя: живой instance DataStore после этого читает пустое
            // состояние, и последующие save() перестают быть видимыми.
            repo.ensureMigrated()
            repo.setSessionActive(false)
        }
    }

    /**
     * На API 33+ App() при первой же композиции показывает системный диалог запроса
     * POST_NOTIFICATIONS. Диалог перекрывает тестовую Activity (RESUMED → PAUSED → DESTROYED),
     * и ComposeTestRule падает с «No compose hierarchies found». Выдаём разрешение заранее —
     * тот же эффект, что нажатие «Разрешить» пользователем; сама логика запроса не меняется.
     */
    private fun grantNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant " + app.packageName + " android.permission.POST_NOTIFICATIONS")
        // Читаем поток до конца: так команда гарантированно завершится до старта теста.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        composeRule.waitForIdle()
    }


    /**
     * Прогрев процесса приложения. После свежей установки (холодный старт, проверка dex/JIT)
     * первые тесты прогона падали с «No compose hierarchies found»: тестовая Activity не успевала
     * запуститься и вызвать setContent. Один запуск MainActivity перед тестами снимает флейк.
     */
    private fun warmUpAppProcess() {
        if (warmedUp) return
        warmedUp = true
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = android.content.Intent(ctx, com.timersound.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { InstrumentationRegistry.getInstrumentation().startActivitySync(intent)?.finish() }
        composeRule.waitForIdle()
    }
    // ------------------------------------------------------------------ helpers

    private fun seed(config: TimerConfig) = runBlocking { repo.save(config) }

    private fun seed(vararg alarms: AlarmConfig) =
        seed(TimerConfig(alarms = alarms.toList(), autoStopMs = 0L))

    private fun showApp() {
        composeRule.setContent {
            // remember: без него новый ViewModel создавался на каждой рекомпозиции.
            val vm = remember { TimerViewModel(app) }
            TimerSoundTheme { App(vm = vm) }
        }
    }

    /** LazyColumn не композит элементы вне экрана — прокручиваем к нужному тегу. */
    private fun scrollTo(tag: String) {
        composeRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag(tag))
    }

    private fun textOf(tag: String): String? =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    /** Ждёт, пока тег существует и его текст совпадёт (текст счётчика = конфиг загружен). */
    private fun awaitText(tag: String, expected: String) {
        try {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching { scrollTo(tag) }.isSuccess && textOf(tag) == expected
            }
        } catch (timeout: ComposeTimeoutException) {
            val scrolled = runCatching { scrollTo(tag) }.isSuccess
            throw AssertionError(
                "не дождались \"$expected\" для $tag: фактически \"${textOf(tag)}\"" +
                    " (scrollTo=$scrolled, узлов=${composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().size})",
                timeout,
            )
        }
    }

    private fun awaitBadge(expected: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) { textOf("status_badge") == expected }
    }

    private fun assertDisplayedAndScrolled(tag: String) {
        scrollTo(tag)
        composeRule.onNodeWithTag(tag).assertIsDisplayed()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun emptyStateThenAddAlarm() {
        seed(TimerConfig(alarms = emptyList(), autoStopMs = 0L))
        showApp()

        awaitText("alarm_count", "Будильники: 0/100")
        assertDisplayedAndScrolled("empty_state")

        composeRule.onNodeWithTag("fab_add").performClick()

        awaitText("alarm_count", "Будильники: 1/100")
        assertDisplayedAndScrolled("alarm_card_0")
    }

    @Test
    fun freshInstallDefaultsAreStartable() {
        // Ровно то, что даёт миграция пустых префов: 5 каналов, id 0 — включённый бип, остальные пустые.
        seed(TimerConfig(alarms = (0 until 5).map { Defaults.newAlarm(it, it) }, autoStopMs = 0L))
        showApp()

        // Контракт первого запуска: 5 каналов (id 0 — включённый встроенный бип, остальные пустые).
        awaitText("alarm_count", "Будильники: 5/100")
        scrollTo("btn_start")
        composeRule.onNodeWithTag("btn_start").assertIsEnabled()
        composeRule.onNodeWithTag("status_hint").assertDoesNotExist()
    }

    @Test
    fun startDisabledAndHintShownWhenAlarmHasNoFile() {
        seed(Defaults.newAlarm(0, 0).copy(name = "Без файла", fileUri = ""))
        showApp()

        awaitText("alarm_count", "Будильники: 1/100")
        scrollTo("btn_start")
        composeRule.onNodeWithTag("btn_start").assertIsNotEnabled()
        composeRule.onNodeWithTag("status_hint")
            .assertTextEquals("Для старта укажите файл: Без файла")
    }

    @Test
    fun startDisabledAndHintShownWhenScheduleInvalid() {
        val broken = Defaults.newAlarm(0, 0).copy(
            name = "Сломанный",
            mode = SceneMode.ONCE_TIME,
            startMinutes = null,
        )
        seed(broken)
        showApp()

        awaitText("alarm_count", "Будильники: 1/100")
        scrollTo("btn_start")
        composeRule.onNodeWithTag("btn_start").assertIsNotEnabled()
        composeRule.onNodeWithTag("status_hint")
            .assertTextEquals("Исправьте расписание: Сломанный (проверьте режим, время и количество).")
        assertDisplayedAndScrolled("alarm_invalid_0")
    }

    @Test
    fun alarmLimitPreventsAddingMore() {
        val full = (0 until Defaults.MAX_ALARMS).map { Defaults.newAlarm(it, it) }
        seed(TimerConfig(alarms = full, autoStopMs = 0L))
        showApp()

        awaitText("alarm_count", "Будильники: 100/100")

        composeRule.onNodeWithTag("fab_add").performClick()

        composeRule.onNodeWithTag("alarm_count").assertTextEquals("Будильники: 100/100")
    }

    @Test
    fun runningSessionLocksEditing() {
        seed(
            Defaults.newAlarm(0, 0),
            Defaults.newAlarm(1, 1),
        )
        TimerStateHolder.set(
            TimerStateHolder.Ui(state = TimerState.RUNNING, nextSound = "Будильник 1", autoStop = "00:01:00"),
        )
        showApp()

        awaitBadge("Идёт")
        awaitText("alarm_count", "Будильники: 2/100")

        assertDisplayedAndScrolled("lock_banner")
        assertDisplayedAndScrolled("btn_pause")
        composeRule.onNodeWithTag("btn_start").assertDoesNotExist()
        composeRule.onNodeWithTag("delete_all").assertDoesNotExist()
        composeRule.onNodeWithTag("alarm_delete_0").assertDoesNotExist()

        // Раскрытие карточки во время сессии не даёт контролов правки.
        scrollTo("alarm_expand_0")
        composeRule.onNodeWithTag("alarm_expand_0").performClick()
        composeRule.onNodeWithTag("alarm_expanded_0").assertDoesNotExist()
    }

    @Test
    fun completedStateOffersRestartAndReset() {
        seed(Defaults.newAlarm(0, 0))
        showApp()
        awaitText("alarm_count", "Будильники: 1/100")

        TimerStateHolder.set(TimerStateHolder.Ui(state = TimerState.COMPLETED))

        awaitBadge("Завершено")
        scrollTo("btn_reset")
        composeRule.onNodeWithTag("btn_reset").assertIsDisplayed()
        composeRule.onNodeWithText("Запустить заново").assertIsDisplayed()
    }

    /** Регресс: диалог «Удалить все» раньше был недостижим из UI. */
    @Test
    fun deleteAllFlowEmptiesTheList() {
        seed(
            Defaults.newAlarm(0, 0),
            Defaults.newAlarm(1, 1),
        )
        showApp()
        awaitText("alarm_count", "Будильники: 2/100")

        scrollTo("delete_all")
        composeRule.onNodeWithTag("delete_all").performClick()
        composeRule.onNodeWithText("Удалить все будильники?").assertIsDisplayed()

        composeRule.onNodeWithTag("confirm_delete_all").performClick()

        awaitText("alarm_count", "Будильники: 0/100")
        assertDisplayedAndScrolled("empty_state")
    }

    @Test
    fun deleteSingleAlarmAsksForConfirmation() {
        seed(
            Defaults.newAlarm(0, 0),
            Defaults.newAlarm(1, 1),
        )
        showApp()
        awaitText("alarm_count", "Будильники: 2/100")

        scrollTo("alarm_delete_0")
        composeRule.onNodeWithTag("alarm_delete_0").performClick()
        composeRule.onNodeWithText("Удалить будильник?").assertIsDisplayed()

        // Отмена ничего не удаляет.
        composeRule.onNodeWithTag("dismiss_delete_one").performClick()
        composeRule.onNodeWithTag("alarm_card_0").assertIsDisplayed()

        // Подтверждение удаляет.
        composeRule.onNodeWithTag("alarm_delete_0").performClick()
        composeRule.onNodeWithTag("confirm_delete_one").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("alarm_card_0").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("alarm_count").assertTextEquals("Будильники: 1/100")
    }

    @Test
    fun overlapWarningShownForSameFileAndSameFireTime() {
        val first = Defaults.newAlarm(0, 0).copy(
            name = "A",
            fileUri = "content://media/a.mp3",
            mode = SceneMode.REPEAT,
            intervalMs = 60_000L,
        )
        val second = Defaults.newAlarm(1, 1).copy(
            name = "B",
            fileUri = "content://media/a.mp3",
            mode = SceneMode.REPEAT,
            intervalMs = 60_000L,
            enabled = true, // по умолчанию включён только id 0
        )
        seed(first, second)
        showApp()

        awaitText("alarm_count", "Будильники: 2/100")
        assertDisplayedAndScrolled("alarm_overlap_0")
        scrollTo("alarm_overlap_1")
        composeRule.onNodeWithTag("alarm_overlap_1").assertExists()
    }

    @Test
    fun cardExpandsAndCollapses() {
        seed(Defaults.newAlarm(0, 0))
        showApp()
        awaitText("alarm_count", "Будильники: 1/100")

        scrollTo("alarm_expand_0")
        composeRule.onNodeWithTag("alarm_expand_0").performClick()
        composeRule.onNodeWithTag("alarm_expanded_0").assertIsDisplayed()

        composeRule.onNodeWithTag("alarm_expand_0").performClick()
        composeRule.onNodeWithTag("alarm_expanded_0").assertDoesNotExist()
    }
    /** Регресс: включённый тумблер авто-остановки с нулевым значением обещал остановку, которой не было. */
    @Test
    fun autostopSwitchWithoutValueWarns() {
        seed(TimerConfig(alarms = listOf(Defaults.newAlarm(0, 0)), autoStopMs = 0L))
        showApp()
        awaitText("alarm_count", "Будильники: 1/100")

        scrollTo("autostop_switch")
        composeRule.onNodeWithTag("autostop_switch").performClick()
        scrollTo("autostop_warning")
        composeRule.onNodeWithTag("autostop_warning").assertIsDisplayed()

        // Это предупреждение, а не запрет: старт остаётся доступен.
        scrollTo("btn_start")
        composeRule.onNodeWithTag("btn_start").assertIsEnabled()
    }

    @Test
    fun autostopWarningHiddenWhenValueIsSet() {
        seed(TimerConfig(alarms = listOf(Defaults.newAlarm(0, 0)), autoStopMs = 60_000L))
        showApp()
        awaitText("alarm_count", "Будильники: 1/100")

        scrollTo("autostop_switch")
        composeRule.onNodeWithTag("autostop_switch").assertIsOn()
        composeRule.onNodeWithTag("autostop_warning").assertDoesNotExist()
    }

    @Test
    fun maxFiresSwitchWithoutValueWarns() {
        seed(TimerConfig(alarms = listOf(Defaults.newAlarm(0, 0)), autoStopMs = 0L))
        showApp()
        awaitText("alarm_count", "Будильники: 1/100")

        scrollTo("maxfires_switch")
        composeRule.onNodeWithTag("maxfires_switch").performClick()
        scrollTo("maxfires_warning")
        composeRule.onNodeWithTag("maxfires_warning").assertIsDisplayed()
    }
}
