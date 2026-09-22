package com.timersound.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.timersound.model.AlarmConfig
import com.timersound.model.SceneMode

/**
 * DTO для JSON-сериализации списка будильников.
 * Формат на диске не привязан жёстко к доменной модели [AlarmConfig].
 */
@Serializable
data class AlarmDto(
    val id: Int,
    val name: String,
    val fileUri: String,
    val fileName: String? = null,
    val mode: SceneMode = SceneMode.REPEAT,
    val intervalMs: Long,
    val startMinutes: Int? = null,
    val endMinutes: Int? = null,
    val launchCount: Int = 0,
    val volumePercent: Int,
    val enabled: Boolean,
)

/** Обёртка для сериализации списка (kotlinx.serialization требует именованный тип для List). */
@Serializable
data class AlarmListDto(
    val alarms: List<AlarmDto>,
)

internal fun AlarmConfig.toDto(): AlarmDto = AlarmDto(
    id = id,
    name = name,
    fileUri = fileUri,
    fileName = fileName,
    mode = mode,
    intervalMs = intervalMs,
    startMinutes = startMinutes,
    endMinutes = endMinutes,
    launchCount = launchCount,
    volumePercent = volumePercent,
    enabled = enabled,
)

internal fun AlarmDto.toAlarmConfig(): AlarmConfig = AlarmConfig(
    id = id,
    name = name,
    fileUri = fileUri,
    fileName = fileName,
    mode = mode,
    intervalMs = intervalMs,
    startMinutes = startMinutes,
    endMinutes = endMinutes,
    launchCount = launchCount,
    volumePercent = volumePercent,
    enabled = enabled,
)

/**
 * JSON-кодек для [AlarmListDto]: игнорирует неизвестные ключи
 * (совместимость при будущем расширении), приватное пространство имён
 * — формат не конфликтует с другими сущностями.
 */
internal val alarmsJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
    encodeDefaults = true
}
