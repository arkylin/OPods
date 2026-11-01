# Third-Party Notices

本项目是面向 ColorOS 的独立实现。以下是设计、协议研究和构建依赖的第三方来源。

| 项目 | 用途 | 许可 / 说明 |
| --- | --- | --- |
| [LibrePods](https://github.com/librepods-org/librepods) | AirPods AACP 行为、型号能力和功能范围参考；本地核对版本为 `v1.0.4`，提交 `89356c08cea3db0b6b54792d9329d09a87cc85fa` | GPL-3.0；本项目不链接 LibrePods APK，也不读取其数据 |
| [HyperPods-X](https://github.com/YeFeng233/HyperPods-X) | HyperOS 原生弹窗、官方设置注入和 AACP 思路参考 | GPL-3.0；ColorOS 路径由本项目重新实现 |
| [airpods-off-fix](https://github.com/Chloeyayo/airpods-off-fix) | ColorOS Off 模式 `DISABLE_OFF` 帧、能力位和假断开过滤行为参考；本地核对提交 `d4914cecbe21b65bce78a57babcd01197fc54999` | 该提交未附带明确许可证文件；这里只记录公开协议常量和行为事实，不复制其源码或二进制 |
| [LibXposed API](https://github.com/libxposed/api) | `libs/libxposed-api.jar`，编译期 LSPosed API | Apache-2.0；运行时由 LSPosed 提供 |

LibXposed API 的 Apache-2.0 全文位于 `licenses/Apache-2.0.txt`。

`libs/libxposed-api.jar` 仅用于编译。APK 不打包 LibrePods、HyperPods-X 或
airpods-off-fix 的代码/资源。ColorOS、OPlus、Apple 及其商标、系统类和 AirPods 产品
归各自权利人所有；本项目与这些厂商没有隶属或背书关系。

如果后续直接复制 GPL 项目的源码，请保留对应版权声明，并确保发行物继续满足
GPL-3.0-or-later 的源码提供要求。
