"""Bounded factory SenXor TCP transport, independent of the DreamShield server.

Protocol references (examined 2026-11-04): Waveshare Thermal-ESP32-Demo
tcpServerTask.c/cmdParser.c/Customer_Interface.c; MeridianInnovation
pysenxor-lite senxor/interface/serial_port/parser.py and regmap/fields.py.
No socket is opened until fetch(). No AI or appliance control is performed.
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass
import math
import socket
import struct
import sys
import threading
import time

PREFIX = b"   #"
WIDTH, HEIGHT = 80, 62
PIXELS = WIDTH * HEIGHT
PAYLOAD_BYTES = 10240
MAX_PACKET_BYTES = 10256


def abortive_linger_value(platform: str | None = None) -> bytes:
    """Winsock uses two unsigned shorts; POSIX uses two ints for linger."""
    return struct.pack("HH" if (platform or sys.platform).startswith("win") else "ii", 1, 0)


class SenxorTCPError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code, self.message = code, message


def encode_command(command: str, data: str = "") -> bytes:
    if command not in ("RREG", "WREG"):
        raise ValueError("Only RREG/WREG are implemented")
    expected = 2 if command == "RREG" else 4
    if len(data) != expected or any(c not in "0123456789ABCDEF" for c in data):
        raise ValueError("Register command data must be uppercase hexadecimal")
    body = command.encode("ascii") + data.encode("ascii")
    covered = f"{len(body)+4:04X}".encode("ascii") + body
    return PREFIX + covered + f"{sum(covered)&0xFFFF:04X}".encode("ascii")


@dataclass(frozen=True)
class Packet:
    command: str
    data: bytes
    checksum_mode: str


class PacketParser:
    """Streaming parser with bounded storage; validates every emitted packet.

Standard checksum is always supported. A strictly identified factory 2.24
profile can explicitly enable its empirically validated fixed +0x01FE checksum
variant. Never learn arbitrary offsets from incoming frames.
"""
    def __init__(self):
        self.buffer = bytearray()
        self.factory_224_checksum = False
        self.invalid_packets = 0
        self.discarded_bytes = 0

    def feed(self, chunk: bytes) -> list[Packet]:
        if len(chunk) > 65536:
            raise SenxorTCPError("thermal_tcp_overflow", "热像网络数据块过大")
        self.buffer.extend(chunk)
        if len(self.buffer) > 131072:
            raise SenxorTCPError("thermal_tcp_overflow", "热像网络缓冲区超过上限")
        packets = []
        while len(self.buffer) >= 8:
            if not self.buffer.startswith(PREFIX):
                index = self.buffer.find(PREFIX)
                discard = index if index >= 0 else len(self.buffer)-3
                self.discarded_bytes += discard
                del self.buffer[:discard]
                if index < 0:
                    break
            if len(self.buffer) < 8:
                break
            length_bytes = bytes(self.buffer[4:8])
            try:
                if any(c not in b"0123456789ABCDEFabcdef" for c in length_bytes):
                    raise ValueError()
                length = int(length_bytes, 16)
                if not 8 <= length <= MAX_PACKET_BYTES-8:
                    raise ValueError()
            except ValueError:
                self.invalid_packets += 1
                del self.buffer[:4]
                continue
            total = length+8
            if len(self.buffer) < total:
                break
            raw = bytes(self.buffer[:total])
            del self.buffer[:total]
            try:
                command = raw[8:12].decode("ascii")
                if command not in ("GFRA", "RREG", "WREG"):
                    raise ValueError()
                checksum_bytes = raw[-4:]
                if any(c not in b"0123456789ABCDEFabcdef" for c in checksum_bytes):
                    raise ValueError()
                actual = int(checksum_bytes, 16)
                standard = sum(raw[4:-4]) & 0xFFFF
                mode = "standard_sum16"
                if command == "GFRA":
                    if actual != standard:
                        if (not self.factory_224_checksum or len(raw) != MAX_PACKET_BYTES
                                or actual != (standard+0x01FE) & 0xFFFF):
                            raise ValueError()
                        mode = "waveshare_224_sum16_plus_01fe"
                elif actual != standard:
                    raise ValueError()
                packets.append(Packet(command, raw[12:-4], mode))
            except (UnicodeError, ValueError):
                self.invalid_packets += 1
        return packets


def decode_frame(packet: Packet, *, resolution_register: int,
                 format_register: int) -> dict:
    if packet.command != "GFRA" or len(packet.data) != PAYLOAD_BYTES:
        raise SenxorTCPError("thermal_tcp_frame_size", "原厂热像帧应为80×62且含完整帧头")
    if format_register not in (0, 1, 2):
        raise SenxorTCPError("thermal_tcp_units", "当前温度输出格式不受支持，请先在原厂软件恢复整数温度格式")
    resolution = 100 if resolution_register & 0x80 else 10
    header = struct.unpack("<80H", packet.data[160:320])
    raw = packet.data[320:]
    values = struct.unpack("<4960H" if format_register == 0 else "<4960h", raw)
    temperatures = []
    for value in values:
        celsius = value/resolution
        if format_register == 0:
            celsius -= 273.15
        elif format_register == 2:
            celsius = (celsius-32)*5/9
        temperatures.append(round(celsius, 4) if math.isfinite(celsius) and -40 <= celsius <= 400 else None)
    invalid = sum(value is None for value in temperatures)
    if invalid > PIXELS//10:
        raise SenxorTCPError("thermal_tcp_pixels_invalid", "热像帧有效温度像素不足90%")
    return {"raw": raw, "frame_number": header[0],
            "temperatures_c": tuple(temperatures), "resolution": resolution,
            "format": format_register, "invalid_pixels": invalid,
            "diagnostics": {"transport": "senxor_tcp", "wire_bytes": MAX_PACKET_BYTES,
                "payload_bytes": PAYLOAD_BYTES, "pixel_offset_in_packet": 332,
                "checksum_mode": packet.checksum_mode,
                "counter_origin": "sensor_header_u16", "device_frame_counter": header[0],
                "temperature_register_31": format_register,
                "resolution_register_b9": resolution_register,
                "sensor_header_u16": list(header)}}


class SenxorTCPClient:
    """One persistent TCP session; continuously drains frames, keeps newest only.

