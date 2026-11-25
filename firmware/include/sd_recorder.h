#pragma once

#include <Arduino.h>
#include <Preferences.h>
#include <SD_MMC.h>
#include <sys/time.h>
#include <time.h>
#include <algorithm>
#include <cstring>
#include <string>
#include <vector>
#include "esp_camera.h"
#include "board_pins.h"

namespace sdrec {

enum class RecordMode : uint8_t { Off = 0, Blackbox = 1, Timelapse = 2 };

struct SdStatus {
    bool mounted = false;
    uint32_t totalMb = 0;
    uint32_t usedMb = 0;
    uint32_t freeMb = 0;
    RecordMode mode = RecordMode::Off;
    bool active = false;
    bool pausedForLive = false;
    bool autoStart = false;
    uint16_t segmentSeconds = 180;
    uint16_t intervalSeconds = 5;
    uint32_t frames = 0;
    uint32_t elapsedSeconds = 0;
    char currentFile[40] = "";
    char lastError[64] = "";
};

struct FileEntry {
    std::string category;
    std::string name;
    size_t size;
};

// External camera hooks provided by main.cpp so camera settings stay 100% unified.
using StartCameraFn = bool (*)(uint8_t options);
using StopCameraFn = void (*)();
using GetOptionsFn = uint8_t (*)();

namespace detail {

constexpr uint64_t kMinFreeBytesForLoop = 256ULL * 1024ULL * 1024ULL;
constexpr uint64_t kHardMinFreeBytes = 64ULL * 1024ULL * 1024ULL;
constexpr size_t kAviHeaderSize = 240;

inline void writeLe16(uint8_t* p, uint16_t v) {
    p[0] = v & 0xFF;
    p[1] = (v >> 8) & 0xFF;
}

inline void writeLe32(uint8_t* p, uint32_t v) {
    p[0] = v & 0xFF;
    p[1] = (v >> 8) & 0xFF;
    p[2] = (v >> 16) & 0xFF;
    p[3] = (v >> 24) & 0xFF;
}

inline void buildAviHeader(uint8_t header[kAviHeaderSize], uint16_t width, uint16_t height,
                           uint32_t frames, uint32_t fps, uint32_t moviDataSize) {
    memset(header, 0, kAviHeaderSize);
    const uint32_t safeFps = fps == 0 ? 8 : fps;
    const uint32_t usPerFrame = 1000000U / safeFps;
    const uint32_t moviListSize = 4 + moviDataSize; // "movi" + chunks
    const uint32_t riffSize = (kAviHeaderSize - 8) + moviDataSize;

    // RIFF header (0..11)
    memcpy(header + 0, "RIFF", 4);
    writeLe32(header + 4, riffSize);
    memcpy(header + 8, "AVI ", 4);

    // LIST hdrl (12..211) -> payload size = 192 (4 "hdrl" + 64 avih + 8 strl hdr + 116 strl payload)
    memcpy(header + 12, "LIST", 4);
    writeLe32(header + 16, 192);
    memcpy(header + 20, "hdrl", 4);

    // avih chunk (24..87, payload 56 bytes)
    memcpy(header + 24, "avih", 4);
    writeLe32(header + 28, 56);
    writeLe32(header + 32, usPerFrame);        // dwMicroSecPerFrame
    writeLe32(header + 36, 512 * 1024);        // dwMaxBytesPerSec
    writeLe32(header + 40, 0);                 // dwPaddingGranularity
    writeLe32(header + 44, 0);                 // dwFlags
    writeLe32(header + 48, frames);            // dwTotalFrames
    writeLe32(header + 52, 0);                 // dwInitialFrames
    writeLe32(header + 56, 1);                 // dwStreams
    writeLe32(header + 60, 128 * 1024);        // dwSuggestedBufferSize
    writeLe32(header + 64, width);             // dwWidth
    writeLe32(header + 68, height);            // dwHeight

    // LIST strl (88..211) -> payload size = 116 (4 "strl" + 64 strh + 48 strf)
    memcpy(header + 88, "LIST", 4);
    writeLe32(header + 92, 116);
    memcpy(header + 96, "strl", 4);

    // strh chunk (100..163, payload 56 bytes)
    memcpy(header + 100, "strh", 4);
    writeLe32(header + 104, 56);
    memcpy(header + 108, "vids", 4);           // fccType
    memcpy(header + 112, "MJPG", 4);           // fccHandler
    writeLe32(header + 116, 0);                // dwFlags
    writeLe32(header + 120, 0);                // wPriority/wLanguage
    writeLe32(header + 124, 0);                // dwInitialFrames
    writeLe32(header + 128, 1);                // dwScale
    writeLe32(header + 132, safeFps);          // dwRate
    writeLe32(header + 136, 0);                // dwStart
    writeLe32(header + 140, frames);           // dwLength
    writeLe32(header + 144, 128 * 1024);       // dwSuggestedBufferSize
    writeLe32(header + 148, 0);                // dwQuality
    writeLe32(header + 152, 0);                // dwSampleSize
    writeLe16(header + 160, width);            // rcFrame.right
    writeLe16(header + 162, height);           // rcFrame.bottom

    // strf chunk (164..211, BITMAPINFOHEADER 40 bytes)
    memcpy(header + 164, "strf", 4);
    writeLe32(header + 168, 40);
    writeLe32(header + 172, 40);               // biSize
    writeLe32(header + 176, width);            // biWidth
    writeLe32(header + 180, height);           // biHeight
    writeLe16(header + 184, 1);                // biPlanes
    writeLe16(header + 186, 24);               // biBitCount
    memcpy(header + 188, "MJPG", 4);           // biCompression
    writeLe32(header + 192, static_cast<uint32_t>(width) * height * 3);

    // JUNK pad (212..227, 8-byte header + 8-byte payload)
    memcpy(header + 212, "JUNK", 4);
    writeLe32(header + 216, 8);

    // LIST movi (228..239)
    memcpy(header + 228, "LIST", 4);
    writeLe32(header + 232, moviListSize);
    memcpy(header + 236, "movi", 4);
}

inline void resolutionDimensions(uint8_t resIndex, uint16_t& w, uint16_t& h) {
    switch (resIndex) {
        case 1: w = 320; h = 240; break;
        case 2: w = 800; h = 600; break;
        case 3: w = 1280; h = 720; break;
        default: w = 640; h = 480; break;
    }
}

} // namespace detail

class Manager {
public:
    bool begin(StartCameraFn startCam, StopCameraFn stopCam, GetOptionsFn getOpts) {
        startCamera_ = startCam;
        stopCamera_ = stopCam;
        getOptions_ = getOpts;
        sdMutex_ = xSemaphoreCreateRecursiveMutex();
        stateMutex_ = xSemaphoreCreateMutex();

        prefsReady_ = prefs_.begin("wcam_sd", false);
        if (prefsReady_) {
            seqCounter_ = prefs_.getULong("seq", 1);
            autoStart_ = prefs_.getBool("autostart", false);
            segmentSeconds_ = prefs_.getUShort("seg_s", 180);
            intervalSeconds_ = prefs_.getUShort("int_s", 5);
            if (segmentSeconds_ < 60 || segmentSeconds_ > 600) segmentSeconds_ = 180;
            if (intervalSeconds_ < 1 || intervalSeconds_ > 60) intervalSeconds_ = 5;
        }

        if (!mountCard()) {
            Serial.println("[SD] Card not mounted");
            return false;
        }
        ensureDirs();

        if (autoStart_) {
            desiredMode_ = RecordMode::Blackbox;
        }

        xTaskCreatePinnedToCore(taskEntry, "sd-rec", 8192, this, 1, &taskHandle_, 1);
        return true;
    }

