# OPods 3.4.47 全量代码审查

审查日期：2026-11-06
基线：`c6eb003`（3.4.47 / versionCode 81）。本次只审查，没有修改任何代码，也没有操作手机。

## 结论

- **硬性规则通过**：`src/` 和 `AndroidManifest.xml` 里没有自建 BLE 扫描、Service、Receiver、Alarm、WakeLock、网络访问或外部进程调用（关键字扫描 0 命中）；Manifest 没有声明任何组件和权限。自有脚本没有重启命令；`.keystore/` 和密码文件从未进入 git 历史。
- **最该先修的两类问题**：一是听力/通透写入路径（只影响 Pro 系列，但可能覆盖耳机里保存的听力数据）；二是默认开启的入耳播放，它会在用户没放音乐时启动播放（当前 AirPods 5 就受影响）。
- **`allowRetries` 状态混乱**：后台实际可以无限重连，和 README、`BATTERY_UPDATE_FLOW.md` 的承诺不符。
- **开盖路径**：这条路径的多个 Hook 还在，其中 ScanFilter 放宽 Hook 从未生效。要不要保留这条路径需要决定（见「待决定」）。

## 范围与验证方式

| 范围 | 处理方式 |
| --- | --- |
| `OPods/src` 27 个 Java 文件（7342 行）、Manifest、`xposed/`、`build.ps1`、CI 工作流 | 逐行阅读 |
| `tools/*.ps1`、`root-launcher` 的 `Daemon.java`、`Main.java` | 阅读 |
| `root-launcher` 其余 probe 和 `OppoAirPodsPopup` 中与主线同名的旧文件、`WirelessSettingsInjector.java` | **只做高危关键字扫描，未逐行审** |
| `OppoAirPodsPopup` 独有的 `BootReceiver`、`CaseScannerService`、`MainActivity`、`NativeAirPodsControl` | 阅读 |
| `ColorPods`（HyperPods-X 的本地 ColorOS 分支，26 处本地改动） | **未审**：独立 App 路线，含应用内 BLE 扫描，与当前硬规则冲突 |
| `HyperPods-X`、`LibrePods-v1.0.4`、`references/airpods-off-fix`、`apk_audit` 反编译代码 | 第三方代码，不审，只用来对照 |

证据等级：

- **[复现]** 写了探针或跑了单测，结果可重复。
- **[日志]** 由已保存的真机日志佐证（`apk_audit/opods_345_debug.log` 等）。
- **[对照]** 与反编译官方代码或 LibrePods 源码对比得出。
- **[阅读]** 只由代码逻辑推出。

没有任何一条在本次真机上复测。没有 AirPods Pro 3，第 1 节全部未经真机验证。

## 1. 听力与通透写入（Pro 系列；AirPods 5 页面提前返回，不受影响）

### 1.1 调右耳 EQ 会同时改左耳 [阅读]
`AirPodsDashboard.java:797`

`value.leftEq[index] = next;` 没有条件，下一行才判断右耳。右耳滑杆一动，左耳同频段被覆盖后写入助听参数。

修法：
```java
if (bothEars || leftEar) value.leftEq[index] = next;
if (bothEars || !leftEar) value.rightEq[index] = next;
```

### 1.2 EQ 滑杆量程差 100 倍 [对照][复现]
`AirPodsDashboard.java:794-796`

代码按 0–1 显示和写回，LibrePods 通透 EQ 的量程是 `0f..100f`（`TransparencySettingsScreen.kt:306`）。同一页的听力图输入框（`:715`）又直接显示原始值，页内自相矛盾。

探针结果：设备值 40 显示成 100%，拖到 60% 写入 0.60，而不是 60。碰一下滑杆，该频段增益就几乎清零。

### 1.3 平衡方向写反，放大值还会漂移 [复现][对照]
`AirPodsDashboard.java:807-808`，与 `AirPodsAttData.java:71-72` 不互逆

```
amp=0.20 bal=-0.40 -> L=-0.20 R=0.20 -> 重新读取 net=0.00 bal=+0.40   （方向翻转）
amp=0.20 bal=+0.40 -> L=0.20  R=0.60 -> 重新读取 net=0.40 bal=+0.40   （放大值漂移 bal/2）
```

