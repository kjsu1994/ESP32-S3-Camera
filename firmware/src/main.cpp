#include <Arduino.h>
#include <ESPmDNS.h>
#include <Preferences.h>
#include <SD_MMC.h>
#include <Update.h>
#include <atomic>
#include <initializer_list>
#include <map>
#include "cJSON.h"
#include <WiFi.h>
#include <Wire.h>
#include "driver/gpio.h"
#include "esp_camera.h"
#include "esp_rom_gpio.h"
#include "soc/gpio_sig_map.h"
#include "esp_http_server.h"
#include "esp_wifi.h"
#include "lwip/sockets.h"
#include "lwip/tcp.h"
#include "board_pins.h"
#include "sd_recorder.h"
#include "web_ui.h"

#if __has_include("config.local.h")
#include "config.local.h"
#else
#include "config.example.h"
#endif

#ifndef POWER_SDA_GPIO
#define POWER_SDA_GPIO -1
#endif
#ifndef POWER_SCL_GPIO
#define POWER_SCL_GPIO -1
#endif
#ifndef POWER_SHUNT_MILLIOHMS
#define POWER_SHUNT_MILLIOHMS 100
#endif

namespace {
constexpr char apName[] = "DockyCam";
constexpr char kFirmwareVersion[] = "0.3.6";
constexpr uint8_t lowBandwidth = 1 << 0;
constexpr uint8_t rotate180 = 1 << 1;
// Retain the v0.1.0 settings round-trip for installed clients.
constexpr uint8_t legacyDimOled = 1 << 2;
constexpr uint8_t resolutionMask = 3 << 3;
constexpr uint8_t nightMode = 1 << 5;
constexpr uint8_t ina219Address = 0x40;
static_assert(POWER_SHUNT_MILLIOHMS >= 1 && POWER_SHUNT_MILLIOHMS <= 1000,
              "Set the INA219 shunt resistance in milliohms");

enum class Resolution : uint8_t { Vga, Qvga, Svga, Hd };
constexpr const char* resolutionKeys[] = {"vga", "qvga", "svga", "hd"};
constexpr framesize_t frameSizes[] = {FRAMESIZE_VGA, FRAMESIZE_QVGA, FRAMESIZE_SVGA, FRAMESIZE_HD};

uint8_t resolutionIndex(uint8_t options) {
    const uint8_t index = (options & resolutionMask) >> 3;
    return index < sizeof(resolutionKeys) / sizeof(*resolutionKeys) ? index : 0;
}
constexpr int xclkHz = 14000000;
constexpr size_t maxJpegBytes = 256 * 1024;
constexpr size_t partHeaderCapacity = 96;

enum class State { Idle, Streaming };
enum class Reason { None, Camera, Disconnected, Stopped };
struct Status {
    State state;
    Reason reason;
};

portMUX_TYPE statusLock = portMUX_INITIALIZER_UNLOCKED;
State state = State::Idle;
Reason reason = Reason::None;
bool stopRequested = false;
uint8_t boardOptions = 0;
Preferences preferences;
Preferences netPrefs;
bool preferencesReady = false;
bool netPrefsReady = false;
bool powerBusReady = false;
bool staModeActive = false;
bool staEnabled = false;
String staSsid;
String staPass;
int activeWifiChannel = 6;
String cachedWifiScanJson = "{\"networks\":[]}";
volatile bool ledEnabled = true;
volatile bool torchEnabled = false;
volatile uint32_t rebootAtMs = 0;
std::atomic<int> activeTransfers{0};
sdrec::Manager sdManager;

void setLedRgb(uint8_t r, uint8_t g, uint8_t b) {
    static int lastR = -1, lastG = -1, lastB = -1;
    if (r == lastR && g == lastG && b == lastB) return;
    lastR = r; lastG = g; lastB = b;
    neopixelWrite(board::statusRgbLed, r, g, b);
}

#if CONFIG_SCCB_HARDWARE_I2C_PORT1
TwoWire& powerBus = Wire;
#else
TwoWire& powerBus = Wire1;
#endif

void setStatus(State nextState, Reason nextReason) {
    portENTER_CRITICAL(&statusLock);
    state = nextState;
    if (state == State::Streaming) stopRequested = false;
    reason = nextReason;
    portEXIT_CRITICAL(&statusLock);
}

Status getStatus() {
    portENTER_CRITICAL(&statusLock);
    const Status result{state, reason};
    portEXIT_CRITICAL(&statusLock);
    return result;
}

const char* stateName(State value) {
    switch (value) {
        case State::Idle: return "idle";
        case State::Streaming: return "streaming";
    }
    return "idle";
}

const char* reasonName(Reason value) {
    switch (value) {
        case Reason::None: return "none";
        case Reason::Camera: return "camera";
        case Reason::Disconnected: return "disconnected";
        case Reason::Stopped: return "stopped";
    }
    return "none";
}

void holdCameraClockLow() {
    pinMode(board::xclk, OUTPUT);
    digitalWrite(board::xclk, LOW);
    esp_rom_gpio_connect_out_signal(board::xclk, SIG_GPIO_OUT_IDX, false, false);
}

uint8_t getOptions() {
    portENTER_CRITICAL(&statusLock);
    const uint8_t options = boardOptions;
    portEXIT_CRITICAL(&statusLock);
    return options;
}

bool shouldStop() {
    portENTER_CRITICAL(&statusLock);
    const bool stop = stopRequested;
    portEXIT_CRITICAL(&statusLock);
    return stop;
}

void stopCamera();

void applyNightMode(sensor_t* sensor, bool enabled) {
    if (!sensor) return;
    if (sensor->set_exposure_ctrl) sensor->set_exposure_ctrl(sensor, 1);
    if (sensor->set_gain_ctrl) sensor->set_gain_ctrl(sensor, 1);
    if (enabled) {
        // OV5640 hardware Night Mode (auto frame-rate drop for longer shutter exposure) + high ISO + B&W.
        if (sensor->set_aec2) sensor->set_aec2(sensor, 1);
        if (sensor->set_gainceiling) sensor->set_gainceiling(sensor, GAINCEILING_128X);
        if (sensor->set_ae_level) {
            if (sensor->set_ae_level(sensor, 3) != 0) sensor->set_ae_level(sensor, 2);
        }
        if (sensor->set_reg) {
            // Raise OV5640 night-mode max exposure VTS (0x3A02/0x3A03 = 1600 lines) for low-light visibility.
            sensor->set_reg(sensor, 0x3A02, 0xFF, 0x06);
            sensor->set_reg(sensor, 0x3A03, 0xFF, 0x40);
        }
        if (sensor->set_special_effect) sensor->set_special_effect(sensor, 2); // Monochrome B&W
        if (sensor->set_brightness) sensor->set_brightness(sensor, 1);
        if (sensor->set_contrast) sensor->set_contrast(sensor, 1);
        if (sensor->set_denoise) sensor->set_denoise(sensor, 4);
    } else {
        if (sensor->set_aec2) sensor->set_aec2(sensor, 0);
        if (sensor->set_gainceiling) sensor->set_gainceiling(sensor, GAINCEILING_16X);
        if (sensor->set_ae_level) sensor->set_ae_level(sensor, 0);
        if (sensor->set_special_effect) sensor->set_special_effect(sensor, 0);
        if (sensor->set_brightness) sensor->set_brightness(sensor, 0);
        if (sensor->set_contrast) sensor->set_contrast(sensor, 0);
        if (sensor->set_denoise) sensor->set_denoise(sensor, 0);
    }
    Serial.printf("[CAM] Night B&W=%s\n", enabled ? "ON" : "OFF");
}

bool startCamera(uint8_t options) {
    if (!psramFound()) {
        Serial.println("[CAM] PSRAM unavailable");
        return false;
    }
    camera_config_t config{};
    config.ledc_channel = LEDC_CHANNEL_0;
    config.ledc_timer = LEDC_TIMER_0;
    config.pin_d0 = board::data[0];
    config.pin_d1 = board::data[1];
    config.pin_d2 = board::data[2];
    config.pin_d3 = board::data[3];
    config.pin_d4 = board::data[4];
    config.pin_d5 = board::data[5];
    config.pin_d6 = board::data[6];
    config.pin_d7 = board::data[7];
    config.pin_xclk = board::xclk;
    config.pin_pclk = board::pclk;
    config.pin_vsync = board::vsync;
    config.pin_href = board::href;
    config.pin_sccb_sda = board::sccbSda;
    config.pin_sccb_scl = board::sccbScl;
    config.pin_pwdn = board::pwdn;
    config.pin_reset = board::reset;
    config.xclk_freq_hz = xclkHz;
    config.pixel_format = PIXFORMAT_JPEG;
    const uint8_t resolution = resolutionIndex(options);
    config.frame_size = frameSizes[resolution];
    // In STA (router) mode, trim JPEG size slightly so multi-hop router RTT stays well below frame interval.
    config.jpeg_quality = (resolution == static_cast<uint8_t>(Resolution::Hd))
                              ? (staModeActive ? 20 : 12)
                              : (staModeActive ? 16 : 14);
    config.fb_count = 2;
    config.fb_location = CAMERA_FB_IN_PSRAM;
    config.grab_mode = CAMERA_GRAB_LATEST;

    const esp_err_t result = esp_camera_init(&config);
    if (result != ESP_OK) {
        Serial.printf("[CAM] Init failed: 0x%x\n", result);
        holdCameraClockLow();
        return false;
    }
    // Lower XCLK drive strength to reduce 2.4GHz RF harmonic coupling into the Wi-Fi antenna.
    gpio_set_drive_capability(static_cast<gpio_num_t>(board::xclk), GPIO_DRIVE_CAP_0);
    sensor_t* sensor = esp_camera_sensor_get();
    const unsigned pid = sensor ? sensor->id.PID : 0;
    Serial.printf("[CAM] PID=0x%04x PSRAM=%u\n", pid, ESP.getPsramSize());
    if (pid != board::ov5640Pid) {
        esp_camera_deinit();
        holdCameraClockLow();
        Serial.println("[CAM] Sensor does not match the OV5640 profile");
        return false;
    }
    if ((options & rotate180) &&
        (sensor->set_vflip(sensor, 1) != 0 || sensor->set_hmirror(sensor, 1) != 0)) {
        Serial.println("[CAM] Could not set image orientation");
        stopCamera();
        return false;
    }
    if (options & nightMode) {
        applyNightMode(sensor, true);
    }
    return true;
}

void stopCamera() {
    sensor_t* sensor = esp_camera_sensor_get();
    // The board profile has no PWDN pin. Request OV5640 standby while SCCB can still write.
    const int standby = sensor && sensor->set_reg ? sensor->set_reg(sensor, 0x3008, 0x40, 0x40) : -1;
    const esp_err_t result = esp_camera_deinit();
    // Arduino's bundled camera driver may leave the S3 clock routed to the pin.
    holdCameraClockLow();
    Serial.printf("[CAM] Standby=%d deinit=0x%x XCLK=low\n", standby, result);
}

esp_err_t sendStatus(httpd_req_t* request) {
    const Status current = getStatus();
    char body[112];
    snprintf(body, sizeof(body), "{\"state\":\"%s\",\"remaining_s\":0,\"reason\":\"%s\"}",
             stateName(current.state), reasonName(current.reason));
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    return httpd_resp_sendstr(request, body);
}

const char* jsonBool(bool value) {
    return value ? "true" : "false";
}

esp_err_t sendOptions(httpd_req_t* request) {
    const uint8_t options = getOptions();
    char body[144];
    snprintf(body, sizeof(body),
             "{\"low_bandwidth\":%s,\"rotate_180\":%s,\"dim_oled\":%s,\"resolution\":\"%s\",\"night_mode\":%s}",
             jsonBool(options & lowBandwidth), jsonBool(options & rotate180), jsonBool(options & legacyDimOled),
             resolutionKeys[resolutionIndex(options)], jsonBool(options & nightMode));
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    return httpd_resp_sendstr(request, body);
}

bool isJsonRequest(httpd_req_t* request) {
    char type[24];
    return httpd_req_get_hdr_value_str(request, "Content-Type", type, sizeof(type)) == ESP_OK &&
           strcmp(type, "application/json") == 0;
}

esp_err_t updateOptions(httpd_req_t* request) {
    if (!isJsonRequest(request)) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Expected application/json");
    }
    char body[160];
    const size_t size = request->content_len;
    if (size == 0 || size >= sizeof(body)) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid settings length");
    }
    size_t received = 0;
    while (received < size) {
        const int count = httpd_req_recv(request, body + received, size - received);
        if (count <= 0) return ESP_FAIL;
        received += count;
    }
    if (memchr(body, '\0', received)) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid settings body");
    }
    body[received] = '\0';

    cJSON* settings = cJSON_ParseWithOpts(body, nullptr, true);
    const cJSON* low = cJSON_GetObjectItemCaseSensitive(settings, "low_bandwidth");
    const cJSON* rotate = cJSON_GetObjectItemCaseSensitive(settings, "rotate_180");
    const cJSON* dim = cJSON_GetObjectItemCaseSensitive(settings, "dim_oled");
    const cJSON* resolution = cJSON_GetObjectItemCaseSensitive(settings, "resolution");
    const cJSON* night = cJSON_GetObjectItemCaseSensitive(settings, "night_mode");
    const uint8_t current = getOptions();
    int index = resolutionIndex(current);
    if (resolution) {
        index = -1;
        if (cJSON_IsString(resolution)) {
            for (uint8_t i = 0; i < sizeof(resolutionKeys) / sizeof(*resolutionKeys); ++i) {
                if (strcmp(resolution->valuestring, resolutionKeys[i]) == 0) index = i;
            }
        }
    }
    const bool valid = cJSON_IsObject(settings) && cJSON_IsBool(low) && cJSON_IsBool(rotate) &&
                       (!dim || cJSON_IsBool(dim)) && (!night || cJSON_IsBool(night)) && index >= 0;
    const uint8_t next = (cJSON_IsTrue(low) ? lowBandwidth : 0) |
                         (cJSON_IsTrue(rotate) ? rotate180 : 0) |
                         (dim ? (cJSON_IsTrue(dim) ? legacyDimOled : 0) : (current & legacyDimOled)) |
                         (index >= 0 ? index << 3 : 0) |
                         (night ? (cJSON_IsTrue(night) ? nightMode : 0) : (current & nightMode));
    cJSON_Delete(settings);
    if (!valid) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid board settings");
    }

    if (next != current) {
        if (!preferencesReady || preferences.putUChar("options", next) != sizeof(next)) {
            return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "Settings could not be saved");
        }
        portENTER_CRITICAL(&statusLock);
        boardOptions = next;
        portEXIT_CRITICAL(&statusLock);
    }
    return sendOptions(request);
}

