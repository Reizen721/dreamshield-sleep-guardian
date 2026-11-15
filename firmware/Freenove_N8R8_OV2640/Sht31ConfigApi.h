#pragma once
#include <WebServer.h>
#include <cstring>
#include "Sht31Telemetry.h"

namespace sht31_config {
// Deliberately narrow JSON grammar: exactly three known keys; ASCII identities
// without escapes; a canonical integer. No arrays, duplicates or unknown keys.
struct Request { char device[32] = {}; char boot[9] = {}; uint32_t seconds = 0; };

inline void whitespace(const char *&p) {
  while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') ++p;
}
inline bool token(const char *&p, char value) {
  whitespace(p);
  if (*p != value) return false;
  ++p;
  return true;
}
inline bool text(const char *&p, char *out, size_t capacity) {
  if (!token(p, '"')) return false;
  size_t size = 0;
  while (*p && *p != '"') {
    const char value = *p++;
    if (!((value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') ||
          (value >= '0' && value <= '9') || value == '_' || value == '-') || size+1 >= capacity)
      return false;
    out[size++] = value;
  }
  if (!size || *p != '"') return false;
  ++p;
  out[size] = 0;
  return true;
}
inline bool decode(const char *body, Request &result) {
  const char *p = body;
  if (!p || !token(p, '{')) return false;
  unsigned fields = 0;
  for (unsigned index = 0; index < 3; ++index) {
    char key[32] = {};
    if (!text(p, key, sizeof(key)) || !token(p, ':')) return false;
    if (!strcmp(key, "device_id")) {
      if (fields & 1 || !text(p, result.device, sizeof(result.device))) return false;
      fields |= 1;
    } else if (!strcmp(key, "boot_id")) {
      if (fields & 2 || !text(p, result.boot, sizeof(result.boot))) return false;
      fields |= 2;
    } else if (!strcmp(key, "sample_interval_seconds")) {
      if (fields & 4) return false;
      whitespace(p);
      if (*p < '1' || *p > '9') return false;
      unsigned digits = 0;
      uint32_t value = 0;
      while (*p >= '0' && *p <= '9') {
        if (++digits > 2) return false;
        value = value * 10 + (*p++ - '0');
      }
      if (value < Sht31Telemetry::MIN_SAMPLE_INTERVAL_SECONDS ||
          value > Sht31Telemetry::MAX_SAMPLE_INTERVAL_SECONDS) return false;
      result.seconds = value;
      fields |= 4;
    } else return false;
    if (index < 2 && !token(p, ',')) return false;
  }
  if (fields != 7 || !token(p, '}')) return false;
  whitespace(p);
  return *p == 0;
}

inline void reject(WebServer &server, int status, const char *error) {
  server.sendHeader("Cache-Control", "no-store");
  server.send(status, "application/json; charset=utf-8",
              "{\"status\":\"rejected\",\"error\":\"" + String(error) + "\"}");
}

inline void handle(WebServer &server, Sht31Telemetry &sensor,
                   const char *deviceId, const char *bootId, bool networkSetupActive) {
  if (server.method() != HTTP_POST) {
    server.sendHeader("Allow", "POST");
    reject(server, 405, "post_required"); return;
  }
  // Local-server API only. Blocking Origin plus strict JSON prevents browser
  // cross-site form writes. This is not LAN authentication; never expose on WAN.
  if (server.header("Origin").length()) {
    reject(server, 403, "origin_rejected"); return;
  }
  if (server.header("Content-Type") != "application/json" ||
      server.args() != 1 || !server.hasArg("plain")) {
    reject(server, 400, "expected_json_body_only"); return;
  }
  const String body = server.arg("plain");
  if (!body.length() || body.length() > 256) {
    reject(server, 413, "body_size_invalid"); return;
  }
  if (strlen(body.c_str()) != body.length()) {
    reject(server, 400, "embedded_nul_rejected"); return;
  }
  Request request;
  if (!decode(body.c_str(), request)) {
    reject(server, 400, "invalid_config_body_or_interval"); return;
  }
  if (networkSetupActive || !deviceId || !*deviceId || !bootId || !*bootId) {
    reject(server, 503, "configuration_unavailable"); return;
  }
  if (strcmp(request.device, deviceId) || strcmp(request.boot, bootId)) {
    reject(server, 409, "device_or_boot_changed"); return;
  }
  // All validation precedes the only mutation. No IR or camera operations.
  if (!sensor.setSampleIntervalSeconds(request.seconds)) {
    reject(server, 400, "unsupported_interval"); return;
  }
  String response = "{\"status\":\"applied\",\"device_id\":\"" + String(deviceId);
  response += "\",\"boot_id\":\"" + String(bootId) + "\",\"sample_interval_seconds\":";
  response += String(request.seconds) + ",\"sample_interval_ms\":" + String(sensor.sampleIntervalMs());
  response += ",\"freshness_limit_ms\":45000,\"persistence\":\"until_restart\"";
  response += ",\"default_sample_interval_seconds\":15,\"execution_allowed\":false}";
  server.sendHeader("Cache-Control", "no-store");
  server.send(200, "application/json; charset=utf-8", response);
}
}  // namespace sht31_config
