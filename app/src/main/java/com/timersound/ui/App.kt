package com.timersound.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import com.timersound.audio.PreviewPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timersound.R
import com.timersound.TimerViewModel
import com.timersound.model.AlarmConfig
import com.timersound.model.Defaults
import com.timersound.service.TimerStateHolder
import com.timersound.timer.TimerState

/** Единственный экран приложения: статус, авто-остановка, список будильников, FAB. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: TimerViewModel = viewModel()) {
    val config by vm.config.collectAsStateWithLifecycle()
    val runtime by vm.runtime.collectAsStateWithLifecycle()
    val canEdit by vm.canEdit.collectAsStateWithLifecycle()
    val wasInterrupted by vm.wasInterrupted.collectAsStateWithLifecycle()
    val notificationPermissionAsked by vm.notificationPermissionAsked.collectAsStateWithLifecycle()

    // Раньше snackbarEvents никто не собирал — сообщения ViewModel терялись молча.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.snackbarEvents.collect { snackbarHostState.showSnackbar(it) }
    }
    val alarmCount = config.alarms.size
    val isLocked = runtime.state == TimerState.RUNNING || runtime.state == TimerState.PAUSED
    // Зеркало проверки TimerViewModel.start(): кнопка «Старт» активна только когда старт
    // действительно возможен — нет включённых каналов без файла и есть хотя бы один канал
    // с файлом и валидным расписанием. Раньше здесь было !isLocked, из-за чего кнопка была
    // активна при пустом файле, молча ничего не делала (start() выходит раньше), а ветка
    // с поясняющей подсказкой была недостижима (!canStart && !locked).
    val canStartConfig = config.missingFileAlarms().isEmpty() &&
        config.playableAlarms().any { it.scheduleValid }
    val overlapIds = remember(config) {
        config.overlappingAlarms(
            nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
            nowWallMs = System.currentTimeMillis(),
        ).flatMap { (a, b) -> listOf(a.id, b.id) }.toSet()
    }

    var expandedAlarmId by remember { mutableStateOf<Int?>(null) }
    var pendingAlarmId by remember { mutableStateOf<Int?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var deleteTargetId by remember { mutableStateOf<Int?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pendingAlarmId?.let { id -> vm.onFilePicked(id, uri) } }
        pendingAlarmId = null
    }

    NotificationPermissionRequest(
        alreadyAsked = notificationPermissionAsked,
        onAsked = vm::markNotificationPermissionAsked,
)

    val view = LocalView.current
    SideEffect {
        view.keepScreenOn = runtime.state == TimerState.RUNNING || runtime.state == TimerState.PAUSED
    }

    DisposableEffect(Unit) {
        onDispose { PreviewPlayer.release() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Timer Sound") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    StatusBadge(state = runtime.state)
                    IconButton(onClick = { showHelpDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Справка",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            AddAlarmFab(
                count = alarmCount,
                enabled = canEdit && alarmCount < Defaults.MAX_ALARMS,
                onAdd = { vm.addAlarm() },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            // bottom = 96 dp: FAB «Добавить» перекрывал нижний ряд чипов раскрытой карточки.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "status") {
                StatusCard(
                    runtime = runtime,
                    canStart = !isLocked && canStartConfig,
                    locked = isLocked,
                    missing = config.missingFileAlarms(),
                    invalid = config.invalidScheduleAlarms(),
                    vm = vm,
                )
            }
            if (isLocked) {
                item(key = "lock_banner") { LockBanner() }
            }
            if (wasInterrupted && !isLocked) {
                item(key = "interrupted_banner") { InterruptedBanner(onDismiss = vm::dismissInterrupted) }
            }
            item(key = "autostop") {
                AutoStopCard(
                    autoStopMs = config.autoStopMs,
                    onAutoStopChange = vm::setAutoStop,
                    locked = isLocked,
                )
            }
            item(key = "maxfires") {
                MaxFiresCard(
                    maxFires = config.maxTotalFiresPerSession,
                    onMaxFiresChange = vm::setMaxTotalFires,
                    locked = isLocked,
                )
            }
            item(key = "fadein") {
                FadeInCard(
                    enabled = config.fadeInEnabled,
                    onChange = vm::setFadeInEnabled,
                    locked = isLocked,
                )
            }
            item(key = "counter") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Будильники: $alarmCount/${Defaults.MAX_ALARMS}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).testTag("alarm_count"),
                    )
                    // Диалог «Удалить все» был недостижим: флаг showDeleteAllDialog
                    // никогда не выставлялся в true — добавлен элемент управления.
                    if (canEdit && alarmCount > 0) {
                        TextButton(
                            onClick = { showDeleteAllDialog = true },
                            modifier = Modifier.testTag("delete_all"),
                        ) {
                            Text("Удалить все")
                        }
                    }
                }
            }
            if (alarmCount == 0) {
                item(key = "empty") { EmptyState() }
            } else {
                items(config.alarms, key = { it.id }) { alarm ->
                    AlarmCard(
                        alarm = alarm,
                        isExpanded = expandedAlarmId == alarm.id,
                        vm = vm,
                        onToggleExpand = {
                            expandedAlarmId = if (expandedAlarmId == alarm.id) null else alarm.id
                        },
                        onDelete = { deleteTargetId = alarm.id; showDeleteDialog = true },
                        onPickFile = { pendingAlarmId = alarm.id; filePicker.launch(arrayOf("audio/*")) },
                        hasOverlap = alarm.id in overlapIds,
                    )
                }
            }
        }
    }

    if (showDeleteDialog && deleteTargetId != null) {
        val alarm = config.alarms.firstOrNull { it.id == deleteTargetId }
        if (alarm != null) {
            DeleteAlarmDialog(
                alarm = alarm,
                onConfirm = {
                    vm.deleteAlarm(alarm.id)
                    showDeleteDialog = false
                    deleteTargetId = null
                },
                onDismiss = { showDeleteDialog = false; deleteTargetId = null },
            )
        }
    }

    if (showDeleteAllDialog) {
        DeleteAllDialog(
            count = alarmCount,
            onConfirm = {
                vm.deleteAllAlarms()
                showDeleteAllDialog = false
            },
            onDismiss = { showDeleteAllDialog = false },
        )
    }

    if (showHelpDialog) {
        HelpDialog(onDismiss = { showHelpDialog = false })
    }
}

// ------------------------------------------------------------------ UI blocks

@Composable
private fun StatusBadge(state: TimerState) {
    val (text, color) = when (state) {
        TimerState.IDLE -> "Готов" to MaterialTheme.colorScheme.onSurfaceVariant
        TimerState.RUNNING -> "Идёт" to MaterialTheme.colorScheme.primary
        TimerState.PAUSED -> "Пауза" to MaterialTheme.colorScheme.tertiary
        TimerState.COMPLETED -> "Завершено" to MaterialTheme.colorScheme.error
    }
    Surface(
        shape = CircleShape,
        color = color,
        modifier = Modifier.size(48.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(4.dp),
        ) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.surface,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .testTag("status_badge")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun LockBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("lock_banner"),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Text(
                text = "Идут срабатывания. Правки — после Стоп.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** А4: прошлая сессия была прервана системой — вместо тихого сброса в IDLE. */
