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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /**
     * Пересчитать и переставить общий будильник. Неблокирующий: уходит
     * в IO-скоп, onReceive держит goAsync() до конца работы.
     * Возвращает необязательно: для тестов есть [rearmBlocking].
     */
    fun rearm(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching { rearmBlocking(appContext) }
        }
    }

    /**
     * Перевооружение по уже загруженному конфигу (из очереди команд сервиса
     * и ViewModel): НЕ читает DataStore, поэтому нет второго инстанса
     * DataStore на тот же файл (иначе — взаимоблокировка) и нет гонки
     * с неприменённой командой.
     */
    fun rearmWith(context: Context, app: com.timersound.model.AppConfig) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching { rearmBlockingWith(appContext, app) }
        }
    }
    /** Синхронная версия для ресиверов и тестов (читает DataStore сама). */
    suspend fun rearmBlocking(context: Context) {
        val prefs = PreferencesRepository(context)
        rearmBlockingWith(context, prefs.appConfig.first())
    }

    /** Синхронная версия по готовому конфигу: гонки с очередью команд нет. */
    suspend fun rearmBlockingWith(context: Context, app: com.timersound.model.AppConfig) {
        val manager = runCatching {
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        }.getOrNull() ?: return
        val nowWallRead = System.currentTimeMillis()
        val autos = app.groups.mapNotNull { g ->
            WakeScheduler.nextAutoStartWall(g, nowWallRead)?.let { it to g.id }
        }
        val sessionNext = TimerStateHolder.groups.value.values.mapNotNull { it.nextFireElapsedMs }
        val autoWalls = autos.map { it.first }
        val autoGroups = autos.toMap()
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
        // Якорь = плановое wall-время автозапуска, но ТОЛЬКО если будит именно
        // автозапуск (его wall раньше ближайшего события сессии). Иначе ресивер
        // получил бы чужую группу и стартовал её посреди чужого тика.
        val nearestAuto = autoWalls.minOrNull()
        val nearestSession = sessionNext.filter { it > nowElapsed }.minOrNull()
        val autoWins = nearestAuto != null &&
            (nearestSession == null || nearestAuto + (nowElapsed - nowWall) <= nearestSession)
        val anchorWall = if (autoWins) nearestAuto else null
        val anchorGroup = anchorWall?.let { w ->
            autoGroups.entries.firstOrNull { it.key == w }?.value
        }
        try {
            if (canSchedule(manager)) {
                manager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerWall, null),
                    wakePendingIntent(context, anchorGroup, anchorWall),
                )
                AppLog.i("WakeSchedulerRearm: armed wall=$triggerWall group=$anchorGroup anchor=$anchorWall")
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

    fun wakePendingIntent(context: Context, anchorGroupId: Int? = null, planAnchorWallMs: Long? = null): PendingIntent {
        val intent = Intent(context, ScheduleWakeReceiver::class.java).setAction(ACTION_WAKE)
        if (anchorGroupId != null) {
            intent.putExtra(TimerSoundService.EXTRA_GROUP_ID, anchorGroupId)
        }
        if (planAnchorWallMs != null) {
            intent.putExtra(TimerSoundService.EXTRA_PLAN_ANCHOR, planAnchorWallMs)
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
