package com.timersound.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.timersound.AppLog

/**
 * Общее пробуждение: точный алярм WakeSchedulerRearm. Если это автозапуск
 * группы — стартует foreground-сервис с ACTION_AUTO_START (разрешено:
 * setAlarmClock даёт окно для FGS). Иначе — шлёт TICK живой сессии.
 */
class ScheduleWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val groupId = intent?.getIntExtra(TimerSoundService.EXTRA_GROUP_ID, -1) ?: -1
        AppLog.i("ScheduleWakeReceiver: wake group=$groupId")
        val svc = if (groupId >= 0) {
            TimerSoundService.commandIntent(context, TimerSoundService.ACTION_AUTO_START, groupId)
                .putExtra(
                    TimerSoundService.EXTRA_PLAN_ANCHOR,
                    intent?.getLongExtra(TimerSoundService.EXTRA_PLAN_ANCHOR, -1L)
                        ?.takeIf { it >= 0 }
                        ?: System.currentTimeMillis(),
                )
        } else {
            TimerSoundService.commandIntent(context, TimerSoundService.ACTION_TICK, -1)
        }
        runCatching { ContextCompat.startForegroundService(context, svc) }
        // Перевооружаемся на следующее событие; goAsync держит ресивер до конца IO-работы.
        val pending = goAsync()
        try {
            runCatching { WakeSchedulerRearm.rearm(context) }
        } finally {
            pending.finish()
        }
    }
}

/**
 * Пересчёт расписания при системных событиях: перезагрузка, смена времени/
 * зоны, обновление пакета.
 */
class ScheduleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCALE_CHANGED,
            -> {
                AppLog.i("ScheduleBootReceiver: ${intent.action}, rearm")
                val pending = goAsync()
                try {
                    runCatching { WakeSchedulerRearm.rearm(context) }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
