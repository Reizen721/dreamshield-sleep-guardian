# 梦盾 v0.1.5 构建指南

解压源码 ZIP，在包含 `Build-Desktop.ps1` 和 `requirements.txt` 的目录打开 PowerShell。电脑端和 Freenove 固件可分别构建。

## Windows 客户端

准备 Windows x64、Python 3.12 x64（含 `py` 启动器）、.NET Framework 4.8 和 Microsoft Edge WebView2 Runtime。桌面构建脚本使用系统的 64 位 .NET Framework C# 编译器，WebView2 SDK 文件已放在 `desktop/deps/webview2/`。

1. 创建 Python 虚拟环境，安装服务器和热像推理所需的依赖。

   ```powershell
   py -3.12 -m venv .venv
   .\.venv\Scripts\python.exe -m pip install -r .\requirements.txt
   ```

2. 编译桌面程序。成功后脚本会输出 `build\DreamShield.exe` 的路径。热像模型权重与校准文件位于 `backend/model/`，启动时从该目录加载。

   ```powershell
   .\Build-Desktop.ps1
   ```

3. 若之前启动过旧版，请从托盘完全退出，并停止占用 `8000` 端口的旧梦盾服务。用启动脚本打开新版，它会给桌面程序指定上面创建的 Python 环境。

   ```powershell
   .\Start-Desktop.ps1
   ```

启动后进入左侧「热像判别」。需要 VLM 核对时，把 API 密钥保存为包外的一行文本，在 `%LOCALAPPDATA%\DreamShield\desktop.json` 中将 `vlm_api_key_file` 设置为该文件的绝对路径，然后重启客户端。沿用已有配置时保留 JSON 中的其他字段。

## 仅运行网页和后台服务

安装 Python 依赖后，可直接在 PowerShell 中启动服务器。

```powershell
.\.venv\Scripts\python.exe -B -X utf8 .\backend\app\server.py
```

打开 [热像判别页面](http://127.0.0.1:8000/thermal-setup)。按 `Ctrl+C` 结束服务。若需要 VLM 核对，在启动前将 `DREAMSHIELD_VLM_KEY_FILE` 设为本地密钥文件的绝对路径。

## Freenove ESP32-S3 N8R8 固件

1. 在 Arduino IDE 的开发板管理器准备 **esp32 by Espressif Systems**。本包已用 `3.3.10-cn` 编译通过。
2. 打开 `firmware/Freenove_N8R8_OV2640/Freenove_N8R8_OV2640.ino`，在「工具」中选择下表设置。

   | 设置 | 值 |
   |---|---|
   | 开发板 | `ESP32S3 Dev Module` |
   | Flash Size | `8MB (64Mb)` |
   | Flash Mode | `QIO 80MHz` |
   | PSRAM | `OPI PSRAM` |
   | Partition Scheme | `8M with spiffs (3MB APP/1.5MB SPIFFS)` |
   | USB CDC On Boot | `Disabled` |
   | Upload Speed | `115200` |
   | Erase All Flash Before Sketch Upload | `Disabled`，保留已保存的 Wi-Fi 配网 |

3. 点击「验证」编译。升级 Freenove 时，连接开发板后选择 CH343 对应的串口，再点击「上传」。固件默认启用 BLE 配网，可从桌面端「连接与设备」进入配网页面。

30139 热像板使用其现有兼容固件。电脑端通过「热像判别」中的设备地址接入。完整的目录和运行配置见 [README_CN.md](README_CN.md)。
