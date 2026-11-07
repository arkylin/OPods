# Architecture

## 目标

模块的核心目标不是复制一个耳机管理 App，而是在 ColorOS 已经提供的 AirPods 体验里补齐缺失能力：

- 复用 `com.heytap.mydevices` 自带的官方弹窗和 AirPods 设置页。
- 把增强条目直接注入官方偏好页，保留 COUI 的视觉、列表弹窗和点击语义。
- 在系统自己的蓝牙进程里修正 ColorOS 对 AirPods 的错误假设。
- 不创建 Activity、Service、Receiver、Provider，不常驻扫描，不依赖 LibrePods。
- 需要后台感知的场景（开盖）只借用官方已经在跑的常驻扫描，过滤尽量放进蓝牙芯片。

## 进程与作用域

`META-INF/xposed/scope.list` 只声明三个包：

| 包名 | 作用 |
| --- | --- |
| `com.heytap.mydevices` | 官方弹窗、开盖唤醒接收（`:cards` 的官方保活服务）、设置页、AACP 控制通道和状态缓存 |
| `com.heytap.accessory` | 给官方常驻扫描追加 AirPods 开盖硬件过滤，一次开盖唤醒一次 MyDevices `:cards` |
| `com.android.bluetooth` | 过滤 ColorOS 写入 AirPods 的 `DISABLE_OFF` 帧 |

`META-INF/xposed/java_init.list` 唯一入口是
`io.github.oppoairpods.popup.AirPodsMyDevicesModule`。`module.prop` 使用
`minApiVersion=101`、`targetApiVersion=101` 和 `staticScope=true`。

`com.oplus.wirelesssettings`、`com.oplus.melody`、Launcher、
SystemUI 和 Settings 均不在作用域内。早期“蓝牙设置 > 耳机功能”实验路径已删除，最终入口
只存在于 MyDevices 的 AirPods 设置页。

## 启动与 Hook 顺序

`onPackageLoaded` 按包名分流：

```text
com.android.bluetooth  ───> AapOffFilter

com.heytap.accessory   ───> FusionProvider.d()（追加开盖过滤）
                            └─ 持久扫描结果出口 output.m.a → LidGate → 绑定 :cards 保活服务

com.heytap.mydevices  ───> MyDevices hook set
                            ├─ DeviceApplication.onCreate
                            ├─ AirPods 特性开关与官方电量策略
                            ├─ 官方 ACL 广播与弹窗生命周期
                            ├─ 官方设置 Fragment/Activity 与详情页
                            └─ DisconnectFilter
```

每个进程只安装一次 Hook，用静态布尔值和锁避免 `onPackageLoaded` 重复执行。Hook 目标来自
ColorOS 私有 DEX，因此以下类名、方法名和混淆名属于版本相关契约：

- `com.heytap.mydevices.plugin.airpods.ui.AirpodsDiscoveryActivity`
- `com.heytap.mydevices.plugin.airpods.ui.AirpodsSettingActivity`
- `com.heytap.mydevices.plugin.airpods.ui.AirpodsSettingPreferencesFragment`
- `com.heytap.mydevices.plugin.airpods.core.AirpodsBluetoothManager`
- `com.heytap.mydevices.plugin.airpods.core.AirpodsScanPolicy`
- `com.heytap.mydevices.DeviceApplication`
- `aa.b2$a`

3.4.20 对关键路径增加了兼容回退：
`SeekBarPreference` 的绑定方法和 `SeekBar`/`TextView` 字段按签名与类型查找，`DisconnectFilter` 优先用
设备自身的 `isConnected` 判断；模块创建的滑杆通过 `SliderRegistry` 标记，只装饰自己的实例。其余 COUI
混淆方法名和特性开关仍保留已知名契约，不能把“可回退”理解为大版本升级后必然可用。

## 官方弹窗与设备选择

连接本身完全由 ColorOS 完成，模块不发起 A2DP/HFP 或任何 profile 连接（3.4.43 删除）。
官方 `AirpodsManager$c` 收到 `ACL_CONNECTED` 后，`AirPodsController` 才启动官方
`AirpodsDiscoveryActivity`，同时传入 `bluetooth_device` 和 `device_model`。设置页打开时
不会重复弹窗；3.5 秒内也不会重复启动。

开盖弹窗借用 OPPO 自家耳机的同一个官方常驻扫描，模块不另开扫描（3.4.50 重做）：

1. 官方扫描：`com.heytap.accessory` 的 pantaconnect `ScanAutomaticFilter` 在亮屏/解锁时启动
   “Auto”扫描（低延迟 1 秒 → 均衡约 5 分钟 → 低功耗），息屏即停；亮屏期间 `ScanFusionLePersistentFilter`
   把 `FusionProvider.d()` 返回的持久过滤并进去，扫描结果分类器 `c.b()` 也逐条用 `d()` 比对。
