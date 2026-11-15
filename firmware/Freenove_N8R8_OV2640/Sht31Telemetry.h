#pragma once

#include <Arduino.h>
#include <Wire.h>
#include <sdkconfig.h>

// The camera creates its SCCB bus on the SDK-configured controller. Do not
// initialize SHT31 on that controller, even though its GPIOs are different.
#if CONFIG_SCCB_HARDWARE_I2C_PORT1
constexpr uint8_t SHT31_BUS = 0;
#else
constexpr uint8_t SHT31_BUS = 1;
#endif

class Sht31Telemetry {
 public:
  // Environmental control changes slowly. Repeated HTTP requests never trigger
  // extra conversions or renew measuredMs_/sequence_.
  static constexpr uint32_t SAMPLE_INTERVAL_MS = 15000;
  static constexpr uint32_t FRESHNESS_MS = 45000;
  static constexpr uint32_t MIN_SAMPLE_INTERVAL_SECONDS = 5;
  static constexpr uint32_t MAX_SAMPLE_INTERVAL_SECONDS = 30;

  Sht31Telemetry() : bus_(SHT31_BUS) {}

  // RAM-only setting; reboot returns to 15s. Never performs I2C or changes the
  // last real measurement time, sequence or last conversion start time.
  bool setSampleIntervalSeconds(uint32_t seconds) {
    if (seconds < MIN_SAMPLE_INTERVAL_SECONDS || seconds > MAX_SAMPLE_INTERVAL_SECONDS) return false;
    sampleIntervalMs_ = seconds * 1000;
    return true;
  }

  uint32_t sampleIntervalMs() const { return sampleIntervalMs_; }

  void begin() {
    ready_ = bus_.begin(41, 42, 100000);
    bus_.setTimeOut(50);
    lastAttemptMs_ = millis() - SAMPLE_INTERVAL_MS;
    error_ = ready_ ? "not_sampled" : "bus_init_failed";
    Serial.printf("SHT31: SDA=41 SCL=42 I2C=%u | every %lu ms | %s\n",
                  SHT31_BUS, static_cast<unsigned long>(sampleIntervalMs_),
                  ready_ ? "ready" : "bus init failed");
  }

  void poll() {
    if (!ready_) return;
    const uint32_t now = millis();
    if (pending_) {
      if (now - conversionMs_ < 20) return;
      pending_ = false;
      // A slow client may hold up the main loop; don't timestamp old data as new.
      if (now - conversionMs_ > 1000) {
        fail("conversion_expired");
        return;
      }
      if (bus_.requestFrom(address_, static_cast<uint8_t>(6)) != 6) {
        while (bus_.available()) bus_.read();
        fail("read_failed");
        return;
      }
      uint8_t bytes[6];
      for (uint8_t &value : bytes) value = bus_.read();
      if (crc8(bytes) != bytes[2] || crc8(bytes + 3) != bytes[5]) {
        fail("crc_failed");
        return;
      }
      const uint16_t rawTemperature = (uint16_t(bytes[0]) << 8) | bytes[1];
      const uint16_t rawHumidity = (uint16_t(bytes[3]) << 8) | bytes[4];
      temperature_ = -45.0f + 175.0f * rawTemperature / 65535.0f;
      humidity_ = 100.0f * rawHumidity / 65535.0f;
      // Values beyond SHT31's specified operating range are not usable readings.
      if (temperature_ < -40.0f || temperature_ > 125.0f) {
        fail("out_of_range");
        return;
      }
      measuredMs_ = conversionMs_ + 20;
      hasSample_ = true;
      latestOk_ = true;
      ++sequence_;
      error_ = "none";
      Serial.printf("SHT31 #%lu | Temperature: %.2f C | Humidity: %.2f %%RH | CRC OK\n",
                    static_cast<unsigned long>(sequence_), temperature_, humidity_);
      return;
    }
    if (now - lastAttemptMs_ < sampleIntervalMs_) return;
    lastAttemptMs_ = now;
    if (!address_) {
      for (uint8_t candidate : {uint8_t(0x44), uint8_t(0x45)}) {
        bus_.beginTransmission(candidate);
        if (bus_.endTransmission() == 0) {
          address_ = candidate;
          break;
        }
      }
      if (!address_) {
        fail("not_found");
        return;
      }
    }
    bus_.beginTransmission(address_);
    bus_.write(0x24);
    bus_.write(0x00);  // High repeatability, no clock stretching; heater stays off.
    if (bus_.endTransmission() != 0) {
      fail("command_failed");
      return;
    }
    conversionMs_ = millis();
    pending_ = true;  // Let camera/network requests run during the conversion.
  }

  String json() const {
    const uint32_t age = millis() - measuredMs_;
    const bool fresh = hasSample_ && latestOk_ && age <= FRESHNESS_MS;
    const char *status = fresh ? "fresh" : (hasSample_ && latestOk_ ? "stale" :
                        (!hasSample_ && errors_ == 0 && ready_ ? "starting" : "error"));
    String result;
    result.reserve(700);
    result = "{\"sensor_model\":\"sht31\",\"input_origin\":\"sensor\",\"sensor_id\":\"sht31-1\"";
    result += ",\"status\":\"" + String(status) + "\",\"valid\":" + (fresh ? "true" : "false");
    result += ",\"ambient_temperature_c\":" + (fresh ? String(temperature_, 2) : String("null"));
    result += ",\"relative_humidity_percent\":" + (fresh ? String(humidity_, 2) : String("null"));
    result += ",\"sample_age_ms\":" + (hasSample_ ? String(age) : String("null"));
    result += ",\"sample_sequence\":" + String(sequence_);
    result += ",\"sample_interval_ms\":" + String(sampleIntervalMs_);
    result += ",\"freshness_limit_ms\":" + String(FRESHNESS_MS);
    result += ",\"sample_interval_configurable\":true,\"sample_interval_min_seconds\":5";
    result += ",\"sample_interval_max_seconds\":30,\"default_sample_interval_seconds\":15";
    result += ",\"configuration_endpoint\":\"/api/v1/environment/config\"";
    result += ",\"configuration_persistence\":\"until_restart\"";
    result += ",\"read_errors\":" + String(errors_);
    result += ",\"last_error\":\"" + String(error_) + "\"}";
    return result;
  }

 private:
  static uint8_t crc8(const uint8_t *bytes) {
    uint8_t crc = 0xFF;
    for (uint8_t i = 0; i < 2; ++i) {
      crc ^= bytes[i];
      for (uint8_t bit = 0; bit < 8; ++bit)
        crc = (crc & 0x80) ? (crc << 1) ^ 0x31 : crc << 1;
    }
    return crc;
  }

  void fail(const char *reason) {
    ++errors_;
    latestOk_ = false;
    address_ = 0;  // Retry discovery at the next scheduled sample.
    error_ = reason;
    Serial.printf("SHT31 unavailable: %s (camera remains active)\n", reason);
  }

  TwoWire bus_;
  bool ready_ = false, pending_ = false, hasSample_ = false, latestOk_ = false;
  uint8_t address_ = 0;
  uint32_t lastAttemptMs_ = 0, conversionMs_ = 0, measuredMs_ = 0;
  uint32_t sequence_ = 0, errors_ = 0;
  uint32_t sampleIntervalMs_ = SAMPLE_INTERVAL_MS;
  float temperature_ = 0, humidity_ = 0;
  const char *error_ = "not_sampled";
};
