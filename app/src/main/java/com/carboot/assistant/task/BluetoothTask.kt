package com.carboot.assistant.task

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.carboot.assistant.core.Status
import com.carboot.assistant.util.Logx
import com.carboot.assistant.util.Prefs

/**
 * 需求 1：实时判断各蓝牙适配器是否连接了设备，未连接则主动回连。
 *
 * 车机可能存在多个蓝牙适配器（双模/双芯），手机往往只连在其中之一上。
 * 因此这里为每个适配器维护独立状态和开关：只要【任一】启用中的适配器
 * 连上设备，整体即视为蓝牙正常。
 *
 * 非 root / 非系统签名下，主动发起 A2DP / HFP 连接必须走隐藏 API 反射，
 * 因此做了 5 级降级策略，只要有一级成功就算下发成功。
 */
@SuppressLint("MissingPermission")
class BluetoothTask(private val ctx: Context) {

    /**
     * 当前要处理的蓝牙适配器列表。用 get() 而非缓存字段，
     * 这样用户在「参数设置」里改了适配器数量后，下一次巡检/启动序列
     * 就能立即纳入「蓝牙 2」等新增适配器，无需重启进程。
     */
    private val adapters: List<BtAdapters.Entry> get() = BtAdapters.detect(ctx)

    private data class ProxyPair(
        var a2dp: BluetoothProfile? = null,
        var headset: BluetoothProfile? = null
    )

    private val proxies = mutableMapOf<Int, ProxyPair>()

    private var lastAttemptAt = 0L
    private var attemptRound = 0

    /** 连接尝试冷却，避免每个 tick 都狂发连接请求把协议栈打崩 */
    private val cooldownMs get() = (20_000L + attemptRound.coerceAtMost(5) * 10_000L)

    /** 是否有任一适配器处于"启用"状态 */
    fun hasEnabledAdapter(): Boolean =
        adapters.any { Prefs.isBtAdapterEnabled(it.index) }

