package com.timersound.model

/** Способ запуска звуков сценария. */
enum class SceneMode {
    REPEAT, ONCE_TIME, INTERVAL, RANDOM;

    /** Короткое отображаемое имя для UI. */
    val label: String
        get() = when (this) {
            REPEAT -> "Повтор"
            ONCE_TIME -> "Один раз"
            INTERVAL -> "N раз"
            RANDOM -> "Случайно"
        }
}