esp_err_t requestStop(httpd_req_t* request) {
    if (!isJsonRequest(request) || request->content_len != 0) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Expected empty application/json request");
    }
    portENTER_CRITICAL(&statusLock);
    if (state == State::Streaming) stopRequested = true;
    portEXIT_CRITICAL(&statusLock);
    httpd_resp_set_status(request, "204 No Content");
    return httpd_resp_send(request, nullptr, 0);
}

bool writeStream(httpd_req_t* request, const char* bytes, size_t length) {
    while (length != 0) {
        const int sent = httpd_send(request, bytes, length);
        if (sent <= 0) return false;
        bytes += sent;
        length -= sent;
    }
    return true;
}

esp_err_t stream(httpd_req_t* request) {
    if (activeTransfers.load() > 0) {
        httpd_resp_set_status(request, "503 Service Unavailable");
        return httpd_resp_sendstr(request, "File transfer in progress");
    }
    char* packet = static_cast<char*>(ps_malloc(maxJpegBytes + partHeaderCapacity + 2));
    if (!packet) {
        httpd_resp_set_status(request, "503 Service Unavailable");
        return httpd_resp_sendstr(request, "Video buffer unavailable");
    }

    const uint32_t sessionStarted = millis();
    sdManager.pauseForLive();
    setStatus(State::Streaming, Reason::None);
    const uint8_t options = getOptions();
    bool appliedNight = (options & nightMode) != 0;
    const uint32_t frameIntervalMs = (options & lowBandwidth) ? 167 : 0;
    if (!startCamera(options)) {
        free(packet);
        setStatus(State::Idle, Reason::Camera);
        sdManager.resumeAfterLive();
        httpd_resp_set_status(request, "503 Service Unavailable");
        return httpd_resp_sendstr(request, "Camera initialization failed");
    }

    // Discard the first frame after init so AEC/AWB and DMA buffers settle cleanly.
    if (camera_fb_t* warmup = esp_camera_fb_get()) {
        esp_camera_fb_return(warmup);
    }

    const int sock = httpd_req_to_sockfd(request);
    const int enable = 1;
    const int keepIdle = 5;
    const int keepInterval = 2;
    const int keepCount = 2;
    setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &enable, sizeof(enable));
    setsockopt(sock, SOL_SOCKET, SO_KEEPALIVE, &enable, sizeof(enable));
    setsockopt(sock, IPPROTO_TCP, TCP_KEEPIDLE, &keepIdle, sizeof(keepIdle));
    setsockopt(sock, IPPROTO_TCP, TCP_KEEPINTVL, &keepInterval, sizeof(keepInterval));
    setsockopt(sock, IPPROTO_TCP, TCP_KEEPCNT, &keepCount, sizeof(keepCount));
    constexpr char response[] =
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n"
        "Access-Control-Allow-Origin: *\r\n"
        "Cache-Control: no-store\r\nConnection: close\r\n\r\n";
    bool sent = writeStream(request, response, sizeof(response) - 1);
    Reason ending = Reason::Disconnected;
    uint32_t sentFrames = 0;
    uint8_t consecutiveFailures = 0;
    uint64_t captureUs = 0;
    uint64_t sendUs = 0;
    uint32_t winStartMs = sessionStarted;
    uint32_t winFrames = 0;
    uint64_t winCapUs = 0;
    uint64_t winSendUs = 0;
    uint32_t winMaxSendUs = 0;
    uint64_t winBytes = 0;

    while (sent && !shouldStop()) {
        const bool wantNight = (getOptions() & nightMode) != 0;
        if (wantNight != appliedNight) {
            applyNightMode(esp_camera_sensor_get(), wantNight);
            appliedNight = wantNight;
        }
        const uint32_t started = millis();
        const uint32_t captureStarted = micros();
        camera_fb_t* frame = esp_camera_fb_get();
        const uint32_t captureElapsed = micros() - captureStarted;
        if (!frame) {
            if (++consecutiveFailures >= 5) {
                ending = Reason::Camera;
                break;
            }
            delay(10);
            continue;
        }
        if (shouldStop()) {
            esp_camera_fb_return(frame);
            break;
        }
        const size_t jpegLength = frame->len;
        if (jpegLength < 4 || jpegLength > maxJpegBytes) {
            esp_camera_fb_return(frame);
            if (++consecutiveFailures >= 5) {
                ending = Reason::Camera;
                break;
            }
            continue;
        }
        consecutiveFailures = 0;
        const int headerLength = snprintf(packet, partHeaderCapacity,
            "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n",
            static_cast<unsigned>(jpegLength));
        memcpy(packet + headerLength, frame->buf, jpegLength);
        // Release the driver buffer immediately so DMA captures into the pair buffer in parallel.
        esp_camera_fb_return(frame);
        sdManager.consumeLiveSnapshot(reinterpret_cast<const uint8_t*>(packet + headerLength), jpegLength);
        const size_t end = headerLength + jpegLength;
        packet[end] = '\r';
        packet[end + 1] = '\n';

        const uint32_t sendStarted = micros();
        sent = writeStream(request, packet, end + 2);
        const uint32_t sendElapsed = micros() - sendStarted;
        const bool linkLost = staModeActive ? (WiFi.status() != WL_CONNECTED) : (WiFi.softAPgetStationNum() == 0);
        if (!sent || linkLost) {
            ending = Reason::Disconnected;
            break;
        }
        ++sentFrames;
        captureUs += captureElapsed;
        sendUs += sendElapsed;
        ++winFrames;
        winCapUs += captureElapsed;
        winSendUs += sendElapsed;
        winBytes += jpegLength;
        if (sendElapsed > winMaxSendUs) winMaxSendUs = sendElapsed;
        const uint32_t winNow = millis();
        if (winNow - winStartMs >= 5000 && winFrames > 0) {
            const float winFps = winFrames * 1000.0f / (winNow - winStartMs);
            Serial.printf("[CAM] Live: fps=%.1f kb=%lu cap=%lums send=%lums max_send=%lums mode=%s ch=%d rssi=%d opts=0x%02x\n",
                          winFps,
                          static_cast<unsigned long>(winBytes / winFrames / 1024),
                          static_cast<unsigned long>(winCapUs / winFrames / 1000),
                          static_cast<unsigned long>(winSendUs / winFrames / 1000),
                          static_cast<unsigned long>(winMaxSendUs / 1000),
                          staModeActive ? "sta" : "ap",
                          staModeActive ? WiFi.channel() : activeWifiChannel,
                          staModeActive ? WiFi.RSSI() : 0,
                          static_cast<unsigned>(getOptions()));
            winStartMs = winNow;
            winFrames = 0;
            winCapUs = 0;
            winSendUs = 0;
            winMaxSendUs = 0;
            winBytes = 0;
        }
        if (frameIntervalMs > 0) {
            const uint32_t elapsed = millis() - started;
            if (elapsed < frameIntervalMs) delay(frameIntervalMs - elapsed);
        }
    }

    if (shouldStop()) ending = Reason::Stopped;
    stopCamera();
    free(packet);
    setStatus(State::Idle, ending);
    sdManager.resumeAfterLive();
    const uint32_t durationMs = millis() - sessionStarted;
    const float fps = durationMs > 0 ? (sentFrames * 1000.0f / durationMs) : 0.0f;
    Serial.printf("[CAM] Stream ended: %s frames=%lu duration_ms=%lu fps=%.1f\n",
                  reasonName(ending), static_cast<unsigned long>(sentFrames),
                  static_cast<unsigned long>(durationMs), fps);
    if (sentFrames) {
        Serial.printf("[CAM] Avg capture=%lu ms, TCP send=%lu ms\n",
                      static_cast<unsigned long>(captureUs / sentFrames / 1000),
                      static_cast<unsigned long>(sendUs / sentFrames / 1000));
    }
    if (sent) writeStream(request, "--frame--\r\n", sizeof("--frame--\r\n") - 1);
    // The raw response body has no Content-Length and ends when this socket closes.
    return ESP_FAIL;
}

