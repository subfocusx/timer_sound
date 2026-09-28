package com.timersound.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.timersound.data.PreferencesRepository
import com.timersound.model.AlarmConfig
import com.timersound.model.AlarmGroup
import com.timersound.model.AppConfig
import com.timersound.model.Defaults
import com.timersound.model.SceneMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
/**
 * DEBUG ONLY: сид тестовых групп из adb.
 *     --es spec "once+1"   # ONCE через ~1 мин от текущего времени
 *
 * spec — список через запятую:
 *   once+N      — ONCE_TIME через N минут (встроенный бип)
 *   repeat      — REPEAT каждые 15 с без старта (вечный)
 *   interval+N  — INTERVAL N раз с шагом 15 с от ближайшей минуты
 *   random      — RANDOM 3 срабатывания в окне [сейчас+30с, +150с]
 *   day=1..7    — маска дней (по умолчанию сегодня); day=0 — без расписания
 *   manual      — только ручной (weekdays=0)
 */
class DebugSeedReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent?) {
        val spec = intent?.getStringExtra("spec") ?: "once+1"
        val pending = goAsync()
        scope.launch {
            try {
                seed(context.applicationContext, spec)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun seed(context: Context, spec: String) {
        val prefs = PreferencesRepository(context)
        prefs.ensureMigrated()
        val parts = spec.split(",").map { it.trim() }
        var day = todayIso()
        var manual = false
        for (p in parts) {
            if (p.startsWith("day=")) day = p.substringAfter("day=").toIntOrNull() ?: day
            if (p == "manual") manual = true
        }
        val now = java.util.Calendar.getInstance()
        val curMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        var alarmId = 100
        val alarms = parts.mapNotNull { p ->
            when {
                p.startsWith("once+") -> {
                    val n = p.substringAfter("once+").toIntOrNull() ?: 1
                    val m = (curMin + n) % 1440
                    beep(alarmId++, "ONCE+$n", SceneMode.ONCE_TIME, m)
                }
                p == "repeat" -> beep(alarmId++, "REPEAT", SceneMode.REPEAT, null)
                    .copy(intervalMs = 15_000L)
                p.startsWith("interval+") -> {
                    val n = p.substringAfter("interval+").toIntOrNull() ?: 3
                    beep(alarmId++, "INT$n", SceneMode.INTERVAL, (curMin + 1) % 1440)
                        .copy(intervalMs = 15_000L, launchCount = n)
                }
                p == "random" -> beep(alarmId++, "RND", SceneMode.RANDOM, curMin)
                    .copy(endMinutes = (curMin + 2) % 1440, launchCount = 3)
                else -> null
            }
        }
        val group = AlarmGroup(
            id = 900,
            name = "TEST $spec",
            alarms = alarms,
            enabled = true,
            weekdays = if (manual) 0 else (1 shl (day - 1)),
        )
        val cur = prefs.appConfig.first()
        val groups = cur.groups.filter { it.id != 900 } + group
        // nextGroupId не трогаем (900 вне счётчика), alarm id 100+ вне счётчика.
        prefs.saveGroups(cur.copy(groups = groups))
        val back = prefs.appConfig.first()
        android.util.Log.i("TimerSound", "DebugSeed: spec=$spec groups=${groups.size} readback=${back.groups.map { it.id }}")
    }

    private fun beep(id: Int, name: String, mode: SceneMode, startMin: Int?): AlarmConfig =
        AlarmConfig(
            id = id, name = name, fileUri = Defaults.BUILT_IN_BEEP,
            mode = mode, intervalMs = Defaults.DEFAULT_INTERVAL_MS,
            startMinutes = startMin, volumePercent = 80, enabled = true,
        )

    private fun todayIso(): Int {
        val cal = java.util.Calendar.getInstance()
        return ((cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7) + 1
    }

    companion object {
        const val ACTION_SEED = "com.timersound.debug.SEED"
    }
}
