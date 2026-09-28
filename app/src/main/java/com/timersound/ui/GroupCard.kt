package com.timersound.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timersound.TimerViewModel
import com.timersound.model.AlarmGroup
import com.timersound.model.Weekdays
import com.timersound.service.TimerStateHolder
import com.timersound.timer.ScheduleConflicts
import com.timersound.timer.TimerSession
import com.timersound.timer.TimerState
import kotlinx.coroutines.delay

private val DAY_SHORT = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

/**
 * Карточка группы: название, Switch, дни недели, время автозапуска,
 * бейдж состояния, бейдж конфликта, минитаймер, кнопки управления, меню.
 */
@Composable
fun GroupCard(
    group: AlarmGroup,
    runtime: TimerStateHolder.GroupRuntime?,
    conflicts: List<ScheduleConflicts.Conflict>,
    vm: TimerViewModel,
    onOpen: () -> Unit,
    onPickRename: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    val state = runtime?.state ?: TimerState.IDLE
    val locked = state == TimerState.RUNNING || state == TimerState.PAUSED
    val groupConflicts = conflicts.filter { it.groupA == group.id || it.groupB == group.id }
    val autoMin = group.autoStartMinutes()
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("group_card_${group.id}"),
        shape = RoundedCornerShape(12.dp),
        onClick = onOpen,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).testTag("group_name_${group.id}"),
                )
                StateBadge(state, group.id)
                Switch(
                    checked = group.enabled,
                    onCheckedChange = { vm.setGroupEnabled(group.id, it) },
                    modifier = Modifier.testTag("group_switch_${group.id}"),
                )
            }
            WeekdayChips(group.weekdays, enabled = !locked, onToggle = { day ->
                vm.setWeekdays(group.id, Weekdays.set(group.weekdays, day, !Weekdays.isSet(group.weekdays, day)))
            })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (autoMin != null) {
                        "Автозапуск: ${"%02d:%02d".format(autoMin / 60, autoMin % 60)}"
                    } else "Только ручной запуск",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).testTag("group_autostart_${group.id}"),
                )
                if (groupConflicts.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("group_conflict_${group.id}"),
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Icon(Icons.Default.Warning, contentDescription = "Пересечение расписаний")
                            Text(" ${groupConflicts.size}")
                        }
                    }
                }
            }
            if (!group.scheduleValid() && group.weekdays != 0) {
                Text(
                    "Расписание включено, но нет будильника со временем старта",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            GroupMiniTimer(runtime)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    TimerState.IDLE, TimerState.COMPLETED -> {
                        OutlinedButton(
                            onClick = { vm.startGroup(group.id) },
                            modifier = Modifier.testTag("group_start_${group.id}"),
                        ) { Text(if (state == TimerState.COMPLETED) "Заново" else "Старт") }
                    }
                    TimerState.RUNNING -> {
                        IconButton(
                            onClick = { vm.pauseGroup(group.id) },
                            modifier = Modifier.testTag("group_pause_${group.id}"),
                        ) { Icon(Icons.Default.Pause, contentDescription = "Пауза") }
                        IconButton(
                            onClick = { vm.stopGroup(group.id) },
                            modifier = Modifier.testTag("group_stop_${group.id}"),
                        ) { Icon(Icons.Default.Stop, contentDescription = "Стоп") }
                    }
                    TimerState.PAUSED -> {
                        IconButton(
                            onClick = { vm.resumeGroup(group.id) },
                            modifier = Modifier.testTag("group_resume_${group.id}"),
                        ) { Icon(Icons.Default.PlayArrow, contentDescription = "Продолжить") }
                        IconButton(
                            onClick = { vm.stopGroup(group.id) },
                            modifier = Modifier.testTag("group_stop_${group.id}"),
                        ) { Icon(Icons.Default.Stop, contentDescription = "Стоп") }
                    }
                }
                if (state != TimerState.IDLE) {
                    IconButton(
                        onClick = { vm.restartGroup(group.id) },
                        modifier = Modifier.testTag("group_restart_${group.id}"),
                    ) { Icon(Icons.Default.Refresh, contentDescription = "Перезапуск") }
                }
                IconButton(onClick = onPickRename, modifier = Modifier.testTag("group_rename_${group.id}")) {
                    Icon(Icons.Default.Edit, contentDescription = "Переименовать")
                }
                IconButton(onClick = { vm.duplicateGroup(group.id) }, modifier = Modifier.testTag("group_copy_${group.id}")) {
                    Text("⧉")
                }
                if (!locked) {
                    IconButton(onClick = onConfirmDelete, modifier = Modifier.testTag("group_delete_${group.id}")) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить")
                    }
                }
            }
            if (showMenu) {
                showMenu = false
            }
        }
    }
}