往「左耳」方向拖，反而把左耳调低，LibrePods 是调高左耳（`HearingAidAdjustmentsScreen.kt:130`）。

修法：写入用 `left = net - bal/2; right = net + bal/2`，正好是解析公式的逆运算。

### 1.4 没读到数据时也能写，会覆盖耳机里的听力参数 [对照]
`AirPodsAttData.java:85-92`，`AirPodsDashboard.java:670-676`、`:686`

- `hearingAidData` 不足 104 字节时，用全 0 模板加默认值写入，帧头是 `00 00 64 00`。设备实际读回的帧头是 `02 02 60 00`。
- LibrePods 在这种情况下直接拒绝发送（`HearingAid.kt` 的 `Current data size ... cannot send settings`）。
- `Settings.defaults()` 的 `balance=0.5` 会让第一次拖动放大滑杆就偏向右耳。
- 听力图输入（`:728`）没有范围校验，`NaN`、`999` 都会写入。

修法：读到数据前禁用所有写入控件，输入值限制范围。

### 1.5 界面承诺「通道建立后写入」，实际没有这个机制 [阅读]
`AirPodsDashboard.java:648`，`AirPodsController.java:294-300`，`AttSession.java:178`

- ATT 未连接时只改本地缓存，连接后的读取（`:178`）会把它覆盖，修改静默丢失。
- 这时滑杆仍可操作。
- `:733` 的「已写入听力图」不判断结果，总是提示。

## 2. 多副 AirPods 同时配对（目标设备另需支持 Pro 3）

### 2.1 总是取第一副已配对的耳机，会拆掉正确的会话 [阅读][对照]
`AirPodsController.java:731`；调用点 `AirPodsMyDevicesModule.java:521`、`:850`、`:960`，`QuickNoiseInjector.java:45`

配对了 A、B 两副，B 连上时：弹窗 `onResume` / `t0` 取到 A → `attachDevice(A)` → 停掉 B 的会话并清空状态 → 对未连接的 A 发起 AACP → `LinkLost`。B 拿不到电量和控制，每次 `t0` 还会再来一遍。

正确的设备官方已经给了：`AirpodsDiscoveryActivity.bluetoothDevice`、`AirpodsDiscoveryViewModel.getBluetoothDevice()`。

修法：优先用官方给的设备；只有在拿不到时才回退，并优先选 `isConnected()` 的那副。

### 2.2 保存和待发送的状态不分设备 [阅读]
`AirPodsController.java:932-934`、`:928-930`、`:968-982`、`:580-589`

- `device_control_*`、`device_equalizer` 没有按地址区分，恢复时也不检查能力，Pro 3 的助听/PPE 设置会发给 AirPods 5。
- 换设备时不清 `pendingControls`、`pendingRename`、`pendingEqualizer`、`transparencyData`、`hearingAidData`、序列号。
- 结果是 A 的听力模板可能写进 B。

## 3. 当前 AirPods 5 就会遇到的问题

### 3.1 入耳播放会在没放音乐时启动播放 [阅读][对照]
`EarPlaybackController.java:14-16`，`AirPodsController.java:170`（默认开启）

`dispatch()` 只要发出按键就返回 `true`，没有判断 `isMusicActive()`（LibrePods `MediaController.kt` 有这个判断）。

- 没在放音乐时摘下再戴上，会发 `KEYCODE_MEDIA_PLAY`，最近的媒体会话开始播放。
- `pausedByEar` 在断开时不清除，隔很久重连戴上也可能触发。

修法：
- 暂停前判断 `audio.isMusicActive()`，只有真的暂停了才置位。
- 在 `onAclDisconnected` 里清除标记。

### 3.2 头部手势整条链路没接上 [阅读]
`AirPodsController.java:126-129`、`:1153-1156`；`AirPodsDashboard.java:450-483`

- `headTrackingPackets`、`lastHeadGesture` 从未赋值，`HeadGestureTracker.accept()` 没有调用方，`head_gesture_calls` 只存不读。
- 所以「测试姿态接收」永远显示收到 0 个包，「最近识别」永远是「尚未识别」，「头部控制通话」开了也没用。
- git 历史里没有计数自增的代码，引入这些字段的提交是 `54db20d`。README:41 仍把它列为功能。

