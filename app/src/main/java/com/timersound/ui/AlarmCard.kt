package com.timersound.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
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
                    onCheckedChange = if (canEdit) { { value: Boolean -> vm.setEnabled(alarm.id, value) } } else null,
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
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
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
                        append(alarm.mode.label)
                        append(" · ")
                        alarm.startMinutes?.let { append(TimerSession.formatHms(it * 60_000L)) }
                            ?: append("Через интервал")
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
            singleLine = true,
            label = { Text("Имя") },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) vm.renameAlarm(alarm.id, name) },
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
                label = "Начать с (HH:MM), пусто — через интервал",
                warning = pastTimeWarning(alarm.startMinutes),
            )
            SceneMode.ONCE_TIME -> TimeField(
                value = alarm.startMinutes,
                onChange = { vm.setStartMinutes(alarm.id, it) },
                label = "Время (HH:MM)",
                warning = pastTimeWarning(alarm.startMinutes),
            )
            SceneMode.INTERVAL -> {
                TimeField(
                    value = alarm.startMinutes,
                    onChange = { vm.setStartMinutes(alarm.id, it) },
                    label = "Первый в (HH:MM)",
                    warning = pastTimeWarning(alarm.startMinutes),
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
                    // Прошедший конец окна = сегодня окна больше нет, звуки уедут на завтра.
                    warning = pastTimeWarning(alarm.endMinutes),
                )
                CountField(
                    value = alarm.launchCount,
                    onChange = { vm.setLaunchCount(alarm.id, it) },
                    label = "Сколько звуков",
                )
            }
        }

        // Interval (REPEAT and INTERVAL)
        if (alarm.mode == SceneMode.REPEAT || alarm.mode == SceneMode.INTERVAL) {
            DurationField(
                valueMs = alarm.intervalMs,
                onChange = { vm.setInterval(alarm.id, it) },
                label = "Интервал ЧЧ:ММ:СС",
            )
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
    var text by remember { mutableStateOf(TextFieldValue(TimerSession.formatHms(valueMs))) }
    var focused by remember { mutableStateOf(false) }
    // Пока поле в фокусе, не перезаписываем ввод значением из модели — иначе посимвольный ввод ломается.
    LaunchedEffect(valueMs, focused) {
        if (!focused) {
            val expected = TimerSession.formatHms(valueMs)
            if (text.text != expected) text = TextFieldValue(expected, TextRange(expected.length))
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val formatted = formatHmsInput(restartIfFull(raw.text, text.text, MAX_HMS_DIGITS))
            // Каретку всегда ставим в конец: иначе после авто-маски Compose переставляет её в середину
            // и следующие цифры вставляются не туда (0048 → 00:84).
            text = TextFieldValue(formatted, TextRange(formatted.length))
            parseHms(formatted)?.let(onChange)
        },
        modifier = Modifier.onFocusChanged { state ->
            val gained = state.isFocused && !focused
            focused = state.isFocused
            // Тап по заполненному полю выделяет всё значение: для экранной клавиатуры первая
            // же цифра заменит его целиком. Если IME проигнорирует выделение, значение
            // подменит restartIfFull() ниже.
            if (gained && text.text.isNotEmpty()) {
                text = TextFieldValue(text.text, TextRange(0, text.text.length))
            }
        },
        singleLine = true,
        isError = text.text.isNotEmpty() && parseHms(text.text) == null,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = { Text("Только цифры, «:» добавятся сами (130 → 1:30). Тап — ввод заменит значение.") },
    )
}

@Composable
fun TimeField(value: Int?, onChange: (Int?) -> Unit, label: String, warning: String? = null) {
    var text by remember { mutableStateOf(TextFieldValue(value?.let(::formatMinutes) ?: "")) }
    var focused by remember { mutableStateOf(false) }

    // Синхронизация с моделью — только когда поле не в фокусе (иначе ввод затирается).
    LaunchedEffect(value, focused) {
        if (!focused) {
            val expected = value?.let(::formatMinutes) ?: ""
            if (text.text != expected) text = TextFieldValue(expected, TextRange(expected.length))
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val formatted = formatHmInput(restartIfFull(raw.text, text.text, MAX_HM_DIGITS))
            // Каретка всегда в конце — авто-маска иначе переставляет её в середину строки.
            text = TextFieldValue(formatted, TextRange(formatted.length))
            onChange(parseHm(formatted))
        },
        modifier = Modifier.onFocusChanged { state ->
            val gained = state.isFocused && !focused
            focused = state.isFocused
            // См. DurationField: заполненное поле выделяется целиком, чтобы ввод его заменял.
            if (gained && text.text.isNotEmpty()) {
                text = TextFieldValue(text.text, TextRange(0, text.text.length))
            }
        },
        singleLine = true,
        isError = text.text.isNotEmpty() && parseHm(text.text) == null,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = {
            if (warning != null) {
                Text(warning, color = MaterialTheme.colorScheme.error)
            } else {
                Text("Только цифры, «:» добавится сам (930 → 09:30). Тап — ввод заменит значение.")
            }
        },
    )
}