httpd_handle_t startServer(uint16_t port, uint16_t controlPort, std::initializer_list<httpd_uri_t> routes) {
    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.server_port = port;
    config.ctrl_port = controlPort;
    config.send_wait_timeout = (port == 80) ? 12 : 6;
    config.recv_wait_timeout = (port == 80) ? 10 : 2;
    config.stack_size = 8192;
    config.core_id = 1;
    config.max_uri_handlers = 32;
    config.lru_purge_enable = true;
    config.max_open_sockets = 6;
    httpd_handle_t server = nullptr;

    if (httpd_start(&server, &config) != ESP_OK) {
        return nullptr;
    }
    for (const auto& route : routes) {
        if (httpd_register_uri_handler(server, &route) != ESP_OK) {
            httpd_stop(server);
            return nullptr;
        }
    }
    return server;
}

bool isPeripheralPin(int pin) {
    if (!GPIO_IS_VALID_OUTPUT_GPIO(pin) || (pin >= 26 && pin <= 37)) {
        return false;
    }
    const int reserved[] = {0, 3, 19, 20, 38, 43, 44, 45, 46, 48,
        board::xclk, board::sccbSda, board::sccbScl, board::vsync, board::href, board::pclk};
    for (const int used : reserved) {
        if (pin == used) return false;
    }
    for (const int used : board::data) {
        if (pin == used) return false;
    }
    return true;
}

void startPowerMonitor() {
    if (POWER_SDA_GPIO < 0 || POWER_SCL_GPIO < 0) return;
    if (POWER_SDA_GPIO == POWER_SCL_GPIO || !isPeripheralPin(POWER_SDA_GPIO) ||
        !isPeripheralPin(POWER_SCL_GPIO)) {
        Serial.println("[POWER] Pins conflict with board functions");
        return;
    }
    powerBusReady = powerBus.begin(POWER_SDA_GPIO, POWER_SCL_GPIO, 100000U);
}

bool readMeter(uint8_t reg, uint16_t& value) {
    powerBus.beginTransmission(ina219Address);
    powerBus.write(reg);
    if (powerBus.endTransmission(false) != 0 ||
        powerBus.requestFrom(ina219Address, static_cast<uint8_t>(2)) != 2) return false;
    const uint16_t high = powerBus.read();
    value = (high << 8) | powerBus.read();
    return true;
}

esp_err_t sendTelemetry(httpd_req_t* request) {
    char temperature[16] = "null";
    const float reading = temperatureRead();
    if (reading > -40 && reading < 125) snprintf(temperature, sizeof(temperature), "%.1f", reading);
    char power[48] = "null";
    uint16_t shunt, bus;
    if (powerBusReady && readMeter(0x02, bus) && (bus & 0x01) == 0 && readMeter(0x01, shunt)) {
        const int mv = (bus >> 3) * 4;
        const int microamps = static_cast<int16_t>(shunt) * 10000 / POWER_SHUNT_MILLIOHMS;
        const int mw = static_cast<int>(static_cast<int64_t>(mv) * microamps / 1000000);
        snprintf(power, sizeof(power), "%d", mw);
    }
    char body[90];
    snprintf(body, sizeof(body), "{\"chip_temp_c\":%s,\"power_mw\":%s}", temperature, power);
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    return httpd_resp_sendstr(request, body);
}

const char* recordModeName(sdrec::RecordMode mode) {
    switch (mode) {
        case sdrec::RecordMode::Blackbox: return "blackbox";
        case sdrec::RecordMode::Timelapse: return "timelapse";
        default: return "off";
    }
}

bool readJsonBody(httpd_req_t* request, char* out, size_t capacity) {
    if (!isJsonRequest(request)) return false;
    const size_t size = request->content_len;
    if (size == 0 || size >= capacity) return false;
    size_t received = 0;
    while (received < size) {
        const int count = httpd_req_recv(request, out + received, size - received);
        if (count <= 0) return false;
        received += count;
    }
    if (memchr(out, '\0', received)) return false;
    out[received] = '\0';
    return true;
}

esp_err_t syncBoardTime(httpd_req_t* request) {
    char body[96];
    if (!readJsonBody(request, body, sizeof(body))) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid time body");
    }
    cJSON* root = cJSON_ParseWithOpts(body, nullptr, true);
    const cJSON* epoch = cJSON_GetObjectItemCaseSensitive(root, "epoch");
    const bool ok = cJSON_IsObject(root) && cJSON_IsNumber(epoch);
    const int64_t epochVal = ok ? static_cast<int64_t>(epoch->valuedouble) : 0;
    cJSON_Delete(root);
    if (!ok) return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Missing epoch");
    sdManager.syncTime(epochVal);
    httpd_resp_set_status(request, "204 No Content");
    return httpd_resp_send(request, nullptr, 0);
}

esp_err_t sendSdStatus(httpd_req_t* request) {
    const sdrec::SdStatus s = sdManager.getStatus();
    cJSON* root = cJSON_CreateObject();
    cJSON_AddBoolToObject(root, "mounted", s.mounted);
    cJSON_AddNumberToObject(root, "total_mb", s.totalMb);
    cJSON_AddNumberToObject(root, "used_mb", s.usedMb);
    cJSON_AddNumberToObject(root, "free_mb", s.freeMb);
    cJSON_AddStringToObject(root, "mode", recordModeName(s.mode));
    cJSON_AddBoolToObject(root, "active", s.active);
    cJSON_AddBoolToObject(root, "paused_for_live", s.pausedForLive);
    cJSON_AddBoolToObject(root, "auto_start", s.autoStart);
    cJSON_AddNumberToObject(root, "segment_s", s.segmentSeconds);
    cJSON_AddNumberToObject(root, "interval_s", s.intervalSeconds);
    cJSON_AddNumberToObject(root, "frames", s.frames);
    cJSON_AddNumberToObject(root, "elapsed_s", s.elapsedSeconds);
    cJSON_AddStringToObject(root, "file", s.currentFile);
    cJSON_AddStringToObject(root, "error", s.lastError);
    char* json = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    if (!json) return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "OOM");
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    const esp_err_t res = httpd_resp_sendstr(request, json);
    cJSON_free(json);
    return res;
}

esp_err_t updateSdRecord(httpd_req_t* request) {
    char body[192];
    if (!readJsonBody(request, body, sizeof(body))) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid record request");
    }
    cJSON* root = cJSON_ParseWithOpts(body, nullptr, true);
    const cJSON* modeItem = cJSON_GetObjectItemCaseSensitive(root, "mode");
    const cJSON* segItem = cJSON_GetObjectItemCaseSensitive(root, "segment_s");
    const cJSON* intItem = cJSON_GetObjectItemCaseSensitive(root, "interval_s");
    const cJSON* autoItem = cJSON_GetObjectItemCaseSensitive(root, "auto_start");

    sdrec::RecordMode mode = sdrec::RecordMode::Off;
    bool validMode = false;
    if (cJSON_IsString(modeItem)) {
        if (strcmp(modeItem->valuestring, "off") == 0) { mode = sdrec::RecordMode::Off; validMode = true; }
        else if (strcmp(modeItem->valuestring, "blackbox") == 0) { mode = sdrec::RecordMode::Blackbox; validMode = true; }
        else if (strcmp(modeItem->valuestring, "timelapse") == 0) { mode = sdrec::RecordMode::Timelapse; validMode = true; }
    }
    const uint16_t segS = cJSON_IsNumber(segItem) ? static_cast<uint16_t>(segItem->valueint) : 0;
    const uint16_t intS = cJSON_IsNumber(intItem) ? static_cast<uint16_t>(intItem->valueint) : 0;
    const int autoOpt = cJSON_IsBool(autoItem) ? (cJSON_IsTrue(autoItem) ? 1 : 0) : -1;
    cJSON_Delete(root);
    if (!validMode) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid recording mode");
    }
    std::string err;
    if (!sdManager.configureRecording(mode, segS, intS, autoOpt, err)) {
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, err.c_str());
    }
    return sendSdStatus(request);
}

