package com.timersound.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.timersound.AppLog
import com.timersound.data.PreferencesRepository
import com.timersound.timer.TimerSession
import com.timersound.timer.WakeScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Держит ОДИН ближайший PendingIntent: минимум из «следующее событие любой
 * активной сессии» и «следующий автозапуск любой армированной группы».
 *
 * Пересчёт (re-arm) при: BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED,
 * MY_PACKAGE_REPLACED, любом изменении групп/расписаний (ViewModel зовёт
 * [rearm] после мутаций).
 *
 * Запуск FGS из setAlarmClock разрешён на Android 12+: setAlarmClock —
 * это будильник (AlarmClockInfo видима пользователю), система даёт
 * временное освобождение от FGS-restrictions. Документировано поведением
 * API: exact alarm / alarm clock может стартовать foreground service.
 */
object WakeSchedulerRearm {

    const val WAKE_REQUEST_CODE = 4100
    const val ACTION_WAKE = "com.timersound.intent.SCHEDULE_WAKE"

    /**
     * Пересчитать и переставить общий будильник. Читает группы синхронно
     * (blocking): вызывается из очереди команд сервиса и из ресиверов.
     */
    fun rearm(context: Context) {
        val manager = runCatching {
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        }.getOrNull() ?: return
        val prefs = PreferencesRepository(context)
        val (sessionNext, autoWalls, autoGroups) = runBlocking {
            val app = prefs.appConfig.first()
            val nowWall = System.currentTimeMillis()
            val autos = app.groups.mapNotNull { g ->
                WakeScheduler.nextAutoStartWall(g, nowWall)?.let { it to g.id }
            }
            val sess = TimerStateHolder.groups.value.values.mapNotNull { it.nextFireElapsedMs }
            Triple(sess, autos.map { it.first }, autos.toMap())
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val wake = WakeScheduler.nextWakeUp(sessionNext, autoWalls, nowElapsed, nowWall)
        val nextElapsed = WakeScheduler.nextWakeElapsed(wake, nowElapsed, nowWall)
        val pending = wakePendingIntent(context)
        manager.cancel(pending)
        if (nextElapsed == null) {
            AppLog.i("WakeSchedulerRearm: будить нечего, снят")
            return
        }
        val triggerWall = nowWall + (nextElapsed - nowElapsed).coerceAtLeast(1_000L)
        // Якорь планового автозапуска: какая группа ближе всего.
        val anchorGroup = autoWalls.minOrNull()?.let { w ->
            autoGroups.entries.firstOrNull { it.key == w }?.value
        }
        try {
            if (canSchedule(manager)) {
                manager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerWall, null),
                    wakePendingIntent(context, anchorGroup),
                )
                AppLog.i("WakeSchedulerRearm: armed wall=$triggerWall group=$anchorGroup")
            }
        } catch (e: SecurityException) {
            AppLog.w("WakeSchedulerRearm: exact denied: ${e.message}")
        } catch (e: Exception) {
            AppLog.w("WakeSchedulerRearm: failed: ${e.message}")
        }
    }

    private fun canSchedule(manager: AlarmManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return manager.canScheduleExactAlarms()
    }

    fun wakePendingIntent(context: Context, anchorGroupId: Int? = null): PendingIntent {
        val intent = Intent(context, ScheduleWakeReceiver::class.java).setAction(ACTION_WAKE)
        if (anchorGroupId != null) {
            intent.putExtra(TimerSoundService.EXTRA_GROUP_ID, anchorGroupId)
        }
        return PendingIntent.getBroadcast(
            context, WAKE_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Чистая обёртка для тестов: минимум в elapsed-шкале. */
    internal fun pickNext(
        sessionNextElapsed: List<Long>,
        autoStartWalls: List<Long>,
        nowElapsedMs: Long,
        nowWallMs: Long,
    ): Long? {
        val wake = WakeScheduler.nextWakeUp(sessionNextElapsed, autoStartWalls, nowElapsedMs, nowWallMs)
        return WakeScheduler.nextWakeElapsed(wake, nowElapsedMs, nowWallMs)
    }
}

/** Публичная строка состояния для логов/уведомлений (те же данные, что UI). */
internal fun TimerSession.SessionSnapshot.describe(): String =
    "state=$state next=${untilNextMs} end=${untilEndMs} remain=$remainingFires"
