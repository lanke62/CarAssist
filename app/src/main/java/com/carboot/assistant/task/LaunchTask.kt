package com.carboot.assistant.task

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.carboot.assistant.core.Status
import com.carboot.assistant.util.Logx
import com.carboot.assistant.util.Prefs
import com.carboot.assistant.util.Shell

/**
 * 需求 5：前置动作完成后自动拉起并跳转到目标应用。
 *
 * 注意：用户给出的 com.zjinnova.zlink.MyWrapperProxyApplication 是 Application 类名，
 * 真正的包名是 com.zjinnova.zlink，这里做了自动纠偏：若配置串不是已安装包名，
 * 则逐级去掉尾部片段再匹配。
 */
class LaunchTask(private val ctx: Context) {

    fun launch(): Boolean {
        val raw = Prefs.targetPackage
        val pkg = resolvePackage(raw)

        if (pkg == null) {
            Logx.e("目标应用未安装或包名无法解析：$raw")
            Status.set(Status.launch, Status.Level.FAIL, "未找到应用：$raw")
            return false
        }

        if (isForeground(pkg)) {
            Logx.i("目标应用已在前台：$pkg")
            Status.set(Status.launch, Status.Level.OK, "$pkg 已在前台")
            return true
        }

        // 通道 1：标准 Launcher Intent
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: queryMainActivity(pkg)

        if (intent != null) {
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            val ok = runCatching { ctx.startActivity(intent); true }
                .getOrElse { Logx.e("startActivity 失败", it); false }
            if (ok) {
                Logx.i("已拉起目标应用：$pkg → ${intent.component?.className ?: "launcher"}")
                Status.set(Status.launch, Status.Level.OK, "已拉起 $pkg")
                return true
            }
        } else {
            Logx.w("$pkg 没有暴露 LAUNCHER 入口，改用 monkey 通道")
        }

        // 通道 2：monkey 兜底（无需 root，能绕过部分后台启动限制）
        val r = Shell.exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1", 4000)
        if (r.code == 0 && !r.out.contains("Error", true)) {
            Logx.i("monkey 通道拉起成功：$pkg")
            Status.set(Status.launch, Status.Level.OK, "已拉起 $pkg (monkey)")
            return true
        }

        Logx.e("拉起失败：$pkg，${r.out.take(120)}")
        Status.set(Status.launch, Status.Level.FAIL, "拉起失败，请授予悬浮窗权限")
        return false
    }

    /** 把 "包名.类名" 之类的串纠偏成真实包名 */
    fun resolvePackage(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (isInstalled(s)) return s

        var cur = s
        while (cur.contains('.')) {
            cur = cur.substringBeforeLast('.')
            if (isInstalled(cur)) {
                Logx.i("包名纠偏：$s → $cur")
                return cur
            }
        }
        return null
    }

    private fun isInstalled(pkg: String): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrElse { false }

    private fun queryMainActivity(pkg: String): Intent? = runCatching {
        val query = Intent(Intent.ACTION_MAIN).setPackage(pkg)
        val list = ctx.packageManager.queryIntentActivities(query, PackageManager.MATCH_ALL)
        val info = list.firstOrNull() ?: return null
        Intent(Intent.ACTION_MAIN).setComponent(
            ComponentName(info.activityInfo.packageName, info.activityInfo.name)
        )
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun isForeground(pkg: String): Boolean = runCatching {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val top = am.runningAppProcesses?.firstOrNull {
            it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
        top?.processName?.startsWith(pkg) == true
    }.getOrDefault(false)
}
