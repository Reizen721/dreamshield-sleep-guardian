#pragma once

// Arduino ESP32 3.3.10 / network_provisioning. No WiFiProv wrapper: its info
// logging is suppressed. Security 1 retains encryption with no PoP/code step,
// as explicitly requested for this prototype; BLE connections need no PIN or bond.
#include <Arduino.h>
// The direct IDF provisioning path does not link an Arduino BLE wrapper.
// Mark BLE as used before initArduino(), or core 3.3.10 releases controller
// memory before setup() and NimBLE initialization crashes on the real board.
#include <esp32-hal-alloc-ble-mem.h>
#include <atomic>
#include <WiFi.h>
#include <Preferences.h>
#include <esp_log.h>
#include <esp_mac.h>
#include <esp_system.h>
#include <esp_wifi.h>
#include <cJSON.h>
#include <network_provisioning/manager.h>
#include <network_provisioning/scheme_ble.h>

#if !CONFIG_ESP_PROTOCOMM_SUPPORT_SECURITY_VERSION_1
#error "DreamShield requires protocomm Security 1 for encrypted no-code provisioning"
#endif

class DreamShieldProvisioning {
 public:
  static constexpr uint32_t WINDOW_MS = 300000;
  static constexpr uint8_t BUTTON_PIN = 0;  // BOOT, active LOW; press after boot.

