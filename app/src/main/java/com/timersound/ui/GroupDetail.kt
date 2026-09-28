package com.timersound.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.timersound.model.Defaults
import com.timersound.model.Weekdays
import com.timersound.service.TimerStateHolder
import com.timersound.timer.ScheduleConflicts
import com.timersound.timer.TimerSession
import com.timersound.timer.TimerState

/**
 * Экран группы: текущий список AlarmCard + блок общего таймера
 * (время каждого будильника, отсчёт до ближайшего, отсчёт до конца,
 * автоостановка/лимит группы, редактор дней недели).
 */
@Composable
fun GroupDetailScreen(
    group: AlarmGroup,
    runtime: TimerStateHolder.GroupRuntime?,
    fadeIn: Boolean,
    vm: TimerViewModel,
    modifier: Modifier = Modifier,
) {
    val state = runtime?.state ?: TimerState.IDLE
    val locked = state == TimerState.RUNNING || state == TimerState.PAUSED
    var expandedAlarmId by remember(group.id) { mutableStateOf<Int?>(null) }
    var pendingAlarmId by remember(group.id) { mutableStateOf<Int?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteTargetId by remember { mutableStateOf<Int?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pendingAlarmId?.let { id -> vm.onFilePicked(group.id, id, uri) } }
        pendingAlarmId = null
    }

    val overlapIds = remember(group) {
        group.toRunConfig(fadeIn).overlappingAlarms(
            nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
            nowWallMs = System.currentTimeMillis(),
        ).flatMap { (a, b) -> listOf(a.id, b.id) }.toSet()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "timer") {
            GroupTimerBlock(group = group, runtime = runtime, fadeIn = fadeIn, vm = vm)
        }
        item(key = "weekdays") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Дни автозапуска", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    WeekdayChips(group.weekdays, enabled = !locked, onToggle = { day ->
                        vm.setWeekdays(group.id, Weekdays.set(group.weekdays, day, !Weekdays.isSet(group.weekdays, day)))
                    })
                }
            }
        }
        item(key = "counter") {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Будильники: ${group.alarms.size}/${Defaults.MAX_ALARMS}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).testTag("alarm_count"),
                )
                if (!locked) {
                    TextButton(
                        onClick = { vm.addAlarm(group.id) },
                        modifier = Modifier.testTag("fab_add"),
                    ) { Text("Добавить") }
                }
            }
        }
        if (group.alarms.isEmpty()) {
            item(key = "empty") {
                Card(modifier = Modifier.fillMaxWidth().testTag("empty_state")) {
                    Text("Будильников нет", modifier = Modifier.padding(24.dp))
                }
            }
        } else {
            items(group.alarms, key = { it.id }) { alarm ->
                AlarmCard(
                    alarm = alarm,
                    groupId = group.id,
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

    if (showDeleteDialog && deleteTargetId != null) {
        val alarm = group.alarms.firstOrNull { it.id == deleteTargetId }
        if (alarm != null) {
            DeleteAlarmDialog(
                alarm = alarm,
                onConfirm = {
                    vm.deleteAlarm(group.id, alarm.id)
                    showDeleteDialog = false
                    deleteTargetId = null
                },
                onDismiss = { showDeleteDialog = false; deleteTargetId = null },
            )
        }
    }
}

/** Блок общего таймера группы. */
@Composable
private fun GroupTimerBlock(
    group: AlarmGroup,
    runtime: TimerStateHolder.GroupRuntime?,
    fadeIn: Boolean,
    vm: TimerViewModel,
) {
    val state = runtime?.state ?: TimerState.IDLE
    val locked = state == TimerState.RUNNING || state == TimerState.PAUSED
    Card(modifier = Modifier.fillMaxWidth().testTag("group_timer_${group.id}")) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Общий таймер", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            // Время срабатывания каждого будильника («сегодня в 14:05»).
            val nowWall = System.currentTimeMillis()
            group.alarms.filter { it.enabled && it.hasFile && it.scheduleValid }.forEach { alarm ->
                val label = when {
                    alarm.startMinutes != null -> TimerSession.describeWallMoment(
                        nextWallFor(alarm.startMinutes, nowWall), nowWall,
                    )
                    else -> "через интервал"
                }
                Text("${alarm.name}: $label", style = MaterialTheme.typography.bodyMedium)
            }
            GroupMiniTimer(runtime)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Автоостановка: ${if (group.autoStopMs > 0) TimerSession.formatHms(group.autoStopMs) else "нет"}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Лимит: ${if (group.maxTotalFiresPerSession > 0) group.maxTotalFiresPerSession.toString() else "нет"}")
            }
            if (!locked) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.setGroupAutoStop(group.id, cycleAutoStop(group.autoStopMs)) }) {
                        Text("Автостоп: ${shortAutoStop(group.autoStopMs)}")
                    }
                    OutlinedButton(onClick = { vm.setGroupMaxFires(group.id, cycleMaxFires(group.maxTotalFiresPerSession)) }) {
                        Text("Лимит: ${if (group.maxTotalFiresPerSession > 0) group.maxTotalFiresPerSession else "—"}")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Плавное нарастание", modifier = Modifier.weight(1f))
                    Switch(checked = fadeIn, onCheckedChange = vm::setFadeInEnabled)
                }
            }
        }
    }
}

