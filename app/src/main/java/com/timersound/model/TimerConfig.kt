package com.timersound.model

import com.timersound.timer.SceneScheduler

/**
 * Настройка одного будильника.
 *
 * @param id            Стабильный монотонный идентификатор (не переиспользуется).
 * @param name          Отображаемое имя; пустое → «Будильник {id}» на рендере.
 * @param fileUri       URI выбранного аудиофайла (SAF, persistable) или [BUILT_IN_BEEP]
 *                      для встроенного сигнала, или "" если файл не задан.
 * @param fileName      Сохранённое отображаемое имя файла (ТЗ §11); если null — имени нет.
 * @param mode          Способ запуска звуков сценария.
 * @param intervalMs    Интервал между воспроизведениями, мс (минимум [MIN_INTERVAL_MS]).
 * @param startMinutes  Минуты с полуночи для запуска; null — поле не используется.
 * @param endMinutes    Минуты с полуночи, конец RANDOM-окна (exclusive).
 * @param launchCount   Количество срабатываний для INTERVAL/RANDOM; 0 — не задано.
 * @param volumePercent Громкость будильника, 0..100 (режим [VolumeMode.FIXED]).
 * @param enabled       Будильник включён в сессию таймера.
 * @param sourceType    Источник звука: один файл или папка со звуками.
 * @param folderUri     SAF-uri дерева-папки (persistable), "" — папка не выбрана.
 * @param folderName    Отображаемое имя папки.
 * @param folderFiles   Кэш сканирования папки (SAF-uri, не абсолютные пути).
 * @param folderPlayMode Режим выбора файла из папки.
 * @param lastFolderIndex Индекс последнего сыгранного файла папки (-1 — ещё не играли).
 * @param folderAccessLost Доступ к папке отозван/папка удалена (сбрасывается при выборе заново).
 * @param volumeMode    Фиксированная громкость или случайная в диапазоне.
 * @param volumeMin     Нижняя граница диапазона громкости, 0..100.
 * @param volumeMax     Верхняя граница диапазона громкости, 0..100 (min <= max).
 */
