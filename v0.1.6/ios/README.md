# DreamShield iOS 0.1.1

SwiftUI 原生客户端，最低支持 iOS／iPadOS 17。交付包括 Xcode 工程、Swift 源码、图片资源、隐私清单和原有测试目标。界面支持配对、状态、环境、抓拍、历史记录和原有 AI 功能入口。v0.1.6 的温控预设及热像闭环仍在电脑控制台操作。

## 构建

在 Mac 上使用 Xcode 16 或兼容版本打开 DreamShield.xcodeproj，选择共享的 DreamShield Scheme 和 iPhone 模拟器，点击 Run。安装到 iPhone 时，在 Signing & Capabilities 选择自己的 Team，再选真机运行。

工程结构可运行 python scripts/validate_project.py 检查。在 Mac 上可运行 bash scripts/verify_build.sh 做模拟器构建。无签名模拟器构建不依赖 Apple 签名。

本次在 Windows 通过 57 项工程结构检查和 34 项辅助工具测试。Swift 编译、XCTest 和真机验收需要在 macOS 的 Xcode 中完成。

## 连接 v0.1.6

电脑启动本套件的 server，然后打开 http://127.0.0.1:8016/mobile-setup。在 iOS App 的设置中粘贴该页面生成的配对 JSON，核对电脑的局域网地址和证书指纹，首次连接时允许本地网络访问。配对信息使用电脑的 HTTPS 网关，默认端口 8746。
