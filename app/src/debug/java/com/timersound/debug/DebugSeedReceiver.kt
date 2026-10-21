package com.timersound.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.timersound.data.PreferencesRepository
import com.timersound.model.AlarmConfig
import com.timersound.model.AlarmGroup
import com.timersound.model.AppConfig
import com.timersound.model.Defaults
import com.timersound.model.FolderPlayMode
import com.timersound.model.SceneMode
import com.timersound.model.SoundSourceType
import com.timersound.model.VolumeMode
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
        val appCtx = context.applicationContext
        // Команды сервису из adb (сервис не exported): --es cmd start|stop|pause|resume|restart --ei group 900
        val cmd = intent?.getStringExtra("cmd")
        if (cmd != null) {
            val gid = intent.getIntExtra("group", 900)
            val action = when (cmd) {
                "start" -> com.timersound.service.TimerSoundService.ACTION_START
                "stop" -> com.timersound.service.TimerSoundService.ACTION_STOP
                "pause" -> com.timersound.service.TimerSoundService.ACTION_PAUSE
                "resume" -> com.timersound.service.TimerSoundService.ACTION_RESUME
                "restart" -> com.timersound.service.TimerSoundService.ACTION_RESTART
                "reset" -> com.timersound.service.TimerSoundService.ACTION_RESET
                else -> null
            } ?: return
            val svc = com.timersound.service.TimerSoundService.commandIntent(appCtx, action, gid)
            if (cmd == "start") androidx.core.content.ContextCompat.startForegroundService(appCtx, svc)
            else appCtx.startService(svc)
            return
        }
        val spec = intent?.getStringExtra("spec") ?: "once+1"
        // Доп. параметры папки (для ручных тестов на устройстве без UI):
        //   --es folderUri content://.../tree/...  — будильник-папка в сидированной группе
        //   --es folderMode sequential|random     — режим папки (по умолчанию random)
        //   --ei volMin 50 --ei volMax 80         — диапазон громкости (плавающая)
        val folderUri = intent?.getStringExtra("folderUri")
        val folderMode = intent?.getStringExtra("folderMode")
        val volMin = intent?.getIntExtra("volMin", -1) ?: -1
        val volMax = intent?.getIntExtra("volMax", -1) ?: -1
        val pending = goAsync()
        scope.launch {
            try {
                seed(appCtx, spec, folderUri, folderMode, volMin, volMax)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun seed(
        context: Context,
        spec: String,
        folderUri: String? = null,
        folderMode: String? = null,
        volMin: Int = -1,
        volMax: Int = -1,
    ) {
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
                p == "random" -> beep(alarmId++, "RND", SceneMode.RANDOM, (curMin + 1) % 1440)
                    .copy(endMinutes = (curMin + 3) % 1440, launchCount = 3)
                else -> null
            }
        }
        val group = AlarmGroup(
            id = 900,
            name = "TEST $spec",
            alarms = alarms + listOfNotNull(folderAlarm(9000, folderUri, folderMode, volMin, volMax)),
            enabled = true,
            weekdays = if (manual) 0 else (1 shl (day - 1)),
        )
        val cur = prefs.appConfig.first()
        val groups = cur.groups.filter { it.id != 900 } + group
        // nextGroupId не трогаем (900 вне счётчика), alarm id 100+ вне счётчика.
        prefs.saveGroups(cur.copy(groups = groups))
        val back = prefs.appConfig.first()
        android.util.Log.i("TimerSound", "DebugSeed: spec=$spec groups=${groups.size} readback=${back.groups.map { it.id }}")
        com.timersound.service.WakeSchedulerRearm.rearmWith(context, cur.copy(groups = groups))
    }

    private fun beep(id: Int, name: String, mode: SceneMode, startMin: Int?): AlarmConfig =
        AlarmConfig(
            id = id, name = name, fileUri = Defaults.BUILT_IN_BEEP,
            mode = mode, intervalMs = Defaults.DEFAULT_INTERVAL_MS,
            startMinutes = startMin, volumePercent = 80, enabled = true,
        )

    /**
     * Будильник-папка для ручных тестов: кэш файлов пустой — сервис пересканирует
     * папку при старте группы (и покажет «доступ потерян», если прав нет).
     */
    private fun folderAlarm(
        id: Int,
        folderUri: String?,
        folderMode: String?,
        volMin: Int,
        volMax: Int,
    ): AlarmConfig? {
        if (folderUri.isNullOrEmpty()) return null
        val mode = if (folderMode == "sequential") {
            FolderPlayMode.SEQUENTIAL
        } else {
            FolderPlayMode.RANDOM_NOREPEAT
        }
        val ranged = volMin in 0..100 && volMax in 0..100
        return AlarmConfig(
            id = id,
            name = "FOLDER",
            fileUri = "",
            mode = SceneMode.REPEAT,
            intervalMs = 15_000L,
            volumePercent = 80,
            enabled = true,
            sourceType = SoundSourceType.FOLDER,
            folderUri = folderUri,
            folderName = folderUri.substringAfterLast('/').substringAfterLast(':'),
            folderPlayMode = mode,
            volumeMode = if (ranged) VolumeMode.RANDOM_RANGE else VolumeMode.FIXED,
            volumeMin = if (ranged) minOf(volMin, volMax) else 0,
            volumeMax = if (ranged) maxOf(volMin, volMax) else 100,
        )
    }

    private fun todayIso(): Int {
        val cal = java.util.Calendar.getInstance()
        return ((cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7) + 1
    }

    companion object {
        const val ACTION_SEED = "com.timersound.debug.SEED"
    }
}