esp_err_t triggerSdSnapshot(httpd_req_t* request) {
    if (!isJsonRequest(request) || request->content_len != 0) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Expected empty application/json");
    }
    if (!sdManager.isMounted()) {
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "SD card unavailable");
    }
    sdManager.requestSnapshot();
    httpd_resp_set_status(request, "204 No Content");
    return httpd_resp_send(request, nullptr, 0);
}

int hexVal(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

void urlDecode(const char* in, char* out, size_t outSize) {
    if (!outSize) return;
    size_t w = 0;
    for (size_t r = 0; in[r] && w + 1 < outSize; ++r) {
        if (in[r] == '%' && in[r + 1] && in[r + 2]) {
            const int hi = hexVal(in[r + 1]);
            const int lo = hexVal(in[r + 2]);
            if (hi >= 0 && lo >= 0) {
                out[w++] = static_cast<char>((hi << 4) | lo);
                r += 2;
                continue;
            }
        } else if (in[r] == '+') {
            out[w++] = ' ';
            continue;
        }
        out[w++] = in[r];
    }
    out[w] = '\0';
}

esp_err_t sendSdFiles(httpd_req_t* request) {
    char query[320] = "";
    char cat[16] = "all";
    char rawDir[220] = "";
    char dir[128] = "";
    if (httpd_req_get_url_query_str(request, query, sizeof(query)) == ESP_OK) {
        httpd_query_key_value(query, "cat", cat, sizeof(cat));
        if (httpd_query_key_value(query, "dir", rawDir, sizeof(rawDir)) == ESP_OK) {
            urlDecode(rawDir, dir, sizeof(dir));
        }
    }
    if (!sdrec::Manager::isValidSubdir(dir)) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid dir");
    }
    std::vector<std::string> dirs;
    const auto files = sdManager.listFiles(cat, 120, dir, &dirs);
    cJSON* root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "dir", dir);
    cJSON* dArr = cJSON_AddArrayToObject(root, "dirs");
    for (const auto& d : dirs) {
        cJSON_AddItemToArray(dArr, cJSON_CreateString(d.c_str()));
    }
    cJSON* arr = cJSON_AddArrayToObject(root, "files");
    for (const auto& f : files) {
        cJSON* item = cJSON_CreateObject();
        cJSON_AddStringToObject(item, "cat", f.category.c_str());
        cJSON_AddStringToObject(item, "name", f.name.c_str());
        cJSON_AddNumberToObject(item, "size", static_cast<double>(f.size));
        cJSON_AddNumberToObject(item, "mtime", static_cast<double>(f.mtime));
        cJSON_AddItemToArray(arr, item);
    }
    char* json = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    if (!json) return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "OOM");
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    const esp_err_t res = httpd_resp_sendstr(request, json);
    cJSON_free(json);
    return res;
}

struct TransferGuard {
    bool active = false;
    TransferGuard() {
        const int prev = activeTransfers.fetch_add(1);
        active = true;
        if (prev == 0) {
            // Stop active live video stream so 100% of Wi-Fi & PSRAM goes to the file transfer.
            if (getStatus().state == State::Streaming) {
                portENTER_CRITICAL(&statusLock);
                stopRequested = true;
                portEXIT_CRITICAL(&statusLock);
                const uint32_t waitEnd = millis() + 750;
                while (getStatus().state == State::Streaming && millis() < waitEnd) {
                    delay(15);
                }
            }
            sdManager.pauseForLive();
        }
    }
    ~TransferGuard() {
        if (active && activeTransfers.fetch_sub(1) == 1) {
            sdManager.resumeAfterLive();
        }
    }
};

const char* mimeForFilename(const char* name) {
    const char* ext = strrchr(name, '.');
    if (!ext) return "application/octet-stream";
    if (strcasecmp(ext, ".jpg") == 0 || strcasecmp(ext, ".jpeg") == 0) return "image/jpeg";
    if (strcasecmp(ext, ".png") == 0) return "image/png";
    if (strcasecmp(ext, ".webp") == 0) return "image/webp";
    if (strcasecmp(ext, ".gif") == 0) return "image/gif";
    if (strcasecmp(ext, ".avi") == 0) return "video/x-msvideo";
    if (strcasecmp(ext, ".mp4") == 0) return "video/mp4";
    if (strcasecmp(ext, ".pdf") == 0) return "application/pdf";
    if (strcasecmp(ext, ".txt") == 0 || strcasecmp(ext, ".md") == 0 || strcasecmp(ext, ".csv") == 0) {
        return "text/plain; charset=utf-8";
    }
    if (strcasecmp(ext, ".json") == 0) return "application/json";
    if (strcasecmp(ext, ".zip") == 0) return "application/zip";
    return "application/octet-stream";
}

esp_err_t sendSdFile(httpd_req_t* request) {
    char query[512] = "";
    char cat[16] = "";
    char rawName[256] = "";
    char name[128] = "";
    char rawDir[220] = "";
    char dir[128] = "";
    if (httpd_req_get_url_query_str(request, query, sizeof(query)) != ESP_OK ||
        httpd_query_key_value(query, "cat", cat, sizeof(cat)) != ESP_OK ||
        httpd_query_key_value(query, "name", rawName, sizeof(rawName)) != ESP_OK) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid file parameter");
    }
    if (httpd_query_key_value(query, "dir", rawDir, sizeof(rawDir)) == ESP_OK) {
        urlDecode(rawDir, dir, sizeof(dir));
    }
    urlDecode(rawName, name, sizeof(name));
    if (!sdrec::Manager::isValidCategory(cat) || !sdrec::Manager::isValidSubdir(dir) ||
        !sdrec::Manager::isValidName(name)) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid file parameter");
    }
    // Pause live stream & SD recording so file download runs at full Wi-Fi/SD speed.
    TransferGuard xferGuard;

    const int sock = httpd_req_to_sockfd(request);
    const int nodelay = 1;
    setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));

    std::string path = sdrec::Manager::buildCategoryPath(cat, dir, name);
    if (!sdManager.lockSd(3000)) {
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "SD busy");
    }
    File file = SD_MMC.open(path.c_str(), FILE_READ);
    if (!file || file.isDirectory()) {
        if (file) file.close();
        sdManager.unlockSd();
        return httpd_resp_send_err(request, HTTPD_404_NOT_FOUND, "File not found");
    }
    const size_t totalSize = file.size();
    httpd_resp_set_type(request, mimeForFilename(name));
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    char lenBuf[24];
    snprintf(lenBuf, sizeof(lenBuf), "%u", static_cast<unsigned>(totalSize));
    httpd_resp_set_hdr(request, "X-File-Size", lenBuf);

    constexpr size_t kChunkSize = 16384;
    char* buf = static_cast<char*>(ps_malloc(kChunkSize));
    if (!buf) {
        file.close();
        sdManager.unlockSd();
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "Buffer unavailable");
    }
    esp_err_t res = ESP_OK;
    while (file.available()) {
        const size_t n = file.readBytes(buf, kChunkSize);
        if (n == 0) break;
        sdManager.unlockSd();
        res = httpd_resp_send_chunk(request, buf, n);
        if (res != ESP_OK) {
            if (sdManager.lockSd(2000)) {
                file.close();
                sdManager.unlockSd();
            }
            free(buf);
            return res;
        }
        if (!sdManager.lockSd(2000)) {
            free(buf);
            return ESP_FAIL;
        }
    }
    file.close();
    sdManager.unlockSd();
    free(buf);
    return httpd_resp_send_chunk(request, nullptr, 0);
}

esp_err_t uploadSdFile(httpd_req_t* request) {
    char query[512] = "";
    char rawName[256] = "";
    char decodedName[128] = "";
    char rawDir[220] = "";
    char dir[128] = "";
    if (httpd_req_get_url_query_str(request, query, sizeof(query)) != ESP_OK ||
        httpd_query_key_value(query, "name", rawName, sizeof(rawName)) != ESP_OK) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Missing filename");
    }
    if (httpd_query_key_value(query, "dir", rawDir, sizeof(rawDir)) == ESP_OK) {
        urlDecode(rawDir, dir, sizeof(dir));
    }
    urlDecode(rawName, decodedName, sizeof(decodedName));
    const std::string safeName = sdrec::Manager::sanitizeSharedName(decodedName);
    if (!sdrec::Manager::isValidSubdir(dir) || !sdrec::Manager::isValidName(safeName.c_str())) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid filename");
    }
    const size_t totalLen = request->content_len;
    if (totalLen == 0) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Empty file");
    }

    // Pause live video & background recording so upload uses 100% Wi-Fi and SD speed.
    TransferGuard xferGuard;

    const int sock = httpd_req_to_sockfd(request);
    const int nodelay = 1;
    setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));

    constexpr size_t kChunkSize = 16384;
    char* buf = static_cast<char*>(ps_malloc(kChunkSize));
    if (!buf) {
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "Buffer unavailable");
    }

    const std::string targetDir = sdrec::Manager::buildCategoryPath("shared", dir);
    const std::string path = sdrec::Manager::buildCategoryPath("shared", dir, safeName.c_str());
    if (!sdManager.lockSd(3000)) {
        free(buf);
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "SD busy");
    }
    const uint64_t totalBytes = SD_MMC.totalBytes();
    const uint64_t usedBytes = SD_MMC.usedBytes();
    const uint64_t freeBytes = totalBytes > usedBytes ? (totalBytes - usedBytes) : 0;
    if (freeBytes < static_cast<uint64_t>(totalLen) + 2ULL * 1024ULL * 1024ULL) {
        sdManager.unlockSd();
        free(buf);
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "SD card full");
    }
    if (!SD_MMC.exists("/dockycam")) SD_MMC.mkdir("/dockycam");
    if (!SD_MMC.exists("/dockycam/shared")) SD_MMC.mkdir("/dockycam/shared");
    if (dir[0] && !SD_MMC.exists(targetDir.c_str())) {
        sdManager.unlockSd();
        free(buf);
        return httpd_resp_send_err(request, HTTPD_404_NOT_FOUND, "Target folder missing");
    }
    File file = SD_MMC.open(path.c_str(), FILE_WRITE);
    if (!file) {
        sdManager.unlockSd();
        free(buf);
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "Could not open file");
    }
    sdManager.unlockSd();

    const uint32_t startedMs = millis();
    size_t remaining = totalLen;
    bool writeOk = true;
    while (remaining > 0) {
        const size_t want = remaining > kChunkSize ? kChunkSize : remaining;
        const int got = httpd_req_recv(request, buf, want);
        if (got <= 0) {
            if (got == HTTPD_SOCK_ERR_TIMEOUT) continue;
            writeOk = false;
            break;
        }
        if (!sdManager.lockSd(3000)) {
            writeOk = false;
            break;
        }
        const size_t wrote = file.write(reinterpret_cast<const uint8_t*>(buf), static_cast<size_t>(got));
        sdManager.unlockSd();
        if (wrote != static_cast<size_t>(got)) {
            writeOk = false;
            break;
        }
        remaining -= static_cast<size_t>(got);
    }

    if (sdManager.lockSd(3000)) {
        file.flush();
        file.close();
        if (!writeOk) {
            SD_MMC.remove(path.c_str());
        }
        sdManager.unlockSd();
    }
    free(buf);

    if (!writeOk) {
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "Upload interrupted");
    }
    const uint32_t elapsedMs = millis() - startedMs;
    Serial.printf("[SD] Uploaded shared/%s (%u bytes in %lu ms)\n",
                  safeName.c_str(), static_cast<unsigned>(totalLen), static_cast<unsigned long>(elapsedMs));

    cJSON* root = cJSON_CreateObject();
    cJSON_AddBoolToObject(root, "ok", true);
    cJSON_AddStringToObject(root, "cat", "shared");
    cJSON_AddStringToObject(root, "name", safeName.c_str());
    cJSON_AddNumberToObject(root, "size", static_cast<double>(totalLen));
    cJSON_AddNumberToObject(root, "ms", static_cast<double>(elapsedMs));
    char* json = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    if (!json) return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "OOM");
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    const esp_err_t res = httpd_resp_sendstr(request, json);
    cJSON_free(json);
    return res;
}