2. 模块在 `d()` 末尾追加一条 Apple `0x004C` 过滤：27 字节图样，掩码只看 `07 19` 和第 8 字节的开盖位
   （`0x08` 为 0 表示开盖）。图样覆盖整条消息，硬件卸载过滤才能命中。耳机戴着、盒盖合上时发的广播在芯片里就被丢掉。
   同时去掉 3.4.48/3.4.49 经官方 `registerPtc` 注册进来的 `07 19` / `07 0F` 两字节宽过滤。ROM 自己的
   `07 19 07` / `07 0F 00`（配对模式）原样保留，新 AirPods 的配对弹窗不受影响。
3. 结果出口 `output.m.a` 把 AirPods 状态消息交给 `LidGate`：同一次开盖只放行第一条（8 秒没有广播才算下一次开盖，
   大于低功耗档 5.12 秒扫描间隔）、RSSI ≥ −70（与官方 AirPods 注册一致）、双耳佩戴不算、多副同时开盖每 2 秒最多一次。
   这些消息不再交给 ROM：ROM 对已配对 AirPods 的开盖本来就不处理，交给它只会触发官方 FusionWakeup，
   而 FusionWakeup 有频控（30 秒内间隔按 2 秒递增到 300 秒），会把真正那次开盖拦掉。
4. 放行的那条经官方保活服务 `com.oplus.mydevices.keepalive.oaf.OafPersistentService`（`:cards`，
   action `com.heytap.accessory.PERSISTENT_BIND`，权限 `com.oplus.permission.safe.PERSISTENT`，accessory 本来就用它保活）
   绑定送到 MyDevices `:cards`，`onBind` 被调用后立即解绑；绑定失败才退回 `CoreService`。`:cards` 每次按需 fork，
   LSPosed 能稳定注入，不依赖注入不上的主进程。
5. `:cards` 里 `AppleAdvertisement` 解析 `0x07` 邻近消息（字段位置与 LibrePods 一致），
   `AirPodsController.onCaseAdvertisement` 做身份校验：系统已解析的身份地址直接比对；否则用 AirPods 经 AACP
   `0x30/0x31` 下发的 IRK 校验 RPA（`ProximityCrypto.verifyRpa`，即蓝牙规范 ah 函数）。有 ENC 密钥时解密出精确电量。
6. 通过校验的那副（或用户允许时、还没拿到密钥的那副）启动官方 `AirpodsDiscoveryActivity`；已验证时先把广播电量
   写进官方弹窗。弹窗已显示同一副耳机时，ACL 连上不再重复拉起。
7. 连接（3.4.51）：官方“回连”弹窗（`bluetooth_device` + `device_model`）只展示已连上的耳机，`getCurrentState()`
   在这种模式下恒为 `STATE_UNKNOW`，从不发起连接。耳机没连上时改用官方弹窗的连接模式：`device_info` 放一个官方
   `DeviceInfo`（broadcastType 2），弹窗进入发现/连接状态机；`onResume`/`onNewIntent` 看到模块的连接标记后调用一次
   `AirpodsDiscoveryViewModel.startConnect()`，即弹窗“连接/重试”按钮的同一调用：`AirpodsBluetoothManager.b0()`
   （setDevice）+ `f0()`（startBound，已配对设备直接官方 `connect()`，20 秒超时）。界面由官方显示正在连接 →
   已连接（电量）/ 连接失败（重试）。手动“通过官方弹窗连接”与“戴回恢复音频”（ACL 仍在、A2DP/HFP 已断）走同一条。
8. accessory 侧第一次把开盖过滤并进官方扫描时，用同一个保活绑定向 `:cards` 发一次就绪通知（每个 accessory 进程一次），
   面板“官方扫描”一行显示时间，用于不接 ADB 时判断 accessory 是否加载了新版模块。

限制：和 OPPO 自家耳机一样只在亮屏时扫描，息屏开盖不会弹窗；需要勾选 `com.heytap.accessory` 作用域，
装新版后停止一次 accessory（官方常驻进程）才会带上新注入。

模块需要知道“当前页面显示的是哪副耳机”时，按以下顺序取设备：

1. 官方给出的设备：弹窗 Intent 的 `bluetooth_device` 或弹窗 ViewModel 的 `getBluetoothDevice()`，
   设置页 ViewModel 的 `getBluetoothDevice()`，详情面板 ViewModel 中 `DeviceInfo.getMac()`。
