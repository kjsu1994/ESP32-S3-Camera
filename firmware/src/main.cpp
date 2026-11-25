#include <Arduino.h>
#include <Preferences.h>
#include <initializer_list>
#include "cJSON.h"
#include <WiFi.h>
#include <Wire.h>
#include "driver/gpio.h"
#include "esp_camera.h"
#include "esp_rom_gpio.h"
#include "soc/gpio_sig_map.h"
#include "esp_http_server.h"
#include "lwip/sockets.h"
#include "board_pins.h"

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
constexpr char apName[] = "WorkbenchCam";
constexpr uint8_t lowBandwidth = 1 << 0;
constexpr uint8_t rotate180 = 1 << 1;
// Retain the v0.1.0 settings round-trip for installed clients.
constexpr uint8_t legacyDimOled = 1 << 2;
constexpr uint8_t resolutionMask = 3 << 3;
constexpr uint8_t ina219Address = 0x40;
static_assert(POWER_SHUNT_MILLIOHMS >= 1 && POWER_SHUNT_MILLIOHMS <= 1000,
              "Set the INA219 shunt resistance in milliohms");

enum class Resolution : uint8_t { Vga, Qvga, Svga };
constexpr const char* resolutionKeys[] = {"vga", "qvga", "svga"};
constexpr framesize_t frameSizes[] = {FRAMESIZE_VGA, FRAMESIZE_QVGA, FRAMESIZE_SVGA};

uint8_t resolutionIndex(uint8_t options) {
    const uint8_t index = (options & resolutionMask) >> 3;
    return index < sizeof(resolutionKeys) / sizeof(*resolutionKeys) ? index : 0;
}
constexpr int xclkHz = 20000000;
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
bool preferencesReady = false;
bool powerBusReady = false;

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
    config.jpeg_quality = resolution == static_cast<uint8_t>(Resolution::Svga) ? 16 : 14;
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
    char body[112];
    snprintf(body, sizeof(body), "{\"low_bandwidth\":%s,\"rotate_180\":%s,\"dim_oled\":%s,\"resolution\":\"%s\"}",
             jsonBool(options & lowBandwidth), jsonBool(options & rotate180), jsonBool(options & legacyDimOled),
             resolutionKeys[resolutionIndex(options)]);
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
    const cJSON* resolution = cJSON_GetObjectItemCaseSensitive(settings, "resolution");
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
                       (!dim || cJSON_IsBool(dim)) && index >= 0;
    const uint8_t next = (cJSON_IsTrue(low) ? lowBandwidth : 0) |
                         (cJSON_IsTrue(rotate) ? rotate180 : 0) |
                         (dim ? (cJSON_IsTrue(dim) ? legacyDimOled : 0) : (current & legacyDimOled)) |
                         (index >= 0 ? index << 3 : 0);
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
    char* packet = static_cast<char*>(ps_malloc(maxJpegBytes + partHeaderCapacity + 2));
    if (!packet) {
        httpd_resp_set_status(request, "503 Service Unavailable");
        return httpd_resp_sendstr(request, "Video buffer unavailable");
    }

    const uint32_t sessionStarted = millis();
    setStatus(State::Streaming, Reason::None);
    const uint8_t options = getOptions();
    const bool highResolution = resolutionIndex(options) == static_cast<uint8_t>(Resolution::Svga);
    const uint32_t frameIntervalMs = (options & lowBandwidth) ? 167 : highResolution ? 100 : 84;
    if (!startCamera(options)) {
        free(packet);
        setStatus(State::Idle, Reason::Camera);
        httpd_resp_set_status(request, "503 Service Unavailable");
        return httpd_resp_sendstr(request, "Camera initialization failed");
    }

    const int disableNagle = 1;
    setsockopt(httpd_req_to_sockfd(request), IPPROTO_TCP, TCP_NODELAY, &disableNagle, sizeof(disableNagle));
    constexpr char response[] =
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n"
        "Cache-Control: no-store\r\nConnection: close\r\n\r\n";
    bool sent = writeStream(request, response, sizeof(response) - 1);
    Reason ending = Reason::Disconnected;
    uint32_t sentFrames = 0;
    uint64_t captureUs = 0;
    uint64_t sendUs = 0;

    while (sent && !shouldStop()) {
        const uint32_t started = millis();
        const uint32_t captureStarted = micros();
        camera_fb_t* frame = esp_camera_fb_get();
        const uint32_t captureElapsed = micros() - captureStarted;
        if (!frame) {
            ending = Reason::Camera;
            break;
        }
        if (shouldStop()) {
            esp_camera_fb_return(frame);
            break;
        }
        const size_t jpegLength = frame->len;
        if (jpegLength > maxJpegBytes) {
            esp_camera_fb_return(frame);
            ending = Reason::Camera;
            break;
        }
        const int headerLength = snprintf(packet, partHeaderCapacity,
            "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n",
            static_cast<unsigned>(jpegLength));
        memcpy(packet + headerLength, frame->buf, jpegLength);
        // Let the camera fill its single driver buffer while TCP transmits the copied frame.
        esp_camera_fb_return(frame);
        const size_t end = headerLength + jpegLength;
        packet[end] = '\r';
        packet[end + 1] = '\n';

        const uint32_t sendStarted = micros();
        sent = writeStream(request, packet, end + 2);
        if (!sent || WiFi.softAPgetStationNum() == 0) {
            ending = Reason::Disconnected;
            break;
        }
        ++sentFrames;
        captureUs += captureElapsed;
        sendUs += micros() - sendStarted;
        const uint32_t elapsed = millis() - started;
        if (elapsed < frameIntervalMs) delay(frameIntervalMs - elapsed);
    }

    if (shouldStop()) ending = Reason::Stopped;
    stopCamera();
    free(packet);
    setStatus(State::Idle, ending);
    Serial.printf("[CAM] Stream ended: %s frames=%lu duration_ms=%lu\n",
                  reasonName(ending), static_cast<unsigned long>(sentFrames),
                  static_cast<unsigned long>(millis() - sessionStarted));
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
    config.send_wait_timeout = 4;
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

}

