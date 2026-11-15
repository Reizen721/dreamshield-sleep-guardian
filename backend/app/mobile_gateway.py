"""Opt-in, authenticated HTTPS gateway for the local Android companion.

The original desktop server stays on loopback. This gateway never hosts its
dashboard or paid manual-VLM endpoints. Pairing credentials are displayed only
by the desktop owner's setup page; the phone trusts the exact certificate pin.
No device/model calls occur merely by importing this module or constructing it.
"""
from __future__ import annotations

import base64
import functools
import hashlib
import hmac
import ipaddress
import io
import json
import math
import os
import re
import secrets
import socket
import ssl
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable

from control_policy import CONTROL_PRESETS, DEFAULT_POLICY_SETTINGS, validate_policy_settings


SERVER_NAME = "梦盾助手"
PROTOCOL_VERSION = 1
PAIRING_TTL_SECONDS = 300
SESSION_TTL_SECONDS = 30 * 86400  # Legacy v1 lifetime; retained for migration fixtures.
# Android releases through 0.5.7 require an ISO expiry in the pairing reply.
# Authorization itself is durable and revocable; this compatibility timestamp
# does not impose a rolling renewal requirement on newly paired clients.
PERSISTENT_CLIENT_EXPIRY = "9999-12-31T23:59:59Z"
SESSION_ID_PATTERN = re.compile(r"^[0-9a-f]{24}$")
MAX_PAIRING_ATTEMPTS = 10
MAX_SESSIONS = 16
MAX_COMMAND_BYTES = 64 * 1024
# A small transport-only allowance lets us return 413 reliably even when a
# client sent a body just over the acceptance limit. It never increases the
# accepted command size, and very large/chunked bodies are never drained.
MAX_REJECT_DRAIN_BYTES = MAX_COMMAND_BYTES + 8 * 1024
MAX_RESPONSE_BYTES = 10 * 1024 * 1024
PROXY_TIMEOUT_SECONDS = 15
PRIVATE_IPV4 = tuple(ipaddress.ip_network(value) for value in
                     ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
GET_ROUTES = frozenset({
    "/api/app/status", "/api/device/status", "/api/device/snapshot",
    "/api/environment/current",
    "/api/captures", "/api/environment/history", "/api/automatic-ai/history",
    "/api/policy-settings",
})
POST_ROUTES = frozenset({"/api/config", "/api/capture", "/api/automatic-ai",
                         "/api/policy-settings", "/api/sensor-settings"})
POLICY_FIELDS = frozenset({"preset", "policy", "environment_interval_seconds", "sensor_sample_target_seconds"})
OWNER_SETTINGS_ROUTES = frozenset({"/api/policy-settings", "/api/sensor-settings"})
CONFIG_FIELDS = frozenset({
    "capture_interval_seconds", "schedule_enabled",
    "environment_recording_enabled", "environment_interval_seconds",
})
AUTOMATIC_AI_FIELDS = frozenset({
    "enabled", "cloud_upload_consent", "min_interval_seconds",
    "max_calls_per_hour", "max_calls_per_session",
})
FILE_PATH = re.compile(r"^/(captures|automatic-ai-results)/[A-Za-z0-9_./-]+$")
TOKEN_PATTERN = re.compile(r"^[A-Za-z0-9_-]{43}$")


def _iso(timestamp: float) -> str:
    return datetime.fromtimestamp(timestamp, timezone.utc).isoformat(
        timespec="seconds").replace("+00:00", "Z")


def _private_address(value: str, *, allow_loopback: bool = True) -> bool:
    try:
        address = ipaddress.ip_address(value)
    except ValueError:
        return False
    if allow_loopback and address.is_loopback:
        return True
    return address.version == 4 and any(address in network for network in PRIVATE_IPV4)


def discover_lan_addresses() -> list[str]:
    """Read local addressing; UDP connect selects a route but sends no packet."""
    preferred = os.environ.get("DREAMSHIELD_MOBILE_LAN_ADDRESS", "").strip()
    candidates: list[str] = [preferred] if _private_address(preferred, allow_loopback=False) else []
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("192.168.255.254", 9))
            candidates.append(probe.getsockname()[0])
    except OSError:
        pass
    try:
        candidates.extend(item[4][0] for item in
                          socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET))
    except OSError:
        pass
    return list(dict.fromkeys(value for value in candidates
                             if _private_address(value, allow_loopback=False)))


