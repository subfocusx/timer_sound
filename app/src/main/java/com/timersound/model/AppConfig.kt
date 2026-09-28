package com.timersound.model

/** Корневой конфиг: группы + глобальный fadeIn. */
data class AppConfig(
    val groups: List<AlarmGroup> = emptyList(),
    val nextGroupId: Int = 0,
    val fadeInEnabled: Boolean = false,
)

object GroupLimits {
    /** Лимит групп (п.1 ТЗ). */
    const val MAX_GROUPS = 30
}
