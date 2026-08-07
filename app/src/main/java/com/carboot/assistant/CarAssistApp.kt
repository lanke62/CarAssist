package com.carboot.assistant

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.carboot.assistant.core.CarAssistService
import com.carboot.assistant.util.Logx
import com.carboot.assistant.util.Prefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CarAssistApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Logx.init(this)
        installCrashHandler()
        applyNightMode(Prefs.nightMode)

        // 进程被系统重建（低内存回收后）时，也要把常驻服务重新拉起来
        CarAssistService.start(this, CarAssistService.REASON_APP_CREATE)
    }

    /**
     * 全局崩溃兜底：把堆栈落盘到 crash.log，同时写入根目录按日归档日志，便于上车后无电脑也能复盘。
     * 注意：它只能记录、无法阻止进程死亡；真正的"不闪退"靠各处的 runCatching 与 worker 线程兜底。
     */
    private fun installCrashHandler() {
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 同步写一行致命错误到根目录归档（含应用私有 crash.log），闪退前也能留下痕迹
            Logx.e("致命崩溃 thread=${thread.name}", throwable)
            runCatching {
                val dir = getExternalFilesDir("logs") ?: File(filesDir, "logs")
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, "crash.log")
                val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                f.appendText("==== $ts thread=${thread.name} ====\n")
                f.appendText(Log.getStackTraceString(throwable) + "\n\n")
            }
            def?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun applyNightMode(mode: Int) {
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}
