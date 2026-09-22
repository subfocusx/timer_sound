package com.timersound.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Play
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timersound.TimerViewModel
import com.timersound.audio.PreviewPlayer
import com.timersound.model.AlarmConfig
import com.timersound.model.SceneMode
import com.timersound.model.Defaults
import com.timersound.timer.TimerSession

/**
 * Карточка будильника: свёрнутая по умолчанию (плеер + прогресс),
 * раскрывается по тапу на имя (полный набор контролов).
 */
@Composable
fun AlarmCard(
    alarm: AlarmConfig,
    isExpanded: Boolean,
    vm: TimerViewModel,
    onToggleExpand: () -> Unit,
    onDelete: () -> Unit,
    onPickFile: () -> Unit,
) {
    val canEdit by vm.canEdit.collectAsStateWithLifecycle()
    val preview by vm.previewState.collectAsStateWithLifecycle()
    val isThisPlaying = preview?.alarmId == alarm.id
    val isPlaying = preview?.isPlaying == true && isThisPlaying
    val positionMs = preview?.positionMs ?: 0L
    val durationMs = preview?.durationMs ?: 0L

    val fileName = vm.fileDisplayName(alarm)
    val isInvalidSchedule = alarm.enabled && !alarm.scheduleValid

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---- Row 1: name (tappable), switch, delete, expand ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = alarm.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onToggleExpand),
                )
                Switch(
                    checked = alarm.enabled,
                    onCheckedChange = if (canEdit) { vm.setEnabled(alarm.id, it) } else null,
                )
                if (canEdit) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить")
                    }
                }
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Свернуть" else "Раскрыть",
                    )
                }
            }

            // ---- Collapsed view ----
            if (!isExpanded) {
                // File + play/pause
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = fileName ?: "Файл не выбран",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (fileName == null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { if (canEdit) vm.playerToggle(alarm.id) },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.Play,
                            contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                        )
                    }
                }

                // Progress + time
                if (isThisPlaying && durationMs > 0) {
                    LinearProgressIndicator(
                        progress = { positionMs.toFloat() / durationMs },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            text = "${TimerSession.formatHms(positionMs)} / ${TimerSession.formatHms(durationMs)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                // Summary chips
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text(alarm.mode.label) },
                    )
                    val summary = buildString {
                        when {
                            alarm.mode == SceneMode.REPEAT -> append("Повтор")
                            alarm.mode == SceneMode.ONCE_TIME -> append("Один раз")
                            alarm.mode == SceneMode.INTERVAL -> append("N раз")
                            alarm.mode == SceneMode.RANDOM -> append("Случайно")
                        }
                        append(" · ")
                        alarm.startMinutes?.let { append(TimerSession.formatHms(it * 60_000L)) }
                            ?: append("Сразу")
                        if (alarm.mode == SceneMode.INTERVAL || alarm.mode == SceneMode.RANDOM) {
                            append(", ${alarm.launchCount}x")
                        }
                        if (alarm.mode == SceneMode.RANDOM) {
                            alarm.endMinutes?.let { append(" до ${TimerSession.formatHms(it * 60_000L)}") }
                        }
                        append(" · ${alarm.volumePercent}%")
                    }
                    FilterChip(selected = true, onClick = {}, label = { Text(summary) })
                }

                // Error line
                if (isInvalidSchedule) {
                    Text(
                        text = "Расписание невалидно — проверьте режим и время/количество",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // ---- Expanded view ----
            if (isExpanded && canEdit) {
                ExpandedAlarmContent(alarm = alarm, vm = vm, onPickFile = onPickFile)
            }
        }
    }
}

// ------------------------------------------------------------------ expanded content

