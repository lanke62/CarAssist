package com.carboot.assistant.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.carboot.assistant.CarAssistApp
import com.carboot.assistant.databinding.ActivityMainBinding
import com.carboot.assistant.databinding.ItemStatusCardBinding
import com.carboot.assistant.core.CarAssistService
import com.carboot.assistant.core.Status
import com.carboot.assistant.task.BtAdapters
import com.carboot.assistant.task.LaunchTask
import com.carboot.assistant.task.VolumeTask
import com.carboot.assistant.util.Logx
import com.carboot.assistant.util.Prefs

/**
 * 1280×720 横屏驾驶舱控制台。
 *
 * 布局原则（按车主反馈调整）：
 *  - 左侧：实时状态卡片（每个开关独占一行，名称完整显示）+ 大号快捷按钮，方便驾驶员盲操作；
 *  - 中间：参数设置；
 *  - 右侧：运行流程说明 + 运行日志。
 *
 * 所有能力都在常驻服务里跑，关掉界面不影响功能。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var vb: ActivityMainBinding
    private val ui = Handler(Looper.getMainLooper())

    /** 动态生成的蓝牙适配器卡片：index → binding */
    private val btCards = mutableListOf<Pair<Int, ItemStatusCardBinding>>()
    private lateinit var cardWifi: ItemStatusCardBinding
    private lateinit var cardVol: ItemStatusCardBinding
    private lateinit var cardKill: ItemStatusCardBinding
    private lateinit var cardLaunch: ItemStatusCardBinding

    private val refresh = object : Runnable {
        override fun run() {
            runCatching { renderStatus() }.onFailure { Logx.e("刷新状态异常", it) }
            ui.postDelayed(this, 1000L)
        }
    }

    /**
     * 关键修复：日志广播可能从 worker 后台线程同步派发（见 Logx.write），
     * 这里所有 UI 更新必须切回主线程，否则 TextView.setText 在后台线程触发
     * CalledFromWrongThreadException 直接杀进程。
     */
    private val localReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Logx.ACTION_LOG -> {
                    val line = intent.getStringExtra("line") ?: return
                    vb.root.post { appendLog(line) }
                }
                Logx.ACTION_STATE -> vb.root.post { renderStatus() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Logx.init(this)
        vb = ActivityMainBinding.inflate(layoutInflater)
        setContentView(vb.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        buildCards()
        bindActions()
        loadSettings()
        renderFlow()
        vb.txtLog.text = Logx.snapshot().joinToString("\n")

        CarAssistService.start(this, CarAssistService.REASON_MANUAL)

        // 若根目录日志归档未就绪（如首次未授权存储权限），补申请，授权后下次启动生效
        ensureRootLogPermission()
    }

    private fun ensureRootLogPermission() {
        if (Build.VERSION.SDK_INT > 32) return // API 33+ 由系统按 legacy 自动处理
        val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            runCatching { requestPermissions(arrayOf(perm), 1002) }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Logx.ensureRootLog()
            Logx.i("已获得存储权限，日志将归档到设备根目录 /CarAssist/logs")
        }
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            localReceiver,
            IntentFilter().apply {
                addAction(Logx.ACTION_LOG)
                addAction(Logx.ACTION_STATE)
            }
        )
        ui.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(localReceiver)
        ui.removeCallbacks(refresh)
    }

    // ---------------- 卡片 ----------------

    private fun buildCards() {
        val inf = LayoutInflater.from(this)

        // 蓝牙适配器卡片：每个适配器独占一行，独立开关（可随数量设置重建）
        buildBtCards()

        // 其余四项固定卡片
        cardWifi = ItemStatusCardBinding.inflate(inf, vb.llStatus, true)
        cardVol = ItemStatusCardBinding.inflate(inf, vb.llStatus, true)
        cardKill = ItemStatusCardBinding.inflate(inf, vb.llStatus, true)
        cardLaunch = ItemStatusCardBinding.inflate(inf, vb.llStatus, true)

        cardWifi.title.text = "WiFi 自动开启"
        cardVol.title.text = "音量锁定"
        cardKill.title.text = "清理高德进程"
        cardLaunch.title.text = "拉起目标应用"

        cardWifi.toggle.isChecked = Prefs.enableWifi
        cardVol.toggle.isChecked = Prefs.enableVolume
        cardKill.toggle.isChecked = Prefs.enableKillAmap
        cardLaunch.toggle.isChecked = Prefs.enableLaunch

        cardWifi.toggle.setOnCheckedChangeListener { _, v -> Prefs.enableWifi = v }
        cardVol.toggle.setOnCheckedChangeListener { _, v -> Prefs.enableVolume = v }
        cardKill.toggle.setOnCheckedChangeListener { _, v -> Prefs.enableKillAmap = v }
        cardLaunch.toggle.setOnCheckedChangeListener { _, v -> Prefs.enableLaunch = v }
    }

    /**
     * 构建/重建蓝牙适配器卡片。会先移除旧的蓝牙卡（保留 WiFi 等固定卡），
     * 再按 [BtAdapters.detect] 当前结果重新插入到状态列表最前面。
     * 这样用户在「参数设置」里改了蓝牙适配器数量后，状态栏可立即出现「蓝牙 2」等开关。
     */
    private fun buildBtCards() {
        // 清掉旧蓝牙卡（重建场景），不影响后面的 WiFi/音量/高德/拉起卡
        btCards.map { it.second.root }.forEach { vb.llStatus.removeView(it) }
        btCards.clear()

        val inf = LayoutInflater.from(this)
        for ((i, a) in BtAdapters.detect(this).withIndex()) {
            val b = ItemStatusCardBinding.inflate(inf, vb.llStatus, false)
            b.title.text = a.label
            b.toggle.isChecked = Prefs.isBtAdapterEnabled(a.index)
            b.toggle.setOnCheckedChangeListener { _, v -> Prefs.setBtAdapterEnabled(a.index, v) }
            // 始终插到状态列表最前面，保持「蓝牙卡在最上方」的布局
            vb.llStatus.addView(b.root, i)
            btCards.add(a.index to b)
        }
    }

    private fun renderStatus() {
        for ((idx, b) in btCards) {
            paint(b, Status.btItems[idx] ?: Status.Item(Status.Level.IDLE, "等待中"))
        }
        paint(cardWifi, Status.wifi)
        paint(cardVol, Status.volume)
        paint(cardKill, Status.killAmap)
        paint(cardLaunch, Status.launch)

        val running = Status.serviceRunning
        vb.pillService.text =
            if (running) "常驻中 · 巡检 #${Status.loopCount}" else "服务未运行"
        vb.pillService.setTextColor(color(if (running) com.carboot.assistant.R.color.ok else com.carboot.assistant.R.color.fail))

        renderEnv()
    }

    private fun paint(card: ItemStatusCardBinding, item: Status.Item) {
        val c = when (item.level) {
            Status.Level.OK -> com.carboot.assistant.R.color.ok
            Status.Level.WARN -> com.carboot.assistant.R.color.warn
            Status.Level.FAIL -> com.carboot.assistant.R.color.fail
            Status.Level.IDLE -> com.carboot.assistant.R.color.idle
        }
        val d = card.dot.background.mutate()
        d.setTint(color(c))
        card.dot.background = d
        card.detail.text = item.detail
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    // ---------------- 交互 ----------------

    private fun bindActions() {
        vb.btnRunNow.setOnClickListener {
            Logx.i("手动触发完整启动序列")
            CarAssistService.start(this, CarAssistService.REASON_RUN_NOW)
            toast("已下发执行")
        }

        vb.btnLaunch.setOnClickListener {
            Thread { LaunchTask(applicationContext).launch() }.start()
        }

        vb.btnPerm.setOnClickListener { runPermissionCheck() }

        vb.btnClearLog.setOnClickListener {
            Logx.clear()
            vb.txtLog.text = ""
        }

        vb.btnTheme.setOnClickListener {
            Prefs.nightMode = (Prefs.nightMode + 1) % 3
            CarAssistApp.applyNightMode(Prefs.nightMode)
            recreate()
        }

        vb.btnSave.setOnClickListener { saveSettings() }
    }

    private fun loadSettings() {
        vb.edVolume.setText(Prefs.volumeLevel.toString())
        vb.edTarget.setText(Prefs.targetPackage)
        vb.edAmap.setText(Prefs.amapPackages)
        vb.edLoop.setText(Prefs.loopIntervalSec.toString())
        vb.edBootDelay.setText(Prefs.bootDelaySec.toString())
        vb.edLaunchDelay.setText(Prefs.launchDelaySec.toString())
        vb.edBtCount.setText(Prefs.btAdapterForceCount.toString())
        vb.btnTheme.text = when (Prefs.nightMode) {
            1 -> "白天"
            2 -> "夜间"
            else -> "跟随系统"
        }
    }

    private fun saveSettings() {
        Prefs.volumeLevel = vb.edVolume.text.toString().toIntOrNull() ?: 10
        Prefs.targetPackage = vb.edTarget.text.toString()
        Prefs.amapPackages = vb.edAmap.text.toString()
        Prefs.loopIntervalSec = vb.edLoop.text.toString().toIntOrNull() ?: 8
        Prefs.bootDelaySec = vb.edBootDelay.text.toString().toIntOrNull() ?: 12
        Prefs.launchDelaySec = vb.edLaunchDelay.text.toString().toIntOrNull() ?: 6
        Prefs.btAdapterForceCount = vb.edBtCount.text.toString().toIntOrNull()?.coerceIn(0, 4) ?: 0
        loadSettings()
        renderFlow()
        buildBtCards() // 蓝牙数量可能变化，立即重建状态栏卡片
        Logx.i("参数已保存：音量=${Prefs.volumeLevel} 目标=${Prefs.targetPackage} 巡检=${Prefs.loopIntervalSec}s 蓝牙适配器数=${Prefs.btAdapterForceCount}")
        toast("已保存，蓝牙卡片已更新")
    }

    /** 权限自检：定位（蓝牙扫描前置）、悬浮窗（后台拉起 Activity）、电池优化白名单 */
    private fun runPermissionCheck() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.ACCESS_FINE_LOCATION

        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(this, "android.permission.BLUETOOTH_CONNECT")
                != PackageManager.PERMISSION_GRANTED
            ) need += "android.permission.BLUETOOTH_CONNECT"
        }

        if (need.isNotEmpty()) {
            runCatching { requestPermissions(need.toTypedArray(), 1001) }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            toast("请授予「悬浮窗」权限，否则开机后无法自动跳转应用")
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBattery()) {
            toast("请把本应用加入「电池优化白名单 / 不优化」以保证常驻")
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }

        toast("权限齐全 ✓")
        Logx.i("权限自检通过")
    }

    private fun isIgnoringBattery(): Boolean = runCatching {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(true)

    // ---------------- 文案渲染 ----------------

    private fun renderFlow() {
        vb.txtFlow.text = buildString {
            append("开机 / 覆盖安装 / 看门狗唤醒\n")
            append("   ↓  延迟 ${Prefs.bootDelaySec}s 等系统服务就绪\n")
            append("① 检查 WiFi 开关 → 未开启则自动打开\n")
            append("② 检查各蓝牙适配器 → 未连接则回连（任一连上即正常）\n")
            append("③ 媒体音量锁定为 ${Prefs.volumeLevel}\n")
            append("④ 杀掉高德进程：${Prefs.amapList().joinToString("、")}\n")
            append("   ↓  延迟 ${Prefs.launchDelaySec}s（拉起前再补杀一次）\n")
            append("⑤ 拉起并跳转：${Prefs.targetPackage}\n")
            append("   ↓\n")
            append("每 ${Prefs.loopIntervalSec}s 巡检蓝牙 / WiFi，异常即刻自愈；\n")
            append("同时监听系统广播，断连时秒级响应。")
        }
    }

    private fun renderEnv() {
        val ver = runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            "${pi.versionName} (code ${pi.longVersionCode})"
        }.getOrDefault("?")
        val vol = VolumeTask(this).current()
        val resolved = LaunchTask(this).resolvePackage(Prefs.targetPackage) ?: "未安装"
        val overlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            (if (Settings.canDrawOverlays(this)) "已授予" else "未授予") else "无需"
        vb.txtEnv.text = buildString {
            append("本应用版本：$ver\n")
            append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("当前媒体音量：${vol.first} / ${vol.second}\n")
            append("目标应用解析：$resolved\n")
            append("悬浮窗权限：$overlay\n")
            append("电池优化白名单：${if (isIgnoringBattery()) "已加入" else "未加入"}")
        }
    }

    private fun appendLog(line: String) {
        val old = vb.txtLog.text.toString()
        val merged = if (old.isEmpty()) line else "$old\n$line"
        vb.txtLog.text = merged.lines().takeLast(300).joinToString("\n")
        vb.logScroll.post { vb.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
