# DreamShield Android 0.6.1

原生 Android 源码，界面使用 Jetpack Compose。包含配对连接、电脑状态、环境、抓拍与记录、策略预设与采样设置，以及客户端的睡眠日记和声景。

## 构建

在 Android Studio 打开本目录。工程固定使用 Gradle 9.3.1、Android Gradle Plugin 9.1.1、compileSdk 37、targetSdk 37 和 minSdk 29。在 SDK Manager 中安装 Android SDK Platform 37 和 Build Tools 37.0.0。本机验证使用 OpenJDK 23.0.2，应用 Java／Kotlin 编译目标为 17。Windows 命令行可运行以下命令。

~~~powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
~~~

SDK 路径由 Android Studio 或 ANDROID_HOME 指定。调试包默认生成在 app/build/outputs/apk/debug。发布签名须使用自己的密钥文件，并由原 Gradle 签名选项启用。

## 连接 v0.1.6

电脑启动本套件的 server，然后打开 http://127.0.0.1:8016/mobile-setup。手机和电脑处于同一家庭局域网时，通过 Android App 使用该页的配对信息连接电脑的 HTTPS 网关，默认端口 8746。