def encode_connection_payload(payload: dict[str, Any]) -> str:
    """Compact out-of-band connection code; never a LAN-discovery response."""
    raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return "DS1:" + base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


@functools.lru_cache(maxsize=32)
def _connection_qr(code: str) -> tuple[str, str]:
    deep_link = "dreamshield://pair?payload=" + urllib.parse.quote(code, safe="")
    try:
        import qrcode
        import qrcode.image.svg
    except ImportError:
        return deep_link, ""
    output = io.BytesIO()
    image = qrcode.make(deep_link, image_factory=qrcode.image.svg.SvgPathImage,
                        error_correction=qrcode.constants.ERROR_CORRECT_M,
                        box_size=6, border=4)
    image.save(output)
    return deep_link, "data:image/svg+xml;base64," + base64.b64encode(output.getvalue()).decode("ascii")


def _private_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    try:
        with temporary.open("wb") as destination:
            destination.write(data)
        # On Windows this does not replace ACL protection; keep the runtime
        # directory in the owner's profile, never in a shared/public folder.
        os.chmod(temporary, 0o600)
        temporary.replace(path)
    finally:
        if temporary.exists():
            temporary.unlink()


class MobileAuth:
    """Short-lived invitations plus durable, revocable, hash-only authorization."""

    def __init__(self, path: Path, clock: Callable[[], float] = time.time):
        self.path = Path(path)
        self.clock = clock
        self.lock = threading.RLock()
        self.sessions: list[dict[str, Any]] = []
        self.pairing_code = ""
        self.pairing_expires = 0.0
        self.pairing_attempts = 0
        self.last_seen: dict[str, float] = {}
        self._load()

    def _load(self) -> None:
        if not self.path.exists():
            return
        try:
            record = json.loads(self.path.read_text(encoding="utf-8"))
            version = record.get("version")
            if version not in (1, 2) or not isinstance(record.get("sessions"), list):
                raise ValueError("Invalid mobile authentication store")
            if len(record["sessions"]) > MAX_SESSIONS:
                raise ValueError("Too many stored mobile sessions")
            for item in record["sessions"]:
                if (not isinstance(item, dict) or
                        not re.fullmatch(r"[0-9a-f]{64}", str(item.get("token_hash", ""))) or
                        not isinstance(item.get("device_name"), str)):
                    raise ValueError("Invalid mobile session record")
                expires = item.get("expires_at")
                persistent = version == 2 and item.get("persistent") is True
                if ((persistent and expires is not None) or (not persistent and
                        (not isinstance(expires, (int, float)) or isinstance(expires, bool)
                         or not math.isfinite(expires) or expires > 253402300799))):
                    raise ValueError("Invalid mobile session expiry")
                if version == 2 and not SESSION_ID_PATTERN.fullmatch(str(item.get("session_id", ""))):
                    raise ValueError("Invalid mobile session identifier")
                created = item.get("created_at", self.clock())
                if (not isinstance(created, (int, float)) or isinstance(created, bool)
                        or not math.isfinite(created) or not 0 <= created <= 253402300799):
                    raise ValueError("Invalid mobile session creation time")
            # Upgrade only still-valid legacy grants; expired grants are never
            # revived. Keep their token hashes so existing phones remain paired.
            for item in record["sessions"]:
                if version == 1 and item["expires_at"] <= self.clock():
                    continue
                migrated = dict(item)
                migrated.setdefault("created_at", self.clock())
                if version == 1:
                    migrated.update(session_id=hashlib.sha256(
                        ("dreamshield-session-id:" + item["token_hash"]).encode("ascii")).hexdigest()[:24],
                        persistent=True, expires_at=None,
                        created_at=item.get("created_at", self.clock()))
                self.sessions.append(migrated)
            if len({item["session_id"] for item in self.sessions}) != len(self.sessions):
                raise ValueError("Duplicate mobile session identifier")
            if version == 1:
                self._save()
        except (OSError, ValueError, TypeError, AttributeError) as exc:
            # Do not silently replace an unreadable owner security file.
            raise RuntimeError("Cannot read mobile authentication data; review the local file before retrying") from exc

    def _save(self) -> None:
        data = json.dumps({"version": 2, "sessions": self.sessions},
                          ensure_ascii=False, indent=2).encode("utf-8")
        _private_write(self.path, data)

    def renew_pairing_code(self) -> dict[str, Any]:
        with self.lock:
            self.pairing_code = secrets.token_hex(6).upper()
            self.pairing_expires = self.clock() + PAIRING_TTL_SECONDS
            self.pairing_attempts = 0
            return self.pairing_info()

    def pairing_info(self) -> dict[str, Any]:
        with self.lock:
            valid = bool(self.pairing_code and self.clock() < self.pairing_expires
                         and self.pairing_attempts < MAX_PAIRING_ATTEMPTS)
            return {
                "pairing_code": self.pairing_code if valid else "",
                "pairing_expires_at": _iso(self.pairing_expires) if valid else None,
                "pairing_available": valid,
            }

    def pair(self, code: Any, device_name: Any) -> dict[str, Any] | None:
        with self.lock:
            if not isinstance(code, str) or len(code) > 128 or not code.isascii():
                code = ""
            if (not isinstance(device_name, str) or not device_name.strip()
                    or len(device_name) > 80
                    or any(ord(char) < 32 for char in device_name)):
                return None
            if not self.pairing_info()["pairing_available"]:
                return None
            self.pairing_attempts += 1
            if not hmac.compare_digest(self.pairing_code, code):
                return None
            token = secrets.token_urlsafe(32)
            sessions = [item for item in self.sessions if self._session_active(item)]
            if len(sessions) >= MAX_SESSIONS:
                return None
            previous = self.sessions
            self.sessions = sessions + [{
                "session_id": secrets.token_hex(12), "persistent": True,
                "token_hash": hashlib.sha256(token.encode("ascii")).hexdigest(),
                "device_name": device_name.strip(), "created_at": self.clock(),
                "expires_at": None,
            }]
            try:
                self._save()
            except OSError:
                self.sessions = previous
                raise
            self.pairing_code = ""  # A copied pairing payload works only once.
            self.pairing_expires = 0
            self.last_seen[self.sessions[-1]["session_id"]] = self.clock()
            return {"ok": True, "token": token, "expires_at": PERSISTENT_CLIENT_EXPIRY,
                    "persistent": True,
                    "device_name": device_name.strip(), "server_name": SERVER_NAME}

    def authenticate(self, authorization: str | None) -> bool:
        if not authorization or not authorization.startswith("Bearer "):
            return False
        token = authorization.removeprefix("Bearer ")
        if not TOKEN_PATTERN.fullmatch(token):
            return False
        digest = hashlib.sha256(token.encode("ascii")).hexdigest()
        with self.lock:
            for item in self.sessions:
                if self._session_active(item) and hmac.compare_digest(item["token_hash"], digest):
                    self.last_seen[item["session_id"]] = self.clock()
                    return True
            return False

    def _session_active(self, item: dict[str, Any]) -> bool:
        return item.get("persistent") is True or item["expires_at"] > self.clock()

    def revoke_session(self, session_id: str) -> bool:
        """Disconnect one grant without changing other phones or invitations."""
        if not isinstance(session_id, str) or not SESSION_ID_PATTERN.fullmatch(session_id):
            raise ValueError("Invalid mobile session identifier")
        with self.lock:
            remaining = [item for item in self.sessions if item["session_id"] != session_id]
            if len(remaining) == len(self.sessions):
                return False
            previous = self.sessions
            self.sessions = remaining
            try:
                self._save()
            except OSError:
                self.sessions = previous
                raise
            self.last_seen.pop(session_id, None)
            return True

    def revoke_sessions(self) -> int:
        with self.lock:
            count = len(self.sessions)
            previous = self.sessions
            self.sessions = []
            try:
                self._save()
            except OSError:
                self.sessions = previous
                raise
            # Also invalidate any copied, not-yet-used setup data.
            self.pairing_code = ""
            self.pairing_expires = 0
            self.last_seen.clear()
            return count

    def session_summary(self) -> list[dict[str, Any]]:
        with self.lock:
            return [{"session_id": item["session_id"], "device_name": item["device_name"],
                     "created_at": _iso(item["created_at"]),
                     "expires_at": None if item.get("persistent") else _iso(item["expires_at"]),
                     "persistent": item.get("persistent") is True,
                     "last_seen_at": _iso(self.last_seen[item["session_id"]])
                         if item["session_id"] in self.last_seen else None,
                     "recently_active": item["session_id"] in self.last_seen
                         and self.clock() - self.last_seen[item["session_id"]] <= 90}
                    for item in self.sessions if self._session_active(item)]


