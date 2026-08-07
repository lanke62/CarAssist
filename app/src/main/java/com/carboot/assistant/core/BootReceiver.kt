package com.carboot.assistant.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.carboot.assistant.util.Logx
import com.carboot.assistant.util.Prefs

/**
 * 开机自启入口。
 * 车机 ROM 千奇百怪，这里同时监听 BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / QUICKBOOT /
 * USER_PRESENT / MY_PACKAGE_REPLACED / POWER_CONNECTED，任意一个到达都会拉起常驻服务，
 * 服务内部做了幂等，重复触发不会重复执行启动序列。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Prefs.init(context)
        Logx.init(context)
        Logx.i("收到自启广播：$action")

        val reason = when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> CarAssistService.REASON_BOOT

            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> CarAssistService.REASON_UPDATE

            else -> CarAssistService.REASON_WAKE
        }

        CarAssistService.start(context, reason)
    }
}
