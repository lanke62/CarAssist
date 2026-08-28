package com.carboot.assistant.task

import android.bluetooth.BluetoothAdapter
import android.content.Context
import com.carboot.assistant.util.Logx
import com.carboot.assistant.util.Prefs
import com.carboot.assistant.util.Shell
import java.io.File

/**
 * 枚举车机上所有蓝牙适配器。
 *
 * 检测通道（从「能否控制」到「仅能感知」排序）：
 *   1) [BluetoothAdapter.getDefaultAdapter]  — 必定存在，可控制
 *   2) [BluetoothManager.getAdapters]        — 隐藏 API 反射（API 31+）
 *   3) [BluetoothAdapter.getAdapter]         — 隐藏静态方法反射
 *   4) `dumpsys bluetooth_manager`           — 系统 Binder 通道，不受隐藏 API 拦截，能拿到 MAC/开关/名称
 *   5) `/sys/class/bluetooth/hci<N>/address` — **内核 sysfs 层**，直接看到物理蓝牙芯片（hci0/hci1...），
 *                                              世界可读、无需 root、无需隐藏 API，能拿到 MAC 与 hci type
 *   6) `getprop`                              — 系统属性辅助信息（默认接口名等），主要用于诊断日志
 *
 * 为什么加 sysfs：车机双蓝牙是两个独立物理芯片，内核会各注册一个 hciN 接口。
 * 即使 Java 反射和 dumpsys 都拿不到第二个，sysfs 也能从硬件层确认「车机到底有几个蓝牙芯片、各是什么 MAC」。
 *
 * 控制边界：enable/disable/connect 必须要有 [BluetoothAdapter] Java 对象（即反射成功）。
 * 检测三路（反射 + dumpsys + sysfs）交叉验证，可以 100% 列出物理蓝牙芯片；
 * 但控制仍受限于反射是否被放行（`hidden_api_policy 1`）。
 *
 * 每次调用都重新枚举（读取最新 Prefs），用户改数量/优先级后无需重启进程即可生效。
 */
object BtAdapters {

    data class Entry(
        val index: Int,
        /** 反射拿到的可控制对象；null 表示该槽位仅能「感知」不能「控制」 */
        val adapter: BluetoothAdapter?,
        val label: String,
        /** 用户优先级列表里配置的 MAC，可空 */
        val expectedMac: String? = null,
        /** 非反射方式（dumpsys/sysfs）检测到的信息，可空 */
        val info: Info? = null
    )

    /** 非反射检测到的适配器信息（dumpsys 与 sysfs 合并，按 MAC 去重） */
    data class Info(
        val mac: String,
        /** 开关状态（来自 dumpsys），null=未知 */
        val enabled: Boolean? = null,
        /** 名称（来自 dumpsys） */
        val name: String? = null,
        /** hci 类型（来自 sysfs：PRIMARY / AMP / LE 等） */
        val hciType: String? = null,
        /** 是否被 sysfs 层发现 */
        val viaSysfs: Boolean = false
    )

    private val MAC_REGEX = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

    // ───── 公开入口 ─────