    fun bindProfiles() {
        for (a in adapters) {
            val adapter = a.adapter ?: continue
            val idx = a.index
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val cur = proxies.getOrPut(idx) { ProxyPair() }
                    proxies[idx] = when (profile) {
                        BluetoothProfile.A2DP -> cur.copy(a2dp = proxy)
                        BluetoothProfile.HEADSET -> cur.copy(headset = proxy)
                        else -> cur
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    val cur = proxies[idx] ?: return
                    proxies[idx] = when (profile) {
                        BluetoothProfile.A2DP -> cur.copy(a2dp = null)
                        BluetoothProfile.HEADSET -> cur.copy(headset = null)
                        else -> cur
                    }
                }
            }
            runCatching { adapter.getProfileProxy(ctx, listener, BluetoothProfile.A2DP) }
            runCatching { adapter.getProfileProxy(ctx, listener, BluetoothProfile.HEADSET) }
        }
    }

    fun release() {
        for (a in adapters) {
            val adapter = a.adapter ?: continue
            runCatching { proxies[a.index]?.a2dp?.let { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) } }
            runCatching { proxies[a.index]?.headset?.let { adapter.closeProfileProxy(BluetoothProfile.HEADSET, it) } }
        }
        proxies.clear()
    }

    /**
     * 核心入口：逐个处理启用的适配器，并刷新聚合状态。
     * @return true 表示当前有适配器已连接或本轮成功下发了连接请求
     */
    fun ensure(): Boolean {
        var anyConnected = false
        var anyAttempted = false

        for (a in adapters) {
            if (!Prefs.isBtAdapterEnabled(a.index)) {
                Status.btItems[a.index] = Status.Item(Status.Level.IDLE, "已关闭该蓝牙")
                continue
            }
            val (connected, attempted) = handleAdapter(a)
            if (connected) anyConnected = true
            if (attempted) anyAttempted = true
        }

        val lvl = when {
            anyConnected -> Status.Level.OK
            hasEnabledAdapter() && anyAttempted -> Status.Level.WARN
            hasEnabledAdapter() -> Status.Level.WARN
            else -> Status.Level.IDLE
        }
        val detail = adapters
            .filter { Prefs.isBtAdapterEnabled(it.index) }
            .joinToString("；") { "${it.label}：${Status.btItems[it.index]?.detail ?: "?"}" }
        Status.set(
            Status.bluetooth,
            lvl,
            if (detail.isBlank()) "未启用任何蓝牙" else detail
        )
        return anyConnected || anyAttempted
    }

    private fun handleAdapter(a: BtAdapters.Entry): Pair<Boolean, Boolean> {
        val adapter = a.adapter
        if (adapter == null) {
            Status.btItems[a.index] = Status.Item(
                Status.Level.FAIL,
                "未检测到该蓝牙（可能隐藏API被拦截，或数量设置偏大）"
            )
            return false to false
        }

        if (!adapter.isEnabled) {
            Logx.w("${a.label} 未开启，尝试开启")
            val ok = runCatching { adapter.enable() }.getOrDefault(false)
            Status.btItems[a.index] = Status.Item(
                if (ok) Status.Level.WARN else Status.Level.FAIL,
                if (ok) "正在开启…" else "开启被系统拒绝"
            )
            return false to false
        }

        if (isAdapterConnected(a)) {
            attemptRound = 0
            val name = connectedName(a)
            Status.btItems[a.index] = Status.Item(Status.Level.OK, "已连接 $name")
            return true to false
        }

        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < cooldownMs) {
            Status.btItems[a.index] = Status.Item(Status.Level.WARN, "未连接，冷却中…")
            return false to false
        }
        lastAttemptAt = now
        attemptRound++

        val target = pickTarget(adapter)
        if (target == null) {
            Status.btItems[a.index] = Status.Item(Status.Level.FAIL, "无已配对设备，请先手动配对")
            Logx.w("${a.label} 未连接，但没有任何已配对设备，无法自动回连")
            return false to false
        }

        Logx.i("${a.label} 主动回连：${safeName(target)} / ${target.address}")
        val ok = connectDevice(a, target)
        Status.btItems[a.index] = Status.Item(
            if (ok) Status.Level.WARN else Status.Level.FAIL,
            if (ok) "已下发连接：${safeName(target)}" else "连接下发失败（隐藏API受限）"
        )
        return false to ok
    }

    /** 该适配器是否已连接任意设备（A2DP / HFP / 已建立 ACL 的任意 profile） */
    private fun isAdapterConnected(a: BtAdapters.Entry): Boolean {
        val adapter = a.adapter ?: return false
        if (!adapter.isEnabled) return false

        val profiles = intArrayOf(
            BluetoothProfile.A2DP,
            BluetoothProfile.HEADSET,
            BluetoothProfile.GATT,
            BluetoothProfile.GATT_SERVER
        )
        for (p in profiles) {
            val st = runCatching { adapter.getProfileConnectionState(p) }
                .getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
            if (st == BluetoothProfile.STATE_CONNECTED || st == BluetoothProfile.STATE_CONNECTING) return true
        }

        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        for (d in bonded) if (isDeviceConnected(d)) return true
        return false
    }

    private fun connectedName(a: BtAdapters.Entry): String {
        val adapter = a.adapter ?: return "设备"
        val d = proxies[a.index]?.a2dp?.connectedDevices?.firstOrNull()
            ?: proxies[a.index]?.headset?.connectedDevices?.firstOrNull()
            ?: adapter.bondedDevices.firstOrNull { isDeviceConnected(it) }
        return d?.let { safeName(it) } ?: "设备"
    }

    private fun safeName(d: BluetoothDevice): String =
        runCatching { d.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: d.address

    /** 优先级：上次成功的 MAC > 手机类设备 > 音频类设备 > 第一个已配对设备 */
    private fun pickTarget(adapter: BluetoothAdapter): BluetoothDevice? {
        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty().toList()
        if (bonded.isEmpty()) return null

        Prefs.lastBtMac.takeIf { it.isNotEmpty() }?.let { mac ->
            bonded.firstOrNull { it.address.equals(mac, true) }?.let { return it }
        }

        val byClass = bonded.sortedByDescending { d ->
            val major = runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull()
            when (major) {
                BluetoothClass.Device.Major.PHONE -> 3
                BluetoothClass.Device.Major.AUDIO_VIDEO -> 2
                BluetoothClass.Device.Major.COMPUTER -> 1
                else -> 0
            }
        }
        return byClass[(attemptRound - 1).coerceAtLeast(0) % byClass.size]
    }

    /** 5 级降级连接策略 */
    private fun connectDevice(a: BtAdapters.Entry, device: BluetoothDevice): Boolean {
        val pair = proxies[a.index] ?: ProxyPair()
        var success = false

        // Lv1：先把连接策略打开，否则协议栈会直接拒绝
        allowConnection(pair.a2dp, device)
        allowConnection(pair.headset, device)

        // Lv2：A2DP.connect()
        if (invokeConnect(pair.a2dp, device)) {
            Logx.i("A2DP.connect 下发成功")
            success = true
        }

        // Lv3：HEADSET.connect()
        if (invokeConnect(pair.headset, device)) {
            Logx.i("HEADSET.connect 下发成功")
            success = true
        }

        // Lv4：BluetoothDevice.connect()（Android 11+ 隐藏 API）
        if (!success) {
            success = runCatching {
                val m = BluetoothDevice::class.java.getDeclaredMethod("connect")
                m.isAccessible = true
                m.invoke(device)
                Logx.i("BluetoothDevice.connect 下发成功")
                true
            }.getOrElse { false }
        }

        // Lv5：触发 SDP 查询，部分 ROM 会因此自动发起回连
        if (!success) {
            runCatching {
                device.fetchUuidsWithSdp()
                Logx.w("隐藏 API 全部受限，已改用 SDP 探测触发系统自动回连")
            }
        }
        return success
    }

    private fun allowConnection(proxy: BluetoothProfile?, device: BluetoothDevice) {
        proxy ?: return
        // Android 11 之前叫 setPriority(PRIORITY_AUTO_CONNECT=1000)
        runCatching {
            val m = proxy.javaClass.getMethod("setPriority", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(proxy, device, 1000)
        }
        // Android 11 起改名 setConnectionPolicy(CONNECTION_POLICY_ALLOWED=100)
        runCatching {
            val m = proxy.javaClass.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(proxy, device, 100)
        }
    }

    private fun invokeConnect(proxy: BluetoothProfile?, device: BluetoothDevice): Boolean {
        proxy ?: return false
        return runCatching {
            val m = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
            m.isAccessible = true
            (m.invoke(proxy, device) as? Boolean) ?: false
        }.getOrElse { false }
    }

    private fun isDeviceConnected(device: BluetoothDevice): Boolean = runCatching {
        val m = BluetoothDevice::class.java.getDeclaredMethod("isConnected")
        m.isAccessible = true
        m.invoke(device) as Boolean
    }.getOrDefault(false)
}