cJSON* loadNotesArrayLocked() {
    constexpr char kNotesPath[] = "/dockycam/notes.json";
    if (!SD_MMC.exists(kNotesPath)) return cJSON_CreateArray();
    File f = SD_MMC.open(kNotesPath, FILE_READ);
    if (!f) return cJSON_CreateArray();
    const size_t sz = f.size();
    if (sz == 0 || sz > 32768) {
        f.close();
        return cJSON_CreateArray();
    }
    char* raw = static_cast<char*>(ps_malloc(sz + 1));
    if (!raw) {
        f.close();
        return cJSON_CreateArray();
    }
    const size_t got = f.readBytes(raw, sz);
    f.close();
    raw[got] = '\0';
    cJSON* parsed = cJSON_Parse(raw);
    free(raw);
    if (!parsed) return cJSON_CreateArray();
    if (cJSON_IsArray(parsed)) return parsed;
    cJSON* inner = cJSON_DetachItemFromObjectCaseSensitive(parsed, "notes");
    cJSON_Delete(parsed);
    return (inner && cJSON_IsArray(inner)) ? inner : cJSON_CreateArray();
}

bool saveNotesArrayLocked(cJSON* notesArr) {
    if (!SD_MMC.exists("/dockycam")) SD_MMC.mkdir("/dockycam");
    cJSON* wrapper = cJSON_CreateObject();
    cJSON_AddItemReferenceToObject(wrapper, "notes", notesArr);
    char* json = cJSON_PrintUnformatted(wrapper);
    cJSON_Delete(wrapper);
    if (!json) return false;
    File f = SD_MMC.open("/dockycam/notes.json", FILE_WRITE);
    bool ok = false;
    if (f) {
        const size_t len = strlen(json);
        ok = (f.write(reinterpret_cast<const uint8_t*>(json), len) == len);
        f.flush();
        f.close();
    }
    cJSON_free(json);
    return ok;
}

esp_err_t sendNotesResponse(httpd_req_t* request, cJSON* notesArr) {
    cJSON* root = cJSON_CreateObject();
    cJSON_AddItemToObject(root, "notes", notesArr);
    char* json = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    if (!json) return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "OOM");
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    const esp_err_t res = httpd_resp_sendstr(request, json);
    cJSON_free(json);
    return res;
}

esp_err_t sendSdNotes(httpd_req_t* request) {
    if (!sdManager.lockSd(2000)) {
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "SD busy");
    }
    cJSON* arr = loadNotesArrayLocked();
    sdManager.unlockSd();
    return sendNotesResponse(request, arr);
}

esp_err_t updateSdNotes(httpd_req_t* request) {
    if (!isJsonRequest(request) || request->content_len == 0 || request->content_len > 6144) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid notes request");
    }
    char* body = static_cast<char*>(ps_malloc(request->content_len + 1));
    if (!body) return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "OOM");
    size_t received = 0;
    while (received < request->content_len) {
        const int n = httpd_req_recv(request, body + received, request->content_len - received);
        if (n <= 0) {
            free(body);
            return ESP_FAIL;
        }
        received += static_cast<size_t>(n);
    }
    body[received] = '\0';
    cJSON* req = cJSON_Parse(body);
    free(body);
    if (!cJSON_IsObject(req)) {
        cJSON_Delete(req);
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid JSON");
    }

    const cJSON* actItem = cJSON_GetObjectItemCaseSensitive(req, "action");
    const char* action = cJSON_IsString(actItem) ? actItem->valuestring : "";
    const cJSON* idItem = cJSON_GetObjectItemCaseSensitive(req, "id");
    const cJSON* titleItem = cJSON_GetObjectItemCaseSensitive(req, "title");
    const cJSON* textItem = cJSON_GetObjectItemCaseSensitive(req, "text");

    if (!sdManager.lockSd(3000)) {
        cJSON_Delete(req);
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "SD busy");
    }
    cJSON* arr = loadNotesArrayLocked();
    bool changed = false;

    if (strcmp(action, "add") == 0) {
        const char* titleStr = (cJSON_IsString(titleItem) && titleItem->valuestring) ? titleItem->valuestring : "";
        const char* textStr = (cJSON_IsString(textItem) && textItem->valuestring) ? textItem->valuestring : "";
        if (titleStr[0] || textStr[0]) {
            time_t nowSec = time(nullptr);
            const double ts = (nowSec > 1700000000) ? static_cast<double>(nowSec) : static_cast<double>(millis() / 1000);
            static uint32_t seq = 1;
            const double noteId = static_cast<double>(millis()) * 100.0 + static_cast<double>(seq++ % 100);
            cJSON* item = cJSON_CreateObject();
            cJSON_AddNumberToObject(item, "id", noteId);
            cJSON_AddStringToObject(item, "title", titleStr);
            cJSON_AddStringToObject(item, "text", textStr);
            cJSON_AddNumberToObject(item, "ts", ts);
            cJSON_InsertItemInArray(arr, 0, item);
            while (cJSON_GetArraySize(arr) > 30) {
                cJSON_DeleteItemFromArray(arr, cJSON_GetArraySize(arr) - 1);
            }
            changed = true;
        }
    } else if (strcmp(action, "update") == 0 && cJSON_IsNumber(idItem)) {
        const double targetId = idItem->valuedouble;
        const int sz = cJSON_GetArraySize(arr);
        for (int i = 0; i < sz; ++i) {
            cJSON* it = cJSON_GetArrayItem(arr, i);
            const cJSON* curId = cJSON_GetObjectItemCaseSensitive(it, "id");
            if (cJSON_IsNumber(curId) && fabs(curId->valuedouble - targetId) < 0.5) {
                if (cJSON_IsString(titleItem) && titleItem->valuestring) {
                    cJSON_ReplaceItemInObjectCaseSensitive(it, "title", cJSON_CreateString(titleItem->valuestring));
                }
                if (cJSON_IsString(textItem) && textItem->valuestring) {
                    cJSON_ReplaceItemInObjectCaseSensitive(it, "text", cJSON_CreateString(textItem->valuestring));
                }
                time_t nowSec = time(nullptr);
                if (nowSec > 1700000000) {
                    cJSON_ReplaceItemInObjectCaseSensitive(it, "ts", cJSON_CreateNumber(static_cast<double>(nowSec)));
                }
                changed = true;
                break;
            }
        }
    } else if (strcmp(action, "delete") == 0 && cJSON_IsNumber(idItem)) {
        const double targetId = idItem->valuedouble;
        const int sz = cJSON_GetArraySize(arr);
        for (int i = 0; i < sz; ++i) {
            const cJSON* it = cJSON_GetArrayItem(arr, i);
            const cJSON* curId = cJSON_GetObjectItemCaseSensitive(it, "id");
            if (cJSON_IsNumber(curId) && fabs(curId->valuedouble - targetId) < 0.5) {
                cJSON_DeleteItemFromArray(arr, i);
                changed = true;
                break;
            }
        }
    } else if (strcmp(action, "clear") == 0) {
        cJSON_Delete(arr);
        arr = cJSON_CreateArray();
        changed = true;
    }

    cJSON_Delete(req);
    bool saved = true;
    if (changed) {
        saved = saveNotesArrayLocked(arr);
    }
    sdManager.unlockSd();
    if (!saved) {
        cJSON_Delete(arr);
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "Could not save notes");
    }
    return sendNotesResponse(request, arr);
}

esp_err_t mkdirSd(httpd_req_t* request) {
    char body[320];
    if (!readJsonBody(request, body, sizeof(body))) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid mkdir request");
    }
    cJSON* root = cJSON_ParseWithOpts(body, nullptr, true);
    const cJSON* dirItem = cJSON_GetObjectItemCaseSensitive(root, "dir");
    const cJSON* nameItem = cJSON_GetObjectItemCaseSensitive(root, "name");
    std::string parent = (cJSON_IsString(dirItem) && dirItem->valuestring) ? dirItem->valuestring : "";
    std::string folder = (cJSON_IsString(nameItem) && nameItem->valuestring) ? nameItem->valuestring : "";
    cJSON_Delete(root);
    std::string created;
    if (folder.empty() || !sdManager.makeSharedDir(parent.c_str(), folder.c_str(), created)) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Could not create folder");
    }
    cJSON* resp = cJSON_CreateObject();
    cJSON_AddBoolToObject(resp, "ok", true);
    cJSON_AddStringToObject(resp, "name", created.c_str());
    char* json = cJSON_PrintUnformatted(resp);
    cJSON_Delete(resp);
    if (!json) return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "OOM");
    httpd_resp_set_type(request, "application/json");
    const esp_err_t res = httpd_resp_sendstr(request, json);
    cJSON_free(json);
    return res;
}