@Composable
private fun StateBadge(state: TimerState, groupId: Int) {
    val (text, color) = when (state) {
        TimerState.IDLE -> "Готов" to MaterialTheme.colorScheme.onSurfaceVariant
        TimerState.RUNNING -> "Идёт" to MaterialTheme.colorScheme.primary
        TimerState.PAUSED -> "Пауза" to MaterialTheme.colorScheme.tertiary
        TimerState.COMPLETED -> "Завершено" to MaterialTheme.colorScheme.error
    }
    Surface(
        color = color, shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(end = 8.dp).testTag("group_state_${groupId}"),
    ) {
        Text(text, color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** Чипы дней недели Пн–Вс. */
@Composable
fun WeekdayChips(mask: Int, enabled: Boolean, onToggle: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..7).forEach { day ->
            FilterChip(
                selected = Weekdays.isSet(mask, day),
                enabled = enabled,
                onClick = { onToggle(day) },
                label = { Text(DAY_SHORT[day - 1]) },
                modifier = Modifier.testTag("weekday_${day}"),
            )
        }
    }
}

/**
 * Минитаймер: до ближайшего / до конца. Отсчёт считает UI тикером на
 * elapsedRealtime; в паузе заморожен (остатки из published snapshot).
 */
@Composable
fun GroupMiniTimer(runtime: TimerStateHolder.GroupRuntime?) {
    if (runtime == null) return
    if (runtime.state != TimerState.RUNNING && runtime.state != TimerState.PAUSED) return
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(runtime.state, runtime.publishedElapsedMs) {
        while (true) {
            delay(1_000L)
            now = SystemClock.elapsedRealtime()
        }
    }
    val untilNext = if (runtime.state == TimerState.PAUSED) {
        runtime.untilNextMs
    } else {
        runtime.nextFireElapsedMs?.let { (it - now).coerceAtLeast(0L) }
    }
    val untilEnd = if (runtime.state == TimerState.PAUSED) {
        runtime.untilEndMs
    } else {
        runtime.untilEndMs?.let { end ->
            // untilEnd опубликовано как остаток от publishedElapsedMs.
            val elapsed = (now - runtime.publishedElapsedMs).coerceAtLeast(0L)
            (end - elapsed).coerceAtLeast(0L)
        }
    }
    Column {
        if (untilNext != null) {
            Text("До ближайшего: ${TimerSession.formatHms(untilNext)}", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "До конца: ${untilEnd?.let { TimerSession.formatHms(it) } ?: "∞"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (runtime.remainingFires != null) {
            Text("Осталось срабатываний: ${runtime.remainingFires}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Диалог выбора пресета для создания группы. */
@Composable
fun PresetPickerDialog(names: List<String>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая группа") },
        text = {
            Column {
                names.forEachIndexed { i, name ->
                    TextButton(onClick = { onPick(i) }, modifier = Modifier.testTag("preset_$i")) {
                        Text(name)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Диалог переименования группы. */
@Composable
fun RenameGroupDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переименовать группу") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Удаление группы с подтверждением. */
@Composable
fun DeleteGroupDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить группу «$name»?") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Предупреждение о пересечениях: имена групп и дни, кнопки «Оставить» / «Изменить». */
@Composable
fun ConflictWarnDialog(
    conflicts: List<ScheduleConflicts.Conflict>,
    groupNames: Map<Int, String>,
    onKeep: () -> Unit,
    onEdit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onEdit,
        title = { Text("Расписания пересекаются") },
        text = {
            Column {
                conflicts.take(5).forEach { c ->
                    val days = c.days.map { DAY_SHORT[it - 1] }.joinToString(", ")
                    Text("«${groupNames[c.groupA] ?: c.groupA}» × «${groupNames[c.groupB] ?: c.groupB}»: $days")
                }
                Text("Это только предупреждение — действия не блокируются.")
            }
        },
        confirmButton = { TextButton(onClick = onKeep) { Text("Оставить") } },
        dismissButton = { TextButton(onClick = onEdit) { Text("Изменить") } },
    )
}
