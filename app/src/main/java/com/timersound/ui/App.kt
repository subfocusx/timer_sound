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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timersound.TimerViewModel
import com.timersound.model.ChannelConfig
import com.timersound.service.TimerStateHolder
import com.timersound.timer.TimerSession
import com.timersound.timer.TimerState

/** Единственный экран приложения: статус, авто-остановка и 5 каналов. */
@Composable
fun App(vm: TimerViewModel = viewModel()) {
    val config by vm.config.collectAsStateWithLifecycle()
    val runtime by vm.runtime.collectAsStateWithLifecycle()
    NotificationPermissionRequest()

    val missing = config.missingFileChannels()
    val canStart = missing.isEmpty() && config.playableChannels().isNotEmpty()

    Scaffold { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "status") {
                StatusCard(runtime = runtime, canStart = canStart, missing = missing, vm = vm)
            }
            item(key = "autostop") {
                AutoStopCard(autoStopMs = config.autoStopMs, onAutoStopChange = vm::setAutoStop)
            }
            items(config.channels, key = { it.id }) { channel ->
                ChannelCard(channel = channel, vm = vm)
            }
        }
    }
}

// ------------------------------------------------------------------ status card

@Composable
private fun StatusCard(
    runtime: TimerStateHolder.Ui,
    canStart: Boolean,
    missing: List<ChannelConfig>,
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
                StatusBadge(state = runtime.state)
            }
            if (runtime.state == TimerState.RUNNING || runtime.state == TimerState.PAUSED) {
                if (runtime.autoStop.isNotEmpty()) {
                    Text(
                        text = "До авто-остановки: ${runtime.autoStop}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (runtime.nextSound.isNotEmpty()) {
                    Text(
                        text = "Следующий звук: ${runtime.nextSound}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (runtime.state) {
                    TimerState.IDLE, TimerState.COMPLETED -> {
                        Button(onClick = { vm.start() }, enabled = canStart) {
                            Text(if (runtime.state == TimerState.COMPLETED) "Запустить заново" else "Старт")
                        }
                        if (runtime.state == TimerState.COMPLETED) {
                            OutlinedButton(onClick = { vm.reset() }) { Text("Сброс") }
                        }
                    }
                    TimerState.RUNNING -> {
                        OutlinedButton(onClick = { vm.pause() }) { Text("Пауза") }
                        StopButton(onClick = { vm.stop() })
                    }
                    TimerState.PAUSED -> {
                        OutlinedButton(onClick = { vm.resume() }) { Text("Продолжить") }
                        StopButton(onClick = { vm.stop() })
                    }
                }
            }
            if (!canStart) {
                val hint = if (missing.isEmpty()) {
                    "Включите хотя бы один канал с файлом."
                } else {
                    "Для старта укажите файл: " + missing.joinToString { it.name }
                }
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
    ) {
        Text("Стоп")
    }
}

@Composable
private fun StatusBadge(state: TimerState) {
    val (text, color) = when (state) {
        TimerState.IDLE -> "Готов" to MaterialTheme.colorScheme.onSurfaceVariant
        TimerState.RUNNING -> "Идёт" to MaterialTheme.colorScheme.primary
        TimerState.PAUSED -> "Пауза" to MaterialTheme.colorScheme.tertiary
        TimerState.COMPLETED -> "Завершено" to MaterialTheme.colorScheme.error
    }
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

// ------------------------------------------------------------------ auto-stop card

@Composable
private fun AutoStopCard(autoStopMs: Long, onAutoStopChange: (Long) -> Unit) {
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
                    onCheckedChange = { limited = it; if (!it) onAutoStopChange(0L) },
                )
            }
            if (limited) {
                DurationField(valueMs = autoStopMs, onChange = onAutoStopChange, label = "ЧЧ:ММ:СС")
                Text(
                    text = "Остановит все каналы и отметит сессию «Завершено».",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    text = "Без ограничения — таймер работает до нажатия «Стоп».",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ channel card

@Composable
private fun ChannelCard(channel: ChannelConfig, vm: TimerViewModel) {
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.onFilePicked(channel.id, it) }
    }
    val fileName = remember(channel.fileUri) { vm.fileDisplayName(channel) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (channel.enabled) "ВКЛ" else "ВЫКЛ",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (channel.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = channel.enabled,
                    onCheckedChange = { vm.setEnabled(channel.id, it) },
                )
            }

            // Файл
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = fileName ?: "Файл не выбран",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (fileName == null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { filePicker.launch(arrayOf("audio/*")) }) { Text("Выбрать") }
                if (fileName != null) {
                    TextButton(onClick = { vm.preview(channel.id) }) { Text("Прослушать") }
                    TextButton(onClick = { vm.removeFile(channel.id) }) {
                        Text(if (channel.isBuiltInBeep) "Сброс" else "Удалить")
                    }
                }
            }

            // Интервал
            DurationField(
                valueMs = channel.intervalMs,
                onChange = { vm.setInterval(channel.id, it) },
                label = "Интервал ЧЧ:ММ:СС",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Presets.forEach { (label, ms) ->
                    FilterChip(
                        selected = channel.intervalMs == ms,
                        onClick = { vm.setInterval(channel.id, ms) },
                        label = { Text(label) },
                    )
                }
            }

            // Громкость
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Громкость",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Slider(
                    value = channel.volumePercent.toFloat(),
                    onValueChange = { vm.setVolume(channel.id, it.toInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(3f),
                )
                Text(
                    text = "${channel.volumePercent}%",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ input helpers

private val Presets: List<Pair<String, Long>> = listOf(
    "1 мин" to 60_000L,
    "3 мин" to 180_000L,
    "5 мин" to 300_000L,
    "10 мин" to 600_000L,
    "15 мин" to 900_000L,
    "30 мин" to 1_800_000L,
)

@Composable
private fun DurationField(valueMs: Long, onChange: (Long) -> Unit, label: String) {
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

/** Строгий разбор «ЧЧ:ММ:СС» (ровно 8 символов). */
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

// ------------------------------------------------------------------ permissions

@Composable
private fun NotificationPermissionRequest() {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}