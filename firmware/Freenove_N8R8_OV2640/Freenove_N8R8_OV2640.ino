#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <ESPmDNS.h>
#include <esp_system.h>
#include "esp_camera.h"
#include "Sht31Telemetry.h"
#include "Sht31ConfigApi.h"
#include "DreamShieldNetwork.h"
#include "EdgeCameraCoordinator.h"
#include "DreamShieldIrEmission.h"

// DreamShield Freenove ESP32-S3-WROOM N8R8 control node with OV2640.
char HOSTNAME[32] = {};
const char *PROTOCOL_VERSION = "0.1";
const char *FIRMWARE_VERSION = "0.9.3";
char DEVICE_ID[32] = {};
char bootId[9] = "00000000";

constexpr uint8_t STATUS_LED_PIN = 2;
constexpr uint8_t RGB_LED_PIN = 48;

// Confirmed Freenove ESP32-S3-WROOM camera pin mapping.
constexpr int CAM_PIN_PWDN = -1;
constexpr int CAM_PIN_RESET = -1;
constexpr int CAM_PIN_XCLK = 15;
constexpr int CAM_PIN_SIOD = 4;
constexpr int CAM_PIN_SIOC = 5;
constexpr int CAM_PIN_D7 = 16;  // Y9
constexpr int CAM_PIN_D6 = 17;  // Y8
constexpr int CAM_PIN_D5 = 18;  // Y7
constexpr int CAM_PIN_D4 = 12;  // Y6
constexpr int CAM_PIN_D3 = 10;  // Y5
constexpr int CAM_PIN_D2 = 8;   // Y4
constexpr int CAM_PIN_D1 = 9;   // Y3
constexpr int CAM_PIN_D0 = 11;  // Y2
constexpr int CAM_PIN_VSYNC = 6;
constexpr int CAM_PIN_HREF = 7;
constexpr int CAM_PIN_PCLK = 13;

WebServer server(80);
Sht31Telemetry environmentSensor;
DreamShieldNetwork provisioning;
EdgeCameraCoordinator cameraCoordinator;
DreamShieldIrEmission irEmission;
bool cameraReady = false;
bool cameraAttempted = false;
bool networkStartupReady = false;
bool stationModeEnabled = false;
bool stationConnected = false;
bool mdnsStarted = false;
uint32_t captureCount = 0;
uint32_t captureFailures = 0;
uint32_t wifiReconnectAttempts = 0;
bool horizontalMirror = false;
bool verticalFlip = false;