修法：要么接上（计数 + `accept` + 通话按键），要么先隐藏这组开关。

### 3.3 `allowRetries` 有多处互相覆盖，后台可以无限重连 [阅读]
`AacpSession.java:46` 默认 `true`，与 `:149` 的注释矛盾；`AirPodsController.java:601`、`:617`、`:636`、`:656`；`AirPodsMyDevicesModule.java:523`、`:537`

- **ACL 连接路径**：`onAclConnected → attachDevice → ensureSession` 新建的会话是 `true`。如果弹窗被 `settingsOpen` 或节流挡住（`AirPodsController.java:713-715`），它会一直保持 `true`，6.5 秒静默回收加最长 60 秒退避，只要 ACL 在就一直重试。这与 README:108、`BATTERY_UPDATE_FLOW.md:61` 不符，也碰到 AGENTS.md 的「禁止主动重连 watchdog」。
- **反向问题**：设置页打开时来一个电量事件，`OfficialBatteryBridge` 调到还没回收的旧弹窗 `t0`，Hook 里 `:850-851` 又把它改成 `false`。下次 socket 抖动，会话就在页面可见时停掉。

修法：新会话默认 `false`，只保留一处计算（`settingsPageOpen || panelOpen || 弹窗可见`）；`t0` Hook 不再改这个标记。

### 3.4 主线程可能被写锁卡住 [日志]
`AacpSession.java:259-274`、`:279-284`、`:116`

`sendInitialization` 持 `writeLock` 睡 480 毫秒，并且在 +0.6 秒和 +5 秒**无条件**重发，不管数据是否已经到了。点击控件走 `sendControl → write`，在主线程等这把锁。日志里 `15:39:17.770` 的 `retry-1` 和点击产生的 `AACP tx` 落在同一毫秒，符合这个现象。蓝牙写本身也在主线程。

修法：
- `lastReceiveAt > 0` 时跳过重发。
- 不要在锁内 sleep。
- 写操作放到单独线程。

### 3.5 ScanFilter 放宽 Hook 从未生效 [对照][日志]
`AirPodsMyDevicesModule.java:136`（只 Hook 三参数重载）；官方 `AirpodsScanHelper.java:265` 用的是两参数重载

保存的日志里 `PTC registration requested` 有 548 行（含重叠快照），`scan filter broadened` 0 行。

所以官方过滤器一直是 `{7,25,7}` 和 `{7,15,0}`。LibrePods 把 `data[2]==1` 当作已配对（`BLEManager.kt:336`）。**推断**：如果 AirPods 5 的第三字节也是 1，官方过滤器匹配不到它。这一层在之前的开盖分析里没有考虑到；不过日志里没有 AirPods 5 的实际广播字节，还需要真机确认。

**不要单独修这个 Hook**，否则会激活下面的 3.6。

### 3.6 开盖路径不校验是哪副耳机 [阅读]
`AirPodsMyDevicesModule.java:946-961`，`AirPodsController.java:685-699`

任何 Apple 开盖广播都会触发第一副已配对耳机的官方弹窗，包括别人的 AirPods，也不判断当前是否已连接。因为 3.5，目前基本触发不到；一旦过滤器放宽，在公共场所可能每 4.5 秒弹一次。

### 3.7 `detachAirPodsListener` 不是被动观察 [对照]
`AirPodsMyDevicesModule.java:357`、`:389`；README 第 114 行写的是「被动观察」

它把官方记录里的 listener 写成 `null`，并且这个改动一直保留。官方三处调用都做了判空（`m.java:118`、`core/a.java:481`、`FusionProvider.java:350`），**不会崩溃**，但这不算被动，也不是最小 Hook。

### 3.8 其他
- **面板界面数据冻结** `AirPodsDashboard.java:96`、`:172`：没有注册 Listener，头部只在打开时刷新一次，AACP 就绪后还显示「控制未连接」。开关内容要切换 Tab 才更新，头部切换 Tab 也不更新。[阅读]
- **EQ 重置无效** `AirPodsDashboard.java:314-317`，`AirPodsController.java:883-895`：`sendEqualizer` 不更新 `controls`，点「重置为平直」后滑杆停在旧位置，再拖一个滑杆又把另外两段的旧值发出去。[阅读]
- **断开时不清官方界面** `AirPodsController.java:681`：电量已置 -1，`schedule()` 因无快照直接返回，官方弹窗和详情页保留旧电量；`OfficialBatteryBridge.java:59`、`:68` 的 `schedule` 和 `clear` 共用 `SCHEDULED`，也可能互相吞掉。这里应改为调用 `clear()`。[阅读]