    fun detect(ctx: Context): List<Entry> {
        val forced = Prefs.btAdapterForceCount
        val priorityMacs = Prefs.btAdapterPriorityMacs()

        // ───── 阶段 1：反射枚举真实适配器（可控制） ─────
        val detected = mutableListOf<BluetoothAdapter>()
        val seen = mutableSetOf<String>()

        fun add(adapter: BluetoothAdapter?) {
            if (adapter == null) return
            val addr = runCatching { adapter.address }.getOrNull()?.uppercase()
            if (addr != null && seen.contains(addr)) return
            if (addr != null) seen.add(addr)
            detected.add(adapter)
        }

        add(BluetoothAdapter.getDefaultAdapter())

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

        val upper = if (forced > 0) maxOf(forced, detected.size) else 4
        runCatching {
            val m = BluetoothAdapter::class.java.getDeclaredMethod(
                "getAdapter", Int::class.javaPrimitiveType
            )
            m.isAccessible = true
            for (i in 1..upper) {
                val a = m.invoke(null, i) as? BluetoothAdapter ?: continue
                add(a)
            }
        }

        Logx.i("【蓝牙检测】反射枚举：${detected.size} 个 → " +
            detected.joinToString { runCatching { it.address }.getOrNull() ?: "?" })

        // ───── 阶段 2：dumpsys 补偿（系统服务层） ─────
        val dumpsysMap = detectViaDumpsys().associateBy { it.mac }
        if (dumpsysMap.isNotEmpty()) {
            Logx.i("【蓝牙检测】dumpsys 发现 ${dumpsysMap.size} 个：${
                dumpsysMap.values.joinToString { "${it.mac}(${if (it.enabled == true) "ON" else if (it.enabled == false) "OFF" else "?"})" }}")
        } else {
            Logx.w("【蓝牙检测】dumpsys 未解析到适配器（可能格式特殊）")
        }

        // ───── 阶段 3：sysfs 补偿（内核硬件层，最底层） ─────
        val sysfsMap = detectViaSysfs().associateBy { it.mac }
        if (sysfsMap.isNotEmpty()) {
            Logx.i("【蓝牙检测】sysfs 发现 ${sysfsMap.size} 个 hci 接口：${
                sysfsMap.values.joinToString { "${it.mac}(${it.hciType ?: "?"})" }}")
        } else {
            Logx.w("【蓝牙检测】sysfs 未读到 hci 接口（可能无 /sys/class/bluetooth 读权限）")
        }

        // 诊断：getprop 蓝牙相关属性
        val propInfo = detectViaGetprop()
        if (propInfo.isNotBlank()) {
            Logx.i("【蓝牙检测】getprop 关键属性：$propInfo")
        }

        // 诊断：硬件层（sysfs）看到的物理蓝牙芯片数 > 反射能拿到的可控对象数，
        // 说明第二个蓝牙的隐藏 API 反射被 ROM 拦截 —— 应用无法自行控制它，
        // 必须在车机本机执行一次 adb 解锁（仅需一次，持久化）。
        if (sysfsMap.size > detected.size && detected.size <= 1) {
            Logx.w("【蓝牙检测】硬件层发现 ${sysfsMap.size} 个物理蓝牙芯片，但仅 ${detected.size} 个可被反射控制。" +
                "第二个蓝牙无法 enable/connect。请在本机执行一次（仅需一次，重启后仍生效）：\n" +
                "  adb shell settings put global hidden_api_policy 1\n" +
                "  adb shell settings put global hidden_api_policy_pre_p_apps 1\n" +
                "  adb shell settings put global hidden_api_policy_p_apps 1\n" +
                "并执行后重启车机/重装本应用，让隐藏 API 策略在进程加载前生效。")
        }

        // ───── 阶段 4：合并 dumpsys + sysfs 信息（按 MAC） ─────
        val infoMap = mutableMapOf<String, Info>()
        for ((mac, ds) in dumpsysMap) {
            infoMap[mac] = Info(mac, ds.enabled, ds.name)
        }
        for ((mac, sy) in sysfsMap) {
            val ex = infoMap[mac]
            infoMap[mac] = Info(mac, ex?.enabled, ex?.name, sy.hciType, true)
        }

        // ───── 阶段 5：按用户优先级生成结果列表 ─────
        val result = mutableListOf<Entry>()
        val usedMacs = mutableSetOf<String>()

        // 5a) 优先 MAC（按用户给定顺序）
        for (mac in priorityMacs) {
            val match = detected.firstOrNull {
                runCatching { it.address }.getOrNull()?.uppercase() == mac
            }
            val info = infoMap[mac]
            val src = when {
                match != null -> "反射可控制"
                info?.viaSysfs == true -> "sysfs(硬件层)"
                info != null -> "dumpsys(系统层)"
                else -> "未检测到"
            }
            result.add(Entry(result.size, match, "蓝牙 ${result.size + 1}", mac, info))
            usedMacs.add(mac)
            Logx.i("优先级 MAC $mac → 蓝牙 ${result.size}（$src" +
                (if (info?.hciType != null) "，hci=${info.hciType}" else "") + ")")
        }

        // 5b) 其余反射检测到的适配器（追加到末尾）
        for (adapter in detected) {
            val mac = runCatching { adapter.address }.getOrNull()?.uppercase() ?: continue
            if (mac in usedMacs) continue
            result.add(Entry(result.size, adapter, "蓝牙 ${result.size + 1}", mac, infoMap[mac]))
            usedMacs.add(mac)
        }

        // 5c) 仅 dumpsys/sysfs 发现的适配器（不在优先级、不在反射列表）
        for ((mac, info) in infoMap) {
            if (mac in usedMacs) continue
            result.add(Entry(result.size, null, "蓝牙 ${result.size + 1}", null, info))
            usedMacs.add(mac)
            Logx.i("仅 ${if (info.viaSysfs) "sysfs" else "dumpsys"} 发现的适配器追加：$mac → 蓝牙 ${result.size}")
        }

        // 5d) 强制数量的补占位条目
        if (forced > result.size) {
            for (idx in result.size until forced) {
                result.add(Entry(idx, null, "蓝牙 ${idx + 1}"))
            }
        }

        if (result.isEmpty()) result.add(Entry(0, null, "蓝牙 1"))
        return result
    }

