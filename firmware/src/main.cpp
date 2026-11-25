#include <Arduino.h>
#include <Preferences.h>
#include <initializer_list>
#include "cJSON.h"
#include <U8x8lib.h>
#include <WiFi.h>
#include <Wire.h>
#include "driver/gpio.h"
#include "esp_camera.h"
#include "esp_rom_gpio.h"
#include "soc/gpio_sig_map.h"
#include "esp_http_server.h"
#include "board_pins.h"

#if __has_include("config.local.h")
#include "config.local.h"
#else
#include "config.example.h"
#endif

namespace {
constexpr char apName[] = "WorkbenchCam";
constexpr uint32_t sessionMs = 60000;
constexpr uint32_t cooldownMs = 60000;
constexpr uint8_t lowBandwidth = 1 << 0;
constexpr uint8_t rotate180 = 1 << 1;
constexpr uint8_t dimOled = 1 << 2;
constexpr int xclkHz = 10000000;

enum class State { Idle, Streaming, Cooldown };
enum class Reason { None, Limit, Camera, Disconnected, Stopped };
struct Status {
    State state;
    Reason reason;
    uint32_t remainingSeconds;
};

portMUX_TYPE statusLock = portMUX_INITIALIZER_UNLOCKED;
State state = State::Idle;
Reason reason = Reason::None;
uint32_t deadline = 0;
bool stopRequested = false;
uint8_t boardOptions = 0;
Preferences preferences;
bool preferencesReady = false;
bool apReady = false;
bool oledReady = false;
U8X8_SSD1306_128X64_NONAME_SW_I2C oled(OLED_SCL_GPIO, OLED_SDA_GPIO);

bool reached(uint32_t now, uint32_t target) {
    return static_cast<int32_t>(now - target) >= 0;
}

void setStatus(State nextState, Reason nextReason, uint32_t durationMs) {
    const uint32_t end = millis() + durationMs;
    portENTER_CRITICAL(&statusLock);
    state = nextState;
    if (state == State::Streaming) stopRequested = false;
    reason = nextReason;
    deadline = end;
    portEXIT_CRITICAL(&statusLock);
}

Status getStatus() {
    const uint32_t now = millis();
    portENTER_CRITICAL(&statusLock);
    if (state == State::Cooldown && reached(now, deadline)) {
        state = State::Idle;
    }
    const uint32_t seconds = state == State::Idle || reached(now, deadline)
        ? 0 : (deadline - now + 999) / 1000;
    const Status result{state, reason, seconds};
    portEXIT_CRITICAL(&statusLock);
    return result;
}

const char* stateName(State value) {
    switch (value) {
        case State::Idle: return "idle";
        case State::Streaming: return "streaming";
        case State::Cooldown: return "cooldown";
    }
    return "idle";
}

const char* reasonName(Reason value) {
    switch (value) {
        case Reason::None: return "none";
        case Reason::Limit: return "limit";
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
    config.frame_size = FRAMESIZE_QVGA;
    config.jpeg_quality = 18;
    config.fb_count = 1;
    config.fb_location = CAMERA_FB_IN_PSRAM;
    config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;

    const esp_err_t result = esp_camera_init(&config);
    if (result != ESP_OK) {
        Serial.printf("[CAM] Init failed: 0x%x\n", result);
        holdCameraClockLow();
        return false;
    }
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
    snprintf(body, sizeof(body), "{\"state\":\"%s\",\"remaining_s\":%lu,\"reason\":\"%s\"}",
             stateName(current.state), static_cast<unsigned long>(current.remainingSeconds), reasonName(current.reason));
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    return httpd_resp_sendstr(request, body);
}

const char* jsonBool(bool value) {
    return value ? "true" : "false";
}

esp_err_t sendOptions(httpd_req_t* request) {
    const uint8_t options = getOptions();
    char body[90];
    snprintf(body, sizeof(body), "{\"low_bandwidth\":%s,\"rotate_180\":%s,\"dim_oled\":%s}",
             jsonBool(options & lowBandwidth), jsonBool(options & rotate180), jsonBool(options & dimOled));
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
    char body[128];
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
    const bool valid = cJSON_IsObject(settings) && cJSON_IsBool(low) && cJSON_IsBool(rotate) && cJSON_IsBool(dim);
    const uint8_t next = (cJSON_IsTrue(low) ? lowBandwidth : 0) |
                         (cJSON_IsTrue(rotate) ? rotate180 : 0) | (cJSON_IsTrue(dim) ? dimOled : 0);
    cJSON_Delete(settings);
    if (!valid) {
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Expected three boolean settings");
    }

    if (next != getOptions()) {
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

esp_err_t stream(httpd_req_t* request) {
    const Status current = getStatus();
    if (current.state == State::Cooldown) {
        char retry[12];
        snprintf(retry, sizeof(retry), "%lu", static_cast<unsigned long>(current.remainingSeconds));
        httpd_resp_set_status(request, "429 Too Many Requests");
        httpd_resp_set_hdr(request, "Retry-After", retry);
        return httpd_resp_sendstr(request, "Camera is cooling down");
    }

    const uint32_t sessionStarted = millis();
    const uint32_t stopAt = sessionStarted + sessionMs;
    uint32_t sentFrames = 0;
    setStatus(State::Streaming, Reason::None, sessionMs);
    const uint8_t options = getOptions();
    const uint32_t frameIntervalMs = (options & lowBandwidth) ? 334 : 200;
    if (!startCamera(options)) {
        setStatus(State::Cooldown, Reason::Camera, cooldownMs);
        httpd_resp_set_status(request, "503 Service Unavailable");
        return httpd_resp_sendstr(request, "Camera initialization failed");
    }

    httpd_resp_set_type(request, "multipart/x-mixed-replace; boundary=frame");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    char limit[8];
    snprintf(limit, sizeof(limit), "%lu", static_cast<unsigned long>(sessionMs / 1000));
    httpd_resp_set_hdr(request, "X-Stream-Limit-Seconds", limit);
    Reason ending = Reason::Limit;
    esp_err_t result = ESP_OK;
    while (!reached(millis(), stopAt) && !shouldStop()) {
        const uint32_t started = millis();
        camera_fb_t* frame = esp_camera_fb_get();
        if (!frame) {
            ending = Reason::Camera;
            break;
        }
        if (reached(millis(), stopAt) || shouldStop()) {
            esp_camera_fb_return(frame);
            break;
        }
        char header[100];
        const int length = snprintf(header, sizeof(header),
            "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n",
            static_cast<unsigned>(frame->len));
        result = httpd_resp_send_chunk(request, header, length);
        if (result == ESP_OK) {
            result = httpd_resp_send_chunk(request, reinterpret_cast<const char*>(frame->buf), frame->len);
        }
        esp_camera_fb_return(frame);
        if (result == ESP_OK) {
            result = httpd_resp_send_chunk(request, "\r\n", 2);
        }
        if (result != ESP_OK || WiFi.softAPgetStationNum() == 0) {
            ending = Reason::Disconnected;
            break;
        }
        ++sentFrames;
        const uint32_t elapsed = millis() - started;
        if (elapsed < frameIntervalMs) {
            delay(frameIntervalMs - elapsed);
        }
    }

    if (shouldStop()) ending = Reason::Stopped;
    stopCamera();
    setStatus(State::Cooldown, ending, cooldownMs);
    Serial.printf("[CAM] Stream ended: %s frames=%lu duration_ms=%lu\n",
                  reasonName(ending), static_cast<unsigned long>(sentFrames),
                  static_cast<unsigned long>(millis() - sessionStarted));
    if (result == ESP_OK) {
        result = httpd_resp_sendstr_chunk(request, "--frame--\r\n");
        if (result == ESP_OK) {
            result = httpd_resp_send_chunk(request, nullptr, 0);
        }
    }
    return result;
}

httpd_handle_t startServer(uint16_t port, uint16_t controlPort, std::initializer_list<httpd_uri_t> routes) {
    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.server_port = port;
    config.ctrl_port = controlPort;
    config.send_wait_timeout = 2;
    config.recv_wait_timeout = 2;
    config.stack_size = 8192;
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

bool isOledPin(int pin) {
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

void startOled() {
    if (OLED_SDA_GPIO < 0 || OLED_SCL_GPIO < 0) {
        Serial.println("[OLED] Pins not configured");
        return;
    }
    if (OLED_SDA_GPIO == OLED_SCL_GPIO || !isOledPin(OLED_SDA_GPIO) || !isOledPin(OLED_SCL_GPIO)) {
        Serial.println("[OLED] Pins conflict with board functions");
        return;
    }
    if (!Wire.begin(OLED_SDA_GPIO, OLED_SCL_GPIO, 100000U)) {
        Serial.println("[OLED] I2C setup failed");
        return;
    }
    uint8_t address = 0;
    for (uint8_t candidate = 0x3C; candidate <= 0x3D; ++candidate) {
        Wire.beginTransmission(candidate);
        if (Wire.endTransmission() == 0) {
            address = candidate;
            break;
        }
    }
    Wire.end();
    if (!address) {
        Serial.println("[OLED] No reply at 0x3C or 0x3D");
        return;
    }
    oled.setI2CAddress(address << 1);
    oled.begin();
    oled.setFont(u8x8_font_chroma48medium8_r);
    oled.clearDisplay();
    oled.drawString(0, 0, apName);
    oledReady = true;
    Serial.printf("[OLED] Address 0x%02x\n", address);
}

void updateOled() {
    if (!oledReady) {
        return;
    }
    if (!apReady) {
        oled.drawString(0, 3, "CHECK WI-FI");
        return;
    }
    const Status current = getStatus();
    char row[17];
    const bool cameraError = current.reason == Reason::Camera && current.state != State::Streaming;
    const char* label = cameraError ? "CAM ERROR" : current.state == State::Streaming ? "LIVE"
        : current.state == State::Cooldown ? "COOLING" : "READY";
    if (current.remainingSeconds > 0) {
        snprintf(row, sizeof(row), "%s %lus", label, static_cast<unsigned long>(current.remainingSeconds));
    } else {
        snprintf(row, sizeof(row), "%s", label);
    }
    static char lastRow[17];
    static bool wasBright = true;
    const bool bright = current.state == State::Streaming && !(getOptions() & dimOled);
    if (bright != wasBright) {
        oled.setContrast(bright ? 127 : 32);
        wasBright = bright;
    }
    if (strcmp(lastRow, row) != 0) {
        strcpy(lastRow, row);
        oled.clearLine(3);
        oled.drawString(0, 3, row);
    }
}
}

void setup() {
    const bool cpuConfigured = setCpuFrequencyMhz(160);
    Serial.begin(115200);
    if (!cpuConfigured) Serial.println("[BOOT] CPU frequency unchanged");
    holdCameraClockLow();
    Serial.printf("[BOOT] PSRAM=%u bytes\n", ESP.getPsramSize());
    preferencesReady = preferences.begin("workbenchcam", false);
    if (preferencesReady) boardOptions = preferences.getUChar("options", 0);
    startOled();
    constexpr size_t passwordLength = sizeof(CAMERA_AP_PASSWORD) - 1;
    if (passwordLength < 8 || passwordLength > 63) {
        Serial.println("[Wi-Fi] Set a unique 8–63 character password in config.local.h");
        return;
    }
    const IPAddress address(192, 168, 4, 1);
    WiFi.mode(WIFI_AP);
    if (!WiFi.softAPConfig(address, address, IPAddress(255, 255, 255, 0)) ||
        !WiFi.softAP(apName, CAMERA_AP_PASSWORD, 1, false, 1)) {
        Serial.println("[Wi-Fi] SoftAP failed");
        return;
    }
    if (!WiFi.setTxPower(WIFI_POWER_15dBm)) Serial.println("[Wi-Fi] TX power unchanged");
    const auto statusServer = startServer(80, 32768, {
        {"/status", HTTP_GET, sendStatus, nullptr},
        {"/settings", HTTP_GET, sendOptions, nullptr},
        {"/settings", HTTP_POST, updateOptions, nullptr},
        {"/stop", HTTP_POST, requestStop, nullptr},
    });
    const auto streamServer = startServer(81, 32769, {{"/stream", HTTP_GET, stream, nullptr}});
    if (!statusServer || !streamServer) {
        if (statusServer) httpd_stop(statusServer);
        if (streamServer) httpd_stop(streamServer);
        WiFi.softAPdisconnect(true);
        Serial.println("[Wi-Fi] HTTP server setup failed");
        return;
    }
    apReady = true;
    Serial.printf("[Wi-Fi] %s at %s\n", apName, address.toString().c_str());
    if (oledReady) oled.drawString(0, 1, "192.168.4.1");
}

void loop() {
    updateOled();
    delay(1000);
}
