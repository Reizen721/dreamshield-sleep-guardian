#pragma once
#include <Arduino.h>
#include <WebServer.h>
#include <esp_system.h>
#include "esp32-hal-rmt.h"
#include "DreamShieldIrProtocol.h"

// Bounded LAN actuator: no receive ISR, general protocol input, raw waveform
// upload, startup emission, automatic retry, or assumption of appliance state.
class DreamShieldIrEmission {
 public:
  static constexpr uint8_t kTxPin = 21;
  static constexpr uint32_t kCarrierHz = 38000;

  bool begin(const char *bootId) {
    strlcpy(bootId_, bootId, sizeof(bootId_));
    pinMode(kTxPin, OUTPUT);
    digitalWrite(kTxPin, LOW);
    snprintf(requestToken_, sizeof(requestToken_), "%08X%08X%08X%08X",
             static_cast<unsigned>(esp_random()), static_cast<unsigned>(esp_random()),
             static_cast<unsigned>(esp_random()), static_cast<unsigned>(esp_random()));
    ready_ = rmtInit(kTxPin, RMT_TX_MODE, RMT_MEM_NUM_BLOCKS_1, 1000000);
    if (ready_) {
      // This core forwards carrier_level as IDF polarity_active_low: false
      // modulates HIGH marks, as verified by the project's optical bench.
      ready_ = rmtSetCarrier(kTxPin, true, false, kCarrierHz, 0.33f) &&
               rmtSetEOT(kTxPin, LOW);
      if (!ready_) disable();
    }
    Serial.printf("AC IR output: %s | GPIO21 | YAPOFB14 experimental | no startup TX\n",
                  ready_ ? "READY" : "INIT_ERROR");
    return ready_;
  }

  void setDeviceId(const char *deviceId) {
    // Main supplies its generated alphanumeric/hyphen device identity, never
    // an HTTP input. Reuse the ID whitelist to keep JSON output unambiguous.
    if (dreamshield_ir::validCommandId(deviceId) && strlen(deviceId) < sizeof(deviceId_))
      strlcpy(deviceId_, deviceId, sizeof(deviceId_));
  }

  static String fixedOptionsJson() {
    return "{\"auxiliary_heat\":\"off\",\"display_light\":\"off\",\"ac_wifi\":\"off\","
           "\"sleep\":\"off\",\"quiet\":\"off\",\"turbo\":\"off\",\"timer\":\"off\","
           "\"ifeel\":\"off\",\"xfan\":\"off\",\"econo\":\"off\",\"temperature_unit\":\"celsius\"}";
  }

  String acJson() const {
    String value;
    value.reserve(1200);
    value = "{\"ready\":" + String(ready_ && deviceId_[0] ? "true" : "false");
    value += ",\"profile\":\"yapofb14_basic_128\",\"state_schema_version\":1";
    value += ",\"endpoint\":\"/api/v1/ac/command\",\"experimental_compatibility\":true";
    value += ",\"emission_only\":true,\"ac_acknowledged\":false,\"carrier_hz\":38000";
    value += ",\"carrier_duty_percent\":33,\"cooldown_ms\":" + String(dreamshield_ir::kCooldownMs);
    value += ",\"cooldown_remaining_ms\":" + String(ledger_.cooldownRemaining(millis()));
    value += ",\"emission_count\":" + String(emissionCount_);
    value += ",\"allowed_modes\":[\"auto\",\"cool\",\"dry\",\"fan\",\"heat\"]";
    value += ",\"setpoint_min_c\":16,\"setpoint_max_c\":30,\"auto_setpoint_c\":25";
    value += ",\"allowed_fans\":[\"auto\",\"low\",\"medium\",\"high\"],\"dry_fan\":\"low\"";
    value += ",\"swing_semantics\":\"on_auto_sweep_off_last_position\"";
    value += ",\"fixed_options\":" + fixedOptionsJson();
    value += ",\"dedupe_scope\":\"last_64_attempts_this_boot\"";
    value += ",\"request_token\":\"" + String(requestToken_) + "\"}";
    return value;
  }

