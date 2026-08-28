# CHANGELOG — 车机常驻助手 (CarAssist)

版本规则：每次迭代 `versionCode` 必须 +1（覆盖安装以此唯一判定），`versionName` 按语义版本递增。

---

## v1.3.7 (versionCode 12) — 2026-08-24
**UI 顺序微调 · 巡检/延迟默认值调整 · 第二个蓝牙诊断增强**

- 【状态卡置顶】左侧状态开关区把「清理高德进程」卡片放到整体第一位（蓝牙卡之前），
  顺序变为：清理高德 → 蓝牙1/蓝牙2 → WiFi → 音量 → 拉起目标应用。
- 【默认时间调整】巡检周期默认 8s → 3s；开机延迟默认 12s → 5s；拉起延迟默认 6s → 5s
  （已同步 UI「参数设置」的兜底值；若车机此前已保存过旧值，需手动重存一次才生效）。
- 【蓝牙诊断增强】`task/BtAdapters.kt`：当硬件层（sysfs）发现多个物理蓝牙芯片、但反射只拿到 1 个
  可控对象时，日志明确提示第二个蓝牙被 ROM 隐藏 API 拦截，并给出一次性 adb 解锁命令。
- 【版本】`versionCode 11 → 12`，`versionName 1.3.6 → 1.3.7`；`compileSdk 34` / `targetSdk 28` 不变。

---

## v1.3.6 (versionCode 11) — 2026-08-20
**蓝牙适配器检测：新增内核 sysfs + getprop 硬件层通道**

- 【sysfs 硬件层检测】`task/BtAdapters.kt` 新增 `detectViaSysfs()`：直接枚举
  `/sys/class/bluetooth/hci*/` 目录，读取每个物理蓝牙芯片的 `address`（MAC）与
  `type`（PRIMARY/AMP/LE）。这是内核导出的世界可读接口，**无需 root、无需隐藏 API**，
  能在反射与 dumpsys 都失败时，从硬件层确认车机到底有几个蓝牙芯片、各是什么 MAC。
- 【getprop 诊断】新增 `detectViaGetprop()`，抓取蓝牙相关系统属性（默认接口名、bdaddr 路径等）
  写入日志，辅助判断车机蓝牙驱动形态。
- 【检测信息统一】把 dumpsys/sysfs 检测结果合并为 `Entry.info`（`Info` 数据类，含 mac/enabled/name/hciType/viaSysfs），
  按 MAC 去重；`BluetoothTask` 占位卡与 `MainActivity` 卡片标题据此精确标注来源：
  「sysfs 硬件层」「dumpsys 系统层」「待检测」。
- 【三路交叉验证】检测 = 反射（可控制）+ dumpsys（系统层）+ sysfs（硬件层），
  日志逐条打印 `【蓝牙检测】` 前缀的检测结果，方便现场排查。
- 【版本】`versionCode 10 → 11`，`versionName 1.3.5 → 1.3.6`。

---

## v1.3.5 (versionCode 10) — 2026-08-11
**启动时自动尝试解除 hidden_api_policy 限制**

- 【自动解除隐藏 API】`core/CarAssistService.kt` 新增 `tryUnlockHiddenApi()`，
  启动时用两条通道（`Settings.Global.putString` + shell `settings put global`）
  写入 `hidden_api_policy` / `_pre_p_apps` / `_p_apps` = 1，让双蓝牙反射能拿到第二个适配器。
  严格 ROM 上无 `WRITE_SECURE_SETTINGS` 权限会失败（日志提示手动 adb），但该设置为持久化，
  成功一次后无需重复。
- 【版本】`versionCode 9 → 10`，`versionName 1.3.4 → 1.3.5`。

---

## v1.3.4 (versionCode 9) — 2026-08-11
**蓝牙适配器检测：新增 dumpsys bluetooth_manager 系统层通道**

- 【dumpsys 检测】`task/BtAdapters.kt` 新增 `detectViaDumpsys()`：解析
  `dumpsys bluetooth_manager`（走 Binder 直连，不受隐藏 API 拦截），提取所有适配器的
  MAC、开关状态、名称，即使反射被拦也能看到第二个适配器的存在与状态。
- 【占位卡增强】占位卡详情按信息源精确显示「dumpsys 发现 XX · 已开启/已关闭」。
- 【版本】`versionCode 8 → 9`，`versionName 1.3.3 → 1.3.4`。

---

## v1.3.3 (versionCode 8) — 2026-08-11
**蓝牙适配器 MAC 优先级：支持主/副蓝牙调换**

- 【新增 MAC 优先级】`Prefs.kt` 新增 `btAdapterPriority`（逗号/空格分隔的 MAC 列表）；
  `task/BtAdapters.kt` 重构 `detect()`：按优先级顺序填充「蓝牙 1」「蓝牙 2」等槽位，
  未列入优先级的已检测适配器追加到末尾；优先 MAC 暂未被反射检测到时仍为其保留占位卡，
  便于用户知道「缺谁」。
- 【占位卡优化】`Entry` 新增 `expectedMac` 字段；`BluetoothTask.handleAdapter` 在占位卡上
  显示「未检测到该蓝牙（优先级 MAC：AA:BB:CC:DD:EE:FF）」。
