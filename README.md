# OPods

面向 OPPO / ColorOS 的 AirPods 原生体验增强模块。它通过 LSPosed 只在 ColorOS 官方进程内工作，
复用官方 AirPods 弹窗和设置页，不提供第二套桌面 App、后台 Service、常驻扫描进程或独立耳机管理界面。

## 当前版本

- 当前版本：`v3.4.52`（`versionCode 86`，**待真机验证**）
- 当前 Release：[v3.4.52-arm64-v8a](https://github.com/arkylin/OPods/releases/tag/v3.4.52-arm64-v8a)，APK 由 GitHub Actions 构建并正式签名
- APK：`OPods-v3.4.52-arm64-v8a.apk`；SHA-256 见同一 Release 中的 `OPods-v3.4.52-arm64-v8a.sha256`
- 签名证书：`CN=OPods Release, OU=Release, O=arkylin, C=CN`；SHA-256 `AB:E8:C3:8E:21:45:CE:77:5B:6C:74:04:65:A8:01:1C:51:A8:72:71:10:3F:86:E8:39:71:66:E1:0F:34:B3:59`
- 已验证设备：一加 13（`PJZ110`）、ColorOS 17.0.0.103、Android 17、AirPods 5
- 3.4.19 已完成真机 UI 验收；3.4.20 新增独立“关于”页和逐控件协议/存储标注；3.4.26 已通过一次官方 ACL/弹窗流程复测；3.4.27 切换到 100 年正式 Release 证书；3.4.29–3.4.31 同步官方弹窗、耳机页与 DeviceInfo；3.4.45 通过官方 CoreService IPC 刷新控制中心磁贴电量；3.4.46 修复磁贴充电状态被空快照覆盖；3.4.47 首连有限热身重试以拿到第一包电量。
- 3.4.48 按 `docs/CODE_REVIEW_3.4.47.md` 修复，并按 LibrePods 补齐功能：
  - 开盖弹窗保留并修复：ROM 的开盖过滤两个重载都放宽；开盖广播先用 AirPods 下发的 IRK 校验是不是自己的耳机，有 ENC 密钥时解密出精确电量；
  - 头部手势：来电时点头接听、摇头拒接，保留姿态测试和备用追踪包；
  - 对话感知手机端：说话时调低媒体音量或暂停，结束后恢复；
  - 取下时断开音频、通过官方弹窗重新连接、耳机调节 EQ、心率开关、全部控制项原始值、高级控制、日志分享；
  - 入耳播放只恢复由模块暂停的播放；听力/通透写入必须先读到耳机参数，左右耳 EQ、量程和平衡方向全部修正；
  - 多副 AirPods 时按官方页面给出的设备工作，设备设置按耳机分别保存；
  - AACP 重试统一由“官方 AirPods 页面是否可见”决定，写入改到独立线程；
  - LibrePods 中做不到的功能也保留入口，并在面板里写明原因（“关于 → LibrePods 功能对照”）。
- 3.4.49 修噪声切换：
  - 自适应降噪强度滑杆改为越大降噪越强（直接对应 AACP `0x2E` 数值），官方页和面板三处一致；
  - 切换降噪不再闪：官方降噪栏只移动选中项，不再让 ROM 每次重建整条栏；
  - 刚连上时切换不再被旧状态顶回去：选中的模式会保持显示，耳机 3 秒内报回的旧模式被忽略，确认或超时后再以耳机为准。
- 3.4.50 重做开盖弹窗，做法和 OPPO 自家耳机一致、不额外耗电：
  - 复用 accessory 里 OPPO 自家耳机用的常驻扫描（只在亮屏时运行），只追加一条覆盖整条 27 字节消息的硬件过滤：只放行 `07 19` 且“开盖”位为开的 AirPods 广播；
  - 一次开盖只唤醒一次 MyDevices，经官方 accessory 保活绑定（`OafPersistentService`）直接送到能稳定注入的 `:cards`，不再依赖注入不上的主进程，也不再走会被官方频控拦掉的 FusionWakeup；
  - 去掉 3.4.48 起把 ROM 过滤放宽成 `07 19` 两字节的 Hook：它让附近所有 AirPods（包括戴在耳朵上时）的每条广播都去唤醒 MyDevices；
  - IRK 校验通过后先把广播里的电量（有 ENC 密钥时为精确值）写进官方弹窗，连上后由 AACP 接管；弹窗已显示同一副耳机时，连上不再重复拉起。
- 3.4.51 补上“开盖自动连接”：
  - 官方“回连”弹窗只展示已经连上的耳机，从不发起连接，所以以前耳机不自己回连就一直连不上；现在耳机没连上时改用官方弹窗的连接模式（`device_info` 配对信息），弹出后调用弹窗自己的 `AirpodsDiscoveryViewModel.startConnect()`——和弹窗里“连接/重试”按钮同一个调用，已配对设备走官方 `connect()`，界面依次显示正在连接 → 已连接（电量）或连接失败（重试）；
  - 同样修好了“通过官方弹窗连接”按钮和“取下断开音频、戴回”这两条：以前它们弹的也是只展示状态的回连弹窗，不会真的连接；
  - 面板“开盖弹窗”新增“官方扫描”一行：accessory 的官方扫描第一次带上开盖过滤时（每个 accessory 进程一次）通知 MyDevices，这里显示时间；“未确认”说明 accessory 还没加载新版模块；
  - 设备页“识别密钥”新增“重新获取识别密钥”。
- 3.4.52 修“有弹窗但耳机不连接”：
  - 真机记录（11-04 蓝牙 dump）坐实：开盖时 AirPods 自己连上蓝牙链路（ACL，`is_locally_initiated:false`），媒体/通话音频（A2DP/HFP）不会自己连，要手机发起；当时是旧版模块的主动 profile 连接在 80 ms 后补上的，3.4.43 去掉它以后就没人再连音频；
  - ACL 一上来，系统就弹出官方“回连”弹窗——它只显示状态、从不连接，这就是“有弹窗但不连接”；3.4.51 又把“ACL 已连”当成“已连接”，开盖路径也只弹状态弹窗，而且会被已显示的回连弹窗挡掉（3.5 秒去重 + 同一副弹窗已显示）；
  - 现在按“这副耳机的音频是否已接通”（系统音频输出里有没有它的 A2DP/通话设备）判断：开盖、手动按钮、戴回，以及官方回连弹窗出现 0.8 秒后音频仍没接通时，都把官方弹窗切到连接模式并调用它自己的 `startConnect()`（官方 `connect()`，与弹窗“连接/重试”按钮同一调用）；连接请求单独去重，每个回连弹窗最多一次，弹窗关掉就不再处理，不是重连看门狗；“开盖自动连接”开关关掉则全部不连；
  - 面板“开盖弹窗”新增“最近开盖连接”：记录这次是开盖/回连/手动/戴回、当时 ACL 和音频状态、弹窗是否调用了 `startConnect`、官方连接是否开始/成功/超时，不接 ADB 也能看出卡在哪一步；
  - 不新增 Hook（复用弹窗 `onResume`/`onNewIntent` 与官方 `i0` 状态 Hook），不新增扫描、进程、Service 或 Receiver。

AirPods Pro 3 的能力映射已写入代码，但 ATT、助听、PPE、心率和头部手势仍需对应真机验收。

## UI：七页卡片面板

官方 AirPods 设置页只保留首屏噪声横条、自适应滑杆和一个“AirPods 增强”入口。
入口内部是七页卡片式面板：

`常用 / 音频 / 操控 / 连接 / 健康 / 设备 / 关于`

每个开关、分段按钮、滑杆和操作按钮下面都会显示：

- 使用的协议，例如 `AACP` 或 `ATT`。
- 发送的数据模板，例如 `AACP 09 00 26 01|02 00 00 00`。
- 设置位置：`设备`、`手机本地`、`手机协同` 或 `官方页面`。

做不到或有限制的功能不会隐藏，而是在原位置用彩色标签说明：`受限`、`实验`、`不可用`，并写清原因。

AACP 控制包公共头是 `04 00 04 00`；面板里为了紧凑，通常只显示 `09 00 <id> <v0..v3>`。
ATT 使用 PSM 31，读 `0A`、写 `12`、通知 `1B`。

面板打开期间会实时刷新：头部的电量/连接状态随事件更新；耳机回报新的设置值时，当前页自动重绘（拖动滑杆时不打断）。

## 功能覆盖

- 噪声四模式、自适应降噪强度（越大降噪越强）、自适应音量、对话感知、单耳降噪。
- 对话感知手机端：说话时调低媒体音量（按比例或上限）、可同时暂停，结束后恢复。
- 入耳播放/暂停（只恢复由模块暂停的播放）、取下时断开音频、低电量提醒、自动人耳检测。
- 开盖自动连接：开盖弹出 OPPO 官方弹窗，由官方流程连接；用 IRK 识别自己的耳机。
- 麦克风模式、按压速度、长按时长、音量滑动与时长、提示音双字节载荷。
- 六种 EQ 预设、自定义三段 EQ、耳机调节 EQ（实验）、单击/双击/三击/长按动作、长按循环模式。
- 通话静音/挂断映射、自动接听、头部手势（来电点头接听、摇头拒接）、姿态测试、备用追踪包。
- 自动连接 `0x20`、睡眠检测、优化充电、允许关闭、PPE、心率传感器开关（实验，Pro 3）。
- 重命名、序列号/固件/硬件版本/更新器信息、识别密钥状态、全部控制项原始值、高级控制（实验）、日志分享/清空。
- ATT 大声降低、通透自定义、助听器调节、双耳 8 段 EQ、听力图写回。
- 个性化空间音频只打开 OPPO 官方 Activity，不复制官方空间音频实现。

保留入口但不可用的 LibrePods 功能（原因在面板里写明）：相机遥控、按耳机状态/来电/播放自动接管、
多设备连接、伪装为苹果设备、媒体辅助、查找（Find My）、高质量双向音频。

## 数据保存在哪里

| 位置 | 内容 | 说明 |
| --- | --- | --- |
| 设备 | 噪声、自适应音量、对话感知、单耳降噪、按键速度/时长、音量滑动、EQ、麦克风、人耳检测、自动连接 `0x20`、睡眠、充电、允许关闭、PPE、心率、助听、通透、耳机调节 EQ | 通过 AACP 控制或 ATT 写入 AirPods |
| 手机 | `ear_playback`、`battery_alerts`、`head_gestures`、`head_gesture_calls`、`alternate_head_tracking`、`ca_*`、`disconnect_when_not_wearing`；每副耳机各自的 `equalizer_preset`、`device_control_*`、`device_equalizer`、`accommodation_*` | MyDevices 进程内 SharedPreferences，按耳机地址分别保存，不写入耳机 |
| 手机（跨进程） | `auto_connect`、`lid_open_unverified_fallback`、最近开盖广播、识别密钥 IRK/ENC、电量快照 | MyDevices 私有文件 `oppo_airpods_state.properties`；日志不记录密钥内容 |
| 手机协同 | 入耳播放、开盖弹窗、头部手势接挂电话、对话感知降音量、取下断开音频 | 耳机上报事件，手机执行媒体键、官方弹窗、Telecom 或音量调整 |
| 官方页面 | 个性化空间音频、部分 OPPO 原生设置 | 只发 Intent 打开官方 Activity；电池桥只重绘官方 View，不写官方持久数据 |
| 只读 | 电量、佩戴、型号、序列号、固件、AACP/ATT 状态 | AACP `0x04`/`0x06`/`0x1D`、ATT `0A` 读取；AACP 电量状态会回写官方弹窗和耳机页的电池组件 |

3.4.48 之前保存的全局设备设置，会在升级后第一次连接时迁移到当时连接的那副耳机。

`关于`页有完整图例和 LibrePods 功能对照，每个控件下方都有对应的协议/存储标注。

## Hook 范围：三个进程，34 个 Hook 点

`xposed/scope.list` 只声明三个包，所有增强逻辑都通过 LibXposed 的
`module.hook(...).intercept(...)` 在官方进程内完成，不使用 `XposedBridge` 直接 Hook，
也不在第三方 App、Launcher、SystemUI 或 Settings 中注入。

| 进程 | Hook 点数 | 职责 |
| --- | ---: | --- |
| `com.heytap.mydevices` | 33 | 官方 AirPods 弹窗（含开盖时调用官方连接）、开盖唤醒接收、详情页、设置页、AACP/ATT、电池 UI/DeviceInfo 与假断开过滤 |
| `com.heytap.accessory` | 2 | 给官方常驻扫描追加 AirPods 开盖硬件过滤；一次开盖唤醒一次 MyDevices `:cards` |
| `com.android.bluetooth` | 1 | 只过滤 ColorOS 连接时发送的 `DISABLE_OFF` 帧 |

> 计数口径是源码中的 `hook(...)` 调用点。`AirpodsScanPolicy` 会遍历所有参数数不少于 5 的构造器，
> 因此运行时实际 Hook 的方法数可能多于调用点数。

### `com.heytap.mydevices`：33 个 Hook 点

| # | 目标类 / 方法 | 作用 |
| ---: | --- | --- |
| 1 | `DeviceApplication.onCreate()` | 记录官方进程的 `Application` 上下文，供后续 UI 与控制器使用。 |
| 2 | `com.oplus.mydevices.keepalive.oaf.OafPersistentService.onBind(Intent)` | 官方 accessory 保活服务（`:cards`）。只读取 accessory 侧开盖唤醒带来的广播数据，交给开盖处理（IRK 校验 → 官方弹窗）；官方 binder 原样返回。 |
| 3 | `AirpodsScanHelper.registerPtc()` | 调用官方实现后读取 `mIsPtcRegistered`，只用于确认官方 PTC 注册结果。 |
| 4 | `aa.b2$a.r()`（回退 `aa.AbstractC0958b2$a`） | 强制返回 `true`，开启官方 AirPods 特性支持。 |
| 5 | `aa.b2$a.J(int)`（按签名回退） | 把能力位与 `0x200` 合并，启用官方 UI 的 Off 模式能力。 |
| 6 | `AirpodsScanPolicy` 所有参数数不少于 5 的构造器 | 登记官方策略对象供 DeviceInfo 电量写入，并补调一次官方 `registerPtc` 作为注册回退。 |
| 7 | `AirpodsScanPolicy.onBatteryLevelChanged(BluetoothDevice, int[])` | 把框架旧电池数组替换为 AACP 快照；这是控制中心 DeviceInfo/设备磁贴的数据上游。 |
| 8 | `AirpodsManager$c.onReceive(Context, Intent)` | 官方广播处理后接收 `ACL_CONNECTED` / `ACL_DISCONNECTED`，同步给 `AirPodsController` 并请求官方弹窗。 |
| 9 | `AirpodsScanHelper$registerPtc$2$a.onServiceFound(...)` | 官方 PTC 回调：解析 Apple 邻近消息，开盖且通过身份校验时请求官方弹窗。 |
| 10 | `FusionServiceImpl.handleEvents(...)` | 对 `onServiceFound` 事件解析 `ServiceNodeParams` 并转换为 `ServiceNode`，走与 PTC 回调相同的处理路径。 |
| 11 | `CoreService.onStartCommand(Intent, int, int)` | 官方 FusionWakeup 唤醒 CoreService 时解析 `serviceNode`；`:cards` 绑定失败时 accessory 的开盖唤醒也落到这里，走同一开盖处理。 |
| 12 | `InnerIpcManager` 构造器 | 捕获 `:cards` 进程的官方 IPC 管理器，供 AACP 电量快照走官方 CoreService 通道。 |
| 13 | `AirpodsManager.process(String, String, Bundle, int)`（按签名回退） | 只识别自定义 `OPODS_AACP_BATTERY_V1`，在核心进程调用官方 `AirpodsScanPolicy.onBatteryLevelChanged`；其他请求原样放行。 |
| 14 | `AirpodsSettingPreferencesFragment.onCreatePreferences(Bundle, String)` | 官方设置页构建完成后注入自适应滑杆和“AirPods 增强”入口。 |
| 15 | `AirpodsDiscoveryActivity.onResume()` | 官方弹窗显示时，按弹窗 Intent/ViewModel 中的设备启动 AACP 电量通道，并允许链路重试；弹窗带模块的连接标记（开盖、手动连接、戴回）时，调用一次官方 `getViewModel().startConnect()`。 |
| 16 | `AirpodsDiscoveryActivity.onPause()` | 弹窗隐藏后保留 AACP 电量同步，但不再允许链路重试。 |
| 17 | `AirpodsDiscoveryActivity.onNewIntent(Intent)` | 弹窗是 singleInstance，已打开时的新请求从这里进来；带连接标记时同样调用一次官方 `startConnect()`。 |
| 18 | `AirpodsDetailPanelFragment.bindPanelBodyViews(View)` | 详情页绑定后安装首屏噪声快捷选择器。 |
| 19 | `AirpodsDetailPanelFragment.initView(View)` | 详情页初始化时再次尝试安装；`WeakHashMap` 保证同一 Fragment 不会重复注入。 |
| 20 | `AirpodsDetailPanelFragment.clearPanelBodyViewRefs()` | 清理视图前释放模块持有的快捷选择器状态与监听。 |
| 21 | `androidx.preference.SeekBarPreference` 的单参数 `void` 绑定方法（优先 `Z`，失败按签名回退） | 仅装饰 `SliderRegistry` 标记的模块滑杆，补刻度、轨道与 COUI 卡片样式；Hook 失败不影响滑杆本身的 AACP 控制。 |
| 22 | `AirpodsSettingActivity.updateFeatureSupport(int)` | 官方刷新后强制展开噪声选择器、空间音频/佩戴检测/对话分组，并设置佩戴检查支持。 |
| 23 | `AirpodsSettingActivity.onResume()` | 从官方 ViewModel 读取当前 `BluetoothDevice` 并交给控制器，标记设置页已打开。 |
| 24 | `AirpodsSettingActivity.onStop()` | 页面离开前台时标记关闭，停止 ATT，AACP 进入只保留电量同步、不重试的模式。 |
| 25 | `AirpodsSettingActivity.onDestroy()` | 页面销毁时同样标记关闭，并释放官方噪声选择器的同步监听。 |
| 26 | `AirpodsSettingActivity.onNoiseControlClick(int)` | 模式 1–4 直接写入 AACP 并更新官方 UI，不再依赖 AirPods 走不通的官方噪声下发路径。 |
| 27 | `AirpodsSettingActivity.updateCurrentFeature(int)` | ROM 用自己蓝牙回调或恢复页面时刷新降噪栏：换成 AACP 实时模式，并只移动选中项（ROM 原实现会重建整条栏，导致图标闪烁）。 |
| 28 | `AirpodsSettingViewModel.setExtendFeatureStatus(int, int)` | 类型 `4`（噪声）、`16`（对话感知）、`32`（佩戴检测）改走 AACP；其他类型继续官方实现。 |
| 29 | `AirPodsDialogFragment$AirPodsDialogContentFragment.t0(int[])` | 记录官方弹窗电池组件，并在 AACP 电量变化时刷新官方弹窗；忽略模块自身触发的重绘。 |
| 30 | `AirpodsDetailPreferencesFragment.initAirpodsCardAppearance()` | 记录官方耳机页的电池 binder，供 AACP 状态即时刷新。 |
| 31 | 官方电池 binder 的 `void(int[], boolean)` 方法（按签名回退） | 官方刷新后再次用 AACP 的组件级电量/充电状态覆盖，避免框架缓存导致“已佩戴仍显示充电”。 |
| 32 | `AirpodsBluetoothManager.C()` | 记录用户主动断开的时间戳，用于区分主动断开与 HFP 空闲链路掉线。 |
| 33 | `AirpodsBluetoothManager.i0(String, BluetoothDevice)` | 用户主动断开 8 秒窗口外收到 `STATE_DISCONNECTED` 时，如果设备或管理器仍报告已连接，则吞掉这次假断开；其余状态原样放行。 |

### `com.heytap.accessory`：2 个 Hook 点

| 目标类 / 方法 | 作用 |
| --- | --- |
| `com.oplus.pantaconnect.discovery.fusionprovider.FusionProvider.d()` | 官方持久过滤器列表：亮屏时被并进官方扫描，并逐条和每个结果比对。追加一条 Apple `0x004C` 过滤（27 字节，掩码 `07 19` + 开盖位），并去掉 3.4.48/3.4.49 注册进来的 `07 19` / `07 0F` 两字节宽过滤。不新建扫描，扫描的启停、档位、亮屏门控全由官方决定。 |
| `com.oplus.pantaconnect.discovery.core.discover.result.output.m.a(...)` | 官方持久扫描结果出口。AirPods 状态消息（`07 19`、非配对模式）由 `LidGate` 判定：同一次开盖只放行第一条（8 秒无广播才算下一次开盖、RSSI ≥ −70、双耳佩戴不算、多副同时开盖每 2 秒最多一次），经官方保活服务绑定唤醒 MyDevices `:cards` 后立即解绑；这些消息不再交给 ROM（它对已配对 AirPods 的开盖本来就不处理），避免每条都触发官方 FusionWakeup。配对模式消息（`07 19 07` / `07 0F 00`）照旧交给 ROM 的新设备弹窗。 |

### `com.android.bluetooth`：1 个 Hook 点

| 目标类 / 方法 | 作用 |
| --- | --- |
| `BluetoothSocket.getOutputStream()` | 用 `FilterOutputStream` 包装官方输出流，只丢弃完全匹配的 11 字节 `04 00 04 00 09 00 34 02 00 00 00`；其他 socket 写入一律原样透传。 |

### 开盖弹窗怎么工作

和 OPPO 自家耳机走同一个官方常驻扫描（`com.heytap.accessory` 的 pantaconnect 持久扫描）：亮屏/解锁后先低延迟 1 秒，再均衡档约 5 分钟，之后低功耗档，息屏即停。模块不另开扫描。

1. accessory：`FusionProvider.d()` 追加一条硬件过滤，蓝牙芯片只把“开盖”状态的 AirPods 广播交给系统，戴在耳朵上、盒盖合上时发的广播在芯片里就被丢掉。
2. accessory：官方结果出口把命中的广播交给 `LidGate`，同一次开盖只放行一次，经官方保活服务 `OafPersistentService` 的绑定把广播数据送到 MyDevices `:cards`，随即解绑。
3. `:cards`：解析 `0x07` 邻近消息（盒盖状态、型号、电量、佩戴和连接状态）。
4. 身份校验：系统已解析成身份地址时直接比对；否则用 AirPods 通过 AACP `0x31` 下发的 IRK 校验广播里的可解析私有地址（RPA）。有 ENC 密钥时解密出精确电量，先显示在官方弹窗上。
5. 只有自己的耳机才会启动官方 `AirpodsDiscoveryActivity`；还没拿到密钥的耳机，按“未拿到密钥时也响应”开关决定是否按未验证方式弹窗。
6. 音频已经接通：官方回连弹窗显示电量。音频没接通（包括 AirPods 自己连上了 ACL、但 A2DP/HFP 没连的常见情况）：官方弹窗的连接模式（`device_info`），弹出后调用它自己的 `startConnect()`（与“连接/重试”按钮相同，已配对设备走官方 `connect()`），显示正在连接 → 已连接（电量）/ 连接失败（重试）。连上后 AACP 接管电量。
7. 开盖广播没送到（例如 accessory 侧没生效）时：AirPods 自己连上 ACL 后系统会弹出官方回连弹窗，0.8 秒后音频仍没接通，就把这个弹窗切到连接模式，同样调用官方 `startConnect()`。

限制：和 OPPO 自家耳机一样只在亮屏时扫描，息屏开盖不会弹窗；必须勾选 `com.heytap.accessory` 作用域，装新版后停止一次 accessory 才会带上新注入。

耗电：没有新增扫描、进程、Service、Receiver、闹钟或唤醒锁；扫描本来就在跑，追加的过滤在芯片里执行，只有附近有人开盖时 accessory 才收到结果，每次开盖最多唤醒一次 `:cards`。

### 明确不 Hook

- `AirpodsDiscoveryActivity` 只 Hook `onResume()` / `onPause()` / `onNewIntent()`：启动和节流官方 AACP 电量通道，带连接标记时调用一次官方 `startConnect()`；不替换 Activity，弹窗的界面、状态和连接都由官方代码完成。
- `com.heytap.accessory` 只保留上述 2 个 Hook 点（持久过滤列表与结果出口）；不碰官方扫描的启停、档位和亮屏门控，Off 帧过滤和 AACP/ATT 仍只在 MyDevices/Bluetooth 中完成。
- `com.oplus.wirelesssettings`：旧版“蓝牙设置 → 耳机功能”实验路径已删除，当前只使用 MyDevices 官方页。
- `com.oplus.melody`、Launcher、SystemUI、Settings 和第三方 App：不 Hook。
- 模块不创建自建 BLE 扫描、主动重连 watchdog、后台监听 Service 或独立连接路径。来电识别只在“点头接听 / 摇头拒接”打开且 AACP 通道在线时，于官方进程内监听音频模式变化。

### 为什么算最小 Hook

- 每个 Hook 只落在必要方法或必要构造函数上，不做全类替换。
- Bluetooth 进程只过滤一个精确帧，其他 socket 写入原样放行。
- MyDevices 中的 Hook 各自独立 try/catch；一个 Hook 失败不会让模块整体崩溃。
- 可选装饰 Hook（自适应滑杆样式）失败时，功能仍由 AACP/Dashboard 控制。
- 连接和弹窗只走官方 PTC/ACL 事件与 `AirpodsDiscoveryActivity`；AACP 仅在官方 `:cards` 进程内保留一个 daemon socket 线程，ACL 断开即停。只有官方 AirPods 弹窗、设置页或详情页可见时才允许链路重试；首连另有最多 3 次、间隔 1.2 秒的热身重试，只用来拿到第一包电量。不新增 BLE 扫描、watchdog、后台 Service 或独立进程。
- 不在第三方 App、桌面、通知中心或输入法里注入。

### OPPO 组件更新后的兼容策略

- 优先使用 `com.heytap.mydevices.plugin.airpods.*` 这类相对稳定的插件类名。
- 混淆类提供候选回退：例如特性类先试 `aa.b2$a`，再试 `aa.AbstractC0958b2$a`；图标类先试 `aa.p20`，再试 `aa.AbstractC2229p20`。
- 混淆方法通过方法签名/参数数量寻找，避免把单个字母方法名写死。
- 自适应滑杆按 `SeekBar`/`TextView` 字段类型定位，并用 `SliderRegistry` 只装饰模块自己创建的实例。
- 每个 Hook 都有日志和失败降级；升级 ColorOS 后查看 `OPPOAirPods` 日志中的 hook failed 项。
- **不能保证** OPPO 大版本更新后仍自动可用；若官方组件改了类名、方法签名或数据包格式，需要重新逆向核对并发布新的 3.4.x 版本。

## 关于页

`关于`是增强菜单最后一页，包含：

- 当前版本与 versionCode。
- 查看 GitHub 仓库。
- 请求给项目点 Star。
- 关注作者 `arkylin`。
- 打开最新 GitHub Release。
- LibrePods 功能对照（每项的状态和所在位置/原因）。
- 数据位置图例。
- 代码来源、GPL-3.0 说明和免责声明。

模块 APK **没有 INTERNET 权限**，不做联网请求、不检查更新、不上传日志。
GitHub 链接只通过 `Intent.ACTION_VIEW` 交给系统浏览器；日志分享由用户在系统分享面板里选择目标。

## 环境要求

- ColorOS / OPlus，Android 13 及以上；主要在 ColorOS 17、Android 17 上验证。
- LSPosed API 101 或兼容版本。
- 已配对 AirPods；AirPods 5 已验证，AirPods Pro 3 待真机验证。
- **COUI**（Apache-2.0）：Slider 的尺寸、关键点、按压动画和配色参数参考；使用 Android `SeekBar`/`Drawable` 独立重实现。
- 构建需要 JDK 17+、Android SDK Platform、Build Tools 和仓库内附带的 LibXposed API JAR。

## 安装

1. 从 Release 下载 `OPods-v3.4.49-arm64-v8a.apk` 后安装：`adb install -r OPods-v3.4.49-arm64-v8a.apk`。
2. 3.4.49 继续使用 3.4.27 起的正式签名证书，可从 3.4.27 之后的版本直接覆盖安装；从更早的 debug 签名包升级仍需先卸载。
3. 在 LSPosed 中启用模块，勾选三个作用域：`com.heytap.mydevices`、`com.heytap.accessory`、`com.android.bluetooth`。
4. 停止 `com.heytap.mydevices` 和 `com.heytap.accessory`；通常不需要重启手机。若 Bluetooth 进程的 Off 过滤需要重新注入，再停止一次蓝牙进程。
5. 打开 AirPods 的官方设置页，确认出现官方噪声横条和“AirPods 增强”入口；连接一次 AirPods 后，“设备 → 识别密钥”应显示已获取。

LSPosed 的启用、作用域勾选、重启/停止进程由设备使用者完成；模块不会自动修改 LSPosed 配置。
覆盖安装后如果模块没有重新生效，先在 LSPosed 中停用再启用，然后停止相关官方 App。

## 构建

```powershell
.\build.ps1
```

默认输出 `dist\OPPO-AirPods-Enhanced-v<versionName>.apk`，版本号取自 `AndroidManifest.xml`。
存在 `.keystore/opods-release.jks` 和 `.keystore/opods-release.pass` 时默认使用正式 Release 证书。
自定义输出：

```powershell
.\build.ps1 -Output dist\OPods-test.apk
```

协议、开盖广播、密钥校验和 ATT 数据层的 JVM 单测在 `test/`，不需要 Android 运行时：

```bash
S=src/io/github/oppoairpods/popup
javac -encoding UTF-8 -d out $S/AacpProtocol.java $S/AirPodsAttData.java $S/HeadGestureTracker.java \
    $S/AppleAdvertisement.java $S/ProximityCrypto.java $S/ListeningModeTracker.java $S/LidGate.java \n    test/io/github/oppoairpods/popup/*.java
java -cp out io.github.oppoairpods.popup.AacpProtocolTest
java -cp out io.github.oppoairpods.popup.ProximityTest
java -cp out io.github.oppoairpods.popup.ListeningModeTrackerTest
java -cp out io.github.oppoairpods.popup.LidGateTest
```

GitHub Actions 在 `.github/workflows/build-apk.yml`，先跑上述单测，再读取 AndroidManifest.xml 的版本号并构建
`OPods-v<version>-arm64-v8a.apk`。

- Push `main`：上传 Actions artifact，不创建 Release。
- Push `v<version>-arm64-v8a` 标签：构建并创建 GitHub Release，附带 APK 和 `.sha256`。
- 正式 Release 必须配置仓库 Secrets 中的 `ANDROID_KEYSTORE_BASE64`、密码和 alias；缺少 Secrets 时只允许生成 CI 调试签名 artifact，tag 发布会被拒绝。
- Pull Request 构建始终使用临时 CI 调试签名，不读取正式签名 Secrets。
- 发布流程：只升小版本号（`AndroidManifest.xml`）→ 提交 → 推送 `main` 与 `v<version>-arm64-v8a` 标签 → workflow 构建并发布 Release。
- 当前 APK 由 Workflow 产物发布，不需要把生成 APK 提交到源码树。

## 诊断

日志：

```text
/sdcard/Android/data/com.heytap.mydevices/files/oppo_airpods/debug.log
```

也可以在面板“设备 → 故障排查”里分享最近日志，或抓 `OPPOAirPods` tag 的 logcat。升级 OPPO 组件后重点看：

- `MyDevices hooks installed`、`official Accessory PTC output hook installed`、`official Accessory persistent filter hook installed`
- `Hook failed` / `hook failed`
- accessory：`official persistent scan: AirPods lid-open filter added`（亮屏后官方扫描并入过滤时）、`official scan heard an AirPods lid opening rssi=... (#n)`、`dropped broad legacy AirPods filter`
- `:cards`：`official keep-alive lid wake hook installed`、`Accessory reports the lid-open filter in the official scan`、`lid wake from Accessory rssi=...`、`Case-open advertisement for ...`、`case-open ignored: ...`、`lid advert battery -> official popup ...`、`official AirPods popup requested (official connect)`、`official popup connect started`
- 不用 ADB 也能看：面板“连接 → 开盖弹窗”的“官方扫描”（accessory 侧是否生效）和“最近开盖广播”（最近一次送到 MyDevices 的开盖广播与校验结果）
- `proximity keys stored: IRK=true ENC=true`
- `official feature support forced for AirPods`
- `official popup battery hook installed` / `official detail battery hook installed`
- `AACP battery L=.../... R=.../... C=.../...` 和 `AACP battery -> official UI [...]`
- `official device battery hook installed` 和 `AACP battery -> official DeviceInfo [...]`
- `AACP channel #n warm-up retry` / `background retry disabled; stopping`
- `head gesture call monitor started`、`incoming call: listening for nod/shake`
- `ignored while switching to ...`、`official noise update a -> AACP mode b`、`listening mode not confirmed`
- `ATT channel closed` / `ATT write not acknowledged`
- `control queued until AACP opens`

AACP 调试时先停止其他占用 PSM `0x1001` 的工具，例如 LibrePods；ATT 使用 PSM 31。

## 代码来源与许可

本项目按 GNU GPL v3.0 或更高版本发布，见 `LICENSE`。详细第三方说明见 `THIRD_PARTY.md`。

- **LibrePods**（`v1.0.4`，GPL-3.0）：AACP 行为、能力表、ATT 通透/助听数据结构、开盖广播格式、IRK/ENC 处理、头部手势与对话感知行为、型号范围参考。
- **HyperPods-X**（GPL-3.0）：HyperOS 原生弹窗、官方设置注入与 AACP 思路参考。
- **airpods-off-fix**：ColorOS Off 帧、能力位、假断开行为参考；该提交未附明确许可文件，
  本项目只记录公开协议事实，代码由本项目独立实现。
- **LibXposed API**（Apache-2.0）：编译期 LSPosed API；运行时由 LSPosed 提供。

ColorOS、OPlus、MyDevices、Apple 和 AirPods 的系统类、商标和产品归各自权利人所有；
本项目不包含其专有源码或资源，所有官方类均通过运行时反射/Hook 使用。

## 免责声明

- 本项目与 Apple、OPPO、一加、ColorOS 官方没有隶属、授权、赞助或背书关系。
- 本项目仅用于互操作性研究和个人设备增强，不保证适用于所有 ROM、系统版本、固件或 AirPods 型号。
- 修改系统进程、使用 LSPosed、覆盖安装和系统升级可能导致功能失效、设置页异常、蓝牙不稳定或其他风险；使用风险由使用者承担。
- 请自行保留系统更新、模块 APK 和签名 keystore 的回退路径；不要在生产、医疗或依赖助听功能的场景中把它当作唯一方案。
- 模块本身不联网、不收集个人数据、不上传日志；GitHub 链接交由系统浏览器处理。
- 如你所在地区或设备不允许此类修改，请停止使用并卸载模块。