  void handleAc(WebServer &server, bool networkSetupActive) {
    if (!authorize(server)) return;
    static const char *const fields[] = {"command_id", "boot_id", "power", "mode", "setpoint_c",
                                        "fan", "swing_vertical", "swing_horizontal"};
    if (server.header("Content-Type") != "application/x-www-form-urlencoded" || server.args() != 8) {
      reject(server, 400, "expected_complete_ac_state_form"); return;
    }
    for (const char *field : fields) if (!server.hasArg(field)) {
      reject(server, 400, "expected_complete_ac_state_form"); return;
    }
    const String id = server.arg("command_id");
    dreamshield_ac::State state;
    if (!dreamshield_ir::validCommandId(id.c_str()) ||
        !dreamshield_ac::parseOnOff(server.arg("power").c_str(), state.power) ||
        !dreamshield_ac::parseMode(server.arg("mode").c_str(), state.mode) ||
        !dreamshield_ac::parseSetpoint(server.arg("setpoint_c").c_str(), state.setpoint) ||
        !dreamshield_ac::parseFan(server.arg("fan").c_str(), state.fan) ||
        !dreamshield_ac::parseOnOff(server.arg("swing_vertical").c_str(), state.swingVertical) ||
        !dreamshield_ac::parseOnOff(server.arg("swing_horizontal").c_str(), state.swingHorizontal)) {
      reject(server, 400, "unsupported_ac_state_or_command_id"); return;
    }
    // Strict rather than silent normalization: the controller/UI must present
    // the final encoded state to the user and compare the exact same receipt.
    if (!dreamshield_ac::canonical(state)) {
      reject(server, 400, "noncanonical_auto_requires_25_dry_requires_low"); return;
    }
    if (server.arg("boot_id") != bootId_) {
      reject(server, 409, "boot_changed_no_transmission"); return;
    }
    if (const dreamshield_ir::Attempt *previous = ledger_.find(id.c_str())) {
      if (!dreamshield_ac::sameState(previous->state, state))
        reject(server, 409, "command_id_conflict");
      else respond(server, *previous, true);
      return;
    }
    if (!deviceId_[0]) { reject(server, 503, "device_identity_unavailable"); return; }
    if (!available(server, networkSetupActive)) return;
    uint16_t raw[dreamshield_ac::kRawDurations] = {};
    if (!dreamshield_ac::buildRaw(state, raw, dreamshield_ac::kRawDurations)) {
      reject(server, 500, "encoding_failed_no_transmission"); return;
    }
    const size_t count = buildSymbols(raw, dreamshield_ac::kRawDurations);
    if (!count) { reject(server, 500, "encoding_failed_no_transmission"); return; }
    // Reserve before physical emission. Never retry an uncertain/partial TX.
    dreamshield_ir::Attempt &attempt = ledger_.reserve(id.c_str(), state, millis());
    Serial.printf("AC_TX_BEGIN | command=%s | YAPOFB14 experimental | power=%s mode=%s temp=%u fan=%s | one frame\n",
                  id.c_str(), state.power ? "on" : "off", dreamshield_ac::modeName(state.mode),
                  state.setpoint, dreamshield_ac::fanName(state.fan));
    transmit(attempt, count);
    respond(server, attempt, false);
  }

 private:
  char bootId_[9] = {};
  char deviceId_[32] = {};
  char requestToken_[33] = {};
  bool ready_ = false;
  uint32_t emissionCount_ = 0;
  dreamshield_ir::Ledger ledger_;
  // Persist through any RMT timeout until disable() stops the channel.
  rmt_data_t symbols_[144] = {};

