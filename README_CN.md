# 梦盾 v0.1.5 功能源码

这份源码对应 Windows 端的热像联合闭环。包括 Windows 客户端、电脑服务和网页、可独立加载的本地热像模型，以及 Freenove 主控固件。Windows 后台的手机配对与连接功能也在包内；手机应用源码属于独立工程。

| 目录 | 内容 |
|---|---|
| `desktop/` | Windows 窗口、托盘、WebView2、服务启动和构建依赖 |
| `backend/app/` | 设备配网、房间总览、温湿度记录、热像预览、VLM 核对、空调控制及网页资源 |
| `backend/model/` | 实时推理、预处理、ONNX 和随机森林模型权重、床面区域与背景校准 |
| `firmware/Freenove_N8R8_OV2640/` | 实际运行的摄像头、SHT31、BLE 配网和 YAPOFB14 红外控制固件 |

不包含测试代码、实验页面、模拟设备、样例回放、训练/采集实验数据、日志、截图、API 密钥或无线网络密码。热像板使用现有兼容固件，由电脑通过 SenXor TCP 或现有热像接口接入。

## 构建和启动

Windows x64，使用 Python 3.12、.NET Framework 4.8 和 WebView2 Runtime。从包根目录运行：

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\Build-Desktop.ps1
.\Start-Desktop.ps1
```

构建输出在 `build/`。启动前完全退出旧版窗口及其托盘进程；若旧服务是单独运行的，也先停止旧服务，避免复用 8000 端口上的另一个版本。启动后在“热像判别”页连接热像设备。桌面启动默认沿用 `%LOCALAPPDATA%\DreamShield\desktop.json` 设置和用户数据目录。初次配置 VLM 可将密钥写入包外的本地文本文件，把文件绝对路径填入 `desktop.json` 的 `vlm_api_key_file`；也可以在启动前设置 `VLM_API_KEY`（或 `DREAMSHIELD_VLM_KEY_FILE` 指向这个文件）。

也可以只运行网页服务：

```powershell
.\.venv\Scripts\python.exe -B -X utf8 .\backend\app\server.py
```

访问 `http://127.0.0.1:8000/thermal-setup`。独立服务默认将数据写在 `backend/app/data/`，可通过 `DREAMSHIELD_DATA_DIR` 指定包外绝对路径。独立服务读取环境变量配置 VLM；桌面用的 `desktop.json` 由桌面宿主读取。已有单独的模型 Python 环境时，也可用 `DREAMSHIELD_MODEL_PYTHON` 指向其解释器，模型文件仍从本包加载。

## 功能入口

热像联合闭环默认关闭。先开启热像预览并确认机位与模型校准一致，在“热像联合闭环”选择“仅建议”，检查模型和 VLM 的联合判断。需要自动调温时，先建立空调控制会话、核对空调处于制冷状态，并开启温湿度记录，再切换“自动调节”。空调侧没有执行确认；页面记录的是红外发射回执和随后的环境变化。具体规则见[闭环判别策略](闭环判别策略_CN.md)。

## Freenove 固件

用 Arduino IDE 打开 `firmware/Freenove_N8R8_OV2640/Freenove_N8R8_OV2640.ino`。已用 ESP32 Arduino Core `3.3.10-cn`，ESP32S3 Dev Module、8 MB Flash、OPI PSRAM、QIO、8 MB 默认分区编译；BLE 配网默认开启，不依赖 `secrets.h`。如自行切换到头文件配网，则复制 `secrets.example.h` 为 `secrets.h` 并在本机填写。