## 4. 协议与健壮性

### 4.1 `Framer` 可能吃掉后续帧（潜在问题，当前固件未触发）[复现][日志]
`AacpProtocol.java:323-326`

- 代码把 `00 00 04 00` 当作握手 ACK，LibrePods 记录的 ACK 是 `01 00 04 00`（`linux/airpods_packets.h:247`）。真实 ACK 走的是逐字节滑动分支。
- 滑动后残留的 `00 00` 和下一帧拼起来会命中 ACK 分支，被吃掉 16 字节。探针结果：ACK 后跟若干个 `00` 时丢 1 帧；ACK 恰好 4 字节时 17 帧全丢。
- **真机上没发生**：日志里 212/212 个 control 帧都解析成功，UI 转储里也有型号 `A3441`。所以这**不是**首连静默的原因，只是固件换了 ACK 形状就会炸。
- 现有单测 `AacpProtocolTest.java:43-47` 把请求字节当响应喂进去，覆盖不到这种情况。
- `:334-337` 的 0x4B 长度非法时也会丢掉同一缓冲里的后续帧。

修法：L2CAP 每次 read 就是一个 SDU（日志里 11 字节一条可以佐证），不以 `04 00 04 00` 开头的 SDU 整包丢弃，不留残留。

### 4.2 小问题
- **僵尸会话** `AacpSession.java:176`：`break` 时没把 `running` 置回 false，之后 `ensureSession` 不再新建，电量和控制失效，直到 ACL 断开。窗口很窄。[阅读]
- **退避不归零** `AacpSession.java:138`：`openAndRead` 只在停止时正常返回，所以这行实际不起作用，多次抖动后固定等 60 秒。[阅读]
- **连接中写入会中止连接** `AacpSession.java:110-126`、`AirPodsController.java:314`、`:328`：重命名和头部追踪没判断 `ready()`，写失败会 `closeSocket()`。[阅读]
- **面板标记卡死** `QuickNoiseInjector.java:47`：`onPanelOpened` 在 try 之前，注入失败后 `panelOpen` 一直为 true，ATT 不再停止。系统更新后最容易遇到。[阅读]
- **ATT 反复重建** `AirPodsController.java:802-803`：只判断 `isConnected()`，`onOpened` 之后紧接着的 `TYPE_INFO` 会杀掉正在连接的第一次尝试。[阅读]
- **线程池竞态** `AttSession.java:84`、`:105`：`stop()` 之后 `execute` 可能抛 `RejectedExecutionException`，抛到主线程点击回调里没人接。窗口很窄。[阅读]
- **恢复比较永远不等** `AirPodsController.java:970`：保存值 `[v]` 与设备值 `[v,0,0,0]` 比较，每次连接都重发。[阅读]
- **日志进程名永远是 unknown** `Logx.java:19`：类加载时进程名还没设置，保存的日志行都是 `[unknown/pid]`。排查注入进程时很不方便，改成懒加载即可。[日志]
- **日志偏多** `FeatureSupportEnabler.java:110`：每次状态变化都写文件，这份 3859 行的日志里占了 262 行。[日志]

## 5. 安全、构建与整洁