    bool isMounted() const { return mounted_; }

    void syncTime(int64_t epochSeconds) {
        if (epochSeconds < 1700000000LL || epochSeconds > 4102444800LL) return;
        struct timeval tv{};
        tv.tv_sec = static_cast<time_t>(epochSeconds);
        tv.tv_usec = 0;
        settimeofday(&tv, nullptr);
        timeSynced_ = true;
    }

    // Called by /stream BEFORE calling startCamera(). Waits until recorder yields the camera cleanly.
    void pauseForLive() {
        xSemaphoreTake(stateMutex_, portMAX_DELAY);
        liveRequested_ = true;
        xSemaphoreGive(stateMutex_);

        const uint32_t deadline = millis() + 4000;
        while (cameraOwnedByRecorder_ && millis() < deadline) {
            vTaskDelay(pdMS_TO_TICKS(15));
        }
    }

    // Called by /stream AFTER calling stopCamera().
    void resumeAfterLive() {
        xSemaphoreTake(stateMutex_, portMAX_DELAY);
        liveRequested_ = false;
        xSemaphoreGive(stateMutex_);
    }

    void requestSnapshot() {
        xSemaphoreTake(stateMutex_, portMAX_DELAY);
        snapshotRequested_ = true;
        xSemaphoreGive(stateMutex_);
    }