data class AlarmConfig(
    val id: Int,
    val name: String,
    val fileUri: String,
    val fileName: String? = null,
    val mode: SceneMode = SceneMode.REPEAT,
    val intervalMs: Long,
    /** Минуты с полуночи (0..1439) — когда запустить; null — не используется. */
    val startMinutes: Int? = null,
    /** Минуты с полуночи — конец RANDOM-окна (exclusive). */
    val endMinutes: Int? = null,
    /** Сколько срабатываний (INTERVAL / RANDOM); 0 — не задано. */
    val launchCount: Int = 0,
    val volumePercent: Int,
    val enabled: Boolean,
    /** Источник звука: FILE (по умолчанию) или FOLDER. */
    val sourceType: SoundSourceType = SoundSourceType.FILE,
    /** SAF-uri дерева-папки (persistable); "" — папка не выбрана. */
    val folderUri: String = "",
    /** Отображаемое имя папки (DISPLAY_NAME провайдера). */
    val folderName: String? = null,
    /** Кэш сканирования папки: SAF-uri + имя. Только первый уровень, сортировка по имени. */
    val folderFiles: List<FolderEntry> = emptyList(),
    /** Режим выбора файла из папки. */
    val folderPlayMode: FolderPlayMode = FolderPlayMode.RANDOM_NOREPEAT,
    /** Индекс последнего сыгранного файла папки; -1 — ещё не играли. */
    val lastFolderIndex: Int = -1,
    /** Доступ к папке отозван или папка удалена — нужно выбрать папку заново. */
    val folderAccessLost: Boolean = false,
    /** Фикс громкости или случайная в диапазоне. */
    val volumeMode: VolumeMode = VolumeMode.FIXED,
    /** Нижняя граница диапазона громкости, 0..100. */
    val volumeMin: Int = 0,
    /** Верхняя граница диапазона громкости, 0..100; инвариант min <= max. */
    val volumeMax: Int = 100,
) {
    /** Источник — папка? */
    val isFolderSource: Boolean get() = sourceType == SoundSourceType.FOLDER

    /** Папка выбрана (uri сохранён)? */
    val hasFolder: Boolean get() = folderUri.isNotEmpty()

    /**
     * Играбелен ли будильник: для FILE — непустой fileUri, для FOLDER — выбранная
     * папка с непустым кэшем и живым доступом.
     * Пустая папка (нет аудио) и потерянный доступ играбельности не дают —
     * старт блокируется как при пустом файле.
     */
    val hasFile: Boolean get() = when (sourceType) {
        SoundSourceType.FILE -> fileUri.isNotEmpty()
        SoundSourceType.FOLDER ->
            hasFolder && !folderAccessLost && folderFiles.isNotEmpty()
    }

    val isBuiltInBeep: Boolean get() =
        sourceType == SoundSourceType.FILE && fileUri == Defaults.BUILT_IN_BEEP

    /**
     * URI, которые будильник реально проигрывает. Для FOLDER — все файлы кэша.
     */
    fun playbackUris(): Set<String> = when (sourceType) {
        SoundSourceType.FILE -> if (fileUri.isEmpty()) emptySet() else setOf(fileUri)
        SoundSourceType.FOLDER -> folderFiles.map { it.uri }.toSet()
    }

    /**
     * Ключи документов для сравнения: один и тот же файл из папки и выбранный
     * отдельно — разные uri, но один документ (см. [SoundUri.documentKey]).
     */
    fun playbackKeys(): Set<String> = playbackUris().map { SoundUri.documentKey(it) }.toSet()

    /** Поля расписания валидны для текущего режима. */
    val scheduleValid: Boolean get() = when (mode) {
        SceneMode.REPEAT -> true
        SceneMode.ONCE_TIME -> startMinutes != null
        SceneMode.INTERVAL -> startMinutes != null && launchCount >= 1
        SceneMode.RANDOM ->
            startMinutes != null && endMinutes != null &&
            endMinutes > startMinutes && endMinutes <= Defaults.MINUTES_PER_DAY &&
            launchCount >= 1
    }
}

/** Полная конфигурация таймера. */
data class TimerConfig(
    val alarms: List<AlarmConfig>,
    /** 0 — без ограничения; иначе авто-остановка через это время от старта. */
    val autoStopMs: Long,
    /** 0 — без ограничения; иначе завершить сессию после этого числа срабатываний. */
    val maxTotalFiresPerSession: Int = 0,
    /** Плавное нарастание громкости срабатываний (false — мгновенный резкий сигнал). */
    val fadeInEnabled: Boolean = false,
) {
    /** Будильники, которые должны звучать: включены и имеют файл. */
    fun playableAlarms(): List<AlarmConfig> = alarms.filter { it.enabled && it.hasFile }

    /** Включённые, но без файла — блокируют старт. */
    fun missingFileAlarms(): List<AlarmConfig> = alarms.filter { it.enabled && !it.hasFile }

    /** Включённые с файлом, но с невалидным расписанием — блокируют старт. */
    fun invalidScheduleAlarms(): List<AlarmConfig> =
        alarms.filter { it.enabled && it.hasFile && !it.scheduleValid }

    /**
     * Пары включённых каналов, которые могут проиграть один и тот же звук
     * (одинаковый файл или пересечение по файлам папки) и чьи срабатывания
     * попадают в [windowMs] друг от друга — риск слышимого наложения/эха.
     * Только предупреждение, старт не блокирует.
     */
    fun overlappingAlarms(
        nowElapsedMs: Long,
        nowWallMs: Long,
        windowMs: Long = 2_000L,
        horizonMs: Long = 3_600_000L,
    ): List<Pair<AlarmConfig, AlarmConfig>> {
        val playable = alarms.filter { it.enabled && it.hasFile && it.scheduleValid }
        val fires: Map<Int, List<Long>> = playable.associate { alarm ->
            alarm.id to fireTimesOnHorizon(alarm, nowElapsedMs, nowWallMs, horizonMs)
        }
        val result = mutableListOf<Pair<AlarmConfig, AlarmConfig>>()
        for (i in playable.indices) {
            for (j in i + 1 until playable.size) {
                val a = playable[i]
                val b = playable[j]
                // Пересечение считаем по фактическим документам: у папки это все файлы кэша,
                // поэтому «папка vs одиночный файл из этой папки» тоже находится
                // (uri разные, документ один — см. [SoundUri.documentKey]).
                if (a.playbackKeys().intersect(b.playbackKeys()).isEmpty()) continue
                val fa = fires[a.id].orEmpty()
                val fb = fires[b.id].orEmpty()
                if (fa.isEmpty() || fb.isEmpty()) continue
                var k = 0
                var l = 0
                var overlap = false
                while (k < fa.size && l < fb.size) {
                    val diff = fa[k] - fb[l]
                    if (kotlin.math.abs(diff) <= windowMs) {
                        overlap = true
                        break
                    }
                    if (fa[k] < fb[l]) k++ else l++
                }
                if (overlap) result.add(a to b)
            }
        }
        return result
    }
}

