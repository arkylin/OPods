# OPPO 耳机电量到控制中心的链路审计

审计对象：ColorOS 17 MyDevices 17.99.02、SystemUI 17.99.02、OPods 3.4.31。

## 结论

控制中心里的 AirPods 设备磁贴不是直接读取 AACP，也不是读取 OPods 的 Dashboard。它读取
MyDevices 提供的 `DeviceInfo.batteryList`，最终由 SystemUI 的
`com.oplus.deviceplugin.sdk` 设备卡绘制。OPPO 原生 TWS 的更新链路是：

1. ColorOS 蓝牙栈通过 `OplusBluetoothExtendFeatureCallback.onBatteryLevelChangedCallback`
   把组件电池数组交给 MyDevices。
2. `BluetoothStatusObserver` 把回调分发给已注册的扫描器。
3. `AirpodsScanPolicy.onBatteryLevelChanged(BluetoothDevice, int[])` 更新
   `DeviceInfo.batteryList`，然后调用 `IDeviceObserver.onDeviceUpdated(DeviceInfo)`。
4. `DevicesManager$e.onDeviceUpdated` 调用 `CachedDevices.n(deviceInfo, true)`。
5. `CachedDevices.n` 在强制更新时发送 `sendStatusDataChangedEvent(deviceInfo)`，同时更新缓存。
6. `ControlCenterManager` 的 `ICachedDeviceObserver.onStatusDataChanged` 收到通知，重建
   `PanelDevice` 响应，再通过 `H()` 调用 `notifyPanelDevicesChanged` 推给控制中心。
7. SystemUI 的 `com.oplus.deviceplugin.sdk.ui.view.progressbar.BatteryCircularProgressLayout`
   按 `BatteryInfo.charge` 显示或隐藏闪电图标；`PanelDevice` 来自
   `ControlCenterManager` 的 `DeviceSpaceFactory`。

因此，只要 `DeviceInfo.batteryList` 的 `charge` 字段变回 `true`，控制中心就重新显示闪电。

## 为什么 AirPods 只更新第一次

`AirpodsScanPolicy` 的入口本身是事件驱动的，不是轮询。它依赖系统蓝牙栈把后续电池变化
交给 `onBatteryLevelChanged`。AirPods 的后续电量实际在 AACP `0x04` 通知里，ColorOS 的
蓝牙框架并不会稳定地把每次 AACP 电量变化都重新分发给该回调，所以会出现：

- 首次连接时框架回调解出一次电量；
- 后续 AACP 已经变化，但 `AirpodsScanPolicy` 没有再次被框架调用；
- 控制中心仍保留第一次的 `charge=true`，继续显示闪电充电图标。

3.4.29 只修了官方弹窗和耳机页的 View，没有修 DeviceInfo 上游，因此控制中心磁贴不受影响。
3.4.30 首次接入 `AirpodsScanPolicy.onBatteryLevelChanged`，但对相同 AACP 数组做了去重；
旧框架值覆盖 DeviceInfo 后，如果没有新的数组值，就不会再次写回。3.4.31 去掉了这个去重，
并在每次 AACP 电量、双耳入耳状态及官方弹窗/耳机页回调时重新触发官方 DeviceInfo 更新。

## 组件状态映射

AACP `0x04` 的每个组件按以下规则映射到官方数组：

| AACP status | 含义 | 官方 `int[]` 状态 | UI |
| ---: | --- | ---: | --- |
| `1` | 充电 | `1` | 显示闪电/充电环 |
| `5` | 优化充电 | `1` | 显示闪电/充电环 |
| `2` | 未充电 | `2` | 不显示闪电 |
| `4` | 断开 | `4` | 隐藏该组件 |

官方数组顺序为 `[左耳, 右耳, 盒, 左状态, 右状态, 盒状态]`。

## 功耗审计

- OPods 没有新增 Activity、Service、Receiver 或独立进程，也没有联网。
- AACP 读取是一个 MyDevices 进程内的 daemon 线程；连接建立后阻塞在
  `InputStream.read()`，电量、佩戴、控制回读都由耳侧通知驱动，不按键轮询。
- 正常连接状态下主要事件是约每分钟一次的电量通知和佩戴变化；不做周期扫描。
- ACL 断开或 AACP 链路失效时立即停止 AACP；不保留后台重连 watchdog。
- 只有官方弹窗或耳机页可见时才允许 AACP 链路异常重试；页面关闭后不重连，等待下一次官方弹窗/耳机页触发。
- 3.4.45 起，AACP 电量在 `:cards` 进程产生，经官方 CoreService IPC 发送到主进程 `AirpodsScanPolicy`，再由官方 `DeviceInfo`/`CachedDevices` 刷新控制中心磁贴。
## 3.4.45 磁贴电量链路

`:cards` AACP battery event -> CrossProcessState snapshot -> official CoreService IPC (`airpods` / `OPODS_AACP_BATTERY_V1`) -> core `AirpodsManager.process` hook -> `AirpodsScanPolicy.onBatteryLevelChanged` -> `DeviceInfo.batteryList` -> `CachedDevices` -> `ControlCenterManager` -> SystemUI tile.

- AACP remains only while the official ACL is connected; no new process, service, scanner or reconnect watchdog is added.
- When the official popup/settings page closes, ATT stops and AACP retries are disabled; a link error stops the session until the next official popup/settings open.
- The cross-process snapshot is also written to the app-private state file, so a late-created `AirpodsScanPolicy` can seed the last valid battery once.


总体功耗与系统自带蓝牙耳机电量回调同一量级，额外成本主要是一条 AACP L2CAP 通道及其
事件通知，不是 CPU 常驻轮询。