def validate_target(method: str, target: str) -> str | None:
    """Accept exact routes and generated file paths, never alternate origins."""
    if (len(target) > 2048 or not target.startswith("/") or target.startswith("//")
            or any(ord(char) < 32 or ord(char) > 126 for char in target)):
        return None
    try:
        parsed = urllib.parse.urlsplit(target)
    except ValueError:
        return None
    path = parsed.path
    if (parsed.scheme or parsed.netloc or parsed.fragment or "%" in path or
            "\\" in path or "//" in path or
            any(part in {".", ".."} for part in path.split("/"))):
        return None
    query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
    if method == "POST":
        return path if path in POST_ROUTES and not parsed.query else None
    if method != "GET":
        return None
    if path in GET_ROUTES:
        allowed_query = {"limit"} if path in {
            "/api/captures", "/api/environment/history"} else set()
        if path == "/api/device/snapshot":
            allowed_query = {"t", "_"}
        if any(key not in allowed_query or len(values) != 1 or
               not re.fullmatch(r"[0-9]{1,16}", values[0])
               for key, values in query.items()):
            return None
        # Reject malformed segments that parse_qs would silently discard.
        if parsed.query and (len(parsed.query.split("&")) != len(query) or
                             any("=" not in part for part in parsed.query.split("&"))):
            return None
        return target
    if FILE_PATH.fullmatch(path) and not parsed.query:
        parts = path.split("/")
        if any(not part for part in parts[1:]):
            return None
        if (path.startswith("/captures/") and path.endswith((".jpg", ".json"))) or (
                path.startswith("/automatic-ai-results/") and path.endswith(".json")):
            return path
    return None


