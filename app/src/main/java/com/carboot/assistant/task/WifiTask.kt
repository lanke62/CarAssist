package com.carboot.assistant.task

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.carboot.assistant.core.Status
import com.carboot.assistant.util.Logx
import com.carboot.assistant.util.Shell

/**
 * 需求 2：实时判断 WiFi 开关状态（注意：判断的是"未开启"，不是"未连接"），未开启则自动打开。
 *
 * 关键点：Google 从 Android 10 起废掉了第三方应用的 setWifiEnabled()，
 * 但这个限制是按 targetSdkVersion 判定的 —— 本工程 targetSdk 锁在 28，因此该 API 依然有效。
 * 其余通道作为兜底。
 */
class WifiTask(private val ctx: Context) {

    private val wm: WifiManager? =
        ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var lastAttemptAt = 0L
    private val cooldownMs = 15_000L
    private var panelOpened = false

    fun isWifiOn(): Boolean = runCatching {
        val m = wm ?: return false
        m.isWifiEnabled || m.wifiState == WifiManager.WIFI_STATE_ENABLING
    }.getOrDefault(false)

    /**
     * @return true 表示 WiFi 已处于开启状态
     */
    fun ensure(): Boolean {
        val m = wm
        if (m == null) {
            Status.set(Status.wifi, Status.Level.FAIL, "无 WiFi 服务")
            return false
        }

        if (isWifiOn()) {
            panelOpened = false
            Status.set(Status.wifi, Status.Level.OK, "WiFi 已开启")
            return true
        }

        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < cooldownMs) {
            Status.set(Status.wifi, Status.Level.WARN, "WiFi 关闭，开启中…")
            return false
        }
        lastAttemptAt = now

        Logx.w("检测到 WiFi 未开启，开始自动开启")

        // 通道 1：标准 API（targetSdk<=28 时对第三方应用仍然放行）
        val direct = runCatching { m.setWifiEnabled(true) }.getOrDefault(false)
        if (direct) {
            Logx.i("setWifiEnabled(true) 下发成功")
            Status.set(Status.wifi, Status.Level.WARN, "已下发开启指令")
            return false
        }

        // 通道 2：反射 WifiManager 隐藏方法
        val byReflect = runCatching {
            val method = WifiManager::class.java.getDeclaredMethod("setWifiEnabled", Boolean::class.javaPrimitiveType)
            method.isAccessible = true
            (method.invoke(m, true) as? Boolean) ?: false
        }.getOrDefault(false)
        if (byReflect) {
            Logx.i("反射 setWifiEnabled 成功")
            Status.set(Status.wifi, Status.Level.WARN, "已下发开启指令(反射)")
            return false
        }

        // 通道 3：shell（部分车机 ROM shell 域宽松）
        val sh = Shell.exec("svc wifi enable")
        if (sh.code == 0) {
            Logx.i("svc wifi enable 执行成功")
            Status.set(Status.wifi, Status.Level.WARN, "已下发开启指令(shell)")
            return false
        }

        // 通道 4：拉起系统 WiFi 面板，最后一层需要用户点一下
        if (!panelOpened && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            panelOpened = true
            runCatching {
                ctx.startActivity(
                    Intent(Settings.Panel.ACTION_WIFI)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                Logx.w("系统限制自动开启，已弹出 WiFi 开关面板")
            }
        }

        Status.set(Status.wifi, Status.Level.FAIL, "自动开启被系统拒绝")
        return false
    }
}
