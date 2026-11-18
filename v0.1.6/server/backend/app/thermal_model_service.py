"""PC-only experimental thermal inference. Reads cached frames; has no actuator/VLM API."""
from __future__ import annotations

import copy
import json
import math
import os
import sys
from pathlib import Path
import queue
import subprocess
import threading
import time
import uuid
from datetime import datetime, timezone


PROJECT = Path(__file__).resolve().parent.parent / "model"
MAX_FRAME_AGE = 10


class ModelError(Exception):
    def __init__(self, message, status=409):
        super().__init__(message)
        self.message, self.status = message, status


class StdioWorker:
    def __init__(self, data_dir):
        self.data_dir = Path(data_dir)
        self.process = None
        self.messages = None
        self.stderr = None

    def _read(self, process, messages):
        try:
            for line in process.stdout:
                messages.put(json.loads(line))
        except (ValueError, OSError):
            pass
        finally:
            messages.put(None)

    def _next(self, timeout):
        try:
            value = self.messages.get(timeout=timeout)
        except queue.Empty:
            self.close()
            raise ModelError("本地模型响应超时，请重试；诊断见 thermal_model/worker.log", 503) from None
        if value is None:
            self.close()
            raise ModelError("本地模型进程已退出，请检查 thermal_model/worker.log", 503)
        return value

    def _start(self):
        if self.process is not None and self.process.poll() is None:
            return
        self.close()
        script = PROJECT / "server_worker.py"
        python = Path(os.environ.get("DREAMSHIELD_MODEL_PYTHON") or sys.executable)
        if not python.is_file() or not script.is_file():
            raise ModelError("找不到本机模型运行环境或模型工程，请恢复安装路径", 503)
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.stderr = (self.data_dir / "worker.log").open("w", encoding="utf-8")
        try:
            self.process = subprocess.Popen([str(python), "-u", str(script)],
                cwd=script.parent, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=self.stderr, text=True, encoding="utf-8", bufsize=1,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            self.messages = queue.Queue()
            threading.Thread(target=self._read, args=(self.process, self.messages), daemon=True).start()
            if self._next(90).get("ready") is not True:
                raise ModelError("本地模型未就绪", 503)
        except Exception:
            self.close()
            raise

    def request(self, payload):
        self._start()
        request_id = uuid.uuid4().hex
        try:
            self.process.stdin.write(json.dumps({**payload, "id": request_id}, allow_nan=False) + "\n")
            self.process.stdin.flush()
            reply = self._next(20)
        except (OSError, ValueError):
            self.close()
            raise ModelError("本地模型通信失败，请重试", 503) from None
        if reply.get("id") != request_id or reply.get("ok") is not True:
            raise ModelError("模型未能完成判别，请检查输入与模型文件", 503)
        return reply["result"]

    def close(self):
        process, self.process = self.process, None
        if process is not None:
            try:
                process.stdin.close()
                process.wait(timeout=2)
            except (OSError, subprocess.TimeoutExpired):
                process.kill()
                process.wait(timeout=2)
            if process.stdout:
                process.stdout.close()
        if self.stderr:
            self.stderr.close()
            self.stderr = None


class ThermalModelService:
    def __init__(self, frame_provider, data_dir, worker=None, clock=time.time):
        self.frame_provider, self.data_dir, self.clock = frame_provider, Path(data_dir), clock
        self.worker = worker or StdioWorker(data_dir)
        self.lock = threading.RLock()
        self.operation = threading.Lock()
        self.wake = threading.Event()
        self.closed = False
        self.thread = None
        self.enabled = False
        self.latest_evidence = None
        self.interval_seconds = 2
        self.generation = 0
        self.bound_source = None
        self.busy = False
        self.state = "idle"
        self.last_error = None
        self.last_live = None
        self.last_key = None

    @staticmethod
    def identity(frame):
        return frame.get("thermal_base_url"), frame.get("stream_id")

    def _frame(self):
        frame = self.frame_provider()
        if not isinstance(frame, dict):
            raise ModelError("还没有热像帧，请先开始实时预览")
        if frame.get("simulated") is not False or frame.get("matrix_unit") != "celsius":
            raise ModelError("仅接受真实设备的摄氏温度矩阵")
        age = frame.get("age_seconds")
        if (not isinstance(age, (int, float)) or not math.isfinite(age) or not 0 <= age <= MAX_FRAME_AGE
                or frame.get("freshness") != "fresh" or frame.get("online") is not True):
            raise ModelError("当前热像帧已过期或设备离线，等待新帧")
        if frame.get("width") != 80 or frame.get("height") != 62 or frame.get("invalid_pixels") != 0:
            raise ModelError("热像帧尺寸或有效像素不符合模型要求")
        matrix = frame.get("temperatures_c")
        if (not isinstance(matrix, list) or len(matrix) != 62 or any(not isinstance(row, list) or len(row) != 80
                or any(type(v) not in (int, float) or not math.isfinite(v) for v in row) for row in matrix)):
            raise ModelError("热像温度矩阵损坏")
        if not all(self.identity(frame)) or not frame.get("capture_id") or not frame.get("raw_sha256"):
            raise ModelError("热像帧缺少来源标识")
        return frame

    def status(self):
        with self.lock:
            result = {"enabled": self.enabled, "interval_seconds": self.interval_seconds,
                "busy": self.busy, "state": self.state, "last_error": self.last_error,
                "last_live": copy.deepcopy(self.last_live),
                "model": "bedside_v003/fusion_50 + v1 random forest",
                "mode": "observation_only", "execution_allowed": False, "cloud_upload_enabled": False,
                "collection_required": False, "scores_are_calibrated_probabilities": False}
        if result["last_live"]:
            live = result["last_live"]
            live["age_seconds"] = max(0, self.clock() - live["frame_received_epoch"])
            try:
                current = self._frame()
                current_source = list(self.identity(current)) == live["source_identity"]
            except ModelError:
                current_source = False
            live["current"] = (current_source and live["age_seconds"] <= MAX_FRAME_AGE
                and result["state"] in ("observing", "analyzed", "waiting_new_frame"))
        return result

    def evidence(self):
        """Internal, exact matrix/result pair from the live stream."""
        with self.lock:
            if not self.enabled or self.state not in ("observing", "waiting_new_frame"):
                raise ModelError("请开启本地持续观察并等待新帧")
            evidence = copy.deepcopy(self.latest_evidence)
        frame = self._frame()
        if (not evidence or self.identity(frame) != tuple(evidence["result"]["source_identity"])
                or not 0 <= self.clock()-evidence["result"]["frame_received_epoch"] <= MAX_FRAME_AGE):
            raise ModelError("本地判别证据已过期")
        return evidence

    def configure(self, body):
        if (not isinstance(body, dict) or set(body) - {"enabled", "interval_seconds", "calibration_confirmed"}
                or type(body.get("enabled")) is not bool):
            raise ModelError("请提供 enabled 开关与可选判别间隔", 400)
        interval = body.get("interval_seconds", self.interval_seconds)
        if type(interval) is not int or not 2 <= interval <= 60:
            raise ModelError("判别间隔须为 2～60 秒整数", 400)
        frame = None
        if body["enabled"]:
            if body.get("calibration_confirmed") is not True:
                raise ModelError("请确认沿用训练时的床头柜机位、朝向和床面范围")
            frame = self._frame()
        with self.lock:
            if self.closed:
                raise ModelError("模型服务已停止", 503)
            self.generation += 1
            self.enabled, self.interval_seconds = body["enabled"], interval
            self.bound_source = self.identity(frame) if frame else None
            self.last_key = None
            self.latest_evidence = None
            self.state, self.last_error = ("observing" if self.enabled else "stopped"), None
            if self.last_live:
                self.last_live = None  # A new confirmation never revives a previous result.
            if self.enabled and self.thread is None:
                self.thread = threading.Thread(target=self._loop, daemon=True)
                self.thread.start()
            self.wake.set()
        return self.status()

    def _decorate(self, result, source):
        result = dict(result)
        result.update(source=source, scope="experimental_bedside_static_state",
            analyzed_at=datetime.fromtimestamp(self.clock(), timezone.utc).isoformat(),
            research_only=True, review_required=True, recognition_release_ready=False,
            execution_allowed=False, air_conditioning_command=None,
            vlm_review_recommended=result.get("predicted_state") == "uncertain",
            collaboration_mode="review_hint_only_no_cloud_call")
        return result

    def _persist(self, result, filename):
        self.data_dir.mkdir(parents=True, exist_ok=True)
        path = self.data_dir / filename
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(result, ensure_ascii=False, allow_nan=False, indent=2), encoding="utf-8")
        temporary.replace(path)

    def analyze(self, confirmed=False, automatic=False):
        if confirmed is not True:
            raise ModelError("请先确认机位和床面范围与训练时相同")
        if not self.operation.acquire(blocking=False):
            raise ModelError("模型正在处理上一帧，请稍候")
        with self.lock:
            generation = self.generation
        try:
            frame = self._frame()
            key = (frame["capture_id"], frame["raw_sha256"])
            with self.lock:
                if self.closed or (automatic and not self.enabled):
                    raise ModelError("持续观察已停止")
                generation = self.generation
                if automatic and self.identity(frame) != self.bound_source:
                    self.enabled = False
                    raise ModelError("热像连接或数据流已变化，请重新确认机位并开启观察")
                if key == self.last_key:
                    if automatic:
                        self.state = "waiting_new_frame"
                    return self.status()
                self.busy = True
            started = self.clock()
            result = self._decorate(self.worker.request({"action": "matrix", "temperatures_c": frame["temperatures_c"]}), "live")
            # The first inference may include model loading. Never publish an old frame as current.
            current = self._frame()
            if self.identity(current) != self.identity(frame) or frame["age_seconds"] + self.clock() - started > MAX_FRAME_AGE:
                raise ModelError("处理期间帧已过期或连接变化，等待新帧后再试")
            result.update(frame_id=frame["capture_id"], frame_number=frame.get("frame_number"),
                frame_received_at=frame.get("received_at"), frame_received_epoch=started-frame["age_seconds"],
                source_identity=list(self.identity(frame)), current=True)
            with self.lock:
                if generation != self.generation or self.closed:
                    return self.status()
                self._persist(result, "latest_live.json")
                self.last_live, self.last_key = result, key
                self.latest_evidence = {"frame": copy.deepcopy(frame), "result": copy.deepcopy(result)}
                self.state = "observing" if self.enabled else "analyzed"
                self.last_error = None
            return self.status()
        except ModelError as error:
            with self.lock:
                if generation == self.generation and not self.closed:
                    self.state, self.last_error = "waiting", error.message
            raise
        finally:
            with self.lock:
                self.busy = False
            self.operation.release()

    def _loop(self):
        while not self.closed:
            self.wake.clear()
            if self.enabled:
                generation = self.generation
                try:
                    self.analyze(confirmed=True, automatic=True)
                except Exception as error:
                    with self.lock:
                        if generation == self.generation and not self.closed:
                            self.state = "waiting"
                            self.last_error = error.message if isinstance(error, ModelError) else "本地判别失败，请查看服务日志并重试"
            self.wake.wait(self.interval_seconds if self.enabled else 60)

    def close(self):
        with self.lock:
            self.closed, self.enabled = True, False
            self.generation += 1
            self.wake.set()
        # Own process only. Never touch the camera stream or existing control workers.
        with self.operation:
            self.worker.close()
