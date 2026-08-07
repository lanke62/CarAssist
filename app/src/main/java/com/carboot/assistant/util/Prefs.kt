package com.carboot.assistant.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 全部可调参数集中在这里，UI 改完立即生效，服务下一个 tick 就会读到新值。
 */
object Prefs {

    private const val FILE = "car_assist_prefs"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    // ---------------- 开关 ----------------
    /**
     * 每个蓝牙适配器独立开关：Key 为 "en_bt_<index>"。
     * 未写入过时默认开启，保证升级旧用户行为不变。
     */
    fun isBtAdapterEnabled(index: Int): Boolean =
        sp.getBoolean("en_bt_$index", true)

    fun setBtAdapterEnabled(index: Int, on: Boolean) =
        sp.edit().putBoolean("en_bt_$index", on).apply()

    /**
     * 蓝牙适配器数量：0 = 自动（按 [BtAdapters.detect] 实际检测结果，至少 1）；
     * >0 = 强制在状态栏显示这么多张蓝牙卡。车机第二个蓝牙多为隐藏适配器，
     * 检测被 ROM 拦截时，手动设为 2 即可让「蓝牙 2」开关稳定出现。范围 0~4。
     */
    var btAdapterForceCount: Int
        get() = sp.getInt("bt_force_count", 0)
        set(v) = sp.edit().putInt("bt_force_count", v.coerceIn(0, 4)).apply()

    var enableWifi: Boolean
        get() = sp.getBoolean("en_wifi", true)
        set(v) = sp.edit().putBoolean("en_wifi", v).apply()

    var enableVolume: Boolean
        get() = sp.getBoolean("en_vol", true)
        set(v) = sp.edit().putBoolean("en_vol", v).apply()

    var enableKillAmap: Boolean
        get() = sp.getBoolean("en_kill", true)
        set(v) = sp.edit().putBoolean("en_kill", v).apply()

    var enableLaunch: Boolean
        get() = sp.getBoolean("en_launch", true)
        set(v) = sp.edit().putBoolean("en_launch", v).apply()

    // ---------------- 参数 ----------------
    /** 目标音量档位（STREAM_MUSIC 绝对值） */
    var volumeLevel: Int
        get() = sp.getInt("vol_level", 10)
        set(v) = sp.edit().putInt("vol_level", v.coerceIn(0, 100)).apply()

    /** 需要拉起的目标应用包名 */
    var targetPackage: String
        get() = sp.getString("target_pkg", "com.zjinnova.zlink") ?: "com.zjinnova.zlink"
        set(v) = sp.edit().putString("target_pkg", v.trim()).apply()

    /** 需要杀掉的高德包名，逗号分隔 */
    var amapPackages: String
        get() = sp.getString("amap_pkgs", DEFAULT_AMAP) ?: DEFAULT_AMAP
        set(v) = sp.edit().putString("amap_pkgs", v.trim()).apply()

    /** 实时巡检周期（秒） */
    var loopIntervalSec: Int
        get() = sp.getInt("loop_sec", 8)
        set(v) = sp.edit().putInt("loop_sec", v.coerceIn(3, 300)).apply()

    /** 开机后延迟多少秒开始执行（等系统服务就绪） */
    var bootDelaySec: Int
        get() = sp.getInt("boot_delay", 12)
        set(v) = sp.edit().putInt("boot_delay", v.coerceIn(0, 120)).apply()

    /** 前置动作全部下发后，再延迟多少秒拉起目标应用 */
    var launchDelaySec: Int
        get() = sp.getInt("launch_delay", 6)
        set(v) = sp.edit().putInt("launch_delay", v.coerceIn(0, 120)).apply()

    /** 上次成功连接的蓝牙设备 MAC，优先回连 */
    var lastBtMac: String
        get() = sp.getString("bt_mac", "") ?: ""
        set(v) = sp.edit().putString("bt_mac", v).apply()

    /** 夜间模式：0=跟随系统 1=白天 2=夜间 */
    var nightMode: Int
        get() = sp.getInt("night_mode", 2)
        set(v) = sp.edit().putInt("night_mode", v).apply()

    fun amapList(): List<String> =
        amapPackages.split(",", "，", " ", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private const val DEFAULT_AMAP =
        "com.autonavi.amapauto,com.autonavi.minimap,com.autonavi.amapautolite"
}
