package com.carboot.assistant.task

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import com.carboot.assistant.core.Status
import com.carboot.assistant.util.Logx
import com.carboot.assistant.util.Prefs
import com.carboot.assistant.util.Shell

/**
 * 需求 4：运行时终结高德地图车机版的全部进程与服务。
 *
 * 已对 Auto_9.5.0.600013_release_signed.apk 的 AndroidManifest 拆解确认，
 * 高德车机版（包名 com.autonavi.amapauto）运行时会拉起以下 4 个进程：
 *   主进程                com.autonavi.amapauto
 *   自升级子进程          com.autonavi.amapauto:selfupdate
 *   推送子进程            com.autonavi.amapauto:push
 *   定位子进程            com.autonavi.amapauto:locationservice
 * 它们都挂载在同一包名 com.autonavi.amapauto 下。无 root 下唯一可靠的官方通道是
 * ActivityManager.killBackgroundProcesses(包名)，它对"同一包名下的全部进程"
 * （含上述 3 个私有子进程）一次性生效；下面再以"按进程名精确核对"兜底，
 * 确保 3 个子进程不被漏杀，并能精准报告残留的是哪一个。
 */
class KillTask(private val ctx: Context) {

    companion object {
        /** 高德车机版包名：主进程与 3 个私有子进程都挂在此包名下 */
        const val AMAP_AUTO_PKG = "com.autonavi.amapauto"

        /**
         * 拆解 APK 得到的高德车机版「全部进程名」（主进程 + 3 个私有子进程）。
         * 运行期按此集合精确核对：只要其中任意一个仍存活，即视为清理未完成。
         */
        val KNOWN_AMAP_PROCESSES: Set<String> = setOf(
            "com.autonavi.amapauto",
            "com.autonavi.amapauto:selfupdate",
            "com.autonavi.amapauto:push",
            "com.autonavi.amapauto:locationservice"
        )
    }

    fun killAll(): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am == null) {
            Status.set(Status.killAmap, Status.Level.FAIL, "无 ActivityManager")
            return false
        }

        // 1) 汇总待清理包名：用户配置 + 已知车机版包名（去重），并过滤已安装项。
        //    始终强制包含 AMAP_AUTO_PKG，即使用户在设置里把它从列表删掉，
        //    车机版这 4 个进程也一定会被纳入清理目标。
        val pkgs = (Prefs.amapList() + AMAP_AUTO_PKG).toSet().filter { isInstalled(it) }
        if (pkgs.isEmpty()) {
            Logx.i("高德相关包均未安装，跳过清理")
            Status.set(Status.killAmap, Status.Level.OK, "未安装，无需清理")
            return true
        }

        // 2) 通道一~三：按包名清理（一次调用覆盖主进程 + 全部私有子进程）
        for (p in pkgs) {
            // 通道 1：官方后台进程清理（无 root 下唯一稳定可用的通道）
            runCatching { am.killBackgroundProcesses(p) }
                .onFailure { Logx.e("killBackgroundProcesses($p) 失败", it) }

            // 通道 2：反射 forceStopPackage（系统签名才生效，普通车机侧载通常失败）
            runCatching {
                val m = ActivityManager::class.java.getDeclaredMethod("forceStopPackage", String::class.java)
                m.isAccessible = true
                m.invoke(am, p)
                Logx.i("forceStopPackage($p) 生效")
            }

            // 通道 3：shell 兜底（部分 ROM 的 shell 域较宽松时可能生效）
            Shell.exec("am force-stop $p", 1500)
        }

        // 3) 通道四（进程级兜底）：直接枚举运行中的高德进程，按进程名逐个补刀。
        //    即便某一子进程被系统重新拉起，也能在此轮再次命中并杀掉，
        //    进一步确保 :selfupdate / :push / :locationservice 不被漏杀。
        killMatchedProcesses(am, pkgs)

        // 4) 按已知进程名精确核对残留
        val alive = aliveAmapProcesses(am, pkgs)
        return if (alive.isEmpty()) {
            Logx.i("已清理高德进程：${pkgs.joinToString()}")
            Status.set(Status.killAmap, Status.Level.OK, "已清理 ${pkgs.size} 个包")
            true
        } else {
            Logx.w("部分高德进程仍存活（可能处于前台或被系统自拉起）：${alive.joinToString()}")
            Status.set(Status.killAmap, Status.Level.WARN, "残留：${alive.joinToString()}")
            false
        }
    }

    /**
     * 枚举当前运行进程：凡 processName 命中已知高德进程名，或命中用户配置的高德包名
     * （含其私有子进程 `包名:xxx`），就按该进程所属包名再补一次 killBackgroundProcesses。
     */
    @Suppress("DEPRECATION")
    private fun killMatchedProcesses(am: ActivityManager, pkgs: Collection<String>) {
        val running = am.runningAppProcesses ?: return
        for (info in running) {
            val name = info.processName
            val base = name.substringBefore(':')
            val hit = KNOWN_AMAP_PROCESSES.contains(name) ||
                    pkgs.any { it == base || name.startsWith("$it:") }
            if (hit) {
                runCatching { am.killBackgroundProcesses(base) }
                    .onFailure { Logx.e("补杀进程 $name 失败", it) }
            }
        }
    }

    /** 返回当前仍存活的高德车机版进程名（精确匹配已知进程名 + 配置包名及其子进程） */
    @Suppress("DEPRECATION")
    private fun aliveAmapProcesses(am: ActivityManager, pkgs: Collection<String>): List<String> {
        val running = am.runningAppProcesses ?: return emptyList()
        return running.mapNotNull { info ->
            val name = info.processName
            val base = name.substringBefore(':')
            when {
                KNOWN_AMAP_PROCESSES.contains(name) -> name
                pkgs.any { it == base || name.startsWith("$it:") } -> name
                else -> null
            }
        }
    }

    private fun isInstalled(pkg: String): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrElse { it !is PackageManager.NameNotFoundException }
}