private fun nextWallFor(startMinutes: Int, nowWall: Long): Long {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = nowWall
    cal.set(java.util.Calendar.HOUR_OF_DAY, startMinutes / 60)
    cal.set(java.util.Calendar.MINUTE, startMinutes % 60)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    if (cal.timeInMillis <= nowWall) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
    return cal.timeInMillis
}

private fun cycleAutoStop(cur: Long): Long = when (cur) {
    0L -> 15 * 60_000L
    15 * 60_000L -> 30 * 60_000L
    30 * 60_000L -> 60 * 60_000L
    60 * 60_000L -> 2 * 3_600_000L
    else -> 0L
}

private fun shortAutoStop(ms: Long): String = if (ms <= 0) "нет" else TimerSession.formatHms(ms)

private fun cycleMaxFires(cur: Int): Int = when (cur) {
    0 -> 5
    5 -> 10
    10 -> 25
    25 -> 50
    else -> 0
}

/**
 * Раздел «Расписание»: список групп по дням недели, ручное включение/
 * отключение, все предупреждения о пересечениях.
 */
@Composable
fun ScheduleScreen(
    groups: List<AlarmGroup>,
    conflicts: List<ScheduleConflicts.Conflict>,
    modifier: Modifier = Modifier,
    onToggleDay: (Int, Int) -> Unit,
    onToggleEnabled: (Int, Boolean) -> Unit,
) {
    val names = groups.associate { it.id to it.name }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (conflicts.isNotEmpty()) {
            item(key = "conflicts") {
                Card(modifier = Modifier.fillMaxWidth().testTag("schedule_conflicts")) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Пересечения расписаний", fontWeight = FontWeight.Bold)
                        conflicts.take(10).forEach { c ->
                            val days = c.days.map {
                                listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")[it - 1]
                            }.joinToString(", ")
                            Text(
                                "«${names[c.groupA] ?: c.groupA}» × «${names[c.groupB] ?: c.groupB}» ($days): ${c.kind}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        (1..7).forEach { day ->
            item(key = "day_$day") {
                val dayName = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")[day - 1]
                Card(modifier = Modifier.fillMaxWidth().testTag("schedule_day_$day")) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(dayName, fontWeight = FontWeight.Bold)
                        val armed = groups.filter { it.enabled && Weekdays.isSet(it.weekdays, day) }
                        if (armed.isEmpty()) {
                            Text("Нет групп", style = MaterialTheme.typography.bodySmall)
                        } else {
                            armed.forEach { g ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val autoMin = g.autoStartMinutes()
                                    Text(
                                        "${g.name} — ${autoMin?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "—"}",
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = true,
                                        onCheckedChange = { onToggleDay(g.id, day) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
