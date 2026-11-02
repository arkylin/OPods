# OPods

面向 OPPO / ColorOS 的 AirPods 原生体验增强模块。它通过 LSPosed 只在 ColorOS 官方进程内工作，
复用官方 AirPods 弹窗和设置页，不提供第二套桌面 App、后台 Service、常驻扫描进程或独立耳机管理界面。

## 当前版本

- 版本：`v3.4.26`（`versionCode 60`）
- APK：`dist/OPPO-AirPods-Enhanced-v3.4.26.apk`
- SHA-256：`B0ACF545B1EC935726689294C7D0A57D27C11396C0F10EF59EBFE28CA382A91E`
- 已验证设备：一加 13（`PJZ110`）、ColorOS 17.0.0.103、Android 17、AirPods 5
- 3.4.19 已完成真机 UI 验收；3.4.20 新增独立“关于”页和逐控件协议/存储标注；3.4.26 已安装并通过一次官方 ACL/弹窗流程复测，已移除自建 BLE 扫描和主动重连

AirPods Pro 3 的能力映射已写入代码，但 ATT、助听、PPE 和头部手势仍需对应真机验收。

## UI：七页卡片面板

官方 AirPods 设置页只保留首屏噪声横条、自适应滑杆和一个“AirPods 增强”入口。
入口内部是七页卡片式面板：

`常用 / 音频 / 操控 / 连接 / 健康 / 设备 / 关于`

每个开关、分段按钮、滑杆和操作按钮下面都会显示：

- 使用的协议，例如 `AACP` 或 `ATT`。
- 发送的数据模板，例如 `AACP 09 00 26 01|02 00 00 00`。
- 设置位置：`设备`、`手机本地`、`手机协同` 或 `官方页面`。

AACP 控制包公共头是 `04 00 04 00`；面板里为了紧凑，通常只显示 `09 00 <id> <v0..v3>`。
ATT 使用 PSM 31，读 `0A`、写 `12`、通知 `1B`。

## 功能覆盖

- 噪声四模式、自适应降噪强度、自适应音量、对话感知、单耳降噪。
- 入耳播放/暂停、自动连接、低电量提醒、自动人耳检测。
- 麦克风模式、按压速度、长按时长、音量滑动与时长、提示音双字节载荷。
- 六种 EQ 预设、自定义三段 EQ、单击/双击/三击/长按动作、长按循环模式。
- 通话静音/挂断映射、自动接听、头部姿态接收与实验性点头/摇头识别。
- 自动连接 `0x20`、睡眠检测、优化充电、允许关闭、PPE。
- 重命名、序列号/固件/硬件版本/更新器信息。
- ATT 大声降低、通透自定义、助听器调节、双耳 8 段 EQ、听力图写回。
- 个性化空间音频只打开 OPPO 官方 Activity，不复制官方空间音频实现。

## 数据保存在哪里

| 位置 | 内容 | 说明 |
| --- | --- | --- |
| 设备 | 噪声、自适应音量、对话感知、单耳降噪、按键速度/时长、音量滑动、EQ、麦克风、人耳检测、自动连接 `0x20`、睡眠、充电、允许关闭、PPE、助听、通透 | 通过 AACP 控制或 ATT 写入 AirPods |
| 手机 | `ear_playback`、`battery_alerts`、`auto_connect`、`equalizer_preset`、`head_gestures`、`head_gesture_calls` | MyDevices 进程内 SharedPreferences，不写入耳机 |
| 手机协同 | 入耳播放、开盖连接、头部手势、通话接听键 | 耳机上报事件，手机执行媒体键/蓝牙 Profile/KEYCODE |
| 官方页面 | 个性化空间音频、部分 OPPO 原生设置 | 只发 Intent 打开官方 Activity，不直接改官方数据 |
| 只读 | 电量、佩戴、型号、序列号、固件、AACP/ATT 状态 | AACP `0x04`/`0x06`/`0x1D`、ATT `0A` 读取 |

`关于`页有完整图例，且每个控件下方都有对应的协议/存储标注。

## Hook 范围：只保留两个进程

`xposed/scope.list` 只声明：