def validate_command(path: str, body: dict[str, Any]) -> bool:
    if not isinstance(body, dict):
        return False
    if path == "/api/sensor-settings":
        return (set(body) == {"sample_interval_seconds"}
                and type(body["sample_interval_seconds"]) is int
                and 5 <= body["sample_interval_seconds"] <= 30)
    if path == "/api/policy-settings":
        if not body or not set(body) <= POLICY_FIELDS:
            return False
        selected = body.get("preset")
        if "preset" in body and (type(selected) is not str or selected not in {*CONTROL_PRESETS, "custom"}):
            return False
        supplied = body.get("policy", {})
        if not isinstance(supplied, dict):
            return False
        if any(type(value) not in (int, float) or abs(value) > 1000000
               or (type(value) is float and not math.isfinite(value))
               for value in supplied.values()):
            return False
        for field in ("environment_interval_seconds", "sensor_sample_target_seconds"):
            if field in body and (type(body[field]) is not int or not 5 <= body[field] <= 30):
                return False
        canonical = CONTROL_PRESETS.get(selected)
        candidate = {**(canonical["policy"] if canonical else DEFAULT_POLICY_SETTINGS), **supplied}
        if canonical is None:
            # Partial custom updates merge with the server's saved values. The
            # gateway has no mutable policy state, so validate supplied fields
            # using a possible counterpart; the server checks the actual merge.
            for low, high, gap in (("temperature_lower_c", "temperature_upper_c", 2),
                                   ("min_setpoint_c", "max_setpoint_c", 0)):
                value = supplied.get(low if low in supplied else high)
                if type(value) in (int, float) and math.isfinite(value):
                    if low in supplied and high not in supplied:
                        candidate[high] = max(candidate[high], value + gap)
                    elif high in supplied and low not in supplied:
                        candidate[low] = min(candidate[low], value - gap)
        try:
            policy = validate_policy_settings(candidate)
        except ValueError:
            return False
        if canonical and (policy != canonical["policy"] or any(
                field in body and body[field] != canonical[field]
                for field in ("environment_interval_seconds", "sensor_sample_target_seconds"))):
            return False
        return True
    if path == "/api/config":
        if not body or not set(body) <= CONFIG_FIELDS:
            return False
        for name in ("schedule_enabled", "environment_recording_enabled"):
            if name in body and type(body[name]) is not bool:
                return False
        for name, upper in (("capture_interval_seconds", 86400),
                            ("environment_interval_seconds", 3600)):
            if name in body and (type(body[name]) is not int or not 5 <= body[name] <= upper):
                return False
        return True
    if path == "/api/capture":
        return not body  # Current configured camera only; no URL overrides.
    if path == "/api/automatic-ai":
        if not set(body) <= AUTOMATIC_AI_FIELDS or type(body.get("enabled")) is not bool:
            return False
        if body["enabled"] and body.get("cloud_upload_consent") is not True:
            return False
        if "cloud_upload_consent" in body and type(body["cloud_upload_consent"]) is not bool:
            return False
        for name, lower, upper in (("min_interval_seconds", 60, 3600),
                                   ("max_calls_per_hour", 2, 120),
                                   ("max_calls_per_session", 2, 500)):
            if name in body and (type(body[name]) is not int or not lower <= body[name] <= upper):
                return False
        return True
    return False


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req: Any, fp: Any, code: int, msg: str,
                         headers: Any, newurl: str) -> None:
        return None


