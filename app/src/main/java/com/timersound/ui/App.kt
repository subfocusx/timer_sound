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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timersound.TimerViewModel
import com.timersound.audio.PreviewPlayer
import com.timersound.model.GroupPresets
import com.timersound.model.Weekdays
import com.timersound.timer.TimerState

/** Главный экран — список групп. State-навигация: null = список, id = экран группы, "schedule" = расписание. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: TimerViewModel = viewModel()) {
    val app by vm.app.collectAsStateWithLifecycle()
    val runtimes by vm.groupRuntimes.collectAsStateWithLifecycle()
    val conflicts by vm.conflicts.collectAsStateWithLifecycle()
    val wasInterrupted by vm.wasInterrupted.collectAsStateWithLifecycle()
    val interruptedGroups by vm.interruptedGroups.collectAsStateWithLifecycle()
    val notificationPermissionAsked by vm.notificationPermissionAsked.collectAsStateWithLifecycle()
    val selectedId by vm.selectedGroupId.collectAsStateWithLifecycle()

    var route by remember { mutableStateOf<String?>(null) }
    var showPresetPicker by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Int?>(null) }
    var deleteTarget by remember { mutableStateOf<Int?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.snackbarEvents.collect { snackbarHostState.showSnackbar(it) }
    }

    NotificationPermissionRequest(
        alreadyAsked = notificationPermissionAsked,
        onAsked = vm::markNotificationPermissionAsked,
    )

    val anyActive = runtimes.values.any { it.state == TimerState.RUNNING || it.state == TimerState.PAUSED }
    val view = LocalView.current
    SideEffect { view.keepScreenOn = anyActive }

    DisposableEffect(Unit) {
        onDispose { PreviewPlayer.release() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (route) {
                            null -> "Timer Sound"
                            "schedule" -> "Расписание"
                            else -> app.groups.firstOrNull { it.id.toString() == route }?.name ?: "Группа"
                        }
                    )
                },
                navigationIcon = {
                    if (route != null) {
                        TextButton(onClick = { route = null }) { Text("← Назад") }
                    }
                },
                actions = {
                    TextButton(onClick = { route = "schedule" }) { Text("Расписание") }
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.Info, contentDescription = "Справка")
                    }
                },
            )
        },
        floatingActionButton = {
            if (route == null) {
                ExtendedFloatingActionButton(
                    onClick = { showPresetPicker = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = "Добавить") },
                    text = { Text("Группа") },
                    modifier = Modifier.testTag("fab_add_group"),
                )
            }
        },
    ) { innerPadding ->
        when {
            route == "schedule" -> ScheduleScreen(
                groups = app.groups,
                conflicts = conflicts,
                modifier = Modifier.padding(innerPadding),
                onToggleDay = { gid, day ->
                    val g = app.groups.firstOrNull { it.id == gid } ?: return@ScheduleScreen
                    vm.setWeekdays(gid, Weekdays.set(g.weekdays, day, !Weekdays.isSet(g.weekdays, day)))
                },
                onToggleEnabled = { gid, on -> vm.setGroupEnabled(gid, on) },
            )
            route != null -> {
                val gid = route!!.toIntOrNull()
                val group = app.groups.firstOrNull { it.id == gid }
                if (group == null) {
                    LaunchedEffect(route) { route = null }
                } else {
                    GroupDetailScreen(
                        group = group,
                        runtime = runtimes[gid],
                        fadeIn = app.fadeInEnabled,
                        vm = vm,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (wasInterrupted) {
                    item(key = "interrupted") {
                        InterruptedBanner(
                            text = if (interruptedGroups.isNotEmpty()) {
                                "Прервано системой: группы ${interruptedGroups.sorted().joinToString(", ")}"
                            } else "Таймер был прерван системой.",
                            onDismiss = vm::dismissInterrupted,
                        )
                    }
                }
                if (anyActive) {
                    item(key = "all_actions") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = vm::pauseAll,
                                modifier = Modifier.testTag("btn_pause_all"),
                            ) { Text("Пауза всех") }
                            OutlinedButton(
                                onClick = vm::stopAll,
                                modifier = Modifier.testTag("btn_stop_all"),
                            ) { Text("Стоп всех") }
                        }
                    }
                }
                items(app.groups, key = { it.id }) { group ->
                    GroupCard(
                        group = group,
                        runtime = runtimes[group.id],
                        conflicts = conflicts,
                        vm = vm,
                        onOpen = {
                            vm.selectGroup(group.id)
                            route = group.id.toString()
                        },
                        onPickRename = { renameTarget = group.id },
                        onConfirmDelete = { deleteTarget = group.id },
                    )
                }
            }
        }
    }

    if (showPresetPicker) {
        PresetPickerDialog(
            names = listOf("Пустая") + GroupPresets.presetNames().drop(1),
            onPick = { i ->
                vm.createGroup(if (i == 0) null else i - 1 + 0)
                showPresetPicker = false
            },
            onDismiss = { showPresetPicker = false },
        )
    }
    renameTarget?.let { gid ->
        val g = app.groups.firstOrNull { it.id == gid }
        if (g != null) {
            RenameGroupDialog(
                current = g.name,
                onConfirm = { vm.renameGroup(gid, it); renameTarget = null },
                onDismiss = { renameTarget = null },
            )
        }
    }
    deleteTarget?.let { gid ->
        val g = app.groups.firstOrNull { it.id == gid }
        if (g != null) {
            DeleteGroupDialog(
                name = g.name,
                onConfirm = { vm.deleteGroup(gid); deleteTarget = null },
                onDismiss = { deleteTarget = null },
            )
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

