# 车机常驻助手 CarAssist

![Platform](https://img.shields.io/badge/platform-Android-3DDC84)
![targetSdk](https://img.shields.io/badge/targetSdk-28-brightgreen)
![minSdk](https://img.shields.io/badge/minSdk-24-blue)
![License](https://img.shields.io/badge/license-MIT-green)

面向 **Android 车机（1280×720 横屏、无 root）** 的开机自启后台常驻助手。开机后自动完成一连串车机初始化动作，并在后台静默巡检、断连秒级自愈，让车机每次上电都处在「蓝牙已连、WiFi 已开、音量已锁、目标 App 已拉起」的可用状态。

## 功能特性

- **开机自启 + 静默常驻**：监听 6 类自启广播，前台服务 + `START_STICKY` + 看门狗 + 进程重建四重保活，杀不掉。
- **双蓝牙自动回连**：状态栏为每个蓝牙适配器（车机多蓝牙场景）提供独立开关；检测到未连接任意设备即主动回连，5 级降级策略兼容受限 ROM。
- **WiFi 自动开启**：判断的是开关状态而非连接状态，4 级开启通道（含 `targetSdk 28` 下仍有效的 `setWifiEnabled`）。
- **音量锁定**：每次启动把媒体音量锁到指定档位。
- **清理高德进程**：拉起目标应用前补杀一次，避免高德抢占焦点。
- **拉起目标应用**：自动纠偏并跳转 `com.zjinnova.zlink`（zLink）。

## 下载

预编译的 release APK 放在仓库 [`releases/`](releases/) 目录，可直接侧载到车机：

- `releases/CarAssist-v1.2.0-release.apk`（当前版本）

> 注：当前仓库未配置 GitHub Releases 发布通道，APK 以仓库文件形式提供；如需固定版本请从 `releases/` 目录获取。

## 执行顺序

```
开机 / 覆盖安装 / 看门狗唤醒
   ↓ 延迟 12s（等系统服务就绪，可调）
① WiFi 未开启 → 自动开启
② 蓝牙未连接任意设备 → 主动回连
③ 媒体音量锁定为 10
④ 杀掉高德进程
   ↓ 延迟 6s（可调），拉起前再补杀一次
⑤ 拉起并跳转 com.zjinnova.zlink
   ↓
每 8s 巡检蓝牙 / WiFi；同时监听系统广播，断连秒级响应
```

## 构建

环境：JDK 17+、Android SDK 34（build-tools 34.0.0）、Gradle 8.12。

> 本仓库未内置 Gradle Wrapper（`gradlew`），请使用系统已安装的 Gradle 8.12 构建：

```bash
# 在项目根目录执行（使用系统 Gradle）
gradle assembleRelease

# 产物：app/build/outputs/apk/release/CarAssist-v<版本号>-release.apk
# 例如：CarAssist-v1.2.0-release.apk
```

release 已配置为复用 debug 签名，可直接覆盖安装；需要自有签名时改 `app/build.gradle` 的 `signingConfigs.release`。

## 安装与授权

```bash
adb connect <车机IP>:5555
adb install -r releases/CarAssist-v1.2.0-release.apk

# 一次性把关键权限用 adb 授掉（免手点，无需 root）
adb shell pm grant com.carboot.assistant android.permission.ACCESS_FINE_LOCATION
adb shell appops set com.carboot.assistant SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +com.carboot.assistant

# 如果日志显示反射被拦（Accessing hidden API），解除隐藏 API 限制可显著提升蓝牙回连成功率
adb shell settings put global hidden_api_policy_pre_p_apps 1
adb shell settings put global hidden_api_policy_p_apps 1
adb shell settings put global hidden_api_policy 1
```

装好后打开一次 App，点右上角 **权限自检**，把定位 / 悬浮窗 / 电池优化白名单过一遍，之后就再也不用管了。

## 需求实现对照

| # | 需求 | 实现位置 | 说明 |
|---|------|----------|------|
| 1 | 实时判断蓝牙未连接任意设备则主动连接 | `task/BluetoothTask.kt` | 轮询 + 系统广播双通道监测；回连采用 5 级降级策略 |
| 2 | 实时判断 WiFi 未**开启**则自动开启 | `task/WifiTask.kt` | 判断的是开关状态而非连接状态；4 级开启通道 |
| 3 | 每次启动音量调整为 10 | `task/VolumeTask.kt` | `STREAM_MUSIC` 绝对档位，可在界面改 |
| 4 | 每次启动杀掉高德进程 | `task/KillTask.kt` | 支持多个高德包名，拉起目标应用前会再补杀一次 |
| 5 | 前置动作完成后拉起 zLink | `task/LaunchTask.kt` | 自动把 `...MyWrapperProxyApplication` 纠偏为包名 `com.zjinnova.zlink` |
| 6 | 后台静默常驻不退出 | `core/CarAssistService.kt` | 前台服务 + START_STICKY + 看门狗 + 进程重建自拉起 |
| — | 开机自启 | `core/BootReceiver.kt` | 监听 6 类自启广播，任一到达即拉起 |

## 关键技术决策

**targetSdk 锁定 28。** 这是整个工程最关键的一处取舍：

- `WifiManager.setWifiEnabled()` 从 Android 10 起对第三方应用失效，但这个限制**按 targetSdkVersion 判定** —— targetSdk ≤ 28 时该 API 依然可用。这是无 root 无系统签名下自动开 WiFi 唯一可靠的通路。
- targetSdk < 31 时，Android 12 的 `BLUETOOTH_CONNECT` 运行时授权、前台服务后台启动限制均不生效，自启链路更稳。
- 车机为侧载安装，不上架应用商店，不受 targetSdk 门槛约束。

**蓝牙回连 5 级降级：** `setConnectionPolicy/setPriority` → `A2DP.connect()` → `HEADSET.connect()` → `BluetoothDevice.connect()` → `fetchUuidsWithSdp()` 触发系统自动回连。前四级都走反射（这些是 `@hide` API），任意一级成功即算下发成功。带 20s 起的指数冷却，避免把协议栈打崩。

**保活四件套：** 前台服务（`IMPORTANCE_MIN` 静默通知）、`START_STICKY` + `onTaskRemoved` 自拉起、AlarmManager 每 2 分钟看门狗、`Application.onCreate` 进程重建兜底。

## 已知限制（说清楚，别踩坑）

1. **前台服务通知无法完全隐藏。** Android 8+ 强制要求前台服务显示通知，这是系统硬限制。已用 `IMPORTANCE_MIN` + `VISIBILITY_SECRET` + 静音，状态栏基本无感。
2. **蓝牙必须先手动配对一次。** 自动回连只对**已配对**设备生效，没有配对记录时无法凭空连接。
3. **隐藏 API 可能被拦。** 部分严格 ROM 会拦截 `A2DP.connect()` 反射，此时会退化到 SDP 探测；执行上面的 `hidden_api_policy` 命令可解决。
4. **后台拉起 Activity 需要悬浮窗权限。** Android 10+ 限制后台启动 Activity，`SYSTEM_ALERT_WINDOW` 是官方豁免通道，未授予时会退化用 `monkey` 通道尝试。
5. **`killBackgroundProcesses` 只能杀后台。** 高德若正处于前台，只能被降级不能被杀死；日志里会标注"残留"。

## 目录结构

```
app/src/main/java/com/carboot/assistant/
├── CarAssistApp.kt              进程入口，初始化 + 服务兜底拉起
├── core/
│   ├── CarAssistService.kt      常驻前台服务：启动序列 + 实时巡检 + 广播响应
│   ├── BootReceiver.kt          6 类自启广播入口
│   ├── WatchdogReceiver.kt      AlarmManager 看门狗
│   └── Status.kt                全局运行态快照
├── task/
│   ├── BluetoothTask.kt         需求 1（含双蓝牙适配器回连）
│   ├── WifiTask.kt              需求 2
│   ├── VolumeTask.kt            需求 3
│   ├── KillTask.kt              需求 4
│   └── LaunchTask.kt            需求 5
├── ui/MainActivity.kt           1280×720 横屏三栏控制台 + 蓝牙状态开关
└── util/                        Prefs / Logx / Shell
```

## 许可证

本项目以 [MIT License](LICENSE) 开源。