@Composable
private fun ExpandedAlarmContent(alarm: AlarmConfig, vm: TimerViewModel, onPickFile: () -> Unit) {
    var name by remember(alarm.name) { mutableStateOf(alarm.name) }

    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Name (editable, commit on focus loss)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(Defaults.MAX_NAME_LENGTH) },
            onEditingFinished = { vm.renameAlarm(alarm.id, name) },
            singleLine = true,
            label = { Text("Имя") },
            modifier = Modifier.fillMaxWidth(),
        )

        // Mode chips
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SceneMode.values().forEach { mode ->
                FilterChip(
                    selected = alarm.mode == mode,
                    onClick = { vm.setMode(alarm.id, mode) },
                    label = { Text(mode.label) },
                )
            }
        }

        // Mode-specific fields
        when (alarm.mode) {
            SceneMode.REPEAT -> TimeField(
                value = alarm.startMinutes,
                onChange = { vm.setStartMinutes(alarm.id, it) },
                label = "Начать с (HH:MM), пусто — сразу",
            )
            SceneMode.ONCE_TIME -> TimeField(
                value = alarm.startMinutes,
                onChange = { vm.setStartMinutes(alarm.id, it) },
                label = "Время (HH:MM)",
            )
            SceneMode.INTERVAL -> {
                TimeField(
                    value = alarm.startMinutes,
                    onChange = { vm.setStartMinutes(alarm.id, it) },
                    label = "Первый в (HH:MM)",
                )
                CountField(
                    value = alarm.launchCount,
                    onChange = { vm.setLaunchCount(alarm.id, it) },
                    label = "Сколько раз",
                )
            }
            SceneMode.RANDOM -> {
                TimeField(
                    value = alarm.startMinutes,
                    onChange = { vm.setStartMinutes(alarm.id, it) },
                    label = "Окно от (HH:MM)",
                )
                TimeField(
                    value = alarm.endMinutes,
                    onChange = { vm.setEndMinutes(alarm.id, it) },
                    label = "Окно до (HH:MM, не вкл.)",
                )
                CountField(
                    value = alarm.launchCount,
                    onChange = { vm.setLaunchCount(alarm.id, it) },
                    label = "Сколько звуков",
                )
            }
        }

        // Interval + presets (REPEAT and INTERVAL)
        if (alarm.mode == SceneMode.REPEAT || alarm.mode == SceneMode.INTERVAL) {
            DurationField(
                valueMs = alarm.intervalMs,
                onChange = { vm.setInterval(alarm.id, it) },
                label = "Интервал ЧЧ:ММ:СС",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Presets.forEach { (label, ms) ->
                    FilterChip(
                        selected = alarm.intervalMs == ms,
                        onClick = { vm.setInterval(alarm.id, ms) },
                        label = { Text(label) },
                    )
                }
            }
        }

        // Volume slider
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Громкость",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Slider(
                value = alarm.volumePercent.toFloat(),
                onValueChange = { vm.setVolume(alarm.id, it.toInt()) },
                valueRange = 0f..100f,
                modifier = Modifier.weight(3f),
            )
            Text(
                text = "${alarm.volumePercent}%",
                style = MaterialTheme.typography.labelLarge,
            )
        }

        // File operations
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onPickFile) { Text("Выбрать") }
            TextButton(onClick = { vm.setBuiltInBeep(alarm.id) }) { Text("Встроенный бип") }
            TextButton(onClick = { vm.removeFile(alarm.id) }) {
                Text(if (alarm.isBuiltInBeep) "Сброс" else "Удалить")
            }
        }
    }
}

// ------------------------------------------------------------------ input helpers

@Composable
fun DurationField(valueMs: Long, onChange: (Long) -> Unit, label: String) {
    var text by remember(valueMs) { mutableStateOf(TimerSession.formatHms(valueMs)) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val cleaned = raw.filter { it.isDigit() || it == ':' }.take(8)
            text = cleaned
            parseHms(cleaned)?.let(onChange)
        },
        singleLine = true,
        isError = text.isNotEmpty() && parseHms(text) == null,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
fun TimeField(value: Int?, onChange: (Int?) -> Unit, label: String) {
    var text by remember(value) { mutableStateOf(value?.let(::formatMinutes) ?: "") }
    
    // Синхронизация при изменении внешнего значения
    LaunchedEffect(value) {
        val expected = value?.let(::formatMinutes) ?: ""
        if (text != expected) {
            text = expected
        }
    }
    
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            // Удаляем всё кроме цифр, ограничиваем 4 символами
            val digits = raw.filter { it.isDigit() }.take(4)
            
            // Форматируем с автоматической вставкой ":" после двух цифр
            val formatted = if (digits.length > 2) {
                "${digits.take(2)}:${digits.drop(2)}"
            } else {
                digits
            }
            
            text = formatted
            onChange(parseHm(formatted))
        },
        singleLine = true,
        isError = text.isNotEmpty() && parseHm(text) == null,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = {
            Text("Вводите только цифры, : добавится автоматически (930 → 09:30)")
        },
    )
}

@Composable
fun CountField(value: Int, onChange: (Int) -> Unit, label: String) {
    OutlinedTextField(
        value = if (value == 0) "" else value.toString(),
        onValueChange = { raw ->
            onChange(raw.filter { it.isDigit() }.take(6).toIntOrNull() ?: 0)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        label = { Text(label) },
    )
}

internal fun parseHms(text: String): Long? {
    if (text.length != 8) return null
    val parts = text.split(':')
    if (parts.size != 3) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val s = parts[2].toIntOrNull() ?: return null
    if (m > 59 || s > 59) return null
    return (h * 3600L + m * 60L + s) * 1000L
}

internal fun parseHm(text: String): Int? {
    if (text.length != 5) return null
    val parts = text.split(':')
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (m > 59) return null
    val total = h * 60 + m
    return when {
        total <= 24 * 60 - 1 -> total
        total == 24 * 60 && m == 0 -> total
        else -> null
    }
}

internal fun formatMinutes(minutes: Int): String =
    if (minutes == 24 * 60) "24:00" else "%02d:%02d".format(minutes / 60, minutes % 60)

internal val Presets: List<Pair<String, Long>> = listOf(
    "1 мин" to 60_000L,
    "3 мин" to 180_000L,
    "5 мин" to 300_000L,
    "10 мин" to 600_000L,
    "15 мин" to 900_000L,
    "30 мин" to 1_800_000L,
)