- **请求了不用的密钥** `AacpSession.java:271-272`：每次连接发 3 轮 `keyRequest`，向耳机索要 IRK/ENC 密钥，但代码不处理 0x31，请求没有意义。建议删除。[阅读]
- **测试不在仓库里**：OPods 仓库 0 个测试文件，CI 不跑测试。`tools/aacp-test` 在仓库外，本次对当前源码运行通过。[复现]
- **`build.ps1:10`**：默认输出路径写死。忘了改就会先删掉（`:215`）再覆盖 `dist/` 里已发布的 APK。建议从 Manifest 读取版本号。
- **`build.ps1:220`**：密码走命令行 `pass:`，建议用 `env:`。
- **CI 工作流**：`:12-13` 的 `contents: write` 全局生效，包括 PR；同仓库 PR 能拿到正式签名；`setup-android@v4.0.4` 只钉 tag。
- **过时文案和回退值**：`AirPodsDashboard.java:566`（「主动请求 A2DP/HFP」，该路径 3.4.43 已删）、`:852` 和 `ProtocolInfo.java:52`（「同时写本机别名」，代码没有 `setAlias`）、`:971`、`:980`（版本回退值还是 3.4.31/65）。
- **死代码**：`pendingSessionStop`，`CrossProcessState.battery()` 和 `batteryAddress()`，`OfficialSettingsInjector` 的 `addSwitch`、`addList`、`addEq`、`addInfo`，`AacpSession.sendPacket`。
- **`SliderTickDrawable.java:56-57`**：先 `setAlpha` 后 `setColor`，alpha 被覆盖。
- **工作区整理**：
  - `tools/root-launcher/src/probe/Daemon.java` 和编译好的 jar 是自建 ACL 监听进程；`OppoAirPodsPopup` 的 `CaseScannerService`、`BootReceiver` 是 BLE 扫描和开机自启。都不进当前 APK，但部署就违反硬规则，建议归档。
  - 工作区根目录有 8 个 0 字节的误建文件（文件名是中文句子片段）。

## 磁贴充电图标问题（遗留）的补充

- **方案 A（解决主进程注入）**有前置条件：主进程的 `AirPodsController` 没有快照，`onBatteryLevelChanged` 的替换不会触发（`AirPodsMyDevicesModule.java:234`），官方值会盖回来。缺的正是还没人调用的 `CrossProcessState.battery()`。另外 `OfficialDeviceStateBridge.register` 首次注册会回放最长 12 小时前的电量，届时也要处理。
- **方案 B**：`AirpodsManager.process` 的官方参数只有 7 个，都和电量无关。走这条 IPC 不需要 Hook 的路不通，需要找别的入口。

## 子审查结论中已否决的项

code-review 技能的初步结果只覆盖 `origin/main..HEAD`，其中几条核实后不成立：

| 原结论 | 否决原因 |
| --- | --- |
| `CrossProcessState` 跨进程丢写 | Manifest 显示设置页、弹窗都在 `:cards`，主进程目前也注入不上，现在只有一个写入进程。方案 A 落地后才成立。 |
| `SharedPreferences` 跨进程不同步、非 `:cards` 控制失败却返回成功 | 同上，所有 UI 都在 `:cards`。 |
| 官方 listener 置空可能崩溃 | 官方三处调用都判空。保留「不是被动」这一点（3.7）。 |
| 滑杆 `removeAllViews` 会删掉兄弟 View | 纯理论，真机 UI 已验收。 |

## 建议修复顺序

1. 3.1 入耳播放；1.1–1.4 听力写入（改动都小）。
2. 2.1 设备选择；2.2 按设备保存。
3. 3.3、3.4 会话状态机和写线程。
4. 3.2 头部手势：接上或隐藏。
5. 其余按需处理。

## 待决定

**开盖路径是留是删？** 涉及 `hookAirPodsScanFilter`、`hookScanCallback`、`hookFusionEvents`、`hookCoreService`、`installAccessoryPtc`、`AppleAdvertisement`、`onCaseAdvertisement`、`auto_connect` 开关和 `com.heytap.accessory` 作用域。

- **删除**：去掉 5 个 Hook 点和 1 个作用域，3.5、3.6、3.7 一起消失。代价是以后要重做。
- **保留并继续做**：先修 3.6，再修 3.5，然后真机确认 AirPods 5 的广播字节。

## 3.4.48 修复情况

3.4.48（versionCode 82）按上面的建议修复。开盖路径一度被删除，用户确认仍需要后已恢复并修复；同时按 LibrePods 补齐了功能（见末尾）。以下全部只经过编译、JVM 单测和代码复查，**还没有真机验证**。

