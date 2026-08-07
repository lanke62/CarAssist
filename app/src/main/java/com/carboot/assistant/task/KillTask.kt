package com.carboot.assistant.task

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import com.carboot.assistant.core.Status
import com.carboot.assistant.util.Logx
import com.carboot.assistant.util.Prefs
import com.carboot.assistant.util.Shell

/**
 * 需求 4：每次启动杀掉高德地图进程。
 *
 * 无 root 下 killBackgroundProcesses() 是官方允许的通道（需 KILL_BACKGROUND_PROCESSES 权限），
 * 能干掉目标应用的后台进程；forceStopPackage / am force-stop 需要系统权限，作为兜底尝试。
 */
class KillTask(private val ctx: Context) {

    fun killAll(): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am == null) {
            Status.set(Status.killAmap, Status.Level.FAIL, "无 ActivityManager")
            return false
        }

        val pkgs = Prefs.amapList()
        if (pkgs.isEmpty()) {
            Status.set(Status.killAmap, Status.Level.IDLE, "未配置目标包名")
            return true
        }

        val installed = pkgs.filter { isInstalled(it) }
        if (installed.isEmpty()) {
            Logx.i("高德相关包均未安装，跳过清理")
            Status.set(Status.killAmap, Status.Level.OK, "未安装，无需清理")
            return true
        }

        var killed = 0
        for (p in installed) {
            // 通道 1：官方后台进程清理
            runCatching { am.killBackgroundProcesses(p) }
                .onSuccess { killed++ }
                .onFailure { Logx.e("killBackgroundProcesses($p) 失败", it) }

            // 通道 2：反射 forceStopPackage（系统签名才生效）
            runCatching {
                val m = ActivityManager::class.java.getDeclaredMethod("forceStopPackage", String::class.java)
                m.isAccessible = true
                m.invoke(am, p)
                Logx.i("forceStopPackage($p) 生效")
            }

            // 通道 3：shell 兜底
            Shell.exec("am force-stop $p", 1500)
        }

        val stillAlive = installed.filter { isRunning(it) }
        return if (stillAlive.isEmpty()) {
            Logx.i("已清理高德进程：${installed.joinToString()}")
            Status.set(Status.killAmap, Status.Level.OK, "已清理 $killed 个包")
            true
        } else {
            Logx.w("部分高德进程仍存活（可能处于前台）：${stillAlive.joinToString()}")
            Status.set(Status.killAmap, Status.Level.WARN, "残留：${stillAlive.joinToString()}")
            false
        }
    }

    private fun isInstalled(pkg: String): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrElse { it !is PackageManager.NameNotFoundException }

    @Suppress("DEPRECATION")
    private fun isRunning(pkg: String): Boolean = runCatching {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses?.any { it.processName.startsWith(pkg) } == true
    }.getOrDefault(false)
}
