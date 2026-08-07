package com.carboot.assistant.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.carboot.assistant.R
import com.carboot.assistant.task.BluetoothTask
import com.carboot.assistant.task.KillTask
import com.carboot.assistant.task.LaunchTask
import com.carboot.assistant.task.VolumeTask
import com.carboot.assistant.task.WifiTask
import com.carboot.assistant.ui.MainActivity
import com.carboot.assistant.util.Logx
import com.carboot.assistant.util.Prefs

/**
 * 需求 6：后台静默常驻，不退出。
 *
 * 保活四件套：
 *  1) 前台服务（Android 8+ 唯一合规的长期后台通道）
 *  2) START_STICKY + onTaskRemoved 自拉起
 *  3) AlarmManager 看门狗每 2 分钟兜底
 *  4) Application.onCreate 进程重建时再拉一次
 */
class CarAssistService : Service() {

    companion object {
        const val CHANNEL_ID = "car_assist_core"
        const val NOTI_ID = 0x0A17

        const val EXTRA_REASON = "reason"
        const val REASON_BOOT = "boot"
        const val REASON_UPDATE = "update"
        const val REASON_WAKE = "wake"
        const val REASON_WATCHDOG = "watchdog"
        const val REASON_APP_CREATE = "app_create"
        const val REASON_MANUAL = "manual"
        const val REASON_RUN_NOW = "run_now"

        fun start(context: Context, reason: String) {
            val i = Intent(context, CarAssistService::class.java).putExtra(EXTRA_REASON, reason)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            }.onFailure { Logx.e("启动常驻服务失败（reason=$reason）", it) }
        }
    }

    private lateinit var worker: HandlerThread
    private lateinit var bg: Handler

    private lateinit var btTask: BluetoothTask
    private lateinit var wifiTask: WifiTask
    private lateinit var volumeTask: VolumeTask
    private lateinit var killTask: KillTask
    private lateinit var launchTask: LaunchTask

    private var wakeLock: PowerManager.WakeLock? = null
    private var looping = false
    private var startupRunning = false
    private var lastReactAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            runCatching { patrol() }.onFailure { Logx.e("巡检异常", it) }
            bg.postDelayed(this, Prefs.loopIntervalSec * 1000L)
        }
    }

    // ---------------- 生命周期 ----------------

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Logx.init(this)

        worker = HandlerThread("car-assist-worker").apply { start() }
        bg = Handler(worker.looper)
        // 关键兜底：worker 线程若抛未捕获异常（隐藏 API 在严格 ROM 上可能抛 Error），
        // 默认处理链会杀进程。这里就地兜住并续跑巡检，进程不再被单点异常击杀。
        worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { t, e ->
            Logx.e("worker 线程未捕获异常（已兜住，继续运行）", e)
            runCatching { bg.postDelayed(tick, Prefs.loopIntervalSec * 1000L) }
        }

        btTask = BluetoothTask(this)
        wifiTask = WifiTask(this)
        volumeTask = VolumeTask(this)
        killTask = KillTask(this)
        launchTask = LaunchTask(this)

        startForeground(NOTI_ID, buildNotification("正在初始化…"))
        acquireWakeLock()
        btTask.bindProfiles()
        registerSystemReceivers()
        WatchdogReceiver.schedule(this)

        Status.serviceRunning = true
        Logx.i("常驻服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: REASON_WAKE
        Logx.i("服务收到指令：$reason")

        when (reason) {
            REASON_BOOT, REASON_UPDATE, REASON_MANUAL, REASON_RUN_NOW -> {
                val delay = if (reason == REASON_BOOT) Prefs.bootDelaySec * 1000L else 0L
                scheduleStartup(delay, force = reason == REASON_RUN_NOW || reason == REASON_MANUAL)
            }
            else -> if (!Status.startupDone) scheduleStartup(1500L, force = false)
        }

        ensureLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Logx.w("任务栈被移除，服务保持常驻并自恢复")
        start(this, REASON_WATCHDOG)
    }

    override fun onDestroy() {
        Logx.w("服务被销毁，触发看门狗自恢复")
        Status.serviceRunning = false
        looping = false
        runCatching { unregisterReceiver(systemReceiver) }
        runCatching { btTask.release() }
        runCatching { bg.removeCallbacksAndMessages(null) }
        runCatching { worker.quitSafely() }
        releaseWakeLock()
        WatchdogReceiver.schedule(this)
        start(applicationContext, REASON_WATCHDOG)
        super.onDestroy()
    }

    // ---------------- 启动序列（需求 5 的执行顺序保证） ----------------

    private fun scheduleStartup(delayMs: Long, force: Boolean) {
        if (startupRunning) return
        if (Status.startupDone && !force) return
        startupRunning = true
        // 防御：启动序列在 worker 线程执行，任何未捕获异常都会杀进程，必须兜住
        bg.postDelayed({ runCatching { runStartupSequence() }.onFailure { Logx.e("启动序列异常", it); startupRunning = false } }, delayMs)
        if (delayMs > 0) Logx.i("启动序列将在 ${delayMs / 1000}s 后执行（等待系统服务就绪）")
    }

    private fun runStartupSequence() {
        Logx.i("=== 启动序列开始 ===")
        updateNotification("启动序列执行中…")

        // ① WiFi：未开启则打开
        runStep("WiFi") {
            if (Prefs.enableWifi) wifiTask.ensure()
            else Status.set(Status.wifi, Status.Level.IDLE, "已关闭该功能")
        }
        sleep(800)

        // ② 蓝牙：逐个适配器检查，未连接则主动回连
        runStep("蓝牙") {
            if (btTask.hasEnabledAdapter()) {
                btTask.bindProfiles()
                sleep(1200) // 等 profile proxy 绑定完成
                btTask.ensure()
            } else Status.set(Status.bluetooth, Status.Level.IDLE, "蓝牙管理已关闭")
        }
        sleep(500)

        // ③ 音量固定
        runStep("音量") {
            if (Prefs.enableVolume) volumeTask.apply()
            else Status.set(Status.volume, Status.Level.IDLE, "已关闭该功能")
        }
        sleep(300)

        // ④ 杀高德
        runStep("清理高德") {
            if (Prefs.enableKillAmap) killTask.killAll()
            else Status.set(Status.killAmap, Status.Level.IDLE, "已关闭该功能")
        }

        // ⑤ 拉起目标应用
        runStep("拉起应用") {
            if (Prefs.enableLaunch) {
                val d = Prefs.launchDelaySec * 1000L
                if (d > 0) {
                    Logx.i("前置动作完成，${d / 1000}s 后拉起目标应用")
                    sleep(d)
                }
                // 拉起前再补一刀，防止高德在这期间被系统重新拉起来
                if (Prefs.enableKillAmap) killTask.killAll()
                launchTask.launch()
            } else Status.set(Status.launch, Status.Level.IDLE, "已关闭该功能")
        }

        Status.startupDone = true
        startupRunning = false
        Logx.i("=== 启动序列完成，转入实时巡检 ===")
        Logx.notifyState(this)
        updateNotification(summary())
    }

    /** 启动序列专用：每一步都落盘记录开始/完成，单步异常不阻断后续步骤 */
    private fun runStep(name: String, block: () -> Unit) {
        try {
            Logx.i("▶ 步骤[$name] 开始")
            block()
            Logx.i("✓ 步骤[$name] 完成")
        } catch (t: Throwable) {
            Logx.e("✗ 步骤[$name] 异常", t)
        }
    }

    /** 巡检专用：静默吞掉单步异常，避免污染日志 */
    private fun safeStep(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Logx.e("步骤[$name] 异常", t)
        }
    }

    // ---------------- 实时巡检 ----------------

    private fun ensureLoop() {
        if (looping) return
        looping = true
        bg.removeCallbacks(tick)
        bg.postDelayed(tick, 3000L)
    }

    private fun patrol() {
        Status.loopCount++
        Status.lastTickAt = System.currentTimeMillis()

        if (Prefs.enableWifi) safeStep("WiFi巡检") { wifiTask.ensure() }
        if (btTask.hasEnabledAdapter()) safeStep("蓝牙巡检") { btTask.ensure() }

        updateNotification(summary())
        Logx.notifyState(this)
    }

    /** 系统事件到达时立即复检一次（比轮询更快），带 3 秒防抖 */
    private fun reactNow(tag: String) {
        val now = System.currentTimeMillis()
        if (now - lastReactAt < 3000L) return
        lastReactAt = now
        Logx.i("系统事件触发即时复检：$tag")
        bg.postDelayed({ runCatching { patrol() }.onFailure { Logx.e("即时复检异常", it) } }, 1200L)
    }

    // ---------------- 系统广播 ----------------

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION -> reactNow("wifi-state")
                BluetoothAdapter.ACTION_STATE_CHANGED -> reactNow("bt-adapter")
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> reactNow("bt-disconnect")
                BluetoothDevice.ACTION_ACL_CONNECTED -> reactNow("bt-connect")
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> reactNow("bt-profile")
                Intent.ACTION_SCREEN_ON -> reactNow("screen-on")
            }
        }
    }

    private fun registerSystemReceivers() {
        val f = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching { registerReceiver(systemReceiver, f) }
            .onFailure { Logx.e("注册系统广播失败", it) }
    }

    // ---------------- 前台通知 ----------------

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    // IMPORTANCE_MIN：无声、无横幅、状态栏不显示图标，最大程度"静默"
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = getString(R.string.channel_desc)
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
                nm.createNotificationChannel(ch)
            }
        }

        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTI_ID, buildNotification(text))
        }
    }

    private fun summary(): String {
        val bt = if (Status.bluetooth.level == Status.Level.OK) "蓝牙✓" else "蓝牙✕"
        val wifi = if (Status.wifi.level == Status.Level.OK) "WiFi✓" else "WiFi✕"
        val vol = Prefs.volumeLevel
        return "$bt  $wifi  音量$vol  巡检#${Status.loopCount}"
    }

    // ---------------- 杂项 ----------------

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarAssist::core").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }
}