  bool begin(const char *bootId) {
    bootId_ = bootId;
    pinMode(BUTTON_PIN, INPUT_PULLUP);
    // Suppress SDK provisioning diagnostics, including future verbose secret
    // dumps. Explicit Serial state messages below never include Wi-Fi secrets.
    esp_log_level_set("*", ESP_LOG_NONE);
    WiFi.persistent(false);
    WiFi.setAutoReconnect(false);
    if (!WiFi.STA.begin(false)) {
      state_ = "wifi_init_error";
      Serial.println("ERROR: Wi-Fi initialization failed; no credentials erased.");
      return false;
    }
    uint8_t mac[6] = {};
    if (esp_read_mac(mac, ESP_MAC_WIFI_STA) != ESP_OK) return false;
    snprintf(deviceId_, sizeof(deviceId_), "dreamshield-%02x%02x%02x%02x%02x%02x",
             mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    snprintf(hostname_, sizeof(hostname_), "dreamshield-%02x%02x%02x", mac[3], mac[4], mac[5]);
    snprintf(serviceName_, sizeof(serviceName_), "DreamShield-FN-%02X%02X%02X", mac[3], mac[4], mac[5]);
    WiFi.setHostname(hostname_);
    WiFi.setSleep(false);
    if (!preferences_.begin("ds-network", false)) {
      state_ = "storage_error";
      Serial.println("ERROR: network NVS unavailable; no erase performed.");
      return false;
    }
    configured_ = loadConfirmed();
    Serial.printf("Device ID: %s | BLE name: %s\n", deviceId_, serviceName_);
    Serial.println("BLE starts on EVERY boot and remains discoverable after setup; no code/PIN.");
    Serial.println("USB commands: p = reopen BLE; s = status; x = close BLE for this boot only.");
    if (!startWindow()) return configured_;
    // Provisioning transport and the normal saved-network connection coexist.
    // Starting BLE must never imply that the saved network is forgotten.
    if (configured_) connectConfirmed();
    return true;
  }

  void poll() {
    while (Serial.available()) {
      const char command = static_cast<char>(Serial.read());
      if (command == 'p') {
        if (active_ && !stopping_) Serial.println("BLE ready: connect directly from DreamShield; no code or PIN.");
        else requestWindow();
      } else if (command == 's') {
        Serial.printf("NETWORK | state=%s | configured=%s | BLE=%s | IP=%s\n",
                      state(), configured_ ? "YES" : "NO", active_ ? "ON" : "OFF", WiFi.localIP().toString().c_str());
      } else if (command == 'x' && active_) {
        if (!committed_) cancelled_ = true;
        Serial.println("BLE cancellation requested. Previously verified Wi-Fi is retained.");
        stopWindow();
      }
    }
    const uint32_t now = millis();
    if (digitalRead(BUTTON_PIN) == LOW) {
      if (buttonDownMs_ == 0) buttonDownMs_ = now;
      if (!buttonLatched_ && now - buttonDownMs_ >= 3000) {
        buttonLatched_ = true;
        buttonRequestPending_ = true;
        Serial.println("BOOT hold accepted. Release BOOT to restart into the BLE window.");
      }
    } else {
      buttonDownMs_ = 0;
      buttonLatched_ = false;
      if (buttonRequestPending_) {
        buttonRequestPending_ = false;
        requestWindow();
      }
    }
    if (successPending_.exchange(false) && !cancelled_ && !stopping_) {
      if (!saveConfirmed()) {
        state_ = "storage_error";
        Serial.println("ERROR: verified connection could not be persisted; previous record retained.");
        stopWindow();
      } else {
        configured_ = true;
        committed_ = true;
        successMs_ = now;
        candidateInProgress_ = false;
        state_ = "provisioned";
        Serial.printf("BLE provisioning verified and saved. IP: %s\n", WiFi.localIP().toString().c_str());
      }
    }
    // Only an unfinished Wi-Fi update has a deadline. BLE discovery itself
    // does not expire and does not stop after a successful connection.
    if (active_ && candidateInProgress_ && !stopping_ && now - candidateStartedMs_ >= WINDOW_MS) {
      candidateInProgress_ = false;
      state_ = "wifi_update_timeout";
      connectConfirmed();
      Serial.println("Wi-Fi update timed out; verified network retained and BLE remains available.");
    }
    if (ended_.exchange(false)) {
      active_ = false;
      // Wait for the end event before deinit; do not call manager APIs from its
      // callback. Free BTDM is irreversible during this boot, so a new window
      // is always a software reboot, with confirmed NVS preserved.
      network_prov_mgr_deinit();
      managerInitialized_ = false;
      stopping_ = false;
      configured_ = loadConfirmed();
      if (committed_ && configured_ && WiFi.status() == WL_CONNECTED) {
        // Keep the DHCP lease the PC just learned over BLE. No unnecessary
        // disconnect/reconnect (and possible IP change) on successful setup.
        esp_wifi_set_storage(WIFI_STORAGE_RAM);
        state_ = "provisioned";
        reconnectMs_ = now;
      } else {
        connectConfirmed();
      }
      Serial.println("BLE stopped. Camera operation may resume; p/BOOT opens a new window via restart.");
    }
    if (!candidateInProgress_ && configured_ && WiFi.status() != WL_CONNECTED && now - reconnectMs_ >= 10000) {
      reconnectMs_ = now;
      ++reconnectAttempts_;
      // No switching to AP and no deletion of verified configuration.
      WiFi.reconnect();
    }
  }

  // Normal operation is paused only while credentials are being applied, not
  // merely because the board is advertising or a phone is reading its identity.
  bool active() const { return active_ && (!configured_ || candidateInProgress_); }
  bool configured() const { return configured_.load(); }
  bool connected() const { return WiFi.status() == WL_CONNECTED; }
  const char *deviceId() const { return deviceId_; }
  const char *hostname() const { return hostname_; }
  const char *serviceName() const { return serviceName_; }
  const char *state() const { return state_.load(); }
  uint32_t reconnectAttempts() const { return reconnectAttempts_; }

 private:
  struct ConfirmedNetwork {
    uint32_t version;
    char ssid[33];
    char password[65];
  };
  static constexpr uint32_t RECORD_VERSION = 1;
  Preferences preferences_;
  ConfirmedNetwork confirmed_ = {};
  char deviceId_[32] = {};
  char hostname_[32] = {};
  char serviceName_[32] = {};
  const char *bootId_ = "";
  std::atomic<const char *> state_{"starting"};
  std::atomic<bool> configured_{false};
  bool active_ = false;
  bool stopping_ = false;
  bool managerInitialized_ = false;
  std::atomic<bool> committed_{false};
  std::atomic<bool> candidateInProgress_{false};
  std::atomic<bool> resetAfterSuccess_{false};
  std::atomic<bool> resetAfterFailure_{false};
  bool cancelled_ = false;
  bool buttonLatched_ = false;
  bool buttonRequestPending_ = false;
  std::atomic<bool> successPending_{false};
  std::atomic<bool> ended_{false};
  uint32_t windowStartedMs_ = 0;
  uint32_t buttonDownMs_ = 0;
  uint32_t reconnectMs_ = 0;
  uint32_t reconnectAttempts_ = 0;
  uint32_t successMs_ = 0;
  uint32_t candidateStartedMs_ = 0;

  bool loadConfirmed() {
    ConfirmedNetwork record = {};
    if (preferences_.getBytesLength("confirmed") != sizeof(record) ||
        preferences_.getBytes("confirmed", &record, sizeof(record)) != sizeof(record) ||
        record.version != RECORD_VERSION || record.ssid[32] != '\0' || record.password[64] != '\0' || !record.ssid[0]) return false;
    memcpy(&confirmed_, &record, sizeof(record));
    memset(&record, 0, sizeof(record));
    return true;
  }

  bool saveConfirmed() {
    wifi_config_t config = {};
    if (esp_wifi_get_config(WIFI_IF_STA, &config) != ESP_OK || !config.sta.ssid[0] || WiFi.status() != WL_CONNECTED) return false;
    ConfirmedNetwork candidate = {};
    candidate.version = RECORD_VERSION;
    memcpy(candidate.ssid, config.sta.ssid, sizeof(config.sta.ssid));
    memcpy(candidate.password, config.sta.password, sizeof(config.sta.password));
    // A single NVS blob is authoritative, not the SDK namespace: the manager
    // may persist candidate credentials BEFORE DHCP success. On failure,
    // cancellation or power loss, this verified record remains unchanged.
    const bool saved = preferences_.putBytes("confirmed", &candidate, sizeof(candidate)) == sizeof(candidate);
    if (saved) memcpy(&confirmed_, &candidate, sizeof(candidate));
    memset(&candidate, 0, sizeof(candidate));
    memset(&config, 0, sizeof(config));
    return saved;
  }

  void connectConfirmed() {
    WiFi.setAutoReconnect(false);
    // RAM-only normal operation, never adopt unverified SDK candidate NVS.
    esp_wifi_set_storage(WIFI_STORAGE_RAM);
    WiFi.disconnect(false, false);
    if (configured_) {
      WiFi.begin(confirmed_.ssid, confirmed_.password);
      state_ = committed_ ? "provisioned" : "station_reconnecting";
    } else {
      wifi_config_t empty = {};
      esp_wifi_set_config(WIFI_IF_STA, &empty);
      state_ = "unconfigured";
    }
    reconnectMs_ = millis();
  }

  void requestWindow() {
    Serial.println("Physical provisioning request accepted; restarting without deleting verified Wi-Fi.");
    Serial.flush();
    delay(100);
    ESP.restart();
  }

  bool startWindow() {
    network_prov_mgr_config_t config = {};
    config.scheme = network_prov_scheme_ble;
    network_prov_event_handler_t scheme = NETWORK_PROV_SCHEME_BLE_EVENT_HANDLER_FREE_BTDM;
    config.scheme_event_handler = scheme;
    config.app_event_handler.event_cb = provisioningEvent;
    config.app_event_handler.user_data = this;
    esp_err_t error = network_prov_mgr_init(config);
    if (error != ESP_OK) {
      state_ = "ble_init_error";
      Serial.printf("ERROR: BLE manager initialization failed: 0x%X\n", error);
      connectConfirmed();
      return false;
    }
    managerInitialized_ = true;
    // Same standard UUID as Espressif Arduino WiFiProv, explicitly fixed so
    // Windows clients can identify the service and its endpoint descriptors.
    static uint8_t serviceUuid[16] = {0xb4, 0xdf, 0x5a, 0x1c, 0x3f, 0x6b, 0xf4, 0xbf, 0xea, 0x4a, 0x82, 0x03, 0x04, 0x90, 0x1a, 0x02};
    error = network_prov_scheme_ble_set_service_uuid(serviceUuid);
    if (error == ESP_OK) error = network_prov_mgr_endpoint_create("dreamshield-info");
    const char *capabilities[] = {"device_identity", "confirmed_wifi_rollback", "phone_ble_setup", "no_pop"};
    if (error == ESP_OK) error = network_prov_mgr_set_app_info("dreamshield", "0.9.3", capabilities, 4);
    // Retain the authenticated info endpoint for 15 seconds after a verified
    // save, so a PC can learn IP/identity before BLE is shut down. The overall
    // window still has an independent five-minute deadline.
    if (error == ESP_OK) error = network_prov_mgr_disable_auto_stop(1000);
    if (error == ESP_OK) error = network_prov_mgr_start_provisioning(NETWORK_PROV_SECURITY_1, nullptr, serviceName_, nullptr);
    if (error == ESP_OK) error = network_prov_mgr_endpoint_register("dreamshield-info", infoEndpoint, this);
    if (error != ESP_OK) {
      state_ = "ble_start_error";
      Serial.printf("ERROR: secure BLE provisioning start failed: 0x%X\n", error);
      network_prov_mgr_deinit();
      managerInitialized_ = false;
      ended_ = false;
      connectConfirmed();
      return false;
    }
    active_ = true;
    state_ = "ble_provisioning";
    windowStartedMs_ = millis();
    Serial.printf("BLE READY | name=%s | Security=1 | Pairing=NO_CODE | availability=PERSISTENT | UUID=021a9004-0382-4aea-bff4-6b3f1c5adfb4\n", serviceName_);
    Serial.println("Saved Wi-Fi and camera may run alongside BLE; no pairing code or PIN is required.");
    return true;
  }

  void stopWindow() {
    if (!stopping_ && managerInitialized_) {
      stopping_ = true;
      network_prov_mgr_stop_provisioning();
    }
  }

  static void provisioningEvent(void *userData, network_prov_cb_event_t event, void *eventData) {
    auto *self = static_cast<DreamShieldProvisioning *>(userData);
    if (event == NETWORK_PROV_WIFI_CRED_RECV) {
      self->committed_ = false;
      self->candidateStartedMs_ = millis();
      self->candidateInProgress_ = true;
      self->state_ = "wifi_verifying";
      Serial.println("BLE credentials received (values omitted); verifying Wi-Fi/DHCP.");
    } else if (event == NETWORK_PROV_WIFI_CRED_SUCCESS) {
      self->resetAfterSuccess_ = true;
      self->resetAfterFailure_ = false;
      if (self->candidateInProgress_) self->successPending_ = true;
    } else if (event == NETWORK_PROV_WIFI_CRED_FAIL) {
      const auto reason = *static_cast<network_prov_wifi_sta_fail_reason_t *>(eventData);
      self->state_ = reason == NETWORK_PROV_WIFI_STA_AUTH_ERROR ? "wifi_auth_error" : "wifi_ap_not_found";
      self->resetAfterFailure_ = true;
      self->resetAfterSuccess_ = false;
      Serial.println("BLE Wi-Fi verification failed. Previous verified configuration is retained; retry or cancel.");
      // Do not auto-reset: the PC must be able to read the failure state.
      // Authenticated prov-ctrl reset can reset the SDK FSM for another try;
      // it never erases the independent ds-network/confirmed blob.
    } else if (event == NETWORK_PROV_END) {
      self->ended_ = true;
    }
  }

  static esp_err_t infoEndpoint(uint32_t, const uint8_t *input, ssize_t inputLen,
                                uint8_t **output, ssize_t *outputLen, void *userContext) {
    if (!input || inputLen <= 0 || inputLen > 128) return ESP_ERR_INVALID_ARG;
    cJSON *request = cJSON_ParseWithLength(reinterpret_cast<const char *>(input), inputLen);
    if (!request) return ESP_ERR_INVALID_ARG;
    const cJSON *action = cJSON_GetObjectItemCaseSensitive(request, "action");
    const bool prepare = cJSON_IsString(action) && strcmp(action->valuestring, "prepare_wifi") == 0;
    const bool valid = cJSON_IsString(action) && (strcmp(action->valuestring, "info") == 0 || prepare);
    cJSON_Delete(request);
    if (!valid) return ESP_ERR_INVALID_ARG;
    auto *self = static_cast<DreamShieldProvisioning *>(userContext);
    if (prepare) {
      esp_err_t result = ESP_OK;
      if (self->resetAfterSuccess_) result = network_prov_mgr_reset_wifi_sm_state_for_reprovision();
      else if (self->resetAfterFailure_) result = network_prov_mgr_reset_wifi_sm_state_on_failure();
      if (result != ESP_OK) return result;
      self->resetAfterSuccess_ = false;
      self->resetAfterFailure_ = false;
      self->committed_ = false;
      self->cancelled_ = false;
    }
    cJSON *response = cJSON_CreateObject();
    if (!response) return ESP_ERR_NO_MEM;
    cJSON_AddStringToObject(response, "device_id", self->deviceId_);
    cJSON_AddStringToObject(response, "boot_id", self->bootId_);
    cJSON_AddStringToObject(response, "model", "DreamShield RS081-dev");
    cJSON_AddStringToObject(response, "board", "freenove_esp32s3_n8r8");
    cJSON_AddStringToObject(response, "firmware_version", "0.9.3");
    cJSON_AddBoolToObject(response, "persistent_ble", true);
    cJSON_AddBoolToObject(response, "ble_available", self->active_);
    cJSON_AddBoolToObject(response, "wifi_update_prepared", prepare);
    cJSON_AddNumberToObject(response, "setup_protocol", 2);
    cJSON_AddStringToObject(response, "pairing_mode", "no_code");
    cJSON_AddStringToObject(response, "hostname", self->hostname_);
    cJSON_AddStringToObject(response, "ip_address", WiFi.localIP().toString().c_str());
    cJSON_AddStringToObject(response, "ssid", WiFi.status() == WL_CONNECTED ? WiFi.SSID().c_str() : "");
    cJSON_AddBoolToObject(response, "configured", self->configured_.load());
    cJSON_AddBoolToObject(response, "candidate_committed", self->committed_.load());
    cJSON_AddStringToObject(response, "provisioning_state", self->state());
    cJSON_AddBoolToObject(response, "wifi_connected", WiFi.status() == WL_CONNECTED);
    char *json = cJSON_PrintUnformatted(response);
    cJSON_Delete(response);
    if (!json) return ESP_ERR_NO_MEM;
    *output = reinterpret_cast<uint8_t *>(json);  // protocomm frees the buffer.
    *outputLen = strlen(json);
    return ESP_OK;
  }
};