/**
 * Моменты срабатываний канала на горизонте [horizonMs] от now.
 * Конечные режимы — через `SceneScheduler.fireTimesFor`; REPEAT — фаза
 * интервалов от `initialRepeatFire` с шагом `intervalMs`.
 *
 * Первое срабатывание REPEAT попадает в список ВСЕГДА, даже если оно дальше
 * горизонта: иначе два канала с одинаковым файлом и поздним стартом считались бы
 * «не пересекающимися» (список пуст → пара пропускалась), хотя звук совпадёт.
 */
private fun fireTimesOnHorizon(
    alarm: AlarmConfig,
    nowElapsedMs: Long,
    nowWallMs: Long,
    horizonMs: Long,
): List<Long> {
    if (alarm.mode == SceneMode.REPEAT) {
        val first = SceneScheduler.initialRepeatFire(
            nowElapsedMs, nowWallMs, alarm.startMinutes, alarm.intervalMs,
        )
        val step = alarm.intervalMs.coerceAtLeast(1_000L)
        val end = nowElapsedMs + horizonMs
        val result = mutableListOf<Long>()
        var t = first
        result.add(t)
        while (true) {
            if (t > Long.MAX_VALUE - step) break
            val next = t + step
            if (next > end) break
            t = next
            result.add(t)
        }
        return result
    }
    return SceneScheduler.fireTimesFor(alarm, nowElapsedMs, nowWallMs)
}

object Defaults {
    const val MAX_ALARMS = 100
    const val MIN_INTERVAL_MS = 1_000L
    const val MINUTES_PER_DAY = 24 * 60
    const val DEFAULT_INTERVAL_MS = 5 * 60_000L
    const val DEFAULT_VOLUME_PERCENT = 80
    const val MAX_NAME_LENGTH = 40

    /** Маркер встроенного тестового сигнала (res/raw/beep.wav). */
    const val BUILT_IN_BEEP = "@beep"

    fun newAlarm(id: Int, orderNumber: Int): AlarmConfig = AlarmConfig(
        id = id,
        name = "Будильник ${orderNumber + 1}",
        // Первый будильник — встроенный тестовый сигнал, остальные пустые.
        fileUri = if (id == 0) BUILT_IN_BEEP else "",
        intervalMs = DEFAULT_INTERVAL_MS,
        volumePercent = DEFAULT_VOLUME_PERCENT,
        enabled = id == 0,
    )

    fun firstRunConfig(): TimerConfig = TimerConfig(
        alarms = listOf(newAlarm(0, 0)),
        autoStopMs = 0L,
    )
}