# OPods

面向 OPPO / ColorOS 的 AirPods 原生体验增强模块（OPPO AirPods Enhanced）。它通过 LSPosed 把能力注入
ColorOS 自带的 MyDevices 与 Bluetooth 进程，复用官方 AirPods 弹窗和设置页，
不提供第二套桌面 App、常驻后台进程或独立耳机管理界面。

## 当前版本

- 版本：`v3.4.1`（`versionCode 35`）
- APK：`dist/OPPO-AirPods-Enhanced-v3.4.1.apk`
- SHA-256：`CB647BB4EA8D9C3137E8B70672083F4A37801F45321BB3CD86BEBA37524DB630`
- 已验证设备：一加 13（`PJZ110`）、ColorOS 17.0.0.103、Android 17、AirPods 5
- 已验证结果：MyDevices 官方 AirPods 设置页可显示增强条目；官方弹窗和 AACP 控制链路可用

AirPods Pro 3 的型号能力映射已经写在代码中，但仍需要对应真机逐项验证，不能把
“代码已覆盖”当作“硬件已验收”。

## 当前能力

- 在 MyDevices 的官方 AirPods 设置页内注入原生 COUI 偏好条目，不另开页面。
- 复用官方 `AirpodsDiscoveryActivity` 弹窗，并在开盖/ACL 事件后尝试自动连接 A2DP 与 HFP。
- 解锁官方噪声选择器，并提供噪声模式、自适应降噪强度等控制。
- 通过 AACP（BR/EDR L2CAP PSM `0x1001`）读取电量、佩戴状态、设备信息和控制状态。
- 官方页可调：自动连接、入耳播放/暂停、低电量提醒、自适应音量、单耳降噪、音量滑动、
  睡眠检测、优化充电、允许关闭聆听模式、麦克风、按压速度、长按时长、音量滑动时长、
  提示音音量；另含六种均衡器预设和设备信息。
- 阻止 ColorOS 连接时发送的 `DISABLE_OFF` 帧，并过滤 MyDevices 因空闲 HFP 断开产生的
  假 `STATE_DISCONNECTED`，避免设置页误灰显。
- 低电量通知和入耳媒体键控制都在官方进程内完成，不创建 Service、Receiver 或 Provider。

## 未实现

当前版本不是 LibrePods 的完整功能平移。以下项目仍在路线图中：空间音频头部追踪、
头部手势、听力辅助/听力测试、通透模式自定义、大声降低、自定义三段 EQ 滑杆、完整的
单击/双击/三击动作、通话管理、重命名、序列号展示和相机遥控。

## 设计边界

- 模块 APK 本身没有 Activity、Service、Receiver 或 Provider，也没有自己的后台进程。
- 模块不申请 root 权限；运行前提是设备已经安装并启用 LSPosed。
- 不依赖 LibrePods，不监听自己的 BLE 扫描，不维护第二套弹窗/设置 App。
- 只声明三个作用域：`com.heytap.mydevices`、`com.android.bluetooth`、`com.heytap.accessory`。
- 蓝牙设置里的“耳机功能”不再注入；增强入口只出现在 MyDevices 的 AirPods 设置页。

## 环境要求

- ColorOS / OPlus，Android 13 及以上；主要在 ColorOS 17、Android 17 上验证。
- LSPosed API 101 或兼容版本。
- 已配对 AirPods；AirPods 5 已验证，AirPods Pro 3 待真机验证。
- 构建需要 JDK 17+、Android SDK Platform、Build Tools，以及仓库内附带的 LibXposed API JAR。

## 安装

1. 覆盖安装 APK：`adb install -r dist\OPPO-AirPods-Enhanced-v3.4.apk`。
2. 在 LSPosed 中启用模块，并确认上述三个作用域都处于勾选状态。
3. 停止 `com.heytap.mydevices`、`com.android.bluetooth`、`com.heytap.accessory` 后重新打开
   MyDevices / 蓝牙设置；通常不需要重启手机。
4. 打开 AirPods 5 的官方设置页，确认出现官方噪声选择器和“AirPods 增强”条目。

LSPosed 的启用、作用域勾选和重启/停止进程等操作由设备使用者完成；模块不会自动修改
LSPosed 配置。覆盖安装后如果模块未重新生效，先停用再启用模块，然后强制停止相关 App。

## 构建

仓库使用轻量 PowerShell 构建，不依赖 Gradle。脚本会自动寻找
`ANDROID_SDK_ROOT`、`ANDROID_HOME` 或 `%LOCALAPPDATA%\Android\Sdk`：

```powershell
.\build.ps1
```

自定义 SDK、Build Tools、签名和输出路径：

```powershell
.\build.ps1 `
  -AndroidSdk C:\Android\Sdk `
  -CompileSdk 37.2 `
  -BuildToolsVersion 36.1.0 `
  -Keystore .keystore\oppo-airpods-debug.keystore `
  -Output dist\OPPO-AirPods-Enhanced-v3.4.apk
```

如果默认签名文件不存在，脚本会生成一个本地调试签名。要覆盖当前手机上的模块，后续 APK
必须继续使用与已安装版本相同的签名；原始调试 keystore 不应提交到 Git，可以放在被忽略的
`.keystore/` 目录中或通过 `-Keystore` 指定。

## GitHub Actions

`.github/workflows/build-apk.yml` 会在手动触发、推送 `main`、提交 Pull Request 和推送
`v*` 标签时构建 APK。工作流读取 `AndroidManifest.xml` 的版本号，产物命名为：

```text
OPods-v3.4-arm64-v8a.apk
OPods-v3.4-arm64-v8a.sha256
```

这个 APK 没有 native 库，是兼容 arm64-v8a 设备的通用包，不是按 ABI 拆分过的原生包。
推送 `v*` 标签时会创建 GitHub Release，但发布前必须配置以下仓库 Secrets，确保签名与
日常安装包一致：

- `ANDROID_KEYSTORE_BASE64`：keystore 文件的 Base64 内容
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

没有 Secrets 时，普通 CI 构建会生成一次性调试签名，只适合检查构建和下载试用，不能覆盖
用本地固定签名安装的旧版本。创建标签示例：

```powershell
git tag v3.4-arm64-v8a
git push origin v3.4-arm64-v8a
```

## 诊断

模块日志写到 MyDevices 的外部文件目录：

```text
/sdcard/Android/data/com.heytap.mydevices/files/oppo_airpods/debug.log
```

也可以按 tag `OPPOAirPods` 抓 logcat。排查 AACP 时先停止其他占用 PSM `0x1001` 的工具，
例如仍在运行的 LibrePods；否则控制通道可能被抢走。ColorOS 内部混淆类可能随 ROM 更新
改名，升级系统后应先看日志中的 hook 失败项。

## 许可

本项目按 GNU GPL v3.0 或更高版本发布，见 `LICENSE`。参考项目、LibXposed API 和
ColorOS 私有类的许可边界见 `THIRD_PARTY.md`。本项目不包含 Apple、OPPO 或 ColorOS 的
专有源码或资源；所有系统类均通过运行时反射/Hook 使用。