2. 回退：已配对 AirPods 中当前持有 ACL 的那副，其次是已附着的那副，最后才是第一副。

切换到另一副耳机时，`AirPodsController` 会停止旧会话，并清空电量、控制值、待发送队列和
ATT 模板，避免把上一副的设置写进新的一副。用户保存的设备设置按耳机地址分别保存。

## AACP 控制通道

`AacpProtocol` 负责 AACP 组帧和解析，`AacpSession` 在 MyDevices 进程里运行一个 daemon
线程，按 Android 版本尝试多个隐藏 `BluetoothSocket` 构造器，连接：

- PSM：`0x1001`
- Service UUID：`74ec2172-0bad-4d01-8f77-997b2be0722a`
- 帧头：`04 00 04 00`

连接完成后依次发送握手、能力声明和通知订阅（不请求 IRK/ENC 等密钥）。只有还没收到任何数据时，
才会在 1.1 秒和 6 秒各重发一次初始化。所有写操作都在会话自己的单写线程上执行，调用方
（通常是主线程）不会被蓝牙 I/O 或初始化间隔阻塞。若通道尚未建立，界面操作会进入有限长度的
待发送队列，通道打开后再补发；相同逻辑也用于 EQ。

链路重试只有一个判断：官方 AirPods 弹窗、设置页或详情面板是否可见
（`AirPodsController.wantsAacpRetries()`）。可见时按 2–60 秒指数退避重连；不可见时链路一断
就停止会话，等下一次官方页面出现。首连另有最多 3 次、间隔 1.2 秒的热身重试，只在 ACL
仍在且本次连接没有收到任何数据时进行，用来拿到第一包电量。ACL 断开时立即停止会话，并把
官方弹窗、耳机页和 DeviceInfo 刷成断开状态。

ATT（PSM 31）只在官方页面打开期间运行。通透和助听参数必须先从本次连接读到，才允许写回；
写入沿用设备返回的数据头，读到之前面板里的相关控件保持禁用。

`AirPodsController` 是 MyDevices 进程内的单例状态源，保存：

- 当前设备地址、型号、固件和左右耳/盒电量；
- 控制 ID 最近一次回读值；
- 佩戴状态、充电状态和设置页生命周期；
- 各官方条目注册的 UI listener。

官方弹窗的 `t0(int[])` 与耳机页电池 binder 的 `void(int[], boolean)` 会把框架缓存交给
`OfficialBatteryBridge`。桥接层只在主线程用当前 AACP 组件级快照重绘同一组官方 View，
同时把 `status=1/5` 识别为充电、`2` 为未充电、`4` 为断开；不创建连接或后台线程。

控制中心磁贴走另一条上游链路：`AirpodsScanPolicy.onBatteryLevelChanged()` 把官方电池数组
写入 `DeviceInfo.batteryList`，再由 `ControlCenterManager` 生成 `PanelDevice`。3.4.31 在该
回调入口用 AACP 快照替换框架旧值，并在每次 AACP 电量/佩戴或官方 UI 回调时通过
`OfficialDeviceStateBridge` 重新触发官方回调；不再按相同数组去重，因此不需要 Hook
`com.android.systemui`。

状态只存在目标官方进程的内存、该包自己的 SharedPreferences，以及供 `:cards` 与主进程共享
电量快照的私有文件 `oppo_airpods_state.properties` 中，不向外导出 Provider 或文件接口。

## 噪声模式只认一个来源

噪声模式可以从四处切换：官方降噪栏、详情页快捷栏、增强面板和耳机柄动作，全部走
`AirPodsController.selectListeningMode()`：

- 选中的模式立即显示并经 AACP `0x0D` 发送；`ListeningModeTracker` 在发出后 3 秒内忽略耳机
  报回的其他模式（切换约有 1 秒延迟，期间的状态转储还带着旧模式），确认后放行；若耳机在收到命令后
  明确报回别的模式（例如没戴上被拒绝），窗口结束后以耳机为准。
- ROM 自己的蓝牙回调或恢复页面时会调用 `updateCurrentFeature`：模块换成 AACP 实时模式，并直接
  移动 `IconTextSelectorSettings` 的选中项（`setSelectedPosition`），不走 ROM 的整条重建
  （`d(items, index)` 会删掉并重新创建所有图标，表现为闪烁）。
- 自适应降噪强度滑杆直接显示 AACP `0x2E` 的数值，越大降噪越强。

## 与 LibrePods 对齐的手机端行为

这些行为都在官方 `:cards` 进程内、AACP 通道在线时运行，不新建进程、Service 或 Receiver：