void setup() {
    const bool cpuConfigured = setCpuFrequencyMhz(160);
    Serial.begin(115200);
    if (!cpuConfigured) Serial.println("[BOOT] CPU frequency unchanged");
    holdCameraClockLow();
    Serial.printf("[BOOT] PSRAM=%u bytes\n", ESP.getPsramSize());
    preferencesReady = preferences.begin("workbenchcam", false);
    if (preferencesReady) boardOptions = preferences.getUChar("options", 0);
    startPowerMonitor();
    constexpr size_t passwordLength = sizeof(CAMERA_AP_PASSWORD) - 1;
    if (passwordLength < 8 || passwordLength > 63) {
        Serial.println("[Wi-Fi] Set a unique 8–63 character password in config.local.h");
        return;
    }
    const IPAddress address(192, 168, 4, 1);
    WiFi.onEvent([](WiFiEvent_t) { Serial.println("[Wi-Fi] Station disconnected"); },
                 ARDUINO_EVENT_WIFI_AP_STADISCONNECTED);
    WiFi.mode(WIFI_AP);
    if (!WiFi.softAPConfig(address, address, IPAddress(255, 255, 255, 0)) ||
        !WiFi.softAP(apName, CAMERA_AP_PASSWORD, 1, false, 1)) {
        Serial.println("[Wi-Fi] SoftAP failed");
        return;
    }
    if (!WiFi.setTxPower(WIFI_POWER_17dBm)) Serial.println("[Wi-Fi] TX power unchanged");
    const auto statusServer = startServer(80, 32768, {
        {"/status", HTTP_GET, sendStatus, nullptr},
        {"/telemetry", HTTP_GET, sendTelemetry, nullptr},
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
    Serial.printf("[Wi-Fi] %s at %s\n", apName, address.toString().c_str());
}

void loop() {
    delay(1000);
}
