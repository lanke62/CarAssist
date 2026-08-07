package com.carboot.assistant.util

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 兜底通道：无 root 时大部分命令会失败，但部分车机 ROM 的 shell 域较宽松，
 * 因此作为「尽力而为」的最后一层策略保留，失败不影响主流程。
 */
object Shell {

    data class Result(val code: Int, val out: String)

    fun exec(cmd: String, timeoutMs: Long = 3000): Result = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val sb = StringBuilder()
        val reader = BufferedReader(InputStreamReader(p.inputStream))
        val err = BufferedReader(InputStreamReader(p.errorStream))

        val worker = Thread {
            runCatching {
                reader.forEachLine { sb.append(it).append('\n') }
                err.forEachLine { sb.append(it).append('\n') }
            }
        }
        worker.start()
        worker.join(timeoutMs)

        val code = runCatching {
            val t = Thread { runCatching { p.waitFor() } }
            t.start(); t.join(timeoutMs)
            if (t.isAlive) { p.destroy(); -1 } else p.exitValue()
        }.getOrDefault(-1)

        Result(code, sb.toString().trim())
    }.getOrElse { Result(-1, it.message ?: "exec failed") }
}
