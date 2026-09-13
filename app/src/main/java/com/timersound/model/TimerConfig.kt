package com.timersound.model

/**
 * Настройка одного звукового канала.
 *
 * @param id            0-based индекс канала (0..4), фиксировано 5 каналов.
 * @param name          Отображаемое имя, «Канал 1..5».
 * @param fileUri       URI выбранного аудиофайла (SAF, persistable) или [BUILT_IN_BEEP]
 *                      для встроенного сигнала канала 1, или "" если файл не задан.
 * @param fileName      Сохранённое отображаемое имя файла (ТЗ §11); если null — имени нет.
 * @param intervalMs    Интервал между воспроизведениями, мс (минимум [MIN_INTERVAL_MS]).
 * @param volumePercent Громкость канала, 0..100.
 * @param enabled       Канал включён в сессию таймера.
 */
data class ChannelConfig(
    val id: Int,
    val name: String,
    val fileUri: String,
    val fileName: String? = null,
    val intervalMs: Long,
    val volumePercent: Int,
    val enabled: Boolean,
) {
    val hasFile: Boolean get() = fileUri.isNotEmpty()
    val isBuiltInBeep: Boolean get() = fileUri == Defaults.BUILT_IN_BEEP
}

/** Полная конфигурация таймера. */
data class TimerConfig(
    val channels: List<ChannelConfig>,
    /** 0 — без ограничения; иначе авто-остановка через это время от старта. */
    val autoStopMs: Long,
) {
    /** Каналы, которые должны звучать: включены и имеют файл. */
    fun playableChannels(): List<ChannelConfig> = channels.filter { it.enabled && it.hasFile }

    /** Включённые, но без файла — блокируют старт. */
    fun missingFileChannels(): List<ChannelConfig> = channels.filter { it.enabled && !it.hasFile }
}

object Defaults {
    const val CHANNEL_COUNT = 5
    const val MIN_INTERVAL_MS = 1_000L
    const val DEFAULT_INTERVAL_MS = 5 * 60_000L
    const val DEFAULT_VOLUME_PERCENT = 80

    /** Маркер встроенного тестового сигнала (res/raw/beep.wav). */
    const val BUILT_IN_BEEP = "@beep"

    fun defaultChannel(id: Int): ChannelConfig = ChannelConfig(
        id = id,
        name = "Канал ${id + 1}",
        // Канал 1 — встроенный тестовый сигнал, остальные пустые.
        fileUri = if (id == 0) BUILT_IN_BEEP else "",
        intervalMs = DEFAULT_INTERVAL_MS,
        volumePercent = DEFAULT_VOLUME_PERCENT,
        enabled = id == 0,
    )

    fun defaultConfig(): TimerConfig = TimerConfig(
        channels = (0 until CHANNEL_COUNT).map(::defaultChannel),
        autoStopMs = 0L,
    )
}