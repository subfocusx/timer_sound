package com.timersound.model

import kotlinx.serialization.Serializable

/**
 * Источник звука будильника.
 *
 * Один будильник = либо один файл ([FILE]), либо папка со звуками ([FOLDER]).
 * Источник задаётся НА УРОВНЕ БУДИЛЬНИКА: соседние будильники группы могут
 * использовать разные источники и не конфликтуют (ключ звука — (groupId, alarmId)).
 */
@Serializable
enum class SoundSourceType {
    FILE, FOLDER;

    val label: String
        get() = when (this) {
            FILE -> "Один файл"
            FOLDER -> "Папка"
        }
}

/**
 * Режим выбора файла из папки.
 *
 * @param RANDOM_NOREPEAT случайный файл, кроме только что игравшего (если файлов > 1).
 * @param SEQUENTIAL      по порядку: 1-й, 2-й, …, после последнего — снова 1-й.
 */
@Serializable
enum class FolderPlayMode {
    RANDOM_NOREPEAT, SEQUENTIAL;

    val label: String
        get() = when (this) {
            RANDOM_NOREPEAT -> "Случайно"
            SEQUENTIAL -> "По порядку"
        }
}

/**
 * Режим громкости будильника.
 *
 * @param FIXED         фиксированная громкость [AlarmConfig.volumePercent].
 * @param RANDOM_RANGE  случайная громкость внутри [AlarmConfig.volumeMin]..[AlarmConfig.volumeMax]
 *                      на каждом срабатывании.
 */
@Serializable
enum class VolumeMode {
    FIXED, RANDOM_RANGE;

    val label: String
        get() = when (this) {
            FIXED -> "Фикс"
            RANDOM_RANGE -> "Диапазон"
        }
}

/**
 * Один аудиофайл в папке.
 *
 * @param uri  SAF-uri документа (не абсолютный путь) — Scoped Storage.
 * @param name отображаемое имя файла (DISPLAY_NAME провайдера).
 */
@Serializable
data class FolderEntry(
    val uri: String,
    val name: String,
)
