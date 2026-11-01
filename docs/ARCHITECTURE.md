# Architecture

## 目标

模块的核心目标不是复制一个耳机管理 App，而是在 ColorOS 已经提供的 AirPods 体验里补齐缺失能力：

- 复用 `com.heytap.mydevices` 自带的官方弹窗和 AirPods 设置页。
- 把增强条目直接注入官方偏好页，保留 COUI 的视觉、列表弹窗和点击语义。
- 在系统自己的蓝牙进程里修正 ColorOS 对 AirPods 的错误假设。
- 不创建 Activity、Service、Receiver、Provider，不常驻扫描，不依赖 LibrePods。

## 进程与作用域

`META-INF/xposed/scope.list` 只声明三个包：

| 包名 | 作用 |
| --- | --- |
| `com.heytap.mydevices` | 官方弹窗、扫描回调、设置页、AACP 控制通道和状态缓存 |
| `com.android.bluetooth` | 过滤 ColorOS 写入 AirPods 的 `DISABLE_OFF` 帧 |
| `com.heytap.accessory` | 同一 `DISABLE_OFF` 过滤的另一潜在写入进程 |

`META-INF/xposed/java_init.list` 唯一入口是
`io.github.oppoairpods.popup.AirPodsMyDevicesModule`。`module.prop` 使用
`minApiVersion=101`、`targetApiVersion=101` 和 `staticScope=true`。

`com.oplus.wirelesssettings` 不在作用域内。源码中的 `WirelessSettingsInjector` 是早期
“蓝牙设置 > 耳机功能”实验路径，当前 `onPackageLoaded` 不会调用它，也不会安装相关 Hook；
最终入口只存在于 MyDevices 的 AirPods 设置页。

## 启动与 Hook 顺序

`onPackageLoaded` 按包名分流：

```text
com.android.bluetooth  ─┬─> AapOffFilter
com.heytap.accessory   ─┘

com.heytap.mydevices  ───> MyDevices hook set
                            ├─ DeviceApplication.onCreate
                            ├─ AirPods 特性/扫描策略
                            ├─ 官方广播和 PTC 扫描回调
                            ├─ 官方设置 Fragment/Activity
                            └─ DisconnectFilter
```

每个进程只安装一次 Hook，用静态布尔值和锁避免 `onPackageLoaded` 重复执行。Hook 目标来自
ColorOS 私有 DEX，因此以下类名、方法名和混淆名属于版本相关契约：

- `com.heytap.mydevices.plugin.airpods.ui.AirpodsDiscoveryActivity`
- `com.heytap.mydevices.plugin.airpods.ui.AirpodsSettingActivity`
- `com.heytap.mydevices.plugin.airpods.ui.AirpodsSettingPreferencesFragment`
- `com.heytap.mydevices.plugin.airpods.core.AirpodsBluetoothManager`
- `com.heytap.mydevices.plugin.airpods.core.AirpodsScanHelper$registerPtc$2$a`
- `com.heytap.mydevices.DeviceApplication`
- `aa.b2$a`

## 官方弹窗与自动连接

开盖信息来自官方 PTC 扫描回调收到的 Apple proximity 数据。`AppleAdvertisement` 只解析
最小必要字段：产品 ID 和盒盖状态。盒盖打开时，`AirPodsController` 会：

1. 绑定已配对的 AirPods 地址，建立或复用控制状态。
2. 通过 `BluetoothA2dp`/`BluetoothHeadset` profile proxy 请求连接。
3. 在 0、1.8 和 4.2 秒三个时间点补一次连接，处理耳机仍被其他设备占用的场景。
4. 启动官方 `AirpodsDiscoveryActivity`，同时传入 `bluetooth_device` 和 `device_model`。

ACL 连接广播也会触发同一路径。设置页打开时不会重复弹窗；3.5 秒内也不会重复启动。
自动连接是否启用保存在 MyDevices 自己的 SharedPreferences `oppo_airpods_enhanced` 中。

## AACP 控制通道

`AacpProtocol` 负责 AACP 组帧和解析，`AacpSession` 在 MyDevices 进程里运行一个 daemon
线程，按 Android 版本尝试多个隐藏 `BluetoothSocket` 构造器，连接：

- PSM：`0x1001`
- Service UUID：`74ec2172-0bad-4d01-8f77-997b2be0722a`
- 帧头：`04 00 04 00`

连接完成后依次发送握手、能力声明和通知订阅，再周期读取输入流。读循环断开时使用指数退避
重连（1 秒到 30 秒），写命令有单独锁。若通道尚未建立，界面操作会进入有限长度的待发送
队列，通道打开后再补发；相同逻辑也用于 EQ。

`AirPodsController` 是 MyDevices 进程内的单例状态源，保存：

- 当前设备地址、型号、固件和左右耳/盒电量；
- 控制 ID 最近一次回读值；
- 佩戴状态、充电状态和设置页生命周期；
- 各官方条目注册的 UI listener。

状态只存在目标官方进程的内存和该包自己的 SharedPreferences 中，不向外导出 Provider 或文件接口。

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

- 模块 APK 没有应用进程；所有代码都在三个官方目标进程中运行。
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