/** Минуты от полуночи по локальному времени — для предупреждений «время уже прошло». */
internal fun nowMinutesOfDay(nowMs: Long = System.currentTimeMillis()): Int {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = nowMs
    return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
}

/**
 * Предупреждение для времени, которое сегодня уже прошло: такое время сработает только завтра,
 * и без явного текста это выглядит как «нажал Старт — звука нет».
 */
internal fun pastTimeWarning(value: Int?, nowMinutes: Int = nowMinutesOfDay()): String? =
    if (value != null && value <= nowMinutes) "Это время сегодня уже прошло — сработает завтра." else null

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
    val parts = text.split(':')
    if (parts.isEmpty() || parts.size > 3) return null
    if (parts.any { it.isEmpty() || it.length > 2 || it.any { c -> !c.isDigit() } }) return null
    val nums = parts.map { it.toInt() }
    // Части читаем справа налево: последняя — секунды, предыдущая — минуты, первая — часы.
    val seconds = nums.last()
    val minutes = if (nums.size >= 2) nums[nums.size - 2] else 0
    val hours = if (nums.size >= 3) nums[0] else 0
    if (minutes > 59 || seconds > 59) return null
    return (hours * 3600L + minutes * 60L + seconds) * 1000L
}

internal fun parseHm(text: String): Int? {
    val parts = text.split(':')
    if (parts.isEmpty() || parts.size > 2) return null
    if (parts.any { it.isEmpty() || it.length > 2 || it.any { c -> !c.isDigit() } }) return null
    val nums = parts.map { it.toInt() }
    val minutes = nums.last()
    val hours = if (nums.size >= 2) nums[0] else 0
    if (minutes > 59) return null
    val total = hours * 60 + minutes
    return when {
        total <= 24 * 60 - 1 -> total
        total == 24 * 60 && minutes == 0 -> total
        else -> null
    }
}

/** Максимум цифр в масках ввода (HH:MM и ЧЧ:ММ:СС). */
internal const val MAX_HM_DIGITS = 4
internal const val MAX_HMS_DIGITS = 6

/**
 * Пользователь начал печатать в уже заполненном поле, а маска отбрасывает лишние цифры
 * справа — без этой правки значение «замирает» (00:05:00 + «2» → 00:05:00), и поле
 * выглядит нередактируемым. Считаем такой ввод новым значением с нуля и берём только
 * реально добавленные цифры (обычно одну).
 *
 * Удаление (Backspace) сюда не попадает: длина цифр не растёт, работает обычная маска.
 */
internal fun restartIfFull(newText: String, oldText: String, maxDigits: Int): String {
    val newDigits = newText.filter { it.isDigit() }
    val oldDigits = oldText.filter { it.isDigit() }
    if (oldDigits.length < maxDigits || newDigits.length <= maxDigits) return newText
    val added = (newDigits.length - oldDigits.length).coerceAtLeast(1)
    return newDigits.takeLast(added)
}

/**
 * Маска ввода HH:MM: «:» вставляется автоматически, лишние символы отбрасываются.
 * 930 → 9:30, 0930 → 09:30, 9 → 9 (неполный ввод — парсер вернёт null и значение не меняется).
 */
internal fun formatHmInput(raw: String): String {
    val d = raw.filter { it.isDigit() }.take(4)
    return when (d.length) {
        0 -> ""
        1, 2 -> d
        3 -> "${d.take(1)}:${d.drop(1)}"
        else -> "${d.take(2)}:${d.drop(2)}"
    }
}

/**
 * Маска ввода HH:MM:SS: «:» вставляется автоматически.
 * 130 → 1:30, 1300 → 13:00, 000003 → 00:00:03, 130000 → 13:00:00.
 */
internal fun formatHmsInput(raw: String): String = when (val d = raw.filter { it.isDigit() }.take(6)) {
    "" -> ""
    else -> when (d.length) {
        1, 2 -> d
        3, 4 -> "${d.dropLast(2)}:${d.takeLast(2)}"
        5 -> "${d.take(1)}:${d.substring(1, 3)}:${d.drop(3)}"
        else -> "${d.take(2)}:${d.substring(2, 4)}:${d.drop(4)}"
    }
}

internal fun formatMinutes(minutes: Int): String =
    if (minutes == 24 * 60) "24:00" else "%02d:%02d".format(minutes / 60, minutes % 60)
