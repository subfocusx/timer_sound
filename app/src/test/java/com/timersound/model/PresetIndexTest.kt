package com.timersound.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Диалог показывает presetNames()[i], createGroup(i) создаёт all()[i]: соответствие имён. */
class PresetIndexTest {

    @Test
    fun presetNamesAlignWithAll() {
        val names = GroupPresets.presetNames()
        val all = GroupPresets.all(0, 0)
        assertEquals(names.size, all.size)
        // 0 = пустая; остальные имена совпадают с фабриками.
        assertEquals("Пустая", names[0])
        assertEquals("Подъём", all[1].name)
        assertEquals("Помодоро", all[2].name)
        assertEquals("Таблетки", all[3].name)
        assertEquals("Разминка каждый час", all[4].name)
        assertEquals("Случайные проверки", all[5].name)
        assertEquals("Медитация", all[6].name)
        for (i in 1 until names.size) {
            assertEquals(names[i], all[i].name)
        }
        // Каждый пресет стартует без блокировки отсутствия файла.
        for (g in all) {
            val run = g.toRunConfig(false)
            assertTrue(run.playableAlarms().isNotEmpty(), g.name)
        }
    }
}
