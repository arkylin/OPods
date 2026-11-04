# Third-Party Notices / 第三方来源

OPods 以 GNU GPL v3.0-or-later 发布。以下项目用于协议研究、互操作性验证和构建。

| 项目 | 用途 | 许可 / 说明 |
| --- | --- | --- |
| [LibrePods](https://github.com/librepods-org/librepods) | AirPods AACP/ATT 行为、型号能力、功能范围、通透/助听数据结构参考；核对版本 `v1.0.4`，提交 `89356c08cea3db0b6b54792d9329d09a87cc85fa` | GPL-3.0；OPods 的 Java 协议层和 UI 功能设计包含对 LibrePods 行为的独立移植，发行物继续遵守 GPL-3.0-or-later |
| [HyperPods-X](https://github.com/YeFeng233/HyperPods-X) | HyperOS 官方弹窗、官方设置注入和 AACP 思路参考 | GPL-3.0；ColorOS/OPPO 路径由本项目重新实现 |
| [airpods-off-fix](https://github.com/Chloeyayo/airpods-off-fix) | ColorOS Off 模式 `DISABLE_OFF` 帧、能力位和假断开过滤行为参考；核对提交 `d4914cecbe21b65bce78a57babcd01197fc54999` | 该提交未附带明确许可证文件；本项目只使用公开协议常量和行为事实，未复制其源码或二进制 |
| [COUI](https://github.com/suqi8/coui) | Slider 的轨道高度、滑块半径、关键点、按压放大与配色参数参考 | Apache-2.0；本项目使用 Android `SeekBar` 和 `Drawable` 独立重实现，不引入 Compose 运行时或上游源码 |
| [LibXposed API](https://github.com/libxposed/api) | `libs/libxposed-api.jar`，编译期 LSPosed API | Apache-2.0；运行时由 LSPosed 提供 |

## Hook 的官方组件

OPods 不注入第三方 App，只声明三个作用域：

- `com.heytap.mydevices`：官方 AirPods 弹窗、详情页、设置页、AACP/ATT 会话和本地 SharedPreferences。
- `com.android.bluetooth`：仅过滤 ColorOS 发送的精确 `DISABLE_OFF` 帧，其他 BluetoothSocket 写入不修改。
- `com.heytap.accessory`：仅在官方 PTC 扫描结果中观察 Apple 邻近包，不建立扫描、连接或后台监听。

`com.oplus.wirelesssettings`、`com.oplus.melody`、Launcher、SystemUI 和 Settings 均不 Hook。

## 许可边界

- 本项目不包含 Apple、OPPO、ColorOS 或 MyDevices 的专有源码、资源、APK 或反编译产物。
- OPPO/OPlus/MyDevices 类只在设备运行时通过反射和 LSPosed Hook 使用。
- Apple、AirPods、OPPO、ColorOS 等商标归各自权利人所有；本项目与这些厂商没有隶属、授权或背书关系。
- 如果分发修改版，请保留 GPL-3.0 许可、版权/来源说明，并提供对应源代码。
