# 服务端 v0.1.6

核心链路为热像接入、FusionNet-50 与随机森林本地判别、时序证据、独立 VLM 复核、SHT31 环境规则和 Freenove 控制请求。保留配套网页控制台与安卓、iOS 使用的移动 HTTPS 网关。

| 入口或模块 | 职责 |
|---|---|
| backend/app/server.py | HTTP 服务、设备与配置接口、任务生命周期 |
| backend/app/senxor_tcp.py、thermal_capture.py | 微雪热像接入与帧管理 |
| backend/model/server_worker.py、predict_optimized.py | 模型进程、预处理与本地判别 |
| backend/app/thermal_temporal.py、thermal_review_scheduler.py、thermal_closed_loop.py | 时间加权证据、关键帧、复核预算与闭环 |
| backend/app/control_policy.py、ac_control.py | 温湿度规则、预设、冷却期、事件记忆与设备请求 |
| backend/app/mobile_gateway.py | 手机配对、会话、TLS 与允许路由 |

## Windows 启动

在本目录用 Python 3.12 x64 创建环境并安装锁定的依赖。

~~~powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\start_server.cmd
~~~

启动脚本默认使用电脑本地 HTTP 端口 8016 和手机 HTTPS 端口 8746。数据默认存入 %LOCALAPPDATA%\DreamShield016Core\data，可用 DREAMSHIELD_DATA_DIR 指定其他绝对路径。如果只使用电脑控制台，在启动前设定 DREAMSHIELD_MOBILE=0。

手机配对入口为 http://127.0.0.1:8016/mobile-setup，热像控制台为 http://127.0.0.1:8016/thermal-setup。端口 8746 用于手机和电脑之间的 HTTPS。首次开启复核前，可用 VLM_PROVIDER 选择 openai 或 deepseek，并设置对应 API 环境变量，或通过 DREAMSHIELD_VLM_KEY_FILE 指向源码目录之外的密钥文件。时序复核与自动调温默认关闭。

模型权重、随机森林和空床背景保存在 backend/model/runs。使用原有热像机位与床面范围时沿用随包校准。策略详见[闭环判别策略](闭环判别策略_CN.md)。