def ensure_certificate(directory: Path, addresses: list[str]) -> tuple[Path, Path, str, bool]:
    """Create/reuse an owner-private TLS certificate and return its DER pin."""
    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID
    except ImportError as exc:
        raise RuntimeError(
            "Android HTTPS requires cryptography; install mobile_requirements.txt before starting mobile mode") from exc
    directory = Path(directory)
    certificate_path = directory / "certificate.pem"
    key_path = directory / "private_key.pem"
    required = {"127.0.0.1", *addresses}
    now = datetime.now(timezone.utc)
    certificate = None
    if certificate_path.exists() and key_path.exists():
        try:
            candidate = x509.load_pem_x509_certificate(certificate_path.read_bytes())
            key = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
            san = candidate.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
            existing = {str(value) for value in san.get_values_for_type(x509.IPAddress)}
            expires = getattr(candidate, "not_valid_after_utc", None)
            if expires is None:
                expires = candidate.not_valid_after.replace(tzinfo=timezone.utc)
            before = getattr(candidate, "not_valid_before_utc", None)
            if before is None:
                before = candidate.not_valid_before.replace(tzinfo=timezone.utc)
            public_format = serialization.PublicFormat.SubjectPublicKeyInfo
            matching = candidate.public_key().public_bytes(
                serialization.Encoding.DER, public_format) == key.public_key().public_bytes(
                    serialization.Encoding.DER, public_format)
            if required <= existing and before <= now and expires > now + timedelta(days=7) and matching:
                certificate = candidate
        except (ValueError, TypeError, OSError, x509.ExtensionNotFound):
            # Corrupt or incomplete credentials rotate, never get trusted as-is.
            certificate = None
    rotated = certificate is None
    if certificate is None:
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "DreamShield local companion")])
        certificate = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject)
                       .public_key(key.public_key()).serial_number(x509.random_serial_number())
                       .not_valid_before(now - timedelta(minutes=5))
                       .not_valid_after(now + timedelta(days=365))
                       .add_extension(x509.SubjectAlternativeName(
                           [x509.IPAddress(ipaddress.ip_address(value)) for value in sorted(required)]
                           + [x509.DNSName("localhost")]), critical=False)
                       .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
                       .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
                       .sign(key, hashes.SHA256()))
        _private_write(key_path, key.private_bytes(serialization.Encoding.PEM,
                                                  serialization.PrivateFormat.PKCS8,
                                                  serialization.NoEncryption()))
        _private_write(certificate_path, certificate.public_bytes(serialization.Encoding.PEM))
    return (certificate_path, key_path,
            certificate.fingerprint(hashes.SHA256()).hex(), rotated)


class _GatewayHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], handler: Any, context: ssl.SSLContext):
        self.tls_context = context
        self.request_slots = threading.BoundedSemaphore(32)
        super().__init__(address, handler)

    def get_request(self) -> tuple[Any, Any]:
        connection, address = self.socket.accept()
        connection.settimeout(5)
        try:
            wrapped = self.tls_context.wrap_socket(connection, server_side=True)
            wrapped.settimeout(15)
            return wrapped, address
        except Exception:
            connection.close()
            raise

    def process_request(self, request: Any, client_address: Any) -> None:
        if not self.request_slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.request_slots.release()
            raise

    def process_request_thread(self, request: Any, client_address: Any) -> None:
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.request_slots.release()

    def handle_error(self, request: Any, client_address: Any) -> None:
        # Raw request paths, headers and credentials must not enter logs.
        pass