- 头部手势接挂电话：开关打开且 AACP 在线时，在官方进程内监听 `AudioManager` 模式变化；
  响铃时开始头部追踪，`HeadGestureTracker` 识别到点头调用 `TelecomManager.acceptRingingCall()`，
  摇头调用 `endCall()`（MyDevices 持有 `MODIFY_PHONE_STATE`），不可用时改发耳机按键（短按接听、长按拒接）。
- 对话感知手机端：AACP `0x4B` 状态 `01/02` 开始说话、`06/08/09` 结束；按设置把媒体音量降到
  当前音量的比例或最大音量的上限，可同时暂停，结束后恢复。
- 取下时断开音频：双耳都不在耳内 3 秒后断开 A2DP/HFP（ACL 与 AACP 保留），戴回时打开官方弹窗，
  由官方流程恢复音频。
- 耳机调节 EQ（AACP `0x53`）、心率开关（`0x30`）以及 LibrePods 已定义但未开放的控制项：
  只写入，结果以耳机回报为准；面板标为“实验”。
- 做不到的 LibrePods 功能（相机遥控、自动接管、多设备、伪装苹果设备、媒体辅助、查找、高质量
  双向音频）在面板对应位置保留入口并写明原因。

## 官方设置页注入

入口 Hook 是 `AirpodsSettingPreferencesFragment.onCreatePreferences`。注入器先确认当前
设备确实是 AirPods，再找到 `cat_airpods_sound_quality` 与 `cat_airpods_settings` 两个
官方分组，使用官方 COUI Preference 类型追加条目：

- `com.coui.appcompat.preference.COUIListPreference`
- `com.coui.appcompat.preference.COUISwitchPreference`
- `com.coui.appcompat.preference.COUIJumpPreference`

ColorOS 的 Preference 实现经过混淆，标准 AndroidX 方法名并不存在。v3.4 的关键兼容层如下：

| 语义 | 优先方法 | ColorOS 混淆回退 |
| --- | --- | --- |
| 添加子项 | `addPreference` | `V0` |
| 标题 | `setTitle` | `O0` |
| 摘要 | `setSummary` | `L0` |
| 开关状态 | `setChecked` | `W0` |
| 列表 entries | 标准 setter | 字段 `X` |
| 列表 entryValues | 标准 setter | 字段 `Y` |

只有真实调用了存在的 setter 才能让条目显示；早期版本把标准 AndroidX 方法当作必然存在，
因此 Hook 成功但页面为空。官方噪声选择器由 `AirpodsSettingActivity.updateFeatureSupport`
之后的 `FeatureSupportEnabler` 强制展开，并刷新视图模型和官方手势/空间音频/对话分组。

所有 UI 更新都通过主线程 Handler 或 Android 自身的偏好回调执行，避免从 AACP 读线程直接改 View。

## ColorOS 修正

### Off 模式

`AapOffFilter` Hook `BluetoothSocket.getOutputStream`，只丢弃完全匹配的 11 字节
`DISABLE_OFF` 帧：

```text
04 00 04 00 09 00 34 02 00 00 00
```

同时把 AirPods 特性掩码补上 `0x200`，让官方 UI 把 Off 作为合法聆听模式。Off 修复本身
发生在系统原本的 socket 写出路径上，不依赖自建第二通道；AACP 状态读取仍使用前文的独立会话。

### 假断开

`DisconnectFilter` Hook `AirpodsBluetoothManager` 的分发方法和用户主动断开方法。用户主动
断开的 8 秒窗口内放行；窗口外收到 `STATE_DISCONNECTED` 时，如果管理器自身仍报告已连接，
则吞掉这次假状态，避免设置页变灰。

## 生命周期与资源

- 模块 APK 没有应用进程；所有代码都在两个官方目标包内运行。
- AACP 线程随官方进程生命周期结束，`stop()` 会关闭 socket 并中断线程。
- UI listener 使用 `WeakReference`/弱表或显式注销，设置页销毁时关闭页面状态。
- 日志最多约 512 KiB，超限轮转到 `debug.log.1`，避免无限增长。
- 模块不把 keystore、LSPosed 配置或设备数据写入仓库。

## 已知风险

ColorOS 升级可能重命名混淆类、改变 Preference 方法或修改 AACP 行为。任何新版 ROM 都应先：

1. 抓取 `OPPOAirPods` tag 的 logcat 或 MyDevices 外部 `debug.log`。
2. 核对 `scope.list` 是否仍为三个包。
3. 在真实 AirPods 上验证官方弹窗、设置页、噪声切换和自动重连。
4. 对 AirPods Pro 3 单独验证听力保护、头部手势等型号差异，不直接复用 AirPods 5 的结论。