@Composable
private fun InterruptedBanner(onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("interrupted_banner"),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Таймер был прерван системой (не штатная остановка).",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Понятно") }
        }
    }
}

@Composable
private fun AddAlarmFab(count: Int, enabled: Boolean, onAdd: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = { if (enabled) onAdd() },
        icon = { Icon(Icons.Default.Add, contentDescription = "Добавить") },
        text = { Text("Добавить") },
        modifier = Modifier.testTag("fab_add"),
    )
}

@Composable
private fun EmptyState() {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("empty_state"),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Будильников нет", style = MaterialTheme.typography.titleMedium)
            Text("Нажмите + Добавить", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ------------------------------------------------------------------ status card

@Composable
private fun StatusCard(
    runtime: TimerStateHolder.Ui,
    canStart: Boolean,
    locked: Boolean,
    missing: List<AlarmConfig>,
    invalid: List<AlarmConfig>,
    vm: TimerViewModel,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Timer Sound",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            if (runtime.state == TimerState.RUNNING || runtime.state == TimerState.PAUSED) {
                if (runtime.autoStop.isNotEmpty()) {
                    Text("До авто-остановки: ${runtime.autoStop}", style = MaterialTheme.typography.bodyMedium)
                }
                if (runtime.nextSound.isNotEmpty()) {
                    Text("Следующий звук: ${runtime.nextSound}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (runtime.state) {
                    TimerState.IDLE, TimerState.COMPLETED -> {
                        Button(
                            onClick = { vm.start() },
                            enabled = canStart,
                            modifier = Modifier.testTag("btn_start"),
                        ) {
                            Text(if (runtime.state == TimerState.COMPLETED) "Запустить заново" else "Старт")
                        }
                        if (runtime.state == TimerState.COMPLETED) {
                            OutlinedButton(
                                onClick = { vm.reset() },
                                modifier = Modifier.testTag("btn_reset"),
                            ) { Text("Сброс") }
                        }
                    }
                    TimerState.RUNNING -> {
                        OutlinedButton(
                            onClick = { vm.pause() },
                            modifier = Modifier.testTag("btn_pause"),
                        ) { Text("Пауза") }
                        StopButton(onClick = { vm.stop() })
                    }
                    TimerState.PAUSED -> {
                        OutlinedButton(
                            onClick = { vm.resume() },
                            modifier = Modifier.testTag("btn_resume"),
                        ) { Text("Продолжить") }
                        StopButton(onClick = { vm.stop() })
                    }
                }
            }
            // Подсказка — только про конфиг (кнопка заблокирована не из-за идущей сессии),
            // иначе во время работы висело «Включите хотя бы один канал с файлом» при выбранном звуке.
            if (!canStart && !locked) {
                val hint = when {
                    invalid.isNotEmpty() -> "Исправьте расписание: " + invalid.joinToString { it.name } +
                        " (проверьте режим, время и количество)."
                    missing.isNotEmpty() -> "Для старта укажите файл: " + missing.joinToString { it.name }
                    else -> "Нет ни одного включённого канала со звуком — включите канал и выберите файл."
                }
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("status_hint"),
                )
            } else if (locked) {
                Text(
                    text = "Сессия идёт. Правки и старт — после кнопки «Стоп».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.testTag("btn_stop"),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
    ) {
        Text("Стоп")
    }
}

// ------------------------------------------------------------------ auto-stop card

@Composable
private fun AutoStopCard(autoStopMs: Long, onAutoStopChange: (Long) -> Unit, locked: Boolean) {
    var limited by remember(autoStopMs) { mutableStateOf(autoStopMs > 0L) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Авто-остановка",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = limited,
                    enabled = !locked,
                    onCheckedChange = { limited = it; if (!it) onAutoStopChange(0L) },
                    modifier = Modifier.testTag("autostop_switch"),
                )
            }
            if (limited) {
                DurationField(valueMs = autoStopMs, onChange = onAutoStopChange, label = "Авто-остановка через (ЧЧ:ММ:СС)")
                if (autoStopMs <= 0L) {
                    // Тумблер включён, но значение не задано (autoStopMs = 0 = без ограничения):
                    // без явной строки интерфейс обещает авто-остановку, которой не будет.
                    Text(
                        text = "Укажите время — без него авто-остановка не сработает.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("autostop_warning"),
                    )
                }
                Text("Остановит все каналы и отметит сессию «Завершено».", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Без ограничения — таймер работает до нажатия «Стоп».", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MaxFiresCard(maxFires: Int, onMaxFiresChange: (Int) -> Unit, locked: Boolean) {
    var limited by remember(maxFires) { mutableStateOf(maxFires > 0) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Лимит срабатываний",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = limited,
                    enabled = !locked,
                    onCheckedChange = { limited = it; if (!it) onMaxFiresChange(0) },
                    modifier = Modifier.testTag("maxfires_switch"),
                )
            }
            if (limited) {
                CountField(value = maxFires, onChange = onMaxFiresChange, label = "Максимум звуков за сессию")
                if (maxFires <= 0) {
                    // Та же ловушка, что и у авто-остановки: включённый тумблер без значения.
                    Text(
                        text = "Укажите число — без него лимит не действует.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("maxfires_warning"),
                    )
                }
                Text("Завершит сессию после N-го срабатывания — защита от опечатки в количестве.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Без ограничения — сессия идёт до конца расписания.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FadeInCard(enabled: Boolean, onChange: (Boolean) -> Unit, locked: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Плавное нарастание громкости",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Звук нарастает ~2 секунды вместо резкого сигнала.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, enabled = !locked, onCheckedChange = onChange)
        }
    }
}

// ------------------------------------------------------------------ permissions

// ------------------------------------------------------------------ permissions

/**
 * Системный запрос POST_NOTIFICATIONS (API 33+) — ровно один раз за жизнь установки.
 * Повторные показы раздражали: разрешение запрашивалось при каждом запуске, пока
 * пользователь не откажет дважды. Флаг [alreadyAsked] хранится в DataStore, а не в памяти
 * процесса, поэтому переживает перезапуск приложения.
 */
@Composable
private fun NotificationPermissionRequest(alreadyAsked: Boolean, onAsked: () -> Unit) {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(alreadyAsked) {
            if (alreadyAsked) return@LaunchedEffect
            // Помечаем ДО показа: если пользователь закроет диалог жестом, спрашивать снова не будем.
            onAsked()
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

// ------------------------------------------------------------------ help dialog

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Как пользоваться Timer Sound") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("📌 Режимы работы:")
                Text("• Повтор — повторение с интервалом", style = MaterialTheme.typography.bodySmall)
                Text("• Один раз — звук в заданное время", style = MaterialTheme.typography.bodySmall)
                Text("• N раз — повторение заданное количество раз", style = MaterialTheme.typography.bodySmall)
                Text("• Случайно — случайные моменты в заданном окне времени", style = MaterialTheme.typography.bodySmall)
                
                Text("⏰ Ввод времени:")
                Text("Вводите только цифры — двоеточие добавится автоматически.", style = MaterialTheme.typography.bodySmall)
                Text("Пример: 930 → 09:30, 1430 → 14:30", style = MaterialTheme.typography.bodySmall)
                
                Text("🔊 Фоновый режим:")
                Text("Приложение работает в фоне даже при выключенном экране.", style = MaterialTheme.typography.bodySmall)
                Text("Для старых телефонов (Huawei, Xiaomi) рекомендуется:", style = MaterialTheme.typography.bodySmall)
                Text("1. Закрепить приложение в памяти", style = MaterialTheme.typography.bodySmall)
                Text("2. Отключить экономию батареи для приложения", style = MaterialTheme.typography.bodySmall)
                Text("3. Разрешить автозапуск", style = MaterialTheme.typography.bodySmall)
                
                Text("🛑 Авто-остановка:")
                Text("Установите лимит времени — таймер остановится автоматически.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Понятно") }
        },
    )
}