  void disable() {
    rmtDeinit(kTxPin);
    pinMode(kTxPin, OUTPUT);
    digitalWrite(kTxPin, LOW);
    ready_ = false;
  }
  bool authorize(WebServer &server) {
    if (server.header("Origin").length() ||
        server.header("X-DreamShield-IR-Token") != requestToken_) {
      reject(server, 403, "request_token_or_origin_rejected"); return false;
    }
    return true;
  }
  bool available(WebServer &server, bool networkSetupActive) {
    if (!ready_ || networkSetupActive) {
      reject(server, 503, "transmitter_unavailable"); return false;
    }
    if (ledger_.cooldownRemaining(millis())) {
      server.sendHeader("Retry-After", String((ledger_.cooldownRemaining(millis()) + 999) / 1000));
      reject(server, 429, "cooldown_active_no_transmission"); return false;
    }
    return true;
  }
  void transmit(dreamshield_ir::Attempt &attempt, size_t symbolCount) {
    attempt.emitted = rmtWrite(kTxPin, symbols_, symbolCount, 1000);
    if (attempt.emitted) ++emissionCount_;
    else disable();
    Serial.println(attempt.emitted ?
        "IR_EMITTED_LOCAL_ONLY | RMT complete; NOT an AC acknowledgement" :
        "IR_TX_FAILED | outcome may be partial; no retry; reset after checking hardware");
  }
  size_t buildSymbols(const uint16_t *raw, size_t count) {
    memset(symbols_, 0, sizeof(symbols_));
    size_t phase = 0;
    for (size_t i = 0; i < count; ++i) {
      uint32_t remaining = raw[i];
      while (remaining) {
        // RMT's individual duration is 15-bit. Split a 39,950 us LOW gap
        // without truncation or introducing a spurious HIGH/carrier pulse.
        const uint16_t duration = remaining > 32767 ? 32767 : remaining;
        if (phase >= 2 * (sizeof(symbols_) / sizeof(symbols_[0]))) return 0;
        rmt_data_t &symbol = symbols_[phase / 2];
        const uint8_t level = i % 2 == 0 ? HIGH : LOW;
        if (phase % 2 == 0) { symbol.level0 = level; symbol.duration0 = duration; }
        else { symbol.level1 = level; symbol.duration1 = duration; }
        ++phase;
        remaining -= duration;
      }
    }
    // The new profile has one long inter-group gap and an odd phase count.
    // Fill the final half-symbol with 1 us LOW, extending only the final idle
    // gap by 1 us. No zero-length mark or early RMT end marker is introduced.
    if (phase % 2) {
      symbols_[phase / 2].level1 = LOW;
      symbols_[phase / 2].duration1 = 1;
    }
    return (phase + 1) / 2;
  }
  void reject(WebServer &server, int code, const char *reason) {
    String value = "{\"status\":\"" + String(reason) + "\",\"emitted\":false";
    value += ",\"transmitted_this_request\":false,\"ac_acknowledged\":false";
    value += ",\"cooldown_remaining_ms\":" + String(ledger_.cooldownRemaining(millis())) + "}";
    server.sendHeader("Cache-Control", "no-store");
    server.send(code, "application/json; charset=utf-8", value);
  }
  void respond(WebServer &server, const dreamshield_ir::Attempt &attempt, bool duplicate) {
    String value = "{\"status\":\"";
    value += attempt.emitted ? "emitted_local_only" : "tx_failed_outcome_unknown";
    value += "\",\"command_id\":\"" + String(attempt.commandId) + "\",\"boot_id\":\"" + String(bootId_) + "\"";
    value += ",\"device_id\":\"" + String(deviceId_) + "\"";
    const dreamshield_ac::State &state = attempt.state;
    value += ",\"profile\":\"yapofb14_basic_128\",\"state_schema_version\":1,\"experimental_compatibility\":true";
    value += ",\"fixed_options\":" + fixedOptionsJson();
    value += ",\"state\":{\"power\":\"" + String(state.power ? "on" : "off") + "\"";
    value += ",\"mode\":\"" + String(dreamshield_ac::modeName(state.mode)) + "\"";
    value += ",\"setpoint_c\":" + String(state.setpoint);
    value += ",\"fan\":\"" + String(dreamshield_ac::fanName(state.fan)) + "\"";
    value += ",\"swing_vertical\":\"" + String(state.swingVertical ? "on" : "off") + "\"";
    value += ",\"swing_horizontal\":\"" + String(state.swingHorizontal ? "on" : "off") + "\"}";
    value += ",\"emitted\":" + String(attempt.emitted ? "true" : "false");
    value += ",\"duplicate\":" + String(duplicate ? "true" : "false");
    value += ",\"transmitted_this_request\":" + String(attempt.emitted && !duplicate ? "true" : "false");
    value += ",\"ac_acknowledged\":false,\"cooldown_remaining_ms\":";
    value += String(ledger_.cooldownRemaining(millis())) + "}";
    server.sendHeader("Cache-Control", "no-store");
    server.send(attempt.emitted ? 200 : 503, "application/json; charset=utf-8", value);
  }
};
