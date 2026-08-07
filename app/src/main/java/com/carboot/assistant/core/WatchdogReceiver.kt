package com.carboot.assistant.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.carboot.assistant.util.Logx

/**
 * 看门狗：每 2 分钟由 AlarmManager 唤醒一次，确认常驻服务还活着，
 * 被系统或用户杀掉后能自愈。AlarmManager 唤醒属于系统豁免场景，不会被后台限制拦下。
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (!Status.serviceRunning) {
            Logx.w("看门狗发现服务未运行，正在恢复")
        }
        CarAssistService.start(context, CarAssistService.REASON_WATCHDOG)
        schedule(context)
    }

    companion object {
        private const val REQ = 0x5A17
        private const val INTERVAL = 2 * 60 * 1000L

        private fun pending(context: Context): PendingIntent {
            val i = Intent(context, WatchdogReceiver::class.java)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags = flags or PendingIntent.FLAG_IMMUTABLE
            }
            return PendingIntent.getBroadcast(context, REQ, i, flags)
        }

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val trigger = SystemClock.elapsedRealtime() + INTERVAL
            runCatching {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending(context))
                    else ->
                        am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending(context))
                }
            }.onFailure {
                // 没有精确闹钟权限时退化为非精确闹钟，功能不受影响
                runCatching {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending(context))
                }
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            runCatching { am.cancel(pending(context)) }
        }
    }
}
