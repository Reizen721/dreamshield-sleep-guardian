# DreamShield v0.1.6 核心源码

| 工程 | 版本 | 入口 |
|---|---|---|
| [server](server/README.md) | v0.1.6 | Python 服务、模型推理、网页控制台、手机 HTTPS 网关 |
| [android](android/README.md) | Android 0.6.1 | Kotlin／Java、Jetpack Compose、Gradle 工程 |
| [ios](ios/README.md) | iOS 0.1.1 | SwiftUI、Xcode 工程 |

手机版本沿用各自源码的版本号。两款 App 通过配对连接电脑上的 HTTPS 网关。电脑浏览器访问 **http://127.0.0.1:8016/mobile-setup** 查看配对信息，手机使用配对信息中的电脑局域网地址和端口 **8746**。每台手机单独配对。

热像时序闭环在电脑的 **http://127.0.0.1:8016/thermal-setup** 查看和开启。Android 现有策略设置通过手机网关对接，iOS 现有界面支持配对、状态、环境、抓拍和记录。各工程的构建说明和功能范围见对应 README。

源码包包含服务端运行所需的模型权重和空床校准。手机和服务端的运行配置由各自环境生成。

## 验证记录

- 服务端回归测试 133 项通过、1 项按原设置跳过。使用临时数据验证了模型进程、电脑页面、HTTPS 配对和手机策略读取。
- Android 调试构建成功，62 项单元测试通过。
- iOS 57 项工程结构检查、34 项辅助工具测试通过。Swift 编译与真机验收需要 macOS 和 Xcode。