class MobileGateway:
    def __init__(self, *, backend_url: str = "http://127.0.0.1:8000",
                 data_dir: Path, bind_host: str = "0.0.0.0", port: int = 8743,
                 addresses: list[str] | None = None,
                 clock: Callable[[], float] = time.time):
        backend = urllib.parse.urlsplit(backend_url)
        if (backend.scheme != "http" or backend.hostname not in {"127.0.0.1", "localhost"}
                or backend.path not in {"", "/"} or backend.query or backend.fragment
                or backend.username or backend.password):
            raise ValueError("Mobile backend must be the loopback desktop HTTP server")
        if bind_host != "0.0.0.0" and not _private_address(bind_host):
            raise ValueError("Mobile gateway may bind only a local/private IPv4 address")
        if isinstance(port, bool) or not isinstance(port, int) or not 0 <= port <= 65535:
            raise ValueError("Invalid mobile port")
        self.backend_url = backend_url.rstrip("/")
        self.data_dir = Path(data_dir)
        self.bind_host = bind_host
        self.port = port
        self.addresses = (discover_lan_addresses() if addresses is None else
                          list(dict.fromkeys(value for value in addresses
                                             if _private_address(value, allow_loopback=False))))
        self.auth = MobileAuth(self.data_dir / "mobile_auth.json", clock)
        self.certificate_sha256 = ""
        self.httpd: _GatewayHTTPServer | None = None
        self.thread: threading.Thread | None = None
        self.lock = threading.RLock()
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), _NoRedirect())

    def start(self) -> dict[str, Any]:
        with self.lock:
            if self.httpd is not None:
                return self.setup_info()
            certificate, key, pin, rotated = ensure_certificate(
                self.data_dir / "mobile_tls", self.addresses)
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.minimum_version = ssl.TLSVersion.TLSv1_2
            context.set_alpn_protocols(["http/1.1"])
            context.load_cert_chain(str(certificate), str(key))
            httpd = _GatewayHTTPServer((self.bind_host, self.port), self.handler_class(), context)
            try:
                if rotated:
                    self.auth.revoke_sessions()
                self.auth.renew_pairing_code()
                self.certificate_sha256 = pin
                self.port = httpd.server_address[1]
                self.httpd = httpd
                self.thread = threading.Thread(target=httpd.serve_forever,
                                               name="dreamshield-mobile-https", daemon=True)
                self.thread.start()
            except Exception:
                httpd.server_close()
                self.httpd = None
                raise
            return self.setup_info()

    def stop(self) -> None:
        with self.lock:
            httpd, thread = self.httpd, self.thread
            self.httpd = None
            self.thread = None
        if httpd is not None:
            httpd.shutdown()
            httpd.server_close()
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=5)

    def renew_pairing_code(self) -> dict[str, Any]:
        with self.lock:
            if self.httpd is None:
                raise RuntimeError("Mobile gateway is not enabled")
            self.auth.renew_pairing_code()
            return self.setup_info()

    def revoke_sessions(self) -> dict[str, Any]:
        with self.lock:
            count = self.auth.revoke_sessions()
            if self.httpd is not None:
                self.auth.renew_pairing_code()
            return {"ok": True, "revoked_sessions": count, **self.setup_info()}

    def revoke_session(self, session_id: str) -> dict[str, Any]:
        with self.lock:
            revoked = self.auth.revoke_session(session_id)
            return {"ok": True, "revoked": revoked, "session_id": session_id, **self.setup_info()}

    def setup_info(self) -> dict[str, Any]:
        """Owner-only: must never be returned by the LAN handler."""
        with self.lock:
            urls = [f"https://{address}:{self.port}" for address in self.addresses]
            pairing = self.auth.pairing_info() if self.httpd else {
                "pairing_code": "", "pairing_expires_at": None, "pairing_available": False}
            primary_url = urls[0] if urls else f"https://127.0.0.1:{self.port}"
            payload = {"version": PROTOCOL_VERSION, "base_url": primary_url,
                       "certificate_sha256": self.certificate_sha256,
                       "pairing_code": pairing["pairing_code"]}
            options = []
            if self.httpd and pairing["pairing_available"]:
                for url in urls:
                    code = encode_connection_payload({**payload, "base_url": url})
                    deep_link, qr = _connection_qr(code)
                    options.append({"base_url": url, "connection_code": code,
                                    "deep_link": deep_link, "qr_svg_data_url": qr})
            return {"enabled": self.httpd is not None, "base_url": primary_url,
                    "urls": urls, "port": self.port, "server_name": SERVER_NAME,
                    "session_policy": "manual_disconnect",
                    "certificate_sha256": self.certificate_sha256,
                    **pairing, "sessions": self.auth.session_summary(),
                    "connection_options": options,
                    "connection_code": options[0]["connection_code"] if options else "",
                    "deep_link": options[0]["deep_link"] if options else "",
                    "pairing_payload": json.dumps(payload, ensure_ascii=False)
                    if self.httpd and pairing["pairing_available"] else ""}

    def forward(self, method: str, target: str, body: bytes | None = None) -> tuple[int, bytes, str]:
        safe_target = validate_target(method, target)
        if safe_target is None:
            return 403, b'{"ok":false,"error":"Route not available to mobile clients"}', "application/json"
        headers = {"Content-Type": "application/json"} if body is not None else {}
        if method == "POST" and safe_target in OWNER_SETTINGS_ROUTES:
            # Native requests have already passed token authentication, strict
            # route/body validation and browser-Origin rejection. Synthesize
            # only the exact loopback owner Origin required by these endpoints;
            # never relay a caller's Origin or mobile credentials to the backend.
            headers["Origin"] = self.backend_url
        request = urllib.request.Request(self.backend_url + safe_target, data=body, method=method,
                                         headers=headers)
        try:
            response = self.opener.open(request, timeout=PROXY_TIMEOUT_SECONDS)
        except urllib.error.HTTPError as exc:
            response = exc
        except (urllib.error.URLError, TimeoutError, OSError):
            return 502, b'{"ok":false,"error":"Desktop backend unavailable"}', "application/json"
        with response:
            data = response.read(MAX_RESPONSE_BYTES + 1)
            if len(data) > MAX_RESPONSE_BYTES:
                return 502, b'{"ok":false,"error":"Backend response too large"}', "application/json"
            status = response.code
            content_type = response.headers.get("Content-Type", "application/json")
            if 300 <= status < 400:
                return 502, b'{"ok":false,"error":"Backend redirect refused"}', "application/json"
            # Internal exception details could reveal local filesystem/security data.
            if status >= 500:
                return status, b'{"ok":false,"error":"Desktop operation failed; check its local console"}', "application/json"
            return status, data, content_type

    def handler_class(self) -> type[BaseHTTPRequestHandler]:
        gateway = self

        class Handler(BaseHTTPRequestHandler):
            server_version = "DreamShieldMobile/1"
            sys_version = ""
            protocol_version = "HTTP/1.1"

            def log_message(self, format: str, *args: Any) -> None:
                pass

            def discard_rejected_body(self) -> None:
                """Avoid a TCP reset swallowing an error response on Windows.

                Closing with unread request bytes can reset a TCP connection
                before the caller sees our status. Drain only a small,
                unambiguously length-framed body, within one total 300 ms
                deadline. Never wait for arbitrary/slow/oversized payloads.
                """
                if getattr(self, "_body_consumed", False):
                    return
                lengths = self.headers.get_all("Content-Length", [])
                if (self.headers.get("Transfer-Encoding") is not None or len(lengths) != 1
                        or not re.fullmatch(r"[0-9]{1,8}", lengths[0])):
                    return
                remaining = int(lengths[0])
                if not 0 < remaining <= MAX_REJECT_DRAIN_BYTES:
                    return
                self._body_consumed = True
                original_timeout = self.connection.gettimeout()
                deadline = time.monotonic() + 0.3
                try:
                    while remaining:
                        timeout = deadline - time.monotonic()
                        if timeout <= 0:
                            break
                        self.connection.settimeout(timeout)
                        block = self.rfile.read1(min(remaining, 8192))
                        if not block:
                            break
                        remaining -= len(block)
                except (TimeoutError, OSError):
                    pass
                finally:
                    self.connection.settimeout(original_timeout)

            def send_data(self, status: int, data: bytes, content_type: str) -> None:
                if status >= 400:
                    self.discard_rejected_body()
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("Connection", "close")
                if status == 401:
                    self.send_header("WWW-Authenticate", 'Bearer realm="DreamShield"')
                self.end_headers()
                self.close_connection = True
                self.wfile.write(data)

            def send_json(self, status: int, value: dict[str, Any]) -> None:
                self.send_data(status, json.dumps(value, ensure_ascii=False).encode("utf-8"),
                               "application/json; charset=utf-8")

            def check_client(self) -> bool:
                if not _private_address(self.client_address[0]):
                    self.send_json(403, {"ok": False, "error": "Local network clients only"})
                    return False
                if self.headers.get("Origin") is not None:
                    self.send_json(403, {"ok": False, "error": "Browser-origin requests are not accepted"})
                    return False
                if len(self.headers.get_all("Authorization", [])) > 1:
                    self.send_json(401, {"ok": False, "error": "Invalid authorization"})
                    return False
                return True

            def read_json(self) -> dict[str, Any] | None:
                if self.headers.get_content_type() != "application/json":
                    self.send_json(415, {"ok": False, "error": "JSON request required"})
                    return None
                lengths = self.headers.get_all("Content-Length", [])
                if self.headers.get("Transfer-Encoding") is not None or len(lengths) != 1:
                    self.send_json(400, {"ok": False, "error": "A single Content-Length is required"})
                    return None
                if not re.fullmatch(r"[0-9]{1,8}", lengths[0]):
                    self.send_json(400, {"ok": False, "error": "Invalid Content-Length"})
                    return None
                length = int(lengths[0])
                if not 0 < length <= MAX_COMMAND_BYTES:
                    self.send_json(413, {"ok": False, "error": "Command body too large or empty"})
                    return None
                try:
                    self._body_consumed = True
                    raw = self.rfile.read(length)
                    if len(raw) != length:
                        raise ValueError("Incomplete body")
                    def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
                        result: dict[str, Any] = {}
                        for key, value in pairs:
                            if key in result:
                                raise ValueError("Duplicate JSON field")
                            result[key] = value
                        return result
                    body = json.loads(raw.decode("utf-8"), object_pairs_hook=reject_duplicates,
                                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Invalid number")))
                    if not isinstance(body, dict):
                        raise ValueError("Invalid body")
                    return body
                except (ValueError, UnicodeDecodeError):
                    self.send_json(400, {"ok": False, "error": "Invalid JSON object"})
                    return None

            def authenticated(self) -> bool:
                if gateway.auth.authenticate(self.headers.get("Authorization")):
                    return True
                self.send_json(401, {"ok": False, "error": "Pairing required or session expired"})
                return False

            def do_GET(self) -> None:
                if not self.check_client():
                    return
                if self.path == "/api/mobile/health":
                    self.send_json(200, {"ok": True, "server_name": SERVER_NAME,
                                         "protocol_version": PROTOCOL_VERSION})
                    return
                if not self.authenticated():
                    return
                target = validate_target("GET", self.path)
                if target is None:
                    self.send_json(403, {"ok": False, "error": "Route not available to mobile clients"})
                    return
                try:
                    self.send_data(*gateway.forward("GET", target))
                except Exception:
                    self.send_json(502, {"ok": False, "error": "Desktop backend unavailable"})

            def do_POST(self) -> None:
                if not self.check_client():
                    return
                if self.path == "/api/mobile/pair":
                    body = self.read_json()
                    if body is None:
                        return
                    if set(body) != {"pairing_code", "device_name"}:
                        self.send_json(400, {"ok": False, "error": "Pairing code and device name required"})
                        return
                    try:
                        paired = gateway.auth.pair(body["pairing_code"], body["device_name"])
                    except Exception:
                        self.send_json(500, {"ok": False, "error": "Pairing could not be saved"})
                        return
                    if paired is None:
                        self.send_json(401, {"ok": False, "error": "Pairing failed; renew the code on the computer"})
                    else:
                        self.send_json(200, paired)
                    return
                if not self.authenticated():
                    return
                target = validate_target("POST", self.path)
                if target is None:
                    self.send_json(403, {"ok": False, "error": "Route not available to mobile clients"})
                    return
                body = self.read_json()
                if body is None:
                    return
                if not validate_command(target, body):
                    self.send_json(400, {"ok": False, "error": "Command contains unsupported fields"})
                    return
                try:
                    self.send_data(*gateway.forward("POST", target,
                                   json.dumps(body, allow_nan=False).encode("utf-8")))
                except Exception:
                    self.send_json(502, {"ok": False, "error": "Desktop operation unavailable"})

            def do_OPTIONS(self) -> None:
                self.send_json(405, {"ok": False, "error": "Browser access is not enabled"})

        return Handler
