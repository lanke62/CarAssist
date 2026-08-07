package com.carboot.assistant.util

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 轻量日志中枢：内存环形缓冲 + 应用私有文件落盘 + 设备根目录按日归档 + 本地广播实时推送 UI。
 *
 * 关键约束：每一条日志都【同步、立即】写入磁盘（appendText 内部开-写-关，天然落盘），
 * 这样即使下一步瞬间闪退，也不会丢失调试所需的"最后一步"记录。
 *
 * 根目录归档：/CarAssist/logs/yyyy-MM-dd.log，随应用启动清理 7 天前的旧日志，
 * 避免常年不重启的车机存储被无限增长的历史日志撑爆。
 */
object Logx {

    const val ACTION_LOG = "com.carboot.assistant.LOG"
    const val ACTION_STATE = "com.carboot.assistant.STATE"

    private const val TAG = "CarAssist"
    private const val MAX_MEMORY_LINES = 400
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val ROOT_LOG_DIR = "CarAssist/logs"
    private const val LOG_KEEP_DAYS = 7L

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private val buffer = ArrayDeque<String>()
    private var logFile: File? = null
    private var rootLogDir: File? = null
    private var todayFile: File? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        runCatching {
            val dir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            logFile = File(dir, "car-assist.log")
        }
        setupRootLog()
    }

    /** 尝试初始化/重试根目录归档。权限授予后调用可让日志落地到设备根目录 */
    fun ensureRootLog() {
        if (rootLogDir != null) return
        setupRootLog()
    }

    private fun setupRootLog() {
        runCatching {
            val root = Environment.getExternalStorageDirectory()
            val rdir = File(root, ROOT_LOG_DIR)
            if (rdir.exists() || rdir.mkdirs()) {
                rootLogDir = rdir
                pruneRootLogs()
                todayFile = File(rdir, "${dayFmt.format(Date())}.log")
                Logx.i("日志归档目录：${rdir.absolutePath}（保留 ${LOG_KEEP_DAYS} 天）")
            }
        }.onFailure {
            // 根目录不可写（权限未授予等）时静默降级到应用私有目录，不影响主功能
            Logx.w("根目录日志归档不可用，已降级到应用私有目录")
        }
    }

    fun i(msg: String) = write("I", msg)
    fun w(msg: String) = write("W", msg)
    fun e(msg: String, tr: Throwable? = null) =
        write("E", if (tr == null) msg else "$msg -> ${tr.javaClass.simpleName}: ${tr.message}")

    @Synchronized
    private fun write(level: String, msg: String) {
        val line = "${fmt.format(Date())} [$level] $msg"
        when (level) {
            "E" -> Log.e(TAG, msg)
            "W" -> Log.w(TAG, msg)
            else -> Log.i(TAG, msg)
        }
        buffer.addLast(line)
        while (buffer.size > MAX_MEMORY_LINES) buffer.removeFirst()

        appContext?.let { ctx ->
            LocalBroadcastManager.getInstance(ctx)
                .sendBroadcast(Intent(ACTION_LOG).putExtra("line", line))
        }

        // 应用私有目录：超限自截断
        runCatching {
            val f = logFile ?: return@runCatching
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                val keep = f.readLines().takeLast(MAX_MEMORY_LINES / 2)
                f.writeText(keep.joinToString("\n") + "\n")
            }
            f.appendText(line + "\n")
        }

        // 设备根目录：按日期归档，每步实时落盘
        runCatching {
            val f = ensureTodayFile() ?: return@runCatching
            f.appendText(line + "\n")
        }
    }

    /** 返回今天的归档文件；跨日自动切换到新文件并在切换时清理一次旧日志 */
    @Synchronized
    private fun ensureTodayFile(): File? {
        val rdir = rootLogDir ?: return null
        val name = "${dayFmt.format(Date())}.log"
        if (todayFile?.name != name) {
            todayFile = File(rdir, name)
            pruneRootLogs()
        }
        return todayFile
    }

    /** 删除 7 天前的归档日志 */
    @Synchronized
    private fun pruneRootLogs() {
        runCatching {
            val rdir = rootLogDir ?: return
            val cut = System.currentTimeMillis() - LOG_KEEP_DAYS * 24 * 3600 * 1000L
            rdir.listFiles { f -> f.isFile && f.name.endsWith(".log") }?.forEach { f ->
                val time = runCatching {
                    dayFmt.parse(f.name.removeSuffix(".log"))?.time ?: f.lastModified()
                }.getOrNull() ?: f.lastModified()
                if (time < cut) {
                    f.delete()
                    Logx.i("已清理过期日志：${f.name}")
                }
            }
        }
    }

    @Synchronized
    fun snapshot(): List<String> = buffer.toList()

    @Synchronized
    fun clear() {
        buffer.clear()
        runCatching { logFile?.writeText("") }
    }

    fun notifyState(context: Context) {
        LocalBroadcastManager.getInstance(context).sendBroadcast(Intent(ACTION_STATE))
    }
}