const char INDEX_HTML[] PROGMEM = R"HTML(
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>DreamShield OV2640 Test</title>
  <style>
    body { margin: 0; font-family: sans-serif; background: #111; color: #eee; text-align: center; }
    main { max-width: 900px; margin: auto; padding: 18px; }
    .camera-wrap { position: relative; width: 100%; }
    img { display: block; width: 100%; height: auto; background: #222; border-radius: 10px; }
    .grid { position: absolute; inset: 0; pointer-events: none; border-radius: 10px;
      background-image:
        linear-gradient(to right, transparent 33.1%, rgba(255,255,255,.55) 33.3%, transparent 33.5%, transparent 66.4%, rgba(255,255,255,.55) 66.6%, transparent 66.8%),
        linear-gradient(to bottom, transparent 33.1%, rgba(255,255,255,.55) 33.3%, transparent 33.5%, transparent 66.4%, rgba(255,255,255,.55) 66.6%, transparent 66.8%); }
    .grid.hidden { display: none; }
    button { font-size: 16px; padding: 10px 18px; margin: 12px; }
    small { color: #aaa; }
  </style>
</head>
<body>
  <main>
    <h2>Freenove N8R8 + Camera Test</h2>
    <div class="camera-wrap">
      <img id="camera" src="/capture" alt="OV2640 capture">
      <div id="grid" class="grid"></div>
    </div>
    <div>
      <button onclick="refreshImage()">Capture Now</button>
      <button id="saveButton" onclick="saveImage()">Save Image</button>
      <button onclick="toggleGrid()">Toggle Grid</button>
      <button id="mirrorButton" onclick="toggleOrientation('hmirror')">Mirror: Off</button>
      <button id="flipButton" onclick="toggleOrientation('vflip')">Flip: Off</button>
    </div>
    <small>The page captures one hardware JPEG every two seconds. This is not a video stream.</small>
    <p><small id="status">Loading device status...</small></p>
    <p id="environment">SHT31: waiting for data...</p>
    <p><small>Framing target: keep the whole bed visible, with the chest/abdomen area and blanket edge away from the outer border.</small></p>
    <p><a href="/status" target="_blank" style="color:#7ec8ff">Open device status (JSON)</a></p>
  </main>
  <script>
    let horizontalMirror = false;
    let verticalFlip = false;
    let environmentDeadline = 0;

    function refreshImage() {
      document.getElementById('camera').src = '/capture?t=' + Date.now();
    }
    async function saveImage() {
      const button = document.getElementById('saveButton');
      button.disabled = true;
      button.textContent = 'Saving...';
      try {
        const response = await fetch('/api/v1/snapshot?t=' + Date.now(), {cache: 'no-store'});
        if (!response.ok) throw new Error('Snapshot request failed');
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        const stamp = new Date().toISOString().replace(/[:.]/g, '-');
        link.href = url;
        link.download = 'dreamshield-' + stamp + '.jpg';
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(url);
      } catch (error) {
        alert('Image save failed');
      } finally {
        button.disabled = false;
        button.textContent = 'Save Image';
      }
    }
    function toggleGrid() {
      document.getElementById('grid').classList.toggle('hidden');
    }
    function updateOrientationButtons() {
      document.getElementById('mirrorButton').textContent =
        'Mirror: ' + (horizontalMirror ? 'On' : 'Off');
      document.getElementById('flipButton').textContent =
        'Flip: ' + (verticalFlip ? 'On' : 'Off');
    }
    async function toggleOrientation(variable) {
      const current = variable === 'hmirror' ? horizontalMirror : verticalFlip;
      const response = await fetch('/control?var=' + variable + '&val=' + (current ? 0 : 1));
      if (!response.ok) {
        alert('Camera control failed');
        return;
      }
      const data = await response.json();
      horizontalMirror = data.horizontal_mirror;
      verticalFlip = data.vertical_flip;
      updateOrientationButtons();
      refreshImage();
    }
    async function refreshStatus() {
      const requestStarted = performance.now();
      try {
        const response = await fetch('/status?t=' + Date.now(), {cache: 'no-store'});
        if (!response.ok) throw new Error('Status request failed');
        const data = await response.json();
        horizontalMirror = data.horizontal_mirror;
        verticalFlip = data.vertical_flip;
        updateOrientationButtons();
        document.getElementById('status').textContent =
          'Uptime: ' + data.uptime_seconds + ' s | Captures: ' +
          data.capture_count + ' | Failures: ' + data.capture_failures +
          ' | Wi-Fi: ' + data.wifi_mode;
        const env = data.environment;
        const age = env?.sample_age_ms;
        environmentDeadline = requestStarted + 10000 - (age ?? 10000);
        const valid = env?.valid === true && env.status === 'fresh' &&
          Number.isFinite(age) && age >= 0 && performance.now() <= environmentDeadline &&
          Number.isFinite(env.ambient_temperature_c) && Number.isFinite(env.relative_humidity_percent);
        document.getElementById('environment').textContent = valid
          ? 'SHT31 | Temperature: ' + env.ambient_temperature_c.toFixed(2) +
            ' C | Humidity: ' + env.relative_humidity_percent.toFixed(2) + ' %RH | Sample #' + env.sample_sequence
          : 'SHT31: unavailable (' + (env?.last_error || env?.status || 'no telemetry') + ')';
        if (!valid) environmentDeadline = 0;
      } catch (error) {
        document.getElementById('status').textContent = 'Status request failed';
        environmentDeadline = 0;
        document.getElementById('environment').textContent = 'SHT31: device offline / data unavailable';
      }
    }
    setInterval(() => {
      if (environmentDeadline && performance.now() > environmentDeadline) {
        document.getElementById('environment').textContent = 'SHT31: stale / waiting for refresh';
        environmentDeadline = 0;
      }
    }, 500);
    setInterval(refreshImage, 2000);
    setInterval(refreshStatus, 5000);
    refreshStatus();
  </script>
</body>
</html>
)HTML";

void blinkStatus(uint8_t count, uint16_t onMs, uint16_t offMs) {
  for (uint8_t i = 0; i < count; ++i) {
    digitalWrite(STATUS_LED_PIN, HIGH);
    delay(onMs);
    digitalWrite(STATUS_LED_PIN, LOW);
    delay(offMs);
  }
}

void initializeProtocolIdentity() {
  snprintf(bootId, sizeof(bootId), "%08X", static_cast<unsigned>(esp_random()));
}

bool initializeCamera() {
  camera_config_t config = {};
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = CAM_PIN_D0;
  config.pin_d1 = CAM_PIN_D1;
  config.pin_d2 = CAM_PIN_D2;
  config.pin_d3 = CAM_PIN_D3;
  config.pin_d4 = CAM_PIN_D4;
  config.pin_d5 = CAM_PIN_D5;
  config.pin_d6 = CAM_PIN_D6;
  config.pin_d7 = CAM_PIN_D7;
  config.pin_xclk = CAM_PIN_XCLK;
  config.pin_pclk = CAM_PIN_PCLK;
  config.pin_vsync = CAM_PIN_VSYNC;
  config.pin_href = CAM_PIN_HREF;
  config.pin_sccb_sda = CAM_PIN_SIOD;
  config.pin_sccb_scl = CAM_PIN_SIOC;
  config.pin_pwdn = CAM_PIN_PWDN;
  config.pin_reset = CAM_PIN_RESET;
  config.xclk_freq_hz = 10000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.frame_size = FRAMESIZE_VGA; // 640 x 480
  config.jpeg_quality = 12;          // Lower value means higher JPEG quality.
  config.fb_count = psramFound() ? 2 : 1;
  config.fb_location = psramFound() ? CAMERA_FB_IN_PSRAM : CAMERA_FB_IN_DRAM;
  config.grab_mode = psramFound() ? CAMERA_GRAB_LATEST : CAMERA_GRAB_WHEN_EMPTY;

  const esp_err_t error = esp_camera_init(&config);
  if (error != ESP_OK) {
    Serial.printf("ERROR: esp_camera_init failed: 0x%X\n", error);
    return false;
  }

  sensor_t *sensor = esp_camera_sensor_get();
  if (sensor != nullptr) {
    camera_sensor_info_t *sensorInfo = esp_camera_sensor_get_info(&sensor->id);
    Serial.printf("Sensor PID : 0x%04X\n", sensor->id.PID);
    Serial.printf("Sensor name: %s\n",
                  sensorInfo != nullptr ? sensorInfo->name : "Unknown");
    Serial.printf("HW JPEG    : %s\n",
                  sensorInfo != nullptr && sensorInfo->support_jpeg ? "YES" : "NO");
  }

  camera_fb_t *frame = esp_camera_fb_get();
  if (frame == nullptr) {
    Serial.println("ERROR: first camera capture failed");
    return false;
  }

  if (frame->format != PIXFORMAT_JPEG) {
    Serial.printf("ERROR: expected JPEG frame, received format %d\n", frame->format);
    esp_camera_fb_return(frame);
    return false;
  }

  Serial.printf("First hardware JPEG: %u bytes, %ux%u\n",
                static_cast<unsigned>(frame->len),
                frame->width,
                frame->height);
  esp_camera_fb_return(frame);
  return true;
}

void startMdnsService() {
  if (!stationConnected) {
    return;
  }

  if (mdnsStarted) {
    MDNS.end();
    mdnsStarted = false;
  }

  if (MDNS.begin(HOSTNAME)) {
    MDNS.addService("http", "tcp", 80);
    mdnsStarted = true;
    Serial.printf("Local hostname (if supported): http://%s.local/\n", HOSTNAME);
  } else {
    Serial.println("mDNS startup failed; use the numeric IP address");
  }
}

void maintainNetwork() {
  provisioning.poll();
  stationModeEnabled = provisioning.configured();
  wifiReconnectAttempts = provisioning.reconnectAttempts();
  if (provisioning.connected() && !provisioning.active()) {
    if (!stationConnected) {
      stationConnected = true;
      Serial.println("Wi-Fi reconnected");
      Serial.printf("New IP address: %s\n", WiFi.localIP().toString().c_str());
      Serial.printf("Signal RSSI  : %d dBm\n", WiFi.RSSI());
      startMdnsService();
    }
    return;
  }

  if (stationConnected) {
    stationConnected = false;
    if (mdnsStarted) {
      MDNS.end();
      mdnsStarted = false;
    }
    Serial.println("Wi-Fi connection lost; automatic reconnect is active");
  }

}

void handleRoot() {
  server.send_P(200, "text/html; charset=utf-8", INDEX_HTML);
}

void handleStatus() {
  String sensorName = "Unknown";
  bool hardwareJpeg = false;
  sensor_t *sensor = esp_camera_sensor_get();
  if (sensor != nullptr) {
    camera_sensor_info_t *sensorInfo = esp_camera_sensor_get_info(&sensor->id);
    if (sensorInfo != nullptr) {
      sensorName = sensorInfo->name;
      hardwareJpeg = sensorInfo->support_jpeg;
    }
  }

  const IPAddress address = WiFi.localIP();

  String json;
  json.reserve(3000);
  json += "{";
  json += "\"protocol_version\":\"" + String(PROTOCOL_VERSION) + "\",";
  json += "\"firmware_version\":\"" + String(FIRMWARE_VERSION) + "\",";
  json += "\"device_id\":\"" + String(DEVICE_ID) + "\",";
  json += "\"boot_id\":\"" + String(bootId) + "\",";
  json += "\"uptime_seconds\":" + String(millis() / 1000UL) + ",";
  json += "\"sensor\":\"" + sensorName + "\",";
  json += "\"hardware_jpeg\":";
  json += hardwareJpeg ? "true" : "false";
  json += ",\"camera_ready\":";
  json += cameraReady ? "true" : "false";
  json += ",\"resolution\":\"640x480\",";
  json += "\"capture_count\":" + String(captureCount) + ",";
  json += "\"capture_failures\":" + String(captureFailures) + ",";
  json += "\"horizontal_mirror\":";
  json += horizontalMirror ? "true" : "false";
  json += ",\"vertical_flip\":";
  json += verticalFlip ? "true" : "false";
  json += ",";
  json += "\"free_heap_bytes\":" + String(ESP.getFreeHeap()) + ",";
  json += "\"free_psram_bytes\":" + String(ESP.getFreePsram()) + ",";
  json += "\"wifi_mode\":\"";
  if (provisioning.active()) {
    json += "ble_provisioning";
  } else if (!stationModeEnabled) {
    json += "unconfigured";
  } else if (stationConnected) {
    json += "station_connected";
  } else {
    json += "station_reconnecting";
  }
  json += "\",\"wifi_connected\":";
  json += provisioning.connected() ? "true" : "false";
  json += ",\"wifi_reconnect_attempts\":" + String(wifiReconnectAttempts);
  json += ",\"rssi_dbm\":";
  if (provisioning.connected()) {
    json += String(WiFi.RSSI());
  } else {
    json += "null";
  }
  json += ",\"local_ip\":\"" + address.toString() + "\"";
  json += ",\"hostname\":\"" + String(HOSTNAME) + "\"";
  json += ",\"model\":\"DreamShield RS081-dev\"";
  json += ",\"network_configuration_source\":\"" + String(provisioning.configurationSource()) + "\"";
  json += ",\"environment\":" + environmentSensor.json();
  json += ",\"ac_control\":" + irEmission.acJson();
  json += "}";

  server.sendHeader("Cache-Control", "no-store");
  server.send(200, "application/json; charset=utf-8", json);
}

void handleControl() {
  if (!cameraReady || provisioning.active()) {
    server.send(503, "text/plain; charset=utf-8", "Camera paused or unavailable");
    return;
  }
  if (!server.hasArg("var") || !server.hasArg("val")) {
    server.send(400, "text/plain; charset=utf-8", "Missing var or val");
    return;
  }

  const String variable = server.arg("var");
  const int value = server.arg("val").toInt();
  if (value != 0 && value != 1) {
    server.send(400, "text/plain; charset=utf-8", "Value must be 0 or 1");
    return;
  }

  if (!cameraCoordinator.lock(10)) {
    server.send(503, "text/plain; charset=utf-8", "Camera busy; retry control");
    return;
  }

  sensor_t *sensor = esp_camera_sensor_get();
  if (sensor == nullptr) {
    cameraCoordinator.unlock();
    server.send(503, "text/plain; charset=utf-8", "Camera sensor unavailable");
    return;
  }

  int result = -1;
  if (variable == "hmirror") {
    result = sensor->set_hmirror(sensor, value);
    if (result == 0) {
      horizontalMirror = value != 0;
    }
  } else if (variable == "vflip") {
    result = sensor->set_vflip(sensor, value);
    if (result == 0) {
      verticalFlip = value != 0;
    }
  } else {
    cameraCoordinator.unlock();
    server.send(400, "text/plain; charset=utf-8", "Unsupported camera control");
    return;
  }

  if (result != 0) {
    cameraCoordinator.unlock();
    server.send(500, "text/plain; charset=utf-8", "Camera rejected the control");
    return;
  }
  cameraCoordinator.orientationChanged();
  cameraCoordinator.unlock();

  String json = "{\"horizontal_mirror\":";
  json += horizontalMirror ? "true" : "false";
  json += ",\"vertical_flip\":";
  json += verticalFlip ? "true" : "false";
  json += "}";
  server.sendHeader("Cache-Control", "no-store");
  server.send(200, "application/json; charset=utf-8", json);
}

void handleCapture() {
  if (!cameraReady || provisioning.active()) {
    server.send(503, "text/plain; charset=utf-8", "Camera paused for network setup or unavailable");
    return;
  }
  if (!cameraCoordinator.lock(10)) {
    server.send(503, "text/plain; charset=utf-8", "Camera busy; retry snapshot");
    return;
  }
  camera_fb_t *frame = esp_camera_fb_get();
  if (frame == nullptr) {
    cameraCoordinator.unlock();
    ++captureFailures;
    server.send(503, "text/plain; charset=utf-8", "Camera capture failed");
    return;
  }

  if (frame->format != PIXFORMAT_JPEG) {
    ++captureFailures;
    esp_camera_fb_return(frame);
    cameraCoordinator.unlock();
    server.send(500, "text/plain; charset=utf-8", "Camera did not return hardware JPEG");
    return;
  }

  ++captureCount;

  WiFiClient client = server.client();
  client.printf(
      "HTTP/1.1 200 OK\r\n"
      "Content-Type: image/jpeg\r\n"
      "Content-Length: %u\r\n"
      "X-DreamShield-Protocol: %s\r\n"
      "X-Device-Id: %s\r\n"
      "X-Boot-Id: %s\r\n"
      "X-Frame-Sequence: %u\r\n"
      "X-Uptime-Ms: %u\r\n"
      "Cache-Control: no-store\r\n"
      "Connection: close\r\n\r\n",
      static_cast<unsigned>(frame->len),
      PROTOCOL_VERSION,
      DEVICE_ID,
      bootId,
      static_cast<unsigned>(captureCount),
      static_cast<unsigned>(millis()));
  client.write(frame->buf, frame->len);
  esp_camera_fb_return(frame);
  cameraCoordinator.unlock();
}

void setup() {
  // GPIO48 drives the onboard addressable RGB LED. Turn it fully off so that
  // visible light does not contaminate nighttime camera observations.
  rgbLedWrite(RGB_LED_PIN, 0, 0, 0);

  pinMode(STATUS_LED_PIN, OUTPUT);
  digitalWrite(STATUS_LED_PIN, LOW);
  // No startup emission. Only the explicit bounded LAN API can request TX;
  // the edge observer itself still has no actuator authority.
  pinMode(21, OUTPUT);
  digitalWrite(21, LOW);

  Serial.begin(115200);
  delay(1500);
  initializeProtocolIdentity();
  irEmission.begin(bootId);
  Serial.println();
  Serial.println("========================================");
  Serial.println("DreamShield Freenove N8R8 control node");
  Serial.println("========================================");
  Serial.printf("Chip       : %s\n", ESP.getChipModel());
  Serial.printf("Flash      : %u MB\n",
                static_cast<unsigned>(ESP.getFlashChipSize() / 1024 / 1024));
  Serial.printf("PSRAM      : %u MB\n",
                static_cast<unsigned>(ESP.getPsramSize() / 1024 / 1024));
  Serial.printf("Boot ID    : %s\n", bootId);
  Serial.printf("Protocol   : v%s\n", PROTOCOL_VERSION);

  // BLE is discoverable on every boot. A configured board can initialize its
  // PSRAM camera while BLE remains available; Wi-Fi updates still pause use.
  environmentSensor.begin();
  networkStartupReady = provisioning.begin(bootId);
  strlcpy(DEVICE_ID, provisioning.deviceId(), sizeof(DEVICE_ID));
  irEmission.setDeviceId(DEVICE_ID);
  strlcpy(HOSTNAME, provisioning.hostname(), sizeof(HOSTNAME));
  stationModeEnabled = provisioning.configured();
  if (!cameraCoordinator.begin()) {
    Serial.println("ERROR: camera coordinator allocation failed; camera stays unavailable");
    cameraAttempted = true;
  }

  server.on("/", HTTP_GET, handleRoot);
  server.on("/capture", HTTP_GET, handleCapture);
  server.on("/status", HTTP_GET, handleStatus);
  server.on("/control", HTTP_GET, handleControl);
  server.on("/api/v1/status", HTTP_GET, handleStatus);
  server.on("/api/v1/snapshot", HTTP_GET, handleCapture);
  const char *irHeaders[] = {"Origin", "Content-Type", "X-DreamShield-IR-Token"};
  server.collectHeaders(irHeaders, 3);
  server.on("/api/v1/environment/config", HTTP_ANY, []() {
    sht31_config::handle(server, environmentSensor, DEVICE_ID, bootId, provisioning.active());
  });
  server.on("/api/v1/ac/command", HTTP_POST, []() {
    irEmission.handleAc(server, provisioning.active());
  });
  server.onNotFound([]() {
    server.send(404, "text/plain", "Not found");
  });
  // Invalid header settings can return before STA/lwIP initialization. Do not
  // create sockets in that state; camera and SHT31 still run independently.
  if (networkStartupReady) server.begin();
  else Serial.println("HTTP server not started: network initialization failed; camera/SHT31 remain available offline.");

#if DREAMSHIELD_USE_BLE_PROVISIONING
  Serial.println("Phone BLE discovery stays enabled across normal operation and every boot.");
#else
  Serial.println("Camera can initialize without Wi-Fi; using secrets.h (BLE is not started).");
#endif
  Serial.println("Edge observer is shadow-only: never gates VLM and never executes an appliance.");
  Serial.println("HTTP camera/status API is reachable after Wi-Fi connects. No default AP is created.");
}

void loop() {
  environmentSensor.poll();
  maintainNetwork();
  if (!provisioning.active() && !cameraAttempted) {
    cameraAttempted = true;
    cameraReady = initializeCamera();
    if (cameraReady) {
      Serial.println("Camera initialization: PASS");
    } else {
      Serial.println("Camera unavailable; SHT31 and network status remain available.");
#if DREAMSHIELD_USE_BLE_PROVISIONING
      Serial.println("Hold BOOT for 3 seconds after startup to reopen phone BLE setup.");
#else
      Serial.println("Header Wi-Fi mode: fix camera wiring separately; network settings require local secrets.h edit and upload.");
#endif
    }
    if (stationConnected) {
      Serial.printf("Open http://%s/ | Device ID=%s\n", WiFi.localIP().toString().c_str(), DEVICE_ID);
    } else {
      Serial.println("Camera initialized offline; LAN monitoring awaits Wi-Fi connection.");
    }
  }
  // Status remains available on the LAN during the post-connect BLE interval;
  // snapshots explicitly return 503 until the camera can resume.
  if (networkStartupReady) server.handleClient();
  digitalWrite(STATUS_LED_PIN, provisioning.active() ? (millis() / 500) % 2 : (!cameraReady && cameraAttempted ? (millis() / 150) % 2 : LOW));
  delay(2);
}