| 包名 | 用途 | Hook 的入口 |
| --- | --- | --- |
| `com.heytap.mydevices` | 官方 AirPods 弹窗、详情页、设置页、AACP/ATT、自动连接 | `AirpodsSettingPreferencesFragment`、`AirpodsSettingActivity`、`AirpodsDetailPanelFragment`、`AirpodsManager` 接收器、`AirpodsScanHelper`、PTC 策略、`AirpodsBluetoothManager` 假断开过滤 |
| `com.android.bluetooth` | 只拦截 ColorOS 连接时发送的 `DISABLE_OFF` 帧 | `BluetoothSocket.getOutputStream`，只匹配精确 11 字节 `04 00 04 00 09 00 34 02 00 00 00` |

不再 Hook：

- `com.heytap.accessory`：不再作为作用域；Off 帧过滤只保留在 `com.android.bluetooth`，避免多进程重复 Hook。
- `com.oplus.wirelesssettings`：旧版“蓝牙设置 → 耳机功能”实验路径已删除，当前只使用 MyDevices 官方页。
- `com.oplus.melody`、Launcher、SystemUI、Settings：不 Hook。

### 为什么算最小 Hook

- 每个 Hook 只落在必要方法或必要构造函数上，不做全类替换。
- Bluetooth 进程只过滤一个精确帧，其他 socket 写入原样放行。
- MyDevices 中的 Hook 各自独立 try/catch；一个 Hook 失败不会让模块整体崩溃。
- 可选装饰 Hook（自适应滑杆样式）失败时，功能仍由 AACP/Dashboard 控制。
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
- 数据位置图例。
- 代码来源、GPL-3.0 说明和免责声明。

模块 APK **没有 INTERNET 权限**，不做联网请求、不检查更新、不上传日志。
GitHub 链接只通过 `Intent.ACTION_VIEW` 交给系统浏览器。

## 环境要求

- ColorOS / OPlus，Android 13 及以上；主要在 ColorOS 17、Android 17 上验证。
- LSPosed API 101 或兼容版本。
- 已配对 AirPods；AirPods 5 已验证，AirPods Pro 3 待真机验证。
- 构建需要 JDK 17+、Android SDK Platform、Build Tools 和仓库内附带的 LibXposed API JAR。

## 安装

1. 覆盖安装 APK：`adb install -r dist\OPPO-AirPods-Enhanced-v3.4.26.apk`。
2. 在 LSPosed 中启用模块，勾选两个作用域：`com.heytap.mydevices`、`com.android.bluetooth`。
3. 停止 `com.heytap.mydevices`；通常不需要重启手机。若 Bluetooth 进程的 Off 过滤需要重新注入，再停止一次蓝牙进程。
4. 打开 AirPods 的官方设置页，确认出现官方噪声横条和“AirPods 增强”入口。

LSPosed 的启用、作用域勾选、重启/停止进程由设备使用者完成；模块不会自动修改 LSPosed 配置。
覆盖安装后如果模块没有重新生效，先在 LSPosed 中停用再启用，然后停止相关官方 App。

## 构建

```powershell
.\build.ps1
```

自定义输出：

```powershell
.\build.ps1 -Output dist\OPPO-AirPods-Enhanced-v3.4.26.apk
```

GitHub Actions 在 `.github/workflows/build-apk.yml`，读取 AndroidManifest.xml 的版本号并构建
`OPods-v<version>-arm64-v8a.apk`。发布标签需要配置仓库 Secrets 中的 keystore 和 alias/password；
没有 Secrets 时只生成 CI 调试签名，不能覆盖本地固定签名版本。

## 诊断

日志：

```text
/sdcard/Android/data/com.heytap.mydevices/files/oppo_airpods/debug.log
```

也可以抓 `OPPOAirPods` tag 的 logcat。升级 OPPO 组件后重点看：

- `MyDevices hooks installed`
- `Hook failed` / `hook failed`
- `official feature support forced for AirPods`
- `ATT channel closed`
- `control queued until AACP opens`

AACP 调试时先停止其他占用 PSM `0x1001` 的工具，例如 LibrePods；ATT 使用 PSM 31。

## 代码来源与许可

本项目按 GNU GPL v3.0 或更高版本发布，见 `LICENSE`。详细第三方说明见 `THIRD_PARTY.md`。

- **LibrePods**（`v1.0.4`，GPL-3.0）：AACP 行为、能力表、ATT 通透/助听数据结构、型号范围参考。
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