esp_err_t renameSd(httpd_req_t* request) {
    char body[512];
    if (!readJsonBody(request, body, sizeof(body))) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid rename request");
    }
    cJSON* root = cJSON_ParseWithOpts(body, nullptr, true);
    const cJSON* catItem = cJSON_GetObjectItemCaseSensitive(root, "cat");
    const cJSON* dirItem = cJSON_GetObjectItemCaseSensitive(root, "dir");
    const cJSON* fromItem = cJSON_GetObjectItemCaseSensitive(root, "from");
    const cJSON* toItem = cJSON_GetObjectItemCaseSensitive(root, "to");
    std::string cat = (cJSON_IsString(catItem) && catItem->valuestring) ? catItem->valuestring : "";
    std::string dir = (cJSON_IsString(dirItem) && dirItem->valuestring) ? dirItem->valuestring : "";
    std::string from = (cJSON_IsString(fromItem) && fromItem->valuestring) ? fromItem->valuestring : "";
    std::string to = (cJSON_IsString(toItem) && toItem->valuestring) ? toItem->valuestring : "";
    cJSON_Delete(root);
    std::string finalName;
    if (!sdManager.renameEntry(cat.c_str(), dir.c_str(), from.c_str(), to.c_str(), finalName)) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Rename failed");
    }
    cJSON* resp = cJSON_CreateObject();
    cJSON_AddBoolToObject(resp, "ok", true);
    cJSON_AddStringToObject(resp, "name", finalName.c_str());
    char* json = cJSON_PrintUnformatted(resp);
    cJSON_Delete(resp);
    if (!json) return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "OOM");
    httpd_resp_set_type(request, "application/json");
    const esp_err_t res = httpd_resp_sendstr(request, json);
    cJSON_free(json);
    return res;
}

esp_err_t uploadOtaFirmware(httpd_req_t* request) {
    const size_t totalLen = request->content_len;
    if (totalLen < 65536 || totalLen > 6 * 1024 * 1024) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid firmware size");
    }
    TransferGuard xferGuard;

    constexpr size_t kChunkSize = 16384;
    uint8_t* buf = static_cast<uint8_t*>(ps_malloc(kChunkSize));
    if (!buf) {
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "Buffer unavailable");
    }

    size_t remaining = totalLen;
    bool began = false;
    bool ok = true;
    while (remaining > 0) {
        const size_t want = remaining > kChunkSize ? kChunkSize : remaining;
        const int got = httpd_req_recv(request, reinterpret_cast<char*>(buf), want);
        if (got <= 0) {
            if (got == HTTPD_SOCK_ERR_TIMEOUT) continue;
            ok = false;
            break;
        }
        if (!began) {
            // Verify ESP32 firmware image magic byte 0xE9 before touching flash.
            if (buf[0] != 0xE9 || !Update.begin(totalLen, U_FLASH)) {
                ok = false;
                break;
            }
            began = true;
            Serial.printf("[OTA] Flashing %u bytes...\n", static_cast<unsigned>(totalLen));
        }
        if (Update.write(buf, static_cast<size_t>(got)) != static_cast<size_t>(got)) {
            ok = false;
            break;
        }
        remaining -= static_cast<size_t>(got);
    }
    free(buf);

    if (!ok || !began || !Update.end(true)) {
        if (began) Update.abort();
        Serial.printf("[OTA] Failed (err=%u)\n", static_cast<unsigned>(Update.getError()));
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "OTA update failed");
    }
    Serial.printf("[OTA] Success (%u bytes) -> rebooting\n", static_cast<unsigned>(totalLen));
    rebootAtMs = millis() + 700;
    char resp[96];
    snprintf(resp, sizeof(resp), "{\"ok\":true,\"size\":%u,\"rebooting\":true}", static_cast<unsigned>(totalLen));
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    return httpd_resp_sendstr(request, resp);
}

esp_err_t deleteSdFile(httpd_req_t* request) {
    if (!isJsonRequest(request) || request->content_len == 0 || request->content_len > 4096) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid delete request");
    }
    char* body = static_cast<char*>(ps_malloc(request->content_len + 1));
    if (!body) return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "OOM");
    size_t received = 0;
    while (received < request->content_len) {
        const int n = httpd_req_recv(request, body + received, request->content_len - received);
        if (n <= 0) {
            free(body);
            return ESP_FAIL;
        }
        received += static_cast<size_t>(n);
    }
    body[received] = '\0';
    cJSON* root = cJSON_Parse(body);
    free(body);
    if (!cJSON_IsObject(root)) {
        cJSON_Delete(root);
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid JSON");
    }

    const cJSON* itemsArr = cJSON_GetObjectItemCaseSensitive(root, "items");
    if (cJSON_IsArray(itemsArr)) {
        int deleted = 0;
        const int cnt = cJSON_GetArraySize(itemsArr);
        for (int i = 0; i < cnt; ++i) {
            const cJSON* it = cJSON_GetArrayItem(itemsArr, i);
            const cJSON* c = cJSON_GetObjectItemCaseSensitive(it, "cat");
            const cJSON* d = cJSON_GetObjectItemCaseSensitive(it, "dir");
            const cJSON* n = cJSON_GetObjectItemCaseSensitive(it, "name");
            const cJSON* isD = cJSON_GetObjectItemCaseSensitive(it, "is_dir");
            if (cJSON_IsString(c) && cJSON_IsString(n)) {
                const char* dirStr = (cJSON_IsString(d) && d->valuestring) ? d->valuestring : "";
                if (sdManager.deleteFile(c->valuestring, n->valuestring, dirStr, cJSON_IsTrue(isD))) {
                    ++deleted;
                }
            }
        }
        cJSON_Delete(root);
        char resp[48];
        snprintf(resp, sizeof(resp), "{\"deleted\":%d}", deleted);
        httpd_resp_set_type(request, "application/json");
        return httpd_resp_sendstr(request, resp);
    }

    const cJSON* catItem = cJSON_GetObjectItemCaseSensitive(root, "cat");
    const cJSON* dirItem = cJSON_GetObjectItemCaseSensitive(root, "dir");
    const cJSON* nameItem = cJSON_GetObjectItemCaseSensitive(root, "name");
    const cJSON* allItem = cJSON_GetObjectItemCaseSensitive(root, "all");
    const cJSON* isDirItem = cJSON_GetObjectItemCaseSensitive(root, "is_dir");
    const bool validCat = cJSON_IsString(catItem) && sdrec::Manager::isValidCategory(catItem->valuestring);
    const bool clearAll = cJSON_IsTrue(allItem);
    const bool isDir = cJSON_IsTrue(isDirItem);
    const bool validName = cJSON_IsString(nameItem) && sdrec::Manager::isValidName(nameItem->valuestring);
    std::string cat = validCat ? catItem->valuestring : "";
    std::string dir = (cJSON_IsString(dirItem) && dirItem->valuestring) ? dirItem->valuestring : "";
    std::string name = validName ? nameItem->valuestring : "";
    cJSON_Delete(root);
    if (!validCat || !sdrec::Manager::isValidSubdir(dir.c_str()) || (!clearAll && !validName)) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid delete target");
    }
    if (clearAll) {
        const int removed = sdManager.clearCategory(cat.c_str());
        char resp[48];
        snprintf(resp, sizeof(resp), "{\"deleted\":%d}", removed < 0 ? 0 : removed);
        httpd_resp_set_type(request, "application/json");
        return httpd_resp_sendstr(request, resp);
    }
    if (!sdManager.deleteFile(cat.c_str(), name.c_str(), dir.c_str(), isDir)) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "File could not be deleted");
    }
    httpd_resp_set_status(request, "204 No Content");
    return httpd_resp_send(request, nullptr, 0);
}

esp_err_t sendWebUi(httpd_req_t* request) {
    httpd_resp_set_type(request, "text/html; charset=utf-8");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    return httpd_resp_send(request, webui::kIndexHtml, HTTPD_RESP_USE_STRLEN);
}

esp_err_t sendWifiStatus(httpd_req_t* request) {
    cJSON* root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "mode", staModeActive ? "sta" : "ap");
    cJSON_AddStringToObject(root, "ssid", staModeActive ? staSsid.c_str() : apName);
    const String ipStr = staModeActive ? WiFi.localIP().toString() : WiFi.softAPIP().toString();
    cJSON_AddStringToObject(root, "ip", ipStr.c_str());
    cJSON_AddStringToObject(root, "host", "dockycam.local");
    cJSON_AddNumberToObject(root, "rssi", staModeActive ? WiFi.RSSI() : 0);
    cJSON_AddNumberToObject(root, "channel", staModeActive ? WiFi.channel() : activeWifiChannel);
    cJSON_AddBoolToObject(root, "sta_enabled", staEnabled);
    cJSON_AddStringToObject(root, "sta_ssid", staSsid.c_str());
    cJSON_AddBoolToObject(root, "led_enabled", ledEnabled);
    cJSON_AddBoolToObject(root, "torch_enabled", torchEnabled);
    cJSON_AddBoolToObject(root, "night_mode", (getOptions() & nightMode) != 0);
    cJSON_AddStringToObject(root, "fw_version", kFirmwareVersion);
    cJSON_AddNumberToObject(root, "uptime_s", static_cast<double>(millis() / 1000));
    const time_t nowSec = time(nullptr);
    cJSON_AddNumberToObject(root, "epoch", static_cast<double>(nowSec));
    cJSON_AddBoolToObject(root, "time_synced", nowSec > 1700000000);
    char* json = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    if (!json) return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "OOM");
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    const esp_err_t res = httpd_resp_sendstr(request, json);
    cJSON_free(json);
    return res;
}