    // ───── 检测通道实现 ─────

    /**
     * dumpsys bluetooth_manager 解析（系统服务层）。
     * 走 Binder 直连，不受隐藏 API 拦截，能拿到 MAC / 开关状态 / 名称，
     * 但拿不到 [BluetoothAdapter] 对象。
     */
    fun detectViaDumpsys(): List<Info> {
        val raw = Shell.exec("dumpsys bluetooth_manager", 2500).out
        if (raw.isBlank()) return emptyList()

        val adapters = mutableListOf<Info>()
        var currentMac: String? = null
        var currentState: Boolean? = null
        var currentName: String? = null

        fun flush() {
            if (currentMac != null && adapters.none { it.mac == currentMac }) {
                adapters.add(Info(currentMac!!, currentState, currentName))
            }
            currentMac = null; currentState = null; currentName = null
        }

        for (line in raw.lines()) {
            val t = line.trim()

            val addrM = Regex("""[Aa]ddress:\s*([0-9A-Fa-f:]{17})""").find(t)
            if (addrM != null) {
                flush()
                currentMac = addrM.groupValues[1].uppercase()
            }

            if (Regex("""State:\s*ON""", RegexOption.IGNORE_CASE).matches(t)
                || Regex("""[Ee]nabled:\s*true""").matches(t)) {
                currentState = true
            } else if (Regex("""State:\s*OFF""", RegexOption.IGNORE_CASE).matches(t)
                || Regex("""[Ee]nabled:\s*false""").matches(t)) {
                currentState = false
            }

            val nameM = Regex("""[Nn]ame:\s*(.+)""").find(t)
            if (nameM != null) {
                currentName = nameM.groupValues[1].trim()
                    .takeIf { it.isNotEmpty() && !it.startsWith("null") }
            }
        }
        flush()
        return adapters
    }

    /**
     * 内核 sysfs 层检测：枚举 /sys/class/bluetooth/hci<N>/ 目录。
     *
     * 每个物理蓝牙芯片在内核里对应一个 hciN 接口，目录下通常有：
     *   - `address` 文件 → MAC 地址
     *   - `type` 文件   → PRIMARY / AMP / LE（区分主副蓝牙）
     *   - `name` 文件   → 设备名（不一定有）
     *
     * sysfs 是内核导出的世界可读接口，普通 App 通常可直接读（无需 root、无需隐藏 API）。
     * 这是最底层的硬件检测方式，能确认「车机物理上到底有几个蓝牙芯片」。
     */
    fun detectViaSysfs(): List<Info> {
        val result = mutableListOf<Info>()
        val btDir = File("/sys/class/bluetooth")
        val hciDirs = runCatching { btDir.listFiles() }
            .getOrNull()
            ?.filter { it.isDirectory && it.name.startsWith("hci") }
            ?: emptyList()

        for (dir in hciDirs) {
            val mac = runCatching { File(dir, "address").readText().trim() }.getOrNull()
                ?.uppercase()
                ?.takeIf { MAC_REGEX.matches(it) }
            val type = runCatching { File(dir, "type").readText().trim() }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
            val name = runCatching { File(dir, "name").readText().trim() }.getOrNull()
                ?.takeIf { it.isNotEmpty() }

            if (mac != null) {
                result.add(Info(mac = mac, hciType = type, name = name, viaSysfs = true))
                Logx.i("sysfs 发现 ${dir.name}：$mac type=${type ?: "?"}")
            }
        }
        return result
    }

    /**
     * getprop 系统属性辅助检测（诊断用）。
     * 主要拿默认蓝牙接口名、蓝牙地址路径等；对「枚举多个适配器」帮助有限，
     * 但能辅助判断车机蓝牙驱动形态。
     */
    fun detectViaGetprop(): String {
        val raw = Shell.exec("getprop", 2000).out
        if (raw.isBlank()) return ""
        return raw.lines()
            .filter { it.contains("bluetooth", true) || it.contains("bt.bd", true) || it.contains("hci", true) }
            .filter { !it.contains("_hidden") }
            .joinToString(" | ") { it.trim() }
            .takeIf { it.isNotEmpty() } ?: ""
    }
}