    // If a live stream is active and a snapshot was requested (e.g. BOOT button), save directly.
    bool consumeLiveSnapshot(const uint8_t* jpeg, size_t len) {
        if (!mounted_ || !jpeg || len < 4) return false;
        bool requested = false;
        xSemaphoreTake(stateMutex_, portMAX_DELAY);
        if (snapshotRequested_) {
            snapshotRequested_ = false;
            requested = true;
        }
        xSemaphoreGive(stateMutex_);
        if (!requested) return false;
        return saveSnapshotBytes(jpeg, len);
    }

    bool configureRecording(RecordMode mode, uint16_t segmentS, uint16_t intervalS,
                            int autoStartOpt, std::string& errOut) {
        if (!mounted_) {
            errOut = "SD카드 미인식";
            return false;
        }
        if (segmentS >= 60 && segmentS <= 600) segmentSeconds_ = segmentS;
        if (intervalS >= 1 && intervalS <= 60) intervalSeconds_ = intervalS;
        if (autoStartOpt >= 0) autoStart_ = (autoStartOpt != 0);
        if (prefsReady_) {
            prefs_.putBool("autostart", autoStart_);
            prefs_.putUShort("seg_s", segmentSeconds_);
            prefs_.putUShort("int_s", intervalSeconds_);
        }
        xSemaphoreTake(stateMutex_, portMAX_DELAY);
        desiredMode_ = mode;
        lastError_[0] = '\0';
        xSemaphoreGive(stateMutex_);
        return true;
    }

    SdStatus getStatus() {
        SdStatus s{};
        if (xSemaphoreTakeRecursive(sdMutex_, pdMS_TO_TICKS(300)) == pdTRUE) {
            uint64_t total = mounted_ ? SD_MMC.totalBytes() : 0;
            if (total == 0 && activeMode_ == RecordMode::Off) {
                tryRemountLocked();
                total = mounted_ ? SD_MMC.totalBytes() : 0;
            }
            s.mounted = mounted_;
            if (mounted_ && total > 0) {
                const uint64_t used = SD_MMC.usedBytes();
                s.totalMb = static_cast<uint32_t>(total / (1024ULL * 1024ULL));
                s.usedMb = static_cast<uint32_t>(used / (1024ULL * 1024ULL));
                s.freeMb = total > used ? static_cast<uint32_t>((total - used) / (1024ULL * 1024ULL)) : 0;
            }
            xSemaphoreGiveRecursive(sdMutex_);
        } else {
            s.mounted = mounted_;
        }
        xSemaphoreTake(stateMutex_, portMAX_DELAY);
        s.mode = desiredMode_;
        s.active = activeMode_ != RecordMode::Off;
        s.pausedForLive = (desiredMode_ != RecordMode::Off) && liveRequested_;
        s.autoStart = autoStart_;
        s.segmentSeconds = segmentSeconds_;
        s.intervalSeconds = intervalSeconds_;
        s.frames = activeFrames_;
        s.elapsedSeconds = activeMode_ != RecordMode::Off ? ((millis() - segmentStartedMs_) / 1000) : 0;
        strncpy(s.currentFile, currentFileName_, sizeof(s.currentFile) - 1);
        strncpy(s.lastError, lastError_, sizeof(s.lastError) - 1);
        xSemaphoreGive(stateMutex_);
        return s;
    }

    static bool isValidCategory(const char* cat) {
        return cat && (strcmp(cat, "blackbox") == 0 || strcmp(cat, "timelapse") == 0 ||
                       strcmp(cat, "snapshots") == 0);
    }

    static bool isValidName(const char* name) {
        if (!name || !name[0] || strlen(name) > 48) return false;
        if (strstr(name, "..") != nullptr) return false;
        for (const char* p = name; *p; ++p) {
            const char c = *p;
            const bool ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                            (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            if (!ok) return false;
        }
        return true;
    }

    std::vector<FileEntry> listFiles(const char* filterCat, size_t maxItems = 60) {
        std::vector<FileEntry> out;
        if (!mounted_) return out;
        const char* cats[3] = {"blackbox", "timelapse", "snapshots"};
        if (xSemaphoreTakeRecursive(sdMutex_, pdMS_TO_TICKS(1000)) != pdTRUE) return out;
        for (const char* cat : cats) {
            if (filterCat && strcmp(filterCat, "all") != 0 && strcmp(filterCat, cat) != 0) continue;
            std::string dirPath = std::string("/workbenchcam/") + cat;
            File dir = SD_MMC.open(dirPath.c_str());
            if (!dir || !dir.isDirectory()) {
                if (dir) dir.close();
                continue;
            }
            while (File f = dir.openNextFile()) {
                if (!f.isDirectory()) {
                    const char* rawName = f.name();
                    const char* base = strrchr(rawName, '/');
                    base = base ? base + 1 : rawName;
                    if (isValidName(base) && f.size() > 0) {
                        out.push_back({cat, base, f.size()});
                    }
                }
                f.close();
            }
            dir.close();
        }
        xSemaphoreGiveRecursive(sdMutex_);
        std::sort(out.begin(), out.end(), [](const FileEntry& a, const FileEntry& b) {
            return a.name > b.name; // Newest first (YYYYMMDD_HHMMSS or 000001)
        });
        if (out.size() > maxItems) out.resize(maxItems);
        return out;
    }

    bool deleteFile(const char* cat, const char* name) {
        if (!mounted_ || !isValidCategory(cat) || !isValidName(name)) return false;
        xSemaphoreTake(stateMutex_, portMAX_DELAY);
        const bool isCurrent = (activeMode_ != RecordMode::Off && strcmp(currentFileName_, name) == 0);
        xSemaphoreGive(stateMutex_);
        if (isCurrent) return false; // Refuse deleting actively open file

        std::string path = std::string("/workbenchcam/") + cat + "/" + name;
        if (xSemaphoreTakeRecursive(sdMutex_, pdMS_TO_TICKS(1000)) != pdTRUE) return false;
        const bool ok = SD_MMC.remove(path.c_str());
        xSemaphoreGiveRecursive(sdMutex_);
        return ok;
    }

    int clearCategory(const char* cat) {
        if (!mounted_ || !isValidCategory(cat)) return -1;
        auto files = listFiles(cat, 500);
        int deleted = 0;
        for (const auto& f : files) {
            if (deleteFile(cat, f.name.c_str())) ++deleted;
        }
        return deleted;
    }

    // Safe locked file reader helper for HTTP download/playback
    bool lockSd(uint32_t timeoutMs = 2000) {
        return mounted_ && xSemaphoreTakeRecursive(sdMutex_, pdMS_TO_TICKS(timeoutMs)) == pdTRUE;
    }
    void unlockSd() {
        xSemaphoreGiveRecursive(sdMutex_);
    }

private:
    bool tryRemountLocked() {
        const uint32_t now = millis();
        if (now - lastRemountMs_ < 3000) return mounted_;
        lastRemountMs_ = now;
        SD_MMC.end();
        mounted_ = false;
        if (mountCard()) {
            ensureDirs();
            return true;
        }
        return false;
    }

    bool mountCard() {
        if (!SD_MMC.setPins(board::sdClk, board::sdCmd, board::sdD0)) return false;
        if (SD_MMC.begin("/sdcard", true, false, SDMMC_FREQ_HIGHSPEED, 6) ||
            SD_MMC.begin("/sdcard", true, false, SDMMC_FREQ_DEFAULT, 6)) {
            mounted_ = (SD_MMC.cardType() != CARD_NONE);
            if (mounted_) {
                const uint64_t totalMb = SD_MMC.totalBytes() / (1024ULL * 1024ULL);
                const uint64_t usedMb = SD_MMC.usedBytes() / (1024ULL * 1024ULL);
                Serial.printf("[SD] Ready type=%u total=%lluMB free=%lluMB\n",
                              static_cast<unsigned>(SD_MMC.cardType()),
                              static_cast<unsigned long long>(totalMb),
                              static_cast<unsigned long long>(totalMb > usedMb ? totalMb - usedMb : 0));
            }
            return mounted_;
        }
        return false;
    }

    void ensureDirs() {
        if (!mounted_) return;
        const char* dirs[] = {
            "/workbenchcam",
            "/workbenchcam/blackbox",
            "/workbenchcam/timelapse",
            "/workbenchcam/snapshots"
        };
        for (const char* d : dirs) {
            if (!SD_MMC.exists(d)) SD_MMC.mkdir(d);
        }
    }

    std::string nextFilename(const char* prefix, const char* ext) {
        char buf[40];
        if (timeSynced_) {
            time_t now = time(nullptr);
            struct tm tmNow{};
            localtime_r(&now, &tmNow);
            snprintf(buf, sizeof(buf), "%s_%04d%02d%02d_%02d%02d%02d.%s",
                     prefix, tmNow.tm_year + 1900, tmNow.tm_mon + 1, tmNow.tm_mday,
                     tmNow.tm_hour, tmNow.tm_min, tmNow.tm_sec, ext);
        } else {
            const uint32_t seq = seqCounter_++;
            if (prefsReady_) prefs_.putULong("seq", seqCounter_);
            snprintf(buf, sizeof(buf), "%s_%06lu.%s", prefix, static_cast<unsigned long>(seq), ext);
        }
        return std::string(buf);
    }

    bool ensureFreeSpaceForBlackbox() {
        if (xSemaphoreTakeRecursive(sdMutex_, pdMS_TO_TICKS(1000)) != pdTRUE) return false;
        uint64_t total = SD_MMC.totalBytes();
        uint64_t used = SD_MMC.usedBytes();
        uint64_t freeBytes = total > used ? (total - used) : 0;

        if (freeBytes < detail::kMinFreeBytesForLoop) {
            // Collect blackbox files sorted oldest first
            std::vector<std::string> bbFiles;
            File dir = SD_MMC.open("/workbenchcam/blackbox");
            if (dir && dir.isDirectory()) {
                while (File f = dir.openNextFile()) {
                    if (!f.isDirectory()) {
                        const char* raw = f.name();
                        const char* base = strrchr(raw, '/');
                        base = base ? base + 1 : raw;
                        if (isValidName(base)) bbFiles.emplace_back(base);
                    }
                    f.close();
                }
                dir.close();
            }
            std::sort(bbFiles.begin(), bbFiles.end()); // Oldest first
            for (const auto& name : bbFiles) {
                if (freeBytes >= detail::kMinFreeBytesForLoop) break;
                std::string path = "/workbenchcam/blackbox/" + name;
                SD_MMC.remove(path.c_str());
                total = SD_MMC.totalBytes();
                used = SD_MMC.usedBytes();
                freeBytes = total > used ? (total - used) : 0;
            }
        }
        xSemaphoreGiveRecursive(sdMutex_);

        if (freeBytes < detail::kHardMinFreeBytes) {
            setError("SD 여유 공간 부족 (다른 파일 정리 필요)");
            return false;
        }
        return true;
    }

    bool saveSnapshotBytes(const uint8_t* jpeg, size_t len) {
        if (!mounted_ || !jpeg || len < 4) return false;
        if (xSemaphoreTakeRecursive(sdMutex_, pdMS_TO_TICKS(1500)) != pdTRUE) return false;
        const std::string name = nextFilename("SNAP", "jpg");
        const std::string path = "/workbenchcam/snapshots/" + name;
        File f = SD_MMC.open(path.c_str(), FILE_WRITE);
        bool ok = false;
        if (f) {
            ok = (f.write(jpeg, len) == len);
            f.close();
        }
        xSemaphoreGiveRecursive(sdMutex_);
        if (ok) Serial.printf("[SD] Saved snapshot %s (%u B)\n", name.c_str(), static_cast<unsigned>(len));
        return ok;
    }

    void setError(const char* msg) {
        xSemaphoreTake(stateMutex_, portMAX_DELAY);
        strncpy(lastError_, msg ? msg : "", sizeof(lastError_) - 1);
        lastError_[sizeof(lastError_) - 1] = '\0';
        xSemaphoreGive(stateMutex_);
    }

    bool openAviSegment(const char* cat, const char* prefix, uint16_t width, uint16_t height, uint32_t fpsHint) {
        if (!ensureFreeSpaceForBlackbox()) return false;
        const std::string name = nextFilename(prefix, "avi");
        const std::string path = std::string("/workbenchcam/") + cat + "/" + name;

        if (xSemaphoreTakeRecursive(sdMutex_, pdMS_TO_TICKS(1500)) != pdTRUE) return false;
        aviFile_ = SD_MMC.open(path.c_str(), FILE_WRITE);
        if (!aviFile_) {
            xSemaphoreGiveRecursive(sdMutex_);
            setError("녹화 파일 생성 실패");
            return false;
        }
        uint8_t header[detail::kAviHeaderSize];
        detail::buildAviHeader(header, width, height, 0, fpsHint, 0);
        const bool ok = (aviFile_.write(header, sizeof(header)) == sizeof(header));
        if (!ok) {
            aviFile_.close();
            SD_MMC.remove(path.c_str());
            xSemaphoreGiveRecursive(sdMutex_);
            setError("SD 쓰기 오류");
            return false;
        }
        xSemaphoreGiveRecursive(sdMutex_);

        aviWidth_ = width;
        aviHeight_ = height;
        aviFpsHint_ = fpsHint;
        aviMoviBytes_ = 0;
        aviFrames_ = 0;
        segmentStartedMs_ = millis();
        currentPath_ = path;

        xSemaphoreTake(stateMutex_, portMAX_DELAY);
        strncpy(currentFileName_, name.c_str(), sizeof(currentFileName_) - 1);
        currentFileName_[sizeof(currentFileName_) - 1] = '\0';
        activeFrames_ = 0;
        xSemaphoreGive(stateMutex_);
        Serial.printf("[SD] Recording -> %s\n", path.c_str());
        return true;
    }

    bool appendAviFrame(const uint8_t* jpeg, size_t len) {
        if (!aviFile_ || !jpeg || len < 4) return false;
        const size_t pad = len & 1U;
        uint8_t chunkHdr[8] = {'0', '0', 'd', 'c', 0, 0, 0, 0};
        detail::writeLe32(chunkHdr + 4, static_cast<uint32_t>(len));
        const uint8_t zero = 0;

        if (xSemaphoreTakeRecursive(sdMutex_, pdMS_TO_TICKS(2000)) != pdTRUE) return false;
        bool ok = (aviFile_.write(chunkHdr, 8) == 8);
        if (ok) ok = (aviFile_.write(jpeg, len) == len);
        if (ok && pad) ok = (aviFile_.write(&zero, 1) == 1);
        xSemaphoreGiveRecursive(sdMutex_);

        if (!ok) {
            setError("SD 쓰기 실패");
            return false;
        }
        aviMoviBytes_ += 8 + len + pad;
        ++aviFrames_;
        xSemaphoreTake(stateMutex_, portMAX_DELAY);
        activeFrames_ = aviFrames_;
        xSemaphoreGive(stateMutex_);
        return true;
    }

    void closeAviSegment(bool useActualFps) {
        if (!aviFile_) return;
        const uint32_t dtMs = millis() - segmentStartedMs_;
        uint32_t finalFps = aviFpsHint_;
        if (useActualFps && dtMs >= 1000 && aviFrames_ > 0) {
            finalFps = static_cast<uint32_t>((static_cast<uint64_t>(aviFrames_) * 1000ULL + dtMs / 2) / dtMs);
            if (finalFps < 1) finalFps = 1;
            if (finalFps > 30) finalFps = 30;
        }
        if (xSemaphoreTakeRecursive(sdMutex_, pdMS_TO_TICKS(2000)) == pdTRUE) {
            if (aviFrames_ > 0) {
                uint8_t header[detail::kAviHeaderSize];
                detail::buildAviHeader(header, aviWidth_, aviHeight_, aviFrames_, finalFps, aviMoviBytes_);
                aviFile_.seek(0);
                aviFile_.write(header, sizeof(header));
                aviFile_.flush();
            }
            aviFile_.close();
            if (aviFrames_ == 0 && !currentPath_.empty()) {
                SD_MMC.remove(currentPath_.c_str());
            }
            xSemaphoreGiveRecursive(sdMutex_);
        }
        Serial.printf("[SD] Closed %s frames=%lu fps=%lu size=%lu\n",
                      currentPath_.c_str(), static_cast<unsigned long>(aviFrames_),
                      static_cast<unsigned long>(finalFps),
                      static_cast<unsigned long>(aviMoviBytes_ + detail::kAviHeaderSize));
        currentPath_.clear();
        xSemaphoreTake(stateMutex_, portMAX_DELAY);
        currentFileName_[0] = '\0';
        activeFrames_ = 0;
        xSemaphoreGive(stateMutex_);
    }

    static void taskEntry(void* arg) {
        static_cast<Manager*>(arg)->runLoop();
    }

    void runLoop() {
        while (true) {
            xSemaphoreTake(stateMutex_, portMAX_DELAY);
            const RecordMode target = desiredMode_;
            const bool live = liveRequested_;
            const bool snap = snapshotRequested_;
            xSemaphoreGive(stateMutex_);

            // Handle standalone snapshot when idle and not streaming
            if (snap && !live && activeMode_ == RecordMode::Off) {
                xSemaphoreTake(stateMutex_, portMAX_DELAY);
                snapshotRequested_ = false;
                xSemaphoreGive(stateMutex_);
                cameraOwnedByRecorder_ = true;
                if (startCamera_(getOptions_())) {
                    if (camera_fb_t* w = esp_camera_fb_get()) esp_camera_fb_return(w);
                    if (camera_fb_t* f = esp_camera_fb_get()) {
                        saveSnapshotBytes(f->buf, f->len);
                        esp_camera_fb_return(f);
                    }
                    stopCamera_();
                }
                cameraOwnedByRecorder_ = false;
                continue;
            }

            if (live || target == RecordMode::Off) {
                if (activeMode_ != RecordMode::Off) {
                    closeAviSegment(activeMode_ == RecordMode::Blackbox);
                    stopCamera_();
                    cameraOwnedByRecorder_ = false;
                    xSemaphoreTake(stateMutex_, portMAX_DELAY);
                    activeMode_ = RecordMode::Off;
                    xSemaphoreGive(stateMutex_);
                }
                vTaskDelay(pdMS_TO_TICKS(100));
                continue;
            }

            // Switch modes if user changed between Blackbox <-> Timelapse
            if (activeMode_ != RecordMode::Off && activeMode_ != target) {
                closeAviSegment(activeMode_ == RecordMode::Blackbox);
                stopCamera_();
                cameraOwnedByRecorder_ = false;
                xSemaphoreTake(stateMutex_, portMAX_DELAY);
                activeMode_ = RecordMode::Off;
                xSemaphoreGive(stateMutex_);
            }

            // Start recording session
            if (activeMode_ == RecordMode::Off) {
                const uint8_t opts = getOptions_();
                uint16_t w = 640, h = 480;
                detail::resolutionDimensions((opts >> 3) & 0x03, w, h);
                cameraOwnedByRecorder_ = true;
                if (!startCamera_(opts)) {
                    cameraOwnedByRecorder_ = false;
                    setError("카메라 시작 실패");
                    vTaskDelay(pdMS_TO_TICKS(1000));
                    continue;
                }
                if (camera_fb_t* w1 = esp_camera_fb_get()) esp_camera_fb_return(w1);

                const bool isBb = (target == RecordMode::Blackbox);
                if (!openAviSegment(isBb ? "blackbox" : "timelapse",
                                    isBb ? "BB" : "TL", w, h, isBb ? 8 : 10)) {
                    stopCamera_();
                    cameraOwnedByRecorder_ = false;
                    xSemaphoreTake(stateMutex_, portMAX_DELAY);
                    desiredMode_ = RecordMode::Off;
                    xSemaphoreGive(stateMutex_);
                    vTaskDelay(pdMS_TO_TICKS(500));
                    continue;
                }
                lastCaptureMs_ = 0;
                xSemaphoreTake(stateMutex_, portMAX_DELAY);
                activeMode_ = target;
                xSemaphoreGive(stateMutex_);
            }

            // Capture and write frames
            if (activeMode_ == RecordMode::Blackbox) {
                camera_fb_t* f = esp_camera_fb_get();
                if (f) {
                    bool snapNow = false;
                    xSemaphoreTake(stateMutex_, portMAX_DELAY);
                    if (snapshotRequested_) { snapshotRequested_ = false; snapNow = true; }
                    xSemaphoreGive(stateMutex_);
                    if (snapNow) saveSnapshotBytes(f->buf, f->len);

                    const bool ok = appendAviFrame(f->buf, f->len);
                    esp_camera_fb_return(f);
                    if (!ok) {
                        closeAviSegment(true);
                        stopCamera_();
                        cameraOwnedByRecorder_ = false;
                        xSemaphoreTake(stateMutex_, portMAX_DELAY);
                        activeMode_ = RecordMode::Off;
                        desiredMode_ = RecordMode::Off;
                        xSemaphoreGive(stateMutex_);
                        continue;
                    }
                    // Rotate segment every segmentSeconds_
                    if ((millis() - segmentStartedMs_) >= static_cast<uint32_t>(segmentSeconds_) * 1000UL) {
                        closeAviSegment(true);
                        if (!openAviSegment("blackbox", "BB", aviWidth_, aviHeight_, 8)) {
                            stopCamera_();
                            cameraOwnedByRecorder_ = false;
                            xSemaphoreTake(stateMutex_, portMAX_DELAY);
                            activeMode_ = RecordMode::Off;
                            desiredMode_ = RecordMode::Off;
                            xSemaphoreGive(stateMutex_);
                        }
                    }
                } else {
                    vTaskDelay(pdMS_TO_TICKS(20));
                }
                // Yield briefly so idle watchdog and Wi-Fi stay happy
                vTaskDelay(pdMS_TO_TICKS(5));
            } else if (activeMode_ == RecordMode::Timelapse) {
                const uint32_t nowMs = millis();
                const uint32_t intervalMs = static_cast<uint32_t>(intervalSeconds_) * 1000UL;
                if (lastCaptureMs_ == 0 || (nowMs - lastCaptureMs_) >= intervalMs) {
                    lastCaptureMs_ = nowMs;
                    // Flush stale buffer so we get the freshest frame
                    if (camera_fb_t* stale = esp_camera_fb_get()) esp_camera_fb_return(stale);
                    camera_fb_t* f = esp_camera_fb_get();
                    if (f) {
                        const bool ok = appendAviFrame(f->buf, f->len);
                        esp_camera_fb_return(f);
                        if (!ok) {
                            closeAviSegment(false);
                            stopCamera_();
                            cameraOwnedByRecorder_ = false;
                            xSemaphoreTake(stateMutex_, portMAX_DELAY);
                            activeMode_ = RecordMode::Off;
                            desiredMode_ = RecordMode::Off;
                            xSemaphoreGive(stateMutex_);
                            continue;
                        }
                        // Flush AVI header periodically (every 30 frames) for power-loss safety
                        if ((aviFrames_ % 30) == 0 && xSemaphoreTakeRecursive(sdMutex_, pdMS_TO_TICKS(1000)) == pdTRUE) {
                            uint8_t header[detail::kAviHeaderSize];
                            detail::buildAviHeader(header, aviWidth_, aviHeight_, aviFrames_, 10, aviMoviBytes_);
                            const size_t pos = aviFile_.position();
                            aviFile_.seek(0);
                            aviFile_.write(header, sizeof(header));
                            aviFile_.seek(pos);
                            aviFile_.flush();
                            xSemaphoreGiveRecursive(sdMutex_);
                        }
                    }
                }
                vTaskDelay(pdMS_TO_TICKS(50));
            }
        }
    }

    StartCameraFn startCamera_ = nullptr;
    StopCameraFn stopCamera_ = nullptr;
    GetOptionsFn getOptions_ = nullptr;
    SemaphoreHandle_t sdMutex_ = nullptr;
    SemaphoreHandle_t stateMutex_ = nullptr;
    TaskHandle_t taskHandle_ = nullptr;
    Preferences prefs_;
    bool prefsReady_ = false;
    bool mounted_ = false;
    bool timeSynced_ = false;
    uint32_t seqCounter_ = 1;

    volatile bool cameraOwnedByRecorder_ = false;
    bool liveRequested_ = false;
    bool snapshotRequested_ = false;
    bool autoStart_ = false;
    uint16_t segmentSeconds_ = 180;
    uint16_t intervalSeconds_ = 5;
    RecordMode desiredMode_ = RecordMode::Off;
    RecordMode activeMode_ = RecordMode::Off;

    File aviFile_;
    std::string currentPath_;
    char currentFileName_[40] = "";
    char lastError_[64] = "";
    uint16_t aviWidth_ = 640;
    uint16_t aviHeight_ = 480;
    uint32_t aviFpsHint_ = 8;
    uint32_t aviMoviBytes_ = 0;
    uint32_t aviFrames_ = 0;
    uint32_t activeFrames_ = 0;
    uint32_t segmentStartedMs_ = 0;
    uint32_t lastCaptureMs_ = 0;
    uint32_t lastRemountMs_ = 0;
};

} // namespace sdrec