size_t cacheWifiScanResults(int found) {
    struct ApInfo {
        String ssid;
        int32_t rssi;
        int32_t channel;
        bool isOpen;
    };
    std::map<String, ApInfo> bestBySsid;
    for (int i = 0; i < found; ++i) {
        String ssid = WiFi.SSID(i);
        if (ssid.isEmpty() || ssid == apName) continue;
        const int32_t rssi = WiFi.RSSI(i);
        const int32_t ch = WiFi.channel(i);
        const bool isOpen = (WiFi.encryptionType(i) == WIFI_AUTH_OPEN);
        auto it = bestBySsid.find(ssid);
        if (it == bestBySsid.end() || rssi > it->second.rssi) {
            bestBySsid[ssid] = {ssid, rssi, ch, isOpen};
        }
    }
    std::vector<ApInfo> sorted;
    sorted.reserve(bestBySsid.size());
    for (const auto& kv : bestBySsid) sorted.push_back(kv.second);
    std::sort(sorted.begin(), sorted.end(), [](const ApInfo& a, const ApInfo& b) {
        return a.rssi > b.rssi;
    });
    if (sorted.size() > 16) sorted.resize(16);

    cJSON* root = cJSON_CreateObject();
    cJSON* arr = cJSON_AddArrayToObject(root, "networks");
    for (const auto& ap : sorted) {
        cJSON* item = cJSON_CreateObject();
        cJSON_AddStringToObject(item, "ssid", ap.ssid.c_str());
        cJSON_AddNumberToObject(item, "rssi", ap.rssi);
        cJSON_AddNumberToObject(item, "ch", ap.channel);
        cJSON_AddBoolToObject(item, "open", ap.isOpen);
        cJSON_AddItemToArray(arr, item);
    }
    char* json = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    if (json) {
        cachedWifiScanJson = String(json);
        cJSON_free(json);
    }
    return sorted.size();
}

esp_err_t scanWifiNetworks(httpd_req_t* request) {
    // In SoftAP mode, switching WIFI_AP -> WIFI_AP_STA + off-channel scanning drops/stalls
    // the phone's HTTP socket, so serve the clean pre-SoftAP boot scan cache instantly.
    // In STA mode, WiFi is already WIFI_STA so a fast active scan (120ms/ch) works safely.
    if (staModeActive) {
        const int found = WiFi.scanNetworks(false, false, false, 120);
        if (found >= 0) {
            cacheWifiScanResults(found);
            WiFi.scanDelete();
        }
    }
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    return httpd_resp_sendstr(request, cachedWifiScanJson.c_str());
}

esp_err_t updateWifiConfig(httpd_req_t* request) {
    char body[256];
    if (!readJsonBody(request, body, sizeof(body))) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid wifi config");
    }
    cJSON* root = cJSON_ParseWithOpts(body, nullptr, true);
    const cJSON* enItem = cJSON_GetObjectItemCaseSensitive(root, "sta_enabled");
    const cJSON* ssidItem = cJSON_GetObjectItemCaseSensitive(root, "sta_ssid");
    const cJSON* passItem = cJSON_GetObjectItemCaseSensitive(root, "sta_pass");
    const cJSON* rebootItem = cJSON_GetObjectItemCaseSensitive(root, "reboot");

    const bool valid = cJSON_IsObject(root) && cJSON_IsBool(enItem);
    const bool nextEnabled = valid && cJSON_IsTrue(enItem);
    String nextSsid = (cJSON_IsString(ssidItem) && ssidItem->valuestring) ? String(ssidItem->valuestring) : staSsid;
    const bool hasPass = cJSON_IsString(passItem) && passItem->valuestring;
    String nextPass = hasPass ? String(passItem->valuestring) : staPass;
    const bool doReboot = !rebootItem || cJSON_IsTrue(rebootItem);
    cJSON_Delete(root);

    if (!valid || (nextEnabled && (nextSsid.isEmpty() || nextSsid.length() > 32 || nextPass.length() > 63))) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid SSID or password");
    }
    staEnabled = nextEnabled;
    if (nextEnabled || !nextSsid.isEmpty()) staSsid = nextSsid;
    if (hasPass) staPass = nextPass;
    if (netPrefsReady) {
        netPrefs.putBool("sta_en", staEnabled);
        netPrefs.putString("sta_ssid", staSsid);
        netPrefs.putString("sta_pass", staPass);
    }
    if (doReboot) rebootAtMs = millis() + 450;
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    return httpd_resp_sendstr(request, doReboot ? "{\"saved\":true,\"rebooting\":true}" : "{\"saved\":true,\"rebooting\":false}");
}

void updateStatusLed();

esp_err_t updateLedMode(httpd_req_t* request) {
    char body[64];
    if (!readJsonBody(request, body, sizeof(body))) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid LED request");
    }
    cJSON* root = cJSON_ParseWithOpts(body, nullptr, true);
    const cJSON* enItem = cJSON_GetObjectItemCaseSensitive(root, "enabled");
    const bool valid = cJSON_IsObject(root) && cJSON_IsBool(enItem);
    const bool next = valid && cJSON_IsTrue(enItem);
    cJSON_Delete(root);
    if (!valid) return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Missing enabled");
    ledEnabled = next;
    if (netPrefsReady) netPrefs.putBool("led_en", next);
    updateStatusLed();
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    return httpd_resp_sendstr(request, next ? "{\"led_enabled\":true}" : "{\"led_enabled\":false}");
}

esp_err_t updateTorchMode(httpd_req_t* request) {
    char body[64];
    if (!readJsonBody(request, body, sizeof(body))) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid torch request");
    }
    cJSON* root = cJSON_ParseWithOpts(body, nullptr, true);
    const cJSON* enItem = cJSON_GetObjectItemCaseSensitive(root, "enabled");
    const bool valid = cJSON_IsObject(root) && cJSON_IsBool(enItem);
    const bool next = valid && cJSON_IsTrue(enItem);
    cJSON_Delete(root);
    if (!valid) return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Missing enabled");
    torchEnabled = next;
    updateStatusLed();
    Serial.printf("[LED] White torch=%s\n", next ? "ON" : "OFF");
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    return httpd_resp_sendstr(request, next ? "{\"torch_enabled\":true}" : "{\"torch_enabled\":false}");
}

esp_err_t updateNightMode(httpd_req_t* request) {
    char body[64];
    if (!readJsonBody(request, body, sizeof(body))) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Invalid night request");
    }
    cJSON* root = cJSON_ParseWithOpts(body, nullptr, true);
    const cJSON* enItem = cJSON_GetObjectItemCaseSensitive(root, "enabled");
    const bool valid = cJSON_IsObject(root) && cJSON_IsBool(enItem);
    const bool next = valid && cJSON_IsTrue(enItem);
    cJSON_Delete(root);
    if (!valid) return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Missing enabled");
    const uint8_t current = getOptions();
    const uint8_t updated = next ? static_cast<uint8_t>(current | nightMode)
                                 : static_cast<uint8_t>(current & ~nightMode);
    if (updated != current) {
        if (!preferencesReady || preferences.putUChar("options", updated) != sizeof(updated)) {
            return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "Could not save night mode");
        }
        portENTER_CRITICAL(&statusLock);
        boardOptions = updated;
        portEXIT_CRITICAL(&statusLock);
    }
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    return httpd_resp_sendstr(request, next ? "{\"night_mode\":true}" : "{\"night_mode\":false}");
}

esp_err_t triggerReboot(httpd_req_t* request) {
    if (!isJsonRequest(request) || request->content_len != 0) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Expected empty application/json");
    }
    rebootAtMs = millis() + 350;
    httpd_resp_set_status(request, "204 No Content");
    return httpd_resp_send(request, nullptr, 0);
}

void updateStatusLed() {
    if (torchEnabled) {
        setLedRgb(255, 255, 255); // Full-bright pure white auxiliary illumination LED
        return;
    }
    if (!ledEnabled) {
        setLedRgb(0, 0, 0);
        return;
    }
    const bool blinkOn = ((millis() / 700) % 2) == 0;
    if (activeTransfers.load() > 0) {
        setLedRgb(0, blinkOn ? 14 : 4, blinkOn ? 18 : 6); // Soft pulsing cyan: cloud file transfer
        return;
    }
    const Status st = getStatus();
    if (st.state == State::Streaming) {
        setLedRgb(0, 8, 18); // Soft blue: live streaming
        return;
    }
    const sdrec::SdStatus sd = sdManager.getStatus();
    if (sd.lastError[0] != '\0') {
        const bool fastBlink = ((millis() / 250) % 2) == 0;
        setLedRgb(fastBlink ? 20 : 0, fastBlink ? 4 : 0, 0);
        return;
    }
    if (sd.active && sd.mode == sdrec::RecordMode::Blackbox) {
        setLedRgb(blinkOn ? 18 : 3, 0, 0); // Soft pulsing red: blackbox recording
        return;
    }
    if (sd.active && sd.mode == sdrec::RecordMode::Timelapse) {
        setLedRgb(blinkOn ? 16 : 3, blinkOn ? 10 : 2, 0); // Soft pulsing amber: timelapse
        return;
    }
    setLedRgb(0, 10, 4); // Gentle emerald: idle & ready
}

int selectQuietChannel() {
    WiFi.persistent(false);
    WiFi.setAutoReconnect(false);
    WiFi.mode(WIFI_STA);
    WiFi.disconnect(false, true);
    delay(50);
    const int apCount = WiFi.scanNetworks(false, true, false, 150);
    int chWeight[14] = {};
    for (int i = 0; i < apCount; ++i) {
        const int ch = WiFi.channel(i);
        if (ch >= 1 && ch <= 13) {
            int w = WiFi.RSSI(i) + 100;
            if (w < 5) w = 5;
            if (w > 60) w = 60;
            chWeight[ch] += w;
        }
    }
    if (apCount >= 0) {
        const size_t cached = cacheWifiScanResults(apCount);
        Serial.printf("[Wi-Fi] Cached %u nearby 2.4GHz APs\n", static_cast<unsigned>(cached));
    }
    WiFi.scanDelete();
    int bestChannel = 6;
    int bestScore = 1000000;
    int score6 = 0, score11 = 0, score1 = 0;
    for (const int candidate : {6, 11, 1}) {
        int score = 0;
        for (int c = 1; c <= 13; ++c) {
            const int distance = abs(c - candidate);
            if (distance == 0) score += chWeight[c] * 3;
            else if (distance == 1) score += chWeight[c] * 2;
            else if (distance == 2) score += chWeight[c];
        }
        if (candidate == 6) score6 = score;
        else if (candidate == 11) score11 = score;
        else if (candidate == 1) score1 = score;
        if (score < bestScore) {
            bestScore = score;
            bestChannel = candidate;
        }
    }
    Serial.printf("[Wi-Fi] Channel noise: ch6=%d ch11=%d ch1=%d -> picked ch%d\n",
                  score6, score11, score1, bestChannel);
    return bestChannel;
}

}

