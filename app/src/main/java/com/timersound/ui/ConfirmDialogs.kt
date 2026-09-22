package com.timersound.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.timersound.model.AlarmConfig
import androidx.compose.runtime.Composable

/** Диалог подтверждения удаления одного будильника (политика delete require_confirmation). */
@Composable
fun DeleteAlarmDialog(
    alarm: AlarmConfig,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить будильник?") },
        text = { Text("Удалить «${alarm.name}»? Это действие отменить нельзя.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Удалить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

/** Диалог подтверждения удаления всех будильников (destructive accent). */
@Composable
fun DeleteAllDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить все будильники?") },
        text = { Text("Удалить все $count будильников? Это действие отменить нельзя.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Удалить все") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
