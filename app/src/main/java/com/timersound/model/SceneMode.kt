package com.timersound.model

import kotlinx.serialization.Serializable

/** Способ запуска звуков сценария. */
@Serializable
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