void setup() {
    const bool cpuConfigured = setCpuFrequencyMhz(160);
    Serial.begin(115200);
    setenv("TZ", "KST-9", 1);
    tzset();
    if (!cpuConfigured) Serial.println("[BOOT] CPU frequency unchanged");
    holdCameraClockLow();
    Serial.printf("[BOOT] CPU=%uMHz PSRAM=%u bytes\n", getCpuFrequencyMhz(), ESP.getPsramSize());
    pinMode(board::bootButton, INPUT_PULLUP);
    preferencesReady = preferences.begin("workbenchcam", false);
    if (preferencesReady) boardOptions = preferences.getUChar("options", 0);
    netPrefsReady = netPrefs.begin("wcam_net", false);
    if (netPrefsReady) {
        staEnabled = netPrefs.getBool("sta_en", false);
        staSsid = netPrefs.getString("sta_ssid", "");
        staPass = netPrefs.getString("sta_pass", "");
        ledEnabled = netPrefs.getBool("led_en", true);
    }
    if (!ledEnabled) setLedRgb(0, 0, 0);
    sdManager.begin(startCamera, stopCamera, getOptions);
    startPowerMonitor();
    constexpr size_t passwordLength = sizeof(CAMERA_AP_PASSWORD) - 1;
    if (passwordLength < 8 || passwordLength > 63) {
        Serial.println("[Wi-Fi] Set a unique 8–63 character password in config.local.h");
        return;
    }

    staModeActive = false;
    WiFi.persistent(false);
    WiFi.setAutoReconnect(false);
    if (staEnabled && !staSsid.isEmpty()) {
        Serial.printf("[Wi-Fi] Trying STA '%s' (12s timeout)...\n", staSsid.c_str());
        WiFi.mode(WIFI_STA);
        esp_wifi_set_bandwidth(WIFI_IF_STA, WIFI_BW_HT20);
        esp_wifi_set_ps(WIFI_PS_NONE);
        WiFi.setSleep(false);
        WiFi.setHostname("dockycam");
        WiFi.begin(staSsid.c_str(), staPass.c_str());
        const uint32_t deadline = millis() + 12000;
        while (WiFi.status() != WL_CONNECTED && millis() < deadline) {
            if (ledEnabled) {
                const bool b = ((millis() / 250) % 2) == 0;
                setLedRgb(0, b ? 12 : 2, b ? 16 : 3);
            }
            delay(100);
        }
        if (WiFi.status() == WL_CONNECTED) {
            staModeActive = true;
            WiFi.setAutoReconnect(true);
            activeWifiChannel = WiFi.channel();
            // Enforce HT20 + zero modem sleep AFTER association so STA never stalls on DTIM beacons.
            esp_wifi_set_bandwidth(WIFI_IF_STA, WIFI_BW_HT20);
            esp_wifi_set_ps(WIFI_PS_NONE);
            WiFi.setSleep(false);
            const int rssi = WiFi.RSSI();
            WiFi.setTxPower(WIFI_POWER_19_5dBm);
            wifi_ps_type_t psType = WIFI_PS_NONE;
            esp_wifi_get_ps(&psType);
            Serial.printf("[Wi-Fi] STA connected: %s at %s (ch=%d rssi=%d ps=%d)\n",
                          staSsid.c_str(), WiFi.localIP().toString().c_str(),
                          activeWifiChannel, rssi, static_cast<int>(psType));
            configTzTime("KST-9", "pool.ntp.org", "time.google.com", "kr.pool.ntp.org");
            Serial.println("[NTP] Syncing KST time...");
        } else {
            Serial.println("[Wi-Fi] STA timeout -> falling back to SoftAP");
            WiFi.setAutoReconnect(false);
            WiFi.disconnect(true, true);
            WiFi.mode(WIFI_OFF);
            delay(200);
        }
    }

    if (!staModeActive) {
        const IPAddress address(192, 168, 4, 1);
        WiFi.onEvent([](WiFiEvent_t) { Serial.println("[Wi-Fi] Station disconnected"); },
                     ARDUINO_EVENT_WIFI_AP_STADISCONNECTED);
        activeWifiChannel = selectQuietChannel();
        WiFi.mode(WIFI_AP);
        WiFi.setSleep(false);
        if (!WiFi.softAPConfig(address, address, IPAddress(255, 255, 255, 0)) ||
            !WiFi.softAP(apName, CAMERA_AP_PASSWORD, activeWifiChannel, false, 2)) {
            Serial.println("[Wi-Fi] SoftAP failed");
            return;
        }
        esp_wifi_set_bandwidth(WIFI_IF_AP, WIFI_BW_HT20);
        if (!WiFi.setTxPower(WIFI_POWER_17dBm)) Serial.println("[Wi-Fi] TX power unchanged");
        Serial.printf("[Wi-Fi] %s at %s (ch=%d)\n", apName, address.toString().c_str(), activeWifiChannel);
    }

    if (MDNS.begin("dockycam")) {
        MDNS.addService("http", "tcp", 80);
        MDNS.addServiceTxt("http", "tcp", "app", "dockycam");
        Serial.println("[mDNS] http://dockycam.local/");
    }

    const auto statusServer = startServer(80, 32768, {
        {"/", HTTP_GET, sendWebUi, nullptr},
        {"/status", HTTP_GET, sendStatus, nullptr},
        {"/telemetry", HTTP_GET, sendTelemetry, nullptr},
        {"/settings", HTTP_GET, sendOptions, nullptr},
        {"/settings", HTTP_POST, updateOptions, nullptr},
        {"/stop", HTTP_POST, requestStop, nullptr},
        {"/time", HTTP_POST, syncBoardTime, nullptr},
        {"/wifi/status", HTTP_GET, sendWifiStatus, nullptr},
        {"/wifi/scan", HTTP_GET, scanWifiNetworks, nullptr},
        {"/wifi/config", HTTP_POST, updateWifiConfig, nullptr},
        {"/led", HTTP_POST, updateLedMode, nullptr},
        {"/torch", HTTP_POST, updateTorchMode, nullptr},
        {"/night", HTTP_POST, updateNightMode, nullptr},
        {"/reboot", HTTP_POST, triggerReboot, nullptr},
        {"/sd/status", HTTP_GET, sendSdStatus, nullptr},
        {"/sd/record", HTTP_POST, updateSdRecord, nullptr},
        {"/sd/snapshot", HTTP_POST, triggerSdSnapshot, nullptr},
        {"/sd/files", HTTP_GET, sendSdFiles, nullptr},
        {"/sd/file", HTTP_GET, sendSdFile, nullptr},
        {"/sd/upload", HTTP_POST, uploadSdFile, nullptr},
        {"/sd/mkdir", HTTP_POST, mkdirSd, nullptr},
        {"/sd/rename", HTTP_POST, renameSd, nullptr},
        {"/sd/notes", HTTP_GET, sendSdNotes, nullptr},
        {"/sd/notes", HTTP_POST, updateSdNotes, nullptr},
        {"/sd/delete", HTTP_POST, deleteSdFile, nullptr},
        {"/ota/upload", HTTP_POST, uploadOtaFirmware, nullptr},
    });
    const auto streamServer = startServer(81, 32769, {{"/stream", HTTP_GET, stream, nullptr}});
    if (!statusServer || !streamServer) {
        if (statusServer) httpd_stop(statusServer);
        if (streamServer) httpd_stop(streamServer);
        if (!staModeActive) WiFi.softAPdisconnect(true);
        Serial.println("[Wi-Fi] HTTP server setup failed");
        return;
    }
    updateStatusLed();
}

void loop() {
    if (rebootAtMs != 0 && static_cast<int32_t>(millis() - rebootAtMs) >= 0) {
        Serial.println("[BOOT] Rebooting by request...");
        delay(50);
        ESP.restart();
    }

    static bool prevPressed = false;
    static uint32_t pressStartMs = 0;
    static bool longPressHandled = false;
    const bool pressed = (digitalRead(board::bootButton) == LOW);
    const uint32_t now = millis();
    if (pressed && !prevPressed) {
        pressStartMs = now;
        longPressHandled = false;
    } else if (pressed && !longPressHandled && (now - pressStartMs) >= 4500) {
        // 5-second hold resets STA config and reboots cleanly into DockyCam AP mode.
        longPressHandled = true;
        Serial.println("[Wi-Fi] BOOT held 5s -> resetting to SoftAP mode");
        if (netPrefsReady) netPrefs.putBool("sta_en", false);
        for (int i = 0; i < 3; ++i) {
            neopixelWrite(board::statusRgbLed, 18, 18, 18);
            delay(120);
            neopixelWrite(board::statusRgbLed, 0, 0, 0);
            delay(120);
        }
        ESP.restart();
    } else if (!pressed && prevPressed && !longPressHandled && (now - pressStartMs) >= 60) {
        // Short press captures instant SD snapshot.
        sdManager.requestSnapshot();
    }
    prevPressed = pressed;

    static uint32_t lastLedMs = 0;
    if (now - lastLedMs >= 250) {
        lastLedMs = now;
        updateStatusLed();
    }

    static uint32_t lastStaCheckMs = 0;
    if (staModeActive && (now - lastStaCheckMs) >= 10000) {
        lastStaCheckMs = now;
        if (WiFi.status() != WL_CONNECTED) {
            Serial.println("[Wi-Fi] STA lost, attempting reconnect...");
            WiFi.reconnect();
        }
    }
    delay(50);
}
