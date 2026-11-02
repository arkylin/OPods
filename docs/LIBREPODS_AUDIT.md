# LibrePods v1.0.4 → OPods 3.4.27 功能接入审计

审计日期：2026-11-02

对照：
- 上游：LibrePods-v1.0.4/android/app/src/main/java/me/kavishdevar/librepods
- 模块：OPods/src/io/github/oppoairpods/popup
- 目标：AirPods 5、AirPods Pro 3
- 构建：dist/OPPO-AirPods-Enhanced-v3.4.27.apk，versionCode 61
- SHA-256：B0ACF545B1EC935726689294C7D0A57D27C11396C0F10EF59EBFE28CA382A91E

## 结论

3.4.27 在 3.4.19 已验收功能的基础上，补齐了逐控件协议/存储标注和独立的“关于”页，
并把关键 Hook 从单一混淆名改为“已知名优先、签名/类型回退”。3.4.19 已由 ADB 安装到
一加 13 并完成 UI 冒烟验收；3.4.27 切换到新的 100 年正式 Release 证书，功能与 3.4.26 相同。

Pro 3 的助听/PPE、ATT 读写和头部手势阈值仍需要对应真机验证。

## 3.4.27 UI

官方页只保留首屏四个噪声快捷按钮、官方噪声横条、自适应强度和“AirPods 增强”入口。
入口内部是七页卡片式 Dialog：

常用 / 音频 / 操控 / 连接 / 健康 / 设备 / 关于

本次修复：
- “关于”现在是独立的第七页，不再附在设备页底部。
- 每个开关卡片显示 AACP/ATT 模板和保存位置；本地开关显示 SharedPreferences 键和手机协同行为。
- 分段按钮、滑杆和操作按钮所在分组显示对应的原始数据模板。
- 七页切换或重复点击当前标签都会回到顶部，避免二级页面继承上一页滚动位置。
- 关于页包含当前版本、GitHub 仓库、Star、关注作者、最新 Release、数据位置图例、上游来源和免责声明。

## 已接入的 AACP 功能

- 噪声：0x0D 四模式、首屏快捷栏、0x2E 自适应强度（按上游取反）、0x26 自适应音量。
- 对话与佩戴：0x28 对话感知、0x0A 自动人耳检测、0x06 佩戴事件、入耳媒体键。
- 耳机柄：0x17 按压速度、0x18 长按时长、0x25 音量滑动、0x23 音量滑动时长。
- 按键动作：0x39 自定义动作配置、0x1A 长按循环模式、0x19 按键事件、本地媒体键/语音助手/噪声循环。
- 通话：0x24 静音/挂断映射、0x1E 自动接听。
- 音频：0x01 麦克风、0x1F 提示音音量（双字节载荷）、0x63 六种预设与自定义三段 EQ。
- 连接/电源：0x20 设备侧自动连接、0x35 睡眠检测、0x3B 优化充电、0x34 允许关闭。
- 设备：0x1A 重命名包、0x1D 型号/厂商/序列号/固件/硬件版本/更新器字段。

## ATT PSM 31 听力通道

- PSM 31，UUID 全零，作为 MyDevices 进程内 daemon 线程运行，不创建 Service/Receiver/Provider。
- 读写 0x18 Transparency、0x1B Loud Sound Reduction、0x2A Hearing Aid。
- 打开 0x19/0x2B CCCD 通知。
- 解析/写回 100/104 字节小端浮点结构。
- 健康页提供大声降低、EN352/PPE、通透开关、放大、平衡、音色、环境降噪、对话增强、双耳 8 段 EQ、助听开关、滑动增益和听力图写回。

## 头部手势

0x17 头部姿态包在 AACP 读取循环中单独识别；Dashboard 提供启用/停止、10 秒测试、姿态包计数和实验性点头/摇头识别。阈值未在 AirPods 5 / Pro 3 实机标定，不能宣称识别率已经验收。

## Hook 范围与更新兼容

xposed/scope.list 只声明两个作用域：

| 包名 | 用途 |
| --- | --- |
| com.heytap.mydevices | 官方 AirPods 弹窗、详情页、设置页、AACP/ATT、自动连接和本地偏好 |
| com.android.bluetooth | 只过滤 ColorOS 精确的 DISABLE_OFF 帧 |

不再 Hook com.heytap.accessory、com.oplus.wirelesssettings、com.oplus.melody、Launcher、SystemUI 或 Settings。

更新兼容措施：
- 将官方 PTC 的 Apple 邻近数据过滤器从 `07 19 07 / 07 0F 00` 放宽为 `07 19 / 07 0F` 前缀，适配 AirPods 5 的邻近包变化。
- 3.4.26 不创建自建 BLE 扫描、不启动主动重连 watchdog；只在官方 PTC/ACL 回调里记录数据并复用官方 `onCaseAdvertisement → connectProfiles → AirpodsDiscoveryActivity` 流程。
- Bluetooth 只拦截 11 字节完全匹配的 04 00 04 00 09 00 34 02 00 00 00，其他写入原样放行。
- AirpodsBluetoothManager 的 STATE_DISCONNECTED 分发方法按参数签名查找，并优先用设备自身 isConnected 判断，减少对混淆方法 P 的依赖。
- 自适应滑杆按一个参数/void 返回查找绑定方法，按 SeekBar/TextView 字段类型取值。
- 模块只装饰自己创建的滑杆实例（SliderRegistry），不会改官方或其他第三方滑杆。
- IconTextSelector 的条目设置和监听注册按参数类型查找，类名仍保留 ColorOS 候选回退。
- 每个 Hook 独立 try/catch，单个入口失效不会让整个 MyDevices 页崩溃。

仍然不能保证 OPPO 大版本更新后自动可用：特性开关 r()、用户主动断开 C()、COUI Preference 的部分方法名和图标字段仍是版本契约；官方组件大改类名、方法签名或数据包格式时需要重新逆向并发布新的 3.4.x 版本。

## 仍不由本模块实现

- 个性化空间音频本身：Dashboard 只打开 OPPO 官方 Activity。
- LibrePods 的接管/多设备策略、桌面 Widget、快捷设置磁贴、通知岛、独立后台服务。
- 相机遥控：LibrePods v1.0.4 当前 UI 本身已注释，不作为缺口。
- 相机自定义 App、日志排障页、主题设置、IRK/ENC key 等独立 App 功能。

## 构建与验收

APK：dist/OPPO-AirPods-Enhanced-v3.4.27.apk

SHA-256：B0ACF545B1EC935726689294C7D0A57D27C11396C0F10EF59EBFE28CA382A91E

AACP 单测已通过。3.4.26 已安装并完成一次官方 ACL/弹窗复测；不要由模块自动操作 LSPosed。
