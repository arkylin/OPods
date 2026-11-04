# OPods 3.4.43 代码审查与修复记录

审查日期：2026-11-04

审查范围：`src/io/github/oppoairpods/popup` 下 27 个 Java 源文件、`xposed/scope.list`、`AndroidManifest.xml`、构建脚本和 28 个 Hook 点。

## 结论

- 模块 APK 没有 INTERNET 权限，没有自建 Activity、Service、Receiver、Alarm、WakeLock 或前台/后台常驻进程。
- 没有自建 BLE 扫描：源码中不存在 `startDiscovery`、`startScan`、`BluetoothLeScanner` 或 `connectGatt`。
- Hook 只落在 `com.heytap.mydevices`、`com.android.bluetooth`、`com.heytap.accessory` 三个官方作用域内，不 Hook 第三方 App、桌面、SystemUI 或 Settings。
- AACP/ATT 是官方页面打开期间建立的 L2CAP 连接，不是 BLE 扫描；3.4.43 起页面关闭 1.5 秒后会停止会话。
- 自定义 A2DP/HFP 主动连接路径已删除，连接与弹窗重新交给官方 `AirpodsDiscoveryActivity` / `AirpodsBluetoothManager`。

## 本次修复

### 会话生命周期与耗电

- `onSettingsPageOpened()`、`onPanelOpened()` 会取消待执行的停止任务并确保 AACP 会话存在。
- `onSettingsPageClosed()`、`onPanelClosed()` 在最后一个官方页面关闭后延迟 1.5 秒停止 AACP 与 ATT，避免页面关闭后线程长期重连。
- `stopSession()` 同时清除 `opened` 和首包等待状态。
- `AacpSession` 只在收到可解析的 AACP 事件后更新有效数据时间；握手 ACK 不再被误判为设备已经可控制。
- AACP 静默看门狗现在能正确回收“socket 已连接但没有任何有效回包”的会话。
- ATT 增加 8 秒连接超时和 8 秒静默回收，避免 `BluetoothSocket.connect()` 永久挂起。

### 官方连接路径

- 删除 `connectProfiles()`、`requestProfile()`、`isAudioConnected()` 以及 0/1.8/4.2 秒的主动 profile 重连。
- ACL/PTC 事件只负责调用官方 `AirpodsDiscoveryActivity`；实际连接继续由官方 `AirpodsDiscoveryViewModel.startConnect()` 和 `AirpodsBluetoothManager` 完成。
- 移除主线程 `lock.wait(350ms)` 阻塞点，避免 UI 卡顿风险。

### 跨进程状态

- 新增 `CrossProcessState`，用官方应用私有目录中的小型属性文件共享 `auto_connect` 和设置页可见状态。
- 修复 `:cards` 写入自动连接开关后，主进程 SharedPreferences 缓存不可见的问题。
- 主进程发起官方弹窗前会同时检查本进程标志和跨进程设置页标志。

### 电量与控制中心

- 静默会话关闭时清空旧电量、佩戴和控制快照，并调用官方电池桥清除旧 UI。
- `OfficialBatteryBridge.clear()` 与 `OfficialDeviceStateBridge.clear()` 允许在无 AACP 快照时写入断开状态 `[-1,-1,-1,4,4,4]`，不再让控制中心继续显示旧值。

### ATT 与型号能力

- `TYPE_INFO` 解析出真实型号后再次调用 `ensureAttSession()`，修复 AirPods Pro 3 因首包前仍被当作 AirPods 5 而不启动 ATT 的问题。
- `AttSession` 收到任一有效 PDU 后刷新静默计时。

## 仍存在的风险

- 多个 Hook 依赖 ColorOS 混淆类名、短方法名或字段名，例如 `aa.b2$a`、`AirpodsSettingActivity.t0`、`SeekBarPreference.Z`、`AirpodsBluetoothManager.i0/P/C`、`f10092b/f10294e/f10462c`。OPPO 组件更新后可能静默失效，需要重新反编译核对。
- `AirPods Pro 3` 的 `CAP_HRM` 已建模但尚未实现；上游对应 AACP `0x30`。如果要接入系统健康数据，需要额外设计健康应用集成。
- 能力位并未覆盖所有 UI 门控：自适应音量、自适应音频、对话感知、耳机柄动作等仍可能对旧型号显示。当前主要验证型号为 AirPods 5 和 AirPods Pro 3。
- `AapOffFilter` 只过滤一次写入中完整匹配的 11 字节 `DISABLE_OFF` 帧；若未来系统拆分写入或修改帧格式，需要重新核对。
- `DisconnectFilter` 会屏蔽 8 秒用户断开窗口之外的假 `STATE_DISCONNECTED`；如果底层连接状态长期错误，可能延迟真实断开显示。
- 跨进程设置页标志使用 10 分钟时间窗；若官方设置页异常退出且未触发 `onDestroy`，最长可能在窗口内抑制弹窗。

## 验证

- `build.ps1` 编译 27 个 Java 文件通过。
- `AacpProtocolTest` 通过。
- `AppleAdvertisementTest` 通过。
- 3.4.43 APK：`dist/OPPO-AirPods-Enhanced-v3.4.43.apk`
- SHA-256：`7F41AAEDB7AC929633663EE2EA8AD267D62B855ABDC56285075682311AA4E863`

真机安装因审查结束时 ADB 设备已断开而未完成；重新连接后应安装正式签名包并复测：开盖弹窗、手动连接、设置页关闭后线程停止、控制中心电量清除、AirPods Pro 3 ATT 功能。