| 条目 | 状态 | 做法 |
| --- | --- | --- |
| 1.1 右耳 EQ 改左耳 | 已修 | `Settings.setEq(band, value, left, right)` 只改指定的耳朵 |
| 1.2 EQ 量程 | 已修 | 滑杆按设备的 0–100 显示和写回 |
| 1.3 平衡方向/漂移 | 已修 | `setAmpBalance` 用解析公式的逆运算；单测验证往返不漂移 |
| 1.4 未读数据也能写 | 已修 | 助听写入必须用设备读回的 104 字节模板，否则不写；默认值改为中性；听力图按 -20–120 dB HL 校验 |
| 1.5 承诺通道建立后写入 | 已修 | 读到参数前控件禁用；写入失败如实提示并回到设备值 |
| 2.1 总取第一副耳机 | 已修 | 优先用官方页面给出的设备；回退时优先已连接的那副 |
| 2.2 状态不分设备 | 已修 | 设备设置按地址保存，切换耳机时清空状态与待发送队列；旧的全局设置迁移给升级后第一次连接的耳机 |
| 3.1 入耳播放乱启动 | 已修 | 只在确实有音乐在放时暂停并认领，断开时清除认领 |
| 3.2 头部手势未接上 | 已修 | 统计姿态包并识别点头/摇头；“启用头部手势”“点头接听/摇头拒接”开关保留并真正实现（响铃时识别，Telecom 接挂，失败改发耳机按键），另加备用追踪包开关 |
| 3.3 `allowRetries` 混乱 | 已修 | 新会话默认不重试；统一由 `wantsAacpRetries()`（弹窗/设置页/详情面板是否可见）决定 |
| 3.4 主线程等写锁 | 已修 | 写操作改到会话自己的单写线程；收到数据后不再重发初始化 |
| 3.5–3.7 开盖路径 | 已修 | 恢复全部开盖 Hook、`com.heytap.accessory` 作用域和开关；`setManufacturerData` 两个重载都放宽；开盖广播先用 IRK 校验 RPA，只响应自己的耳机，未拿到密钥时是否响应由开关决定；Accessory 侧仍会置空官方 listener，README 已如实说明 |
| 3.8 面板冻结 / EQ 重置 / 断开不清官方界面 | 已修 | 面板注册监听并按版本号重绘；`sendEqualizer` 同步本地状态；断开时调用 `clear()`，清除与刷新各用独立的排队标记 |
| 4.1 Framer 吃帧 | 已修 | 不以 AACP 头开头的 SDU 整包丢弃；区分“数据不够”和“长度非法”；单测覆盖真实 ACK 形状 |
| 4.2 僵尸会话、退避、连接中写入、面板标记、ATT 重建、线程池竞态、恢复比较、日志进程名、日志偏多 | 已修 | 见对应源码注释 |
| 5 密钥请求 | 已改 | 只在这副耳机还没拿到密钥时请求（类型 0x05，与 LibrePods 一致）；密钥用于开盖身份校验和广播电量解密，日志只记录“收到哪种密钥” |
| 5 测试 / build.ps1 / PR 签名 / 过时文案 / 死代码 / 滑杆 alpha | 已修 | 单测移入 `test/` 并进 CI；输出名取自 Manifest；密码走 `env:`；PR 不读正式签名 |
| 5 CI `contents: write`、`setup-android` 只钉 tag | 未改 | 需要拆分任务，本地无法验证 CI，留待下次 |
| 5 工作区整理（`Daemon.java`、`OppoAirPodsPopup` 扫描服务、根目录空文件） | 未处理 | 在仓库之外，删除前需用户确认 |
| 磁贴充电图标滞留 | 未处理 | 仍待在方案 A/B/C 中决定 |

Hook 点：3 个进程共 33 个（MyDevices 31、Accessory 1、Bluetooth 1）。

另按 LibrePods 补齐：对话感知手机端降音量/暂停、点头接听/摇头拒接、取下时断开音频、通过官方弹窗重新连接、耳机调节 EQ（0x53）、心率开关、识别密钥状态、全部控制项原始值、高级控制、日志分享/清空；做不到的（相机遥控、自动接管、多设备、伪装苹果设备、媒体辅助、查找、高质量双向音频）保留入口并在面板写明原因，汇总在“关于 → LibrePods 功能对照”。新增 `ProximityTest` 单测（含蓝牙规范 ah 测试向量）。