- 【标题带 MAC】`MainActivity.buildBtCards()` 把 MAC 加到卡片标题，例如
  「蓝牙 1 · 00:87:61:60:34:04」或「蓝牙 1 · 00:87:61...（待检测）」，调换结果一目了然。
- 【设置 UI】`activity_main.xml` / `strings.xml` 新增「蓝牙适配器 MAC 优先级」输入框；
  示例：`00:87:61:60:34:04, 08:18:57:A9:D7:C1`（手机蓝牙主，OBD 蓝牙副）。
- 【配合动作】若填入的 MAC 未被检测到，建议在车机执行：
  `adb shell settings put global hidden_api_policy 1`
  解锁隐藏 API 反射；日志会打印"优先级 MAC 未检测到：..."及提示。
- 【版本】`versionCode 7 → 8`，`versionName 1.3.2 → 1.3.3`；`compileSdk 34` / `targetSdk 28` 不变。

---

## v1.3.2 (versionCode 7) — 2026-08-08
**WiFi 通道重排：shell 优先尝试静默开启，绕过车机系统「是否同意」弹窗**

- 【通道顺序优化】`task/WifiTask.kt` 把 `svc wifi enable`（shell）从第 3 通道提到第 1 通道：
  shell 命令走 Binder 直连 `WifiService`，绕过 `WifiManager` 的权限拦截层，
  在不少车机 ROM 上可静默开启 WiFi，不弹"该应用想要开启 WiFi，是否同意？"确认框；
  失败再退回 `setWifiEnabled()` / 反射 / WiFi 面板兜底。
- 【版本】`versionCode 6 → 7`，`versionName 1.3.1 → 1.3.2`。

---

## v1.3.1 (versionCode 6) — 2026-08-08
**状态栏开关改为执行状态驱动（手机设置风格）· 新增加载动画与高德巡检**

- 【开关行为重写】`ui/MainActivity.kt` 中所有状态卡片开关（WiFi / 蓝牙 / 音量 / 杀高德 / 拉起），
  从「功能启用/关闭」改为**「实际执行状态驱动」**（类似手机设置里的 WiFi 开关）：
  - 开关 ON = 该操作执行成功并正在生效（如 WiFi 确实已开、蓝牙确实已连）；
  - 开关 OFF = 操作尚未执行 / 未成功 / 已被关闭；
  - 从 OFF 拨到 ON：立刻在后台执行对应操作，开关暂保持 OFF 并显示转圈动画，成功后才跳到 ON；
  - 从 ON 拨到 OFF：关闭该功能的自动维护，状态重置为「已关闭」。
- 【加载动画】`item_status_card.xml` 新增小号 `ProgressBar`（转圈），`Status.Level` 新增 `LOADING`
  级别；执行中时替换彩色圆点为旋转动画，开关暂且禁用防止连击。
- 【高德巡检】`CarAssistService` 巡检循环新增高德进程检测：若杀高德成功后又被系统重新拉起，
  状态栏「清理高德进程」开关会自动从 ON 跳回 OFF（WARN），避免开关虚假点亮。
- 【`KillTask.checkKill()`】新增轻量巡检方法，仅检查进程是否复活并更新 Status，不触发重复清理。
- 【版本】`versionCode 5 → 6`，`versionName 1.3.0 → 1.3.1`；`compileSdk 34` / `targetSdk 28` 不变。

---

## v1.3.0 (versionCode 5) — 2026-08-07
**高德车机版进程清理：按拆解确认的 4 个进程名精确终结**

- 【背景】对 `Auto_9.5.0.600013_release_signed.apk` 的 `AndroidManifest.xml` 拆解确认，
  高德车机版（包名 `com.autonavi.amapauto`）运行时会拉起 4 个进程：主进程
  `com.autonavi.amapauto`，以及私有子进程 `:selfupdate`（自升级）、`:push`（推送）、
  `:locationservice`（定位）。
- 【KillTask 重写】`task/KillTask.kt` 现显式编码这 4 个进程名（`KNOWN_AMAP_PROCESSES`）：
  - 待清理包名 = 用户配置 + 已知车机版包名 `com.autonavi.amapauto`（去重），即使用户在设置里
    把该包名从列表删掉，车机版这 4 个进程也一定会被纳入清理目标；
  - 在原有「按包名 killBackgroundProcesses + 反射 forceStopPackage + shell am force-stop」
    三通道基础上，新增「进程级兜底」：枚举 `runningAppProcesses`，凡命中已知进程名或配置包名
    （含 `包名:xxx` 子进程）即按所属包名再补一次 `killBackgroundProcesses`，确保 3 个私有子进程
    不被漏杀、即便被系统重新拉起也能在本轮再次命中；
  - 残留核对改用「已知进程名精确匹配」，能精准报告仍存活的是哪一个进程（而非只报包名）。
- 【已知限制】`killBackgroundProcesses` 只能杀后台进程；若高德主进程正处于前台（用户正在看地图），
  主进程只能被降级、子进程仍会被杀，日志会标注"残留"的具体进程名，符合 README 已说明的行为。
- 【版本】`versionCode 4 → 5`，`versionName 1.2.0 → 1.3.0`；`compileSdk 34` / `targetSdk 28` 不变。

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
