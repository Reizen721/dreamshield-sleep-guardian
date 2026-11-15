#pragma once
#include <Arduino.h>
#include <WiFi.h>
#include <esp_log.h>
#include <esp_mac.h>
#include <esp_wifi.h>
#include <cstring>

// Phone onboarding uses secure BLE provisioning by default. The legacy local
// secrets.h build remains selectable with 0; neither mode erases verified NVS.
#ifndef DREAMSHIELD_USE_BLE_PROVISIONING
#define DREAMSHIELD_USE_BLE_PROVISIONING 1
#endif

#if DREAMSHIELD_USE_BLE_PROVISIONING
#include "DreamShieldProvisioning.h"
class DreamShieldNetwork : public DreamShieldProvisioning {
 public:
  const char *configurationSource() const { return "ble_confirmed_nvs"; }
};
#else

#if __has_include("secrets.h")
#include "secrets.h"
#else
#error "Missing secrets.h: copy secrets.example.h and enter your own 2.4 GHz Wi-Fi settings locally. Never share or commit secrets.h."
#endif

class DreamShieldNetwork {
 public:
  static constexpr uint32_t CONNECT_TIMEOUT_MS = 30000;
  static constexpr uint32_t RECONNECT_COOLDOWN_MS = 10000;

  bool begin(const char *bootId) {
    (void)bootId;
    uint8_t mac[6] = {};
    if (esp_read_mac(mac, ESP_MAC_WIFI_STA) != ESP_OK) {
      Serial.println("ERROR: network identity unavailable."); return false;
    }
    snprintf(deviceId_, sizeof(deviceId_), "dreamshield-%02x%02x%02x%02x%02x%02x",
        mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    snprintf(hostname_, sizeof(hostname_), "dreamshield-%02x%02x%02x", mac[3], mac[4], mac[5]);
    // Suppress SDK debug strings that might expose credentials in later SDKs.
    esp_log_level_set("*", ESP_LOG_NONE);
    // Hostname must be set BEFORE starting the STA interface.
    WiFi.setHostname(hostname_);
    WiFi.persistent(false);
    WiFi.setAutoReconnect(false);
    const size_t ssidLength = WIFI_SSID ? strlen(WIFI_SSID) : 0;
    const size_t passwordLength = WIFI_PASSWORD ? strlen(WIFI_PASSWORD) : 0;
    configured_ = ssidLength >= 1 && ssidLength <= 32 &&
        (passwordLength == 0 || (passwordLength >= 8 && passwordLength <= 63)) &&
        strcmp(WIFI_SSID, "YOUR_2_4_GHZ_WIFI_NAME") != 0 &&
        (!WIFI_PASSWORD || strcmp(WIFI_PASSWORD, "YOUR_WIFI_PASSWORD") != 0);
    Serial.println("Network source : compiled_header");
    Serial.println("BLE provisioning: DISABLED (BOOT and USB p do not open a BLE window).");
    if (!configured_) {
      Serial.println("ERROR: invalid or placeholder Wi-Fi settings in secrets.h; no values printed and no NVS erased.");
      return false;
    }
    // STA startup may wait briefly for driver initialization, never for joining
    // the router. The connection itself is asynchronous, keeping capture/SHT31
    // and HTTP tasks alive during router loss or an incorrect password.
    initialized_ = WiFi.STA.begin(false);
    if (!initialized_ || esp_wifi_set_storage(WIFI_STORAGE_RAM) != ESP_OK) {
      initialized_ = false;
      Serial.println("ERROR: Wi-Fi RAM-only initialization failed; stored configurations preserved.");
      return false;
    }
    WiFi.setSleep(false);
    connect(false);
    return true;
  }

  void poll() {
    // Intentionally no BOOT read and no USB command processing in header mode.
    if (!initialized_ || !configured_) return;
    const uint32_t now = millis();
    if (WiFi.status() == WL_CONNECTED) {
      connecting_ = false;
      return;
    }
    if (connecting_) {
      if (static_cast<uint32_t>(now - attemptStartedMs_) < CONNECT_TIMEOUT_MS) return;
      // Neither radio-off nor erase: only cancel this pending RAM connection.
      WiFi.disconnect(false, false);
      connecting_ = false;
      lastAttemptMs_ = now;
      Serial.println("Wi-Fi join timed out; retry cooldown active (credentials not logged).");
      return;
    }
    if (static_cast<uint32_t>(now - lastAttemptMs_) >= RECONNECT_COOLDOWN_MS) connect(true);
  }

  bool active() const { return false; }
  bool configured() const { return configured_; }
  bool connected() const { return initialized_ && WiFi.status() == WL_CONNECTED; }
  uint32_t reconnectAttempts() const { return reconnectAttempts_; }
  const char *deviceId() const { return deviceId_; }
  const char *hostname() const { return hostname_; }
  const char *configurationSource() const { return "compiled_header"; }

 private:
  bool initialized_ = false, configured_ = false, connecting_ = false;
  uint32_t attemptStartedMs_ = 0, lastAttemptMs_ = 0, reconnectAttempts_ = 0;
  char deviceId_[32] = {}, hostname_[32] = {};
  void connect(bool retry) {
    if (retry) ++reconnectAttempts_;
    attemptStartedMs_ = lastAttemptMs_ = millis();
    connecting_ = WiFi.STA.connect(WIFI_SSID, WIFI_PASSWORD, 0, nullptr, true);
    Serial.println(connecting_ ? "Wi-Fi join requested; waiting without blocking capture." :
        "Wi-Fi join request rejected; retry cooldown active.");
  }
};
#endif
