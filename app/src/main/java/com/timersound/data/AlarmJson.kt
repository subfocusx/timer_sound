package com.timersound.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.timersound.model.AlarmConfig
import com.timersound.model.FolderEntry
import com.timersound.model.FolderPlayMode
import com.timersound.model.SceneMode
import com.timersound.model.SoundSourceType
import com.timersound.model.VolumeMode

/**
 * DTO для JSON-сериализации списка будильников.
 * Формат на диске не привязан жёстко к доменной модели [AlarmConfig].
 *
 * Схема v4 добавила поля источника звука (файл/папка) и режима громкости.
 * Все новые поля имеют значения по умолчанию, поэтому старый JSON (v3 и ниже)
 * читается как есть: `ignoreUnknownKeys = true` + дефолты = FILE / громкость FIXED.
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
    /** Источник звука: FILE (по умолчанию) или FOLDER. */
    val sourceType: SoundSourceType = SoundSourceType.FILE,
    /** SAF-uri дерева-папки; "" — папка не выбрана. */
    val folderUri: String = "",
    /** Отображаемое имя папки. */
    val folderName: String? = null,
    /** Кэш сканирования папки (SAF-uri + имя). */
    val folderFiles: List<FolderEntry> = emptyList(),
    /** Режим выбора файла из папки. */
    val folderPlayMode: FolderPlayMode = FolderPlayMode.RANDOM_NOREPEAT,
    /** Индекс последнего сыгранного файла папки; -1 — ещё не играли. */
    val lastFolderIndex: Int = -1,
    /** Доступ к папке отозван или папка удалена. */
    val folderAccessLost: Boolean = false,
    /** Фикс громкости или случайная в диапазоне. */
    val volumeMode: VolumeMode = VolumeMode.FIXED,
    /** Нижняя граница диапазона громкости, 0..100. */
    val volumeMin: Int = 0,
    /** Верхняя граница диапазона громкости, 0..100. */
    val volumeMax: Int = 100,
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
    sourceType = sourceType,
    folderUri = folderUri,
    folderName = folderName,
    folderFiles = folderFiles,
    folderPlayMode = folderPlayMode,
    lastFolderIndex = lastFolderIndex,
    folderAccessLost = folderAccessLost,
    volumeMode = volumeMode,
    volumeMin = volumeMin,
    volumeMax = volumeMax,
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
    sourceType = sourceType,
    folderUri = folderUri,
    folderName = folderName,
    folderFiles = folderFiles,
    folderPlayMode = folderPlayMode,
    lastFolderIndex = lastFolderIndex,
    folderAccessLost = folderAccessLost,
    volumeMode = volumeMode,
    volumeMin = volumeMin,
    volumeMax = volumeMax,
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

/** DTO группы будильников (схема v3; будильники внутри — схема v4). */
@Serializable
data class GroupDto(
    val id: Int,
    val name: String,
    val alarms: List<AlarmDto> = emptyList(),
    val enabled: Boolean = true,
    val weekdays: Int = 0,
    val autoStopMs: Long = 0L,
    val maxTotalFires: Int = 0,
)

/** Обёртка списка групп. */
@Serializable
data class GroupsListDto(
    val groups: List<GroupDto>,
    val nextGroupId: Int = 0,
)

internal fun com.timersound.model.AlarmGroup.toGroupDto(): GroupDto = GroupDto(
    id = id,
    name = name,
    alarms = alarms.map { it.toDto() },
    enabled = enabled,
    weekdays = weekdays,
    autoStopMs = autoStopMs,
    maxTotalFires = maxTotalFiresPerSession,
)

internal fun GroupDto.toAlarmGroup(): com.timersound.model.AlarmGroup =
    com.timersound.model.AlarmGroup(
        id = id,
        name = name,
        alarms = alarms.map { it.toAlarmConfig() },
        enabled = enabled,
        weekdays = weekdays,
        autoStopMs = autoStopMs,
        maxTotalFiresPerSession = maxTotalFires,
    )

internal val groupsJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
    encodeDefaults = true
}