host must already have been validated and pinned by the caller. The thermal
board supports one TCP client; the official viewer must be disconnected first.
fetch returns a native dict, or None if no newer frame arrives before timeout.
Device register settings are read, except B1 capture stop/start. No writes to
temperature units, calibration, network settings, or flash are issued.
"""
    def __init__(self, host: str, port: int = 3333, *, socket_factory=None):
        self.host, self.port = host, port
        self._socket_factory = socket_factory or socket.create_connection
        self._socket = None
        self._parser = PacketParser()
        self._queue = deque()
        self._thread = None
        self._stop = threading.Event()
        self._condition = threading.Condition()
        self._operation = threading.RLock()
        self._latest = None
        self._last_error = None
        self._format = None
        self._resolution = None
        self._stage = "disconnected"
        self._units_source = "register_31"
        self._observed_format = None
        self._factory_profile = None
        self._identity_registers = {}

    @staticmethod
    def _remaining(deadline):
        value = deadline-time.monotonic()
        if value <= 0:
            raise SenxorTCPError("thermal_tcp_timeout", "热像板响应超时；请先断开SenXor预览软件")
        return value

    def _receive_packets(self, deadline):
        while not self._queue:
            sock = self._socket
            if sock is None:
                raise SenxorTCPError("thermal_tcp_disconnected", "热像连接已关闭")
            sock.settimeout(min(self._remaining(deadline), 0.5))
            try:
                chunk = sock.recv(16384)
            except socket.timeout:
                continue
            if not chunk:
                raise SenxorTCPError("thermal_tcp_disconnected", "热像板关闭了连接")
            self._queue.extend(self._parser.feed(chunk))
        return self._queue.popleft()

    def _command(self, command, data, deadline):
        self._socket.settimeout(self._remaining(deadline))
        self._socket.sendall(encode_command(command, data))
        while True:
            packet = self._receive_packets(deadline)
            if packet.command == command:
                if command == "WREG" and packet.data:
                    raise SenxorTCPError("thermal_tcp_ack", "热像板写寄存器应答无效")
                return packet.data
            if packet.command == "GFRA" and self._format in (0, 1, 2) and self._resolution is not None:
                self._publish(packet)

    def _read_register(self, address, deadline):
        raw = self._command("RREG", f"{address:02X}", deadline)
        try:
            if len(raw) != 2 or any(c not in b"0123456789ABCDEFabcdef" for c in raw):
                raise ValueError()
            return int(raw, 16)
        except ValueError:
            raise SenxorTCPError("thermal_tcp_ack", "热像板读寄存器应答无效") from None

    def _open(self, deadline):
        self._stage = "connecting"
        try:
            self._socket = self._socket_factory((self.host, self.port), self._remaining(deadline))
        except (TimeoutError, SenxorTCPError):
            raise SenxorTCPError("thermal_tcp_connect_timeout",
                "TCP连接尚未建立：请确认热像板IP、家庭Wi-Fi和局域网可达性；退出SenXor后重试") from None
        except ConnectionRefusedError:
            raise SenxorTCPError("thermal_tcp_connect_refused",
                "热像板拒绝TCP连接：请确认原厂固件正在监听3333端口，必要时复位热像板") from None
        except OSError:
            raise SenxorTCPError("thermal_tcp_connect_failed",
                "无法建立热像TCP连接：请核对地址、Wi-Fi和电脑到热像板的网络通路") from None
        self._stop.clear()
        self._parser = PacketParser()
        self._queue.clear()
        self._latest = self._last_error = None
        self._format = self._resolution = None
        self._units_source = "register_31"
        self._factory_profile = None
        self._identity_registers = {}
        self._stage = "stop_capture"
        self._command("WREG", "B100", deadline)
        self._stage = "read_temperature_units"
        self._format = self._read_register(0x31, deadline)
        self._observed_format = self._format
        self._stage = "read_temperature_resolution"
        self._resolution = self._read_register(0xB9, deadline)
        if self._format == 0xFF:
            self._stage = "identify_factory_firmware"
            self._identity_registers = {address: self._read_register(address, deadline)
                                        for address in (0xB2, 0xB3, 0xBB)}
            if self._identity_registers == {0xB2: 0x28, 0xB3: 0x00, 0xBB: 0x14}:
                # Published Waveshare application 2.24.0: cmdParser routes
                # through Acces_Read_Reg, whose linked implementation does not
                # call Customer_Interface_* for low registers. Format 31 is
                # inaccessible; Image_Processing is global-zero-initialized
                # and that application never changes MCU_REG_31 through TCP.
                self._format = 0
                self._units_source = "waveshare_factory_2.24_fixed_kelvin"
                self._factory_profile = "waveshare_224_00_model14"
                self._parser.factory_224_checksum = True
        if self._format not in (0, 1, 2):
            raise SenxorTCPError("thermal_tcp_units", "当前温度输出格式不受支持，请在原厂软件恢复整数温度格式")
        self._stage = "start_capture"
        self._command("WREG", "B103", deadline)
        self._stage = "streaming"
        self._thread = threading.Thread(target=self._reader, name="senxor-tcp-reader", daemon=True)
        self._thread.start()

    def _publish(self, packet):
        frame = decode_frame(packet, resolution_register=self._resolution, format_register=self._format)
        frame["diagnostics"]["invalid_packets"] = self._parser.invalid_packets
        frame["diagnostics"]["discarded_bytes"] = self._parser.discarded_bytes
        frame["diagnostics"]["received_monotonic"] = time.monotonic()
        frame["diagnostics"].update({
            "temperature_register_31_observed": self._observed_format,
            "units_source": self._units_source,
            "factory_profile": self._factory_profile,
            "identity_registers": {f"{address:02X}": value
                                    for address,value in self._identity_registers.items()},
            "checksum_variant_evidence": "restricted_factory_profile_empirical_01fe"
                if packet.checksum_mode == "waveshare_224_sum16_plus_01fe" else "standard_protocol"})
        with self._condition:
            self._latest = frame
            self._condition.notify_all()

    def _reader(self):
        try:
            while not self._stop.is_set():
                try:
                    packet = self._receive_packets(time.monotonic()+0.75)
                except SenxorTCPError as exc:
                    if exc.code == "thermal_tcp_timeout":
                        continue
                    raise
                if packet.command == "GFRA":
                    self._publish(packet)
        except (OSError, SenxorTCPError) as exc:
            if not self._stop.is_set():
                with self._condition:
                    self._last_error = exc if isinstance(exc, SenxorTCPError) else SenxorTCPError("thermal_tcp_io", "热像网络连接中断")
                    self._condition.notify_all()

    def fetch(self, since=None, timeout=4.0):
        if not isinstance(timeout, (int,float)) or not math.isfinite(timeout) or not 0 < timeout <= 30:
            raise ValueError("timeout must be within (0,30]")
        deadline = time.monotonic()+timeout
        with self._operation:
            try:
                if self._socket is None:
                    self._open(deadline)
                with self._condition:
                    while True:
                        if self._last_error is not None:
                            raise self._last_error
                        if self._latest is not None:
                            age = time.monotonic()-self._latest["diagnostics"]["received_monotonic"]
                            if age < 2 and self._latest["frame_number"] != since:
                                return self._latest
                        remaining = deadline-time.monotonic()
                        if remaining <= 0:
                            raise SenxorTCPError("thermal_tcp_timeout", "未收到新的有效热像帧；请断开其他预览客户端后重试")
                        self._condition.wait(remaining)
            except (OSError, SenxorTCPError) as exc:
                stage = self._stage
                self.close()
                if isinstance(exc, SenxorTCPError):
                    if exc.code == "thermal_tcp_timeout" and stage not in ("streaming", "disconnected"):
                        raise SenxorTCPError("thermal_tcp_command_timeout",
                            "TCP已建立，但热像板未完成控制握手（" + stage +
                            "）；请退出其他预览软件，复位热像板后只连接本网页") from None
                    raise
                raise SenxorTCPError("thermal_tcp_io", "热像TCP连接失败；请检查地址并断开SenXor预览软件") from None

    def close(self):
        with self._operation:
            self._stop.set()
            sock, self._socket = self._socket, None
            if sock is not None:
                try:
                    sock.settimeout(0.2)
                    sock.sendall(encode_command("WREG", "B100"))
                except (OSError, AttributeError):
                    pass
                try:
                    # Stock TCP firmware restarts accept() only for read()<0;
                    # normal EOF read()==0 strands its sole client slot. Use
                    # RST, never shutdown(), to exercise its supported path.
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, abortive_linger_value())
                except (OSError, AttributeError):
                    pass
                try:
                    sock.close()
                except OSError:
                    pass
            thread, self._thread = self._thread, None
            if thread is not None and thread is not threading.current_thread():
                thread.join(timeout=1)
            with self._condition:
                self._latest = None
                self._stage = "disconnected"
                self._condition.notify_all()
