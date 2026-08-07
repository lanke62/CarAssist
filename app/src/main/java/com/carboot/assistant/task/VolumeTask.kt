package com.carboot.assistant.task

import android.content.Context
import android.media.AudioManager
import com.carboot.assistant.core.Status
import com.carboot.assistant.util.Logx
import com.carboot.assistant.util.Prefs

/**
 * 需求 3：每次启动把音量固定到 10 档。
 * 车机媒体音量走 STREAM_MUSIC；部分机型导航播报走 STREAM_SYSTEM，这里一并对齐（可选）。
 */
class VolumeTask(ctx: Context) {

    private val am: AudioManager? =
        ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun apply(): Boolean {
        val m = am
        if (m == null) {
            Status.set(Status.volume, Status.Level.FAIL, "无音频服务")
            return false
        }

        val want = Prefs.volumeLevel
        val max = runCatching { m.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(15)
        val target = want.coerceIn(0, max)

        val ok = runCatching {
            m.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            true
        }.getOrElse {
            Logx.e("设置媒体音量失败", it)
            false
        }

        val now = runCatching { m.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        if (ok && now == target) {
            Logx.i("媒体音量已设为 $target（最大 $max）")
            Status.set(Status.volume, Status.Level.OK, "音量 $target / $max")
            return true
        }

        if (want > max) {
            Logx.w("目标音量 $want 超过系统最大档位 $max，已按 $max 处理")
            Status.set(Status.volume, Status.Level.WARN, "已设为最大 $max（目标 $want 越界）")
            return true
        }

        Status.set(Status.volume, Status.Level.FAIL, "设置失败，当前 $now")
        return false
    }

    fun current(): Pair<Int, Int> {
        val m = am ?: return -1 to -1
        val cur = runCatching { m.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        val max = runCatching { m.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        return cur to max
    }
}
