# CHANGELOG — 车机常驻助手 (CarAssist)

版本规则：每次迭代 `versionCode` 必须 +1（覆盖安装以此唯一判定），`versionName` 按语义版本递增。

---

## v1.2.0 (versionCode 4) — 2026-08-07
**状态栏「蓝牙 2」开关可控 · 双蓝牙检测加固**

- 【蓝牙开关可控数量】部分双蓝牙车机的第二个蓝牙是隐藏适配器，
  `BtAdapters.detect()` 的隐藏 API 反射常被 ROM 拦截，导致状态栏只剩「蓝牙 1」，
  手机实际连在「蓝牙 2」上却看不到对应开关。新增参数 `蓝牙适配器数量`（0=自动，设 2 即强制显示两张卡）：
  - 在「参数设置」里设为 2 后，状态栏立即出现「蓝牙 2」独立开关，可单独开/关其自动回连；
  - `BtAdapters.detect()` 改为每次读取最新设置并尽力用 `getAdapter(index)` 反射拿真实适配器对象：
    若拿到，「蓝牙 2」可正常显示连接状态并自动回连；若仍被拦截，卡片提示「未检测到该蓝牙」但开关仍可用。
- 【检测/巡检实时生效】`BluetoothTask` 由构造时缓存适配器列表改为每次 `ensure()` 实时重新枚举，
  改了数量设置后无需重启进程，下一轮巡检即纳入新增适配器。
- 【版本】`versionCode 3 → 4`，`versionName 1.1.0 → 1.2.0`；`compileSdk 34` / `targetSdk 28` 不变。

---

## v1.1.0 (versionCode 3) — 2026-08-03
**双蓝牙适配 · 日志根目录归档 · UI 重排 · 闪退健壮性加固**

- 【双蓝牙适配器】车机可能存在多个蓝牙适配器（双模/双芯），手机往往只连在其中一个上。
  新增 `BtAdapters` 枚举工具（默认适配器 + `BluetoothManager.getAdapters()` 反射 + `BluetoothAdapter.getAdapter(int)` 兜底），
  `BluetoothTask` 改为逐适配器独立检测/回连，**任一启用中的适配器连上设备即视为蓝牙正常**；
  UI 为每个适配器动态生成独立状态卡片与开关。
- 【日志根目录归档】新增第四日志通道：每条日志**同步实时**写入 `/CarAssist/logs/yyyy-MM-dd.log`
  （按日期命名），应用启动/跨日时自动清理 **7 天前**的旧日志；原三通道（内存环 + 应用私有文件 + 本地广播）不变。
  闪退前最后一步操作也能在根目录日志中回溯。
- 【UI 重排（驾驶员友好）】左侧放实时状态卡片（每开关独占一行，名称完整显示，去掉 ①②③ 编号）+ 大号快捷按钮
  （最小高度 54dp，方便盲操作）；右侧放运行流程说明 + 运行日志。
- 【闪退健壮性】`CarAssistService` 的 worker 线程设置 `UncaughtExceptionHandler` 兜底——隐藏 API 在严格 ROM 上
  抛出的 `Error` 不再击杀进程，巡检自动续跑；启动序列每步独立 try/catch 并落盘「▶ 步骤开始 / ✓ 完成 / ✗ 异常」，
  单步失败不阻断后续步骤；致命崩溃同时写入根目录归档日志。
- 【版本】`versionCode 2 → 3`，`versionName 1.0.1 → 1.1.0`；`compileSdk 34` / `targetSdk 28` 不变（保留 WiFi `setWifiEnabled` 兼容）。

---

## v1.0.1 (versionCode 2) — 2026-08-01
**修复：打开 App 约 3 秒内闪退（CalledFromWrongThreadException）**

- 【根因】`Logx.write()` 在后台 worker 线程通过 `LocalBroadcastManager` **同步**派发日志，
  `MainActivity` 的接收器在**同一后台线程**上执行 `TextView.setText`，
  触发 `ViewRootImpl.checkThread()` 抛出 `CalledFromWrongThreadException`，
  该异常在 worker 线程未被捕获 → 整个进程被杀。现象表现为"运行到『启动序列开始』这一步就闪退"。
- 【修复】
  - 接收器里所有 UI 更新改回主线程（`vb.root.post { ... }`）。
  - 给 `runStartupSequence` / `tick` / `reactNow` / `refresh` 等所有回调包 `runCatching`，单点异常不再杀进程。
  - 去掉 `foregroundServiceType="connectedDevice"`：车机实为 **Android 14 / API 34**，
    该类型在 API 34 上必须配套 `FOREGROUND_SERVICE_CONNECTED_DEVICE` 权限，否则 `startForeground()` 直接崩；
    改用"无类型纯通知保活服务"对 API 34 完全合规。
  - `CarAssistApp` 增加全局崩溃捕获，把堆栈落盘到 `crash.log`，便于现场复盘。
  - 声明 `POST_NOTIFICATIONS` 权限（Android 13+ 规范）。
- 【新增】参数设置页底部新增"本应用版本"显示，现场可直读 `versionName (code)` 核对装机版本。

---

## v1.0.0 (versionCode 1) — 2026-07-30
**首发可用版本**

- 五大能力：蓝牙自动回连 / WiFi 自动开启 / 音量锁定 10 / 杀高德进程 / 拉起 zLink。
- 开机自启（多广播触发）+ 前台常驻 + 看门狗自恢复。
- 1280×720 横屏控制台，日夜双主题，实时状态与日志。
- `targetSdk 28` 以绕过 Android 10+ 的 WiFi/蓝牙限制。
