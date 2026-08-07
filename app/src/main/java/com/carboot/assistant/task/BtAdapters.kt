package com.carboot.assistant.task

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Build
import com.carboot.assistant.util.Prefs

/**
 * 枚举车机上所有蓝牙适配器。
 *
 * 部分车机（如双模/双芯方案）会暴露多个 BluetoothAdapter，手机往往只连在
 * 其中一个上。默认只取 getDefaultAdapter() 会漏掉另一个，因此这里用反射
 * 尽力枚举：
 *   1) BluetoothAdapter.getDefaultAdapter()  —— 必定存在
 *   2) BluetoothManager.getAdapters()        —— 隐藏 API（API 31+）
 *   3) BluetoothAdapter.getAdapter(int)      —— 隐藏静态方法，按索引兜底
 *
 * 真实设备的隐藏 API 常被 ROM 拦截（日志里常见 Accessing hidden API），
 * 此时反射拿不到第二个适配器，界面就只剩「蓝牙 1」。为了不让用户干等检测，
 * 这里额外支持 [Prefs.btAdapterForceCount] 手动指定适配器数量：
 *   检测到的真实适配器照常工作；若数量不够，按索引补占位条目，
 *   保证状态栏始终出现对应开关（占位条目状态会提示「未检测到该蓝牙」）。
 *
 * 每次调用都重新枚举（读取最新 Prefs），因此用户改了数量后无需重启进程即可生效。
 */
object BtAdapters {

    data class Entry(
        val index: Int,
        val adapter: BluetoothAdapter?,
        val label: String
    )

    fun detect(ctx: Context): List<Entry> {
        val forced = Prefs.btAdapterForceCount // 0 = 自动（按检测结果）

        val list = mutableListOf<Entry>()
        val seen = mutableSetOf<String>()

        fun add(adapter: BluetoothAdapter?) {
            if (adapter == null) return
            val addr = runCatching { adapter.address }.getOrNull()
            if (addr != null && seen.contains(addr)) return
            if (addr != null) seen.add(addr)
            val idx = list.size
            list.add(Entry(idx, adapter, "蓝牙 ${idx + 1}"))
        }

        // 1) 默认适配器（必定存在）
        add(BluetoothAdapter.getDefaultAdapter())

        // 2) BluetoothManager.getAdapters()（隐藏 API）
        runCatching {
            val svc = ctx.getSystemService(Context.BLUETOOTH_SERVICE)
            val bmClass = Class.forName("android.bluetooth.BluetoothManager")
            val m = bmClass.getDeclaredMethod("getAdapters")
            m.isAccessible = true
            val res = m.invoke(svc)
            (res as? List<*>)?.forEach { a ->
                runCatching { add(a as BluetoothAdapter) }
            }
        }

        // 3) BluetoothAdapter.getAdapter(int)（隐藏静态方法）。
        //    若用户显式设了数量，则至少枚举到该数量；否则枚举到 4。
        val upper = if (forced > 0) maxOf(forced, list.size) else 4
        runCatching {
            val m = BluetoothAdapter::class.java.getDeclaredMethod(
                "getAdapter", Int::class.javaPrimitiveType
            )
            m.isAccessible = true
            // 从索引 1 开始（索引 0 即默认适配器，已在第 1 步加入）；
            // 某个索引返回 null 不代表结束，继续尝试后续索引以覆盖不同编号方案。
            for (i in 1..upper) {
                val a = m.invoke(null, i) as? BluetoothAdapter ?: continue
                add(a)
            }
        }

        // 4) 用户显式设定了数量但反射仍拿不到那么多真实适配器：
        //    补占位条目，保证 UI 始终显示对应开关。
        if (forced > list.size) {
            for (idx in list.size until forced) {
                list.add(Entry(idx, null, "蓝牙 ${idx + 1}"))
            }
        }

        if (list.isEmpty()) list.add(Entry(0, null, "蓝牙 1"))
        return list
    }
}
