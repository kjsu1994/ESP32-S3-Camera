package io.github.kjsu1994.workbenchcam

import android.net.Network
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

internal enum class VideoResolution(val key: String, val label: String, val shortLabel: String) {
    QVGA("qvga", "320 × 240", "QVGA"),
    VGA("vga", "640 × 480", "VGA"),
    SVGA("svga", "800 × 600", "SVGA"),
    HD("hd", "1280 × 720", "HD")
}

internal enum class SdRecordMode(val key: String, val label: String) {
    OFF("off", "대기"),
    BLACKBOX("blackbox", "블랙박스"),
    TIMELAPSE("timelapse", "타임랩스");

    companion object {
        fun fromKey(key: String?): SdRecordMode = entries.firstOrNull { it.key == key } ?: OFF
    }
}

internal enum class ConnectPreference(val key: String, val label: String) {
    AUTO("auto", "자동 (공유기 우선)"),
    LAN_ONLY("lan", "집 공유기"),
    AP_ONLY("ap", "직결 AP");

    companion object {
        fun fromKey(key: String?): ConnectPreference = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

internal data class BoardTarget(
    val network: Network?,
    val host: String,
    val viaLan: Boolean
)

internal data class BoardTelemetry(val chipTempC: Double?, val powerMw: Int?)

internal data class BoardOptions(
    val lowBandwidth: Boolean,
    val rotate180: Boolean,
    val resolution: VideoResolution,
    val nightMode: Boolean = false
)

internal data class WifiStatus(
    val mode: String,
    val ssid: String,
    val ip: String,
    val host: String,
    val rssi: Int,
    val channel: Int,
    val staEnabled: Boolean,
    val staSsid: String,
    val ledEnabled: Boolean,
    val fwVersion: String = "",
    val uptimeSeconds: Long = 0L,
    val torchEnabled: Boolean = false,
    val nightMode: Boolean = false
)

internal data class ScannedWifi(
    val ssid: String,
    val rssi: Int,
    val channel: Int,
    val open: Boolean
)

internal data class SdStatus(
    val mounted: Boolean,
    val totalMb: Long,
    val usedMb: Long,
    val freeMb: Long,
    val mode: SdRecordMode,
    val active: Boolean,
    val pausedForLive: Boolean,
    val autoStart: Boolean,
    val segmentSeconds: Int,
    val intervalSeconds: Int,
    val frames: Int,
    val elapsedSeconds: Int,
    val currentFile: String,
    val error: String
)

internal data class SdFileItem(
    val category: String,
    val name: String,
    val sizeBytes: Long,
    val dir: String = "",
    val mtime: Long = 0L
)

internal data class SdListing(
    val dir: String,
    val dirs: List<String>,
    val files: List<SdFileItem>
)

internal data class SdDeleteTarget(
    val category: String,
    val dir: String,
    val name: String,
    val isDir: Boolean = false
)

internal data class SharedNote(
    val id: Long,
    val title: String,
    val text: String,
    val ts: Long
)

internal fun isAllowedLocalHost(host: String): Boolean {
    val clean = host.trim().lowercase()
    if (clean == "dockycam.local" || clean == "workbenchcam.local") return true
    val parts = clean.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 }) return false
    return octets[0] == 10 ||
        (octets[0] == 192 && octets[1] == 168) ||
        (octets[0] == 172 && octets[1] in 16..31)
}

internal class BoardApi(private val target: BoardTarget) {
    fun open(path: String, readTimeoutMs: Int = 6000, connectTimeoutMs: Int = 3000): HttpURLConnection {
        if (!isAllowedLocalHost(target.host)) throw IOException("Disallowed non-local host: ${target.host}")
        val url = URL("http://${target.host}$path")
        val connection = (target.network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Connection", "close")
        return connection
    }

    fun getOptions(): BoardOptions = optionsRequest(null)

    fun getTelemetry(): BoardTelemetry {
        val connection = open("/telemetry", 2500)
        try {
            if (connection.responseCode != 200) throw IOException("Board telemetry unavailable")
            val json = connection.readBoardJson()
            val temp = if (json.isNull("chip_temp_c")) null else json.getDouble("chip_temp_c").takeIf { it.isFinite() }
            val power = if (json.isNull("power_mw")) null else json.getInt("power_mw")
            return BoardTelemetry(temp, power)
        } finally {
            connection.disconnect()
        }
    }

    fun setOptions(options: BoardOptions): BoardOptions {
        val json = JSONObject()
            .put("low_bandwidth", options.lowBandwidth)
            .put("rotate_180", options.rotate180)
            .put("resolution", options.resolution.key)
            .put("night_mode", options.nightMode)
        return optionsRequest(json.toString().toByteArray())
    }

    fun stop() {
        val connection = open("/stop", 2000)
        try {
            connection.post(byteArrayOf())
            if (connection.responseCode != 204) throw IOException("Board did not acknowledge stop")
        } finally {
            connection.disconnect()
        }
    }

    fun syncTime(epochSeconds: Long) {
        val connection = open("/time", 2000)
        try {
            val body = JSONObject().put("epoch", epochSeconds).toString().toByteArray()
            connection.post(body)
            if (connection.responseCode !in 200..204) throw IOException("Time sync failed")
        } finally {
            connection.disconnect()
        }
    }

    fun getWifiStatus(timeoutMs: Int = 2500): WifiStatus {
        val connection = open("/wifi/status", timeoutMs, timeoutMs.coerceAtMost(2000))
        try {
            if (connection.responseCode != 200) throw IOException("Wi-Fi status unavailable")
            val json = connection.readBoardJson(1024)
            return WifiStatus(
                mode = json.optString("mode", "ap"),
                ssid = json.optString("ssid", "DockyCam"),
                ip = json.optString("ip", target.host),
                host = json.optString("host", "dockycam.local"),
                rssi = json.optInt("rssi", 0),
                channel = json.optInt("channel", 6),
                staEnabled = json.optBoolean("sta_enabled", false),
                staSsid = json.optString("sta_ssid", ""),
                ledEnabled = json.optBoolean("led_enabled", true),
                fwVersion = json.optString("fw_version", ""),
                uptimeSeconds = json.optLong("uptime_s", 0L),
                torchEnabled = json.optBoolean("torch_enabled", false),
                nightMode = json.optBoolean("night_mode", false)
            )
        } finally {
            connection.disconnect()
        }
    }

    fun scanWifi(): List<ScannedWifi> {
        val connection = open("/wifi/scan", 10000)
        try {
            if (connection.responseCode != 200) throw IOException("Wi-Fi scan unavailable")
            val json = connection.readBoardJson(16384)
            val arr = json.optJSONArray("networks") ?: return emptyList()
            return List(arr.length()) { idx ->
                val obj = arr.getJSONObject(idx)
                ScannedWifi(
                    ssid = obj.optString("ssid"),
                    rssi = obj.optInt("rssi", -99),
                    channel = obj.optInt("ch", 0),
                    open = obj.optBoolean("open", false)
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    fun setWifiConfig(staEnabled: Boolean, staSsid: String?, staPass: String?, reboot: Boolean = true): Boolean {
        val connection = open("/wifi/config", 4000)
        try {
            val json = JSONObject()
                .put("sta_enabled", staEnabled)
                .put("reboot", reboot)
            if (staSsid != null) json.put("sta_ssid", staSsid)
            if (staPass != null) json.put("sta_pass", staPass)
            connection.post(json.toString().toByteArray())
            return connection.responseCode == 200
        } finally {
            connection.disconnect()
        }
    }

    fun setLedEnabled(enabled: Boolean): Boolean {
        val connection = open("/led", 2500)
        try {
            val json = JSONObject().put("enabled", enabled)
            connection.post(json.toString().toByteArray())
            if (connection.responseCode != 200) throw IOException("LED update failed")
            return connection.readBoardJson(256).optBoolean("led_enabled", enabled)
        } finally {
            connection.disconnect()
        }
    }

    fun setTorchEnabled(enabled: Boolean): Boolean {
        val connection = open("/torch", 2500)
        try {
            val json = JSONObject().put("enabled", enabled)
            connection.post(json.toString().toByteArray())
            if (connection.responseCode != 200) throw IOException("Torch update failed")
            return connection.readBoardJson(256).optBoolean("torch_enabled", enabled)
        } finally {
            connection.disconnect()
        }
    }

    fun setNightMode(enabled: Boolean): Boolean {
        val connection = open("/night", 2500)
        try {
            val json = JSONObject().put("enabled", enabled)
            connection.post(json.toString().toByteArray())
            if (connection.responseCode != 200) throw IOException("Night mode update failed")
            return connection.readBoardJson(256).optBoolean("night_mode", enabled)
        } finally {
            connection.disconnect()
        }
    }

    fun reboot() {
        val connection = open("/reboot", 2000)
        try {
            connection.post(byteArrayOf())
            if (connection.responseCode !in 200..204) throw IOException("Reboot failed")
        } finally {
            connection.disconnect()
        }
    }

    fun getSdStatus(): SdStatus {
        val connection = open("/sd/status", 3000)
        try {
            if (connection.responseCode != 200) throw IOException("SD status unavailable")
            return parseSdStatus(connection.readBoardJson(4096))
        } finally {
            connection.disconnect()
        }
    }

    fun setSdRecord(
        mode: SdRecordMode,
        segmentSeconds: Int? = null,
        intervalSeconds: Int? = null,
        autoStart: Boolean? = null
    ): SdStatus {
        val connection = open("/sd/record", 4000)
        try {
            val json = JSONObject().put("mode", mode.key)
            if (segmentSeconds != null) json.put("segment_s", segmentSeconds)
            if (intervalSeconds != null) json.put("interval_s", intervalSeconds)
            if (autoStart != null) json.put("auto_start", autoStart)
            connection.post(json.toString().toByteArray())
            if (connection.responseCode != 200) throw IOException("Could not update SD recording")
            return parseSdStatus(connection.readBoardJson(4096))
        } finally {
            connection.disconnect()
        }
    }

    fun triggerSdSnapshot() {
        val connection = open("/sd/snapshot", 3000)
        try {
            connection.post(byteArrayOf())
            if (connection.responseCode != 204) throw IOException("Snapshot failed")
        } finally {
            connection.disconnect()
        }
    }

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun webDashboardUrl(preferredHost: String? = null): String {
        val host = preferredHost?.takeIf(::isAllowedLocalHost) ?: target.host
        return "http://$host/"
    }

    fun sdFileUrl(item: SdFileItem, preferredHost: String? = null): String {
        val host = preferredHost?.takeIf(::isAllowedLocalHost) ?: target.host
        val encCat = encodeQuery(item.category)
        val encName = encodeQuery(item.name)
        val dirParam = if (item.dir.isNotEmpty()) "&dir=${encodeQuery(item.dir)}" else ""
        return "http://$host/sd/file?cat=$encCat&name=$encName$dirParam"
    }

    fun listSdDirectory(category: String = "all", dir: String = ""): SdListing {
        val encCat = encodeQuery(category)
        val dirParam = if (dir.isNotEmpty()) "&dir=${encodeQuery(dir)}" else ""
        val connection = open("/sd/files?cat=$encCat$dirParam", 5000)
        try {
            if (connection.responseCode != 200) throw IOException("SD files unavailable")
            val json = connection.readBoardJson(65536)
            val resolvedDir = json.optString("dir", dir)
            val dirsArr = json.optJSONArray("dirs")
            val dirs = if (dirsArr != null) {
                List(dirsArr.length()) { idx -> dirsArr.optString(idx) }.filter { it.isNotEmpty() }
            } else emptyList()
            val arr = json.optJSONArray("files")
            val files = if (arr != null) {
                List(arr.length()) { idx ->
                    val obj = arr.getJSONObject(idx)
                    SdFileItem(
                        category = obj.optString("cat"),
                        name = obj.optString("name"),
                        sizeBytes = obj.optLong("size", 0L),
                        dir = if (obj.optString("cat") == "shared") resolvedDir else "",
                        mtime = obj.optLong("mtime", 0L)
                    )
                }
            } else emptyList()
            return SdListing(resolvedDir, dirs, files)
        } finally {
            connection.disconnect()
        }
    }

    fun openSdFile(category: String, name: String, readTimeoutMs: Int = 10000, dir: String = ""): HttpURLConnection {
        val encCat = encodeQuery(category)
        val encName = encodeQuery(name)
        val dirParam = if (dir.isNotEmpty()) "&dir=${encodeQuery(dir)}" else ""
        return open("/sd/file?cat=$encCat&name=$encName$dirParam", readTimeoutMs)
    }

    fun deleteSdFile(category: String, name: String, dir: String = "", isDir: Boolean = false) {
        val connection = open("/sd/delete", 5000)
        try {
            val payload = JSONObject().put("cat", category).put("name", name)
            if (dir.isNotEmpty()) payload.put("dir", dir)
            if (isDir) payload.put("is_dir", true)
            connection.post(payload.toString().toByteArray(Charsets.UTF_8))
            if (connection.responseCode !in 200..204) throw IOException("Delete failed")
        } finally {
            connection.disconnect()
        }
    }

    fun deleteSdBatch(items: List<SdDeleteTarget>): Int {
        if (items.isEmpty()) return 0
        val connection = open("/sd/delete", 8000)
        try {
            val arr = JSONArray()
            items.forEach { target ->
                val obj = JSONObject()
                    .put("cat", target.category)
                    .put("dir", target.dir)
                    .put("name", target.name)
                if (target.isDir) obj.put("is_dir", true)
                arr.put(obj)
            }
            val body = JSONObject().put("items", arr).toString().toByteArray(Charsets.UTF_8)
            connection.post(body)
            if (connection.responseCode != 200) throw IOException("Batch delete failed")
            return connection.readBoardJson(512).optInt("deleted", 0)
        } finally {
            connection.disconnect()
        }
    }

    fun createSharedFolder(parentDir: String, name: String): String {
        val connection = open("/sd/mkdir", 4000)
        try {
            val body = JSONObject().put("dir", parentDir).put("name", name).toString().toByteArray(Charsets.UTF_8)
            connection.post(body)
            if (connection.responseCode != 200) throw IOException("Create folder failed")
            return connection.readBoardJson(512).optString("name", name)
        } finally {
            connection.disconnect()
        }
    }

    fun renameSdEntry(category: String, dir: String, from: String, to: String): String {
        val connection = open("/sd/rename", 4000)
        try {
            val body = JSONObject()
                .put("cat", category)
                .put("dir", dir)
                .put("from", from)
                .put("to", to)
                .toString()
                .toByteArray(Charsets.UTF_8)
            connection.post(body)
            if (connection.responseCode != 200) throw IOException("Rename failed")
            return connection.readBoardJson(512).optString("name", to)
        } finally {
            connection.disconnect()
        }
    }

    fun clearSdCategory(category: String): Int {
        val connection = open("/sd/delete", 8000)
        try {
            val body = JSONObject().put("cat", category).put("all", true).toString().toByteArray()
            connection.post(body)
            if (connection.responseCode != 200) throw IOException("Clear failed")
            return connection.readBoardJson(512).optInt("deleted", 0)
        } finally {
            connection.disconnect()
        }
    }

    fun uploadSharedFile(
        filename: String,
        contentLength: Long,
        input: InputStream,
        dir: String = "",
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): SdFileItem {
        val encName = encodeQuery(filename)
        val dirParam = if (dir.isNotEmpty()) "&dir=${encodeQuery(dir)}" else ""
        val connection = open("/sd/upload?name=$encName$dirParam", readTimeoutMs = 30000, connectTimeoutMs = 5000)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(contentLength)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            val buf = ByteArray(16384)
            var sent = 0L
            var lastReport = 0L
            connection.outputStream.use { out ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    sent += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 200 || sent >= contentLength) {
                        lastReport = now
                        onProgress(sent, contentLength)
                    }
                }
                out.flush()
            }
            if (connection.responseCode != 200) throw IOException("Upload failed (HTTP ${connection.responseCode})")
            val json = connection.readBoardJson(2048)
            return SdFileItem(
                category = json.optString("cat", "shared"),
                name = json.optString("name", filename),
                sizeBytes = json.optLong("size", sent),
                dir = dir
            )
        } finally {
            connection.disconnect()
        }
    }

    fun uploadOtaFirmware(
        contentLength: Long,
        input: InputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        if (contentLength < 65536L || contentLength > 6L * 1024L * 1024L) {
            throw IOException("펌웨어 크기가 올바르지 않습니다 (64KB~6MB)")
        }
        val firstChunk = ByteArray(16384)
        val firstRead = input.read(firstChunk)
        if (firstRead <= 0 || firstChunk[0] != 0xE9.toByte()) {
            throw IOException("올바른 ESP32 펌웨어(.bin) 이미지가 아닙니다")
        }
        val connection = open("/ota/upload", readTimeoutMs = 60000, connectTimeoutMs = 5000)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(contentLength)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            var sent = 0L
            var lastReport = 0L
            connection.outputStream.use { out ->
                out.write(firstChunk, 0, firstRead)
                sent += firstRead
                onProgress(sent, contentLength)
                while (true) {
                    val n = input.read(firstChunk)
                    if (n <= 0) break
                    out.write(firstChunk, 0, n)
                    sent += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 150 || sent >= contentLength) {
                        lastReport = now
                        onProgress(sent, contentLength)
                    }
                }
                out.flush()
            }
            if (connection.responseCode != 200) {
                throw IOException("OTA 실패 (HTTP ${connection.responseCode})")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun listNotes(): List<SharedNote> {
        val connection = open("/sd/notes", 4000)
        try {
            if (connection.responseCode != 200) throw IOException("Notes unavailable")
            return parseNotes(connection.readBoardJson(65536))
        } finally {
            connection.disconnect()
        }
    }

    fun addNote(title: String, text: String): List<SharedNote> =
        postNotes(JSONObject().put("action", "add").put("title", title).put("text", text))

    fun updateNote(id: Long, title: String, text: String): List<SharedNote> =
        postNotes(JSONObject().put("action", "update").put("id", id).put("title", title).put("text", text))

    fun deleteNote(id: Long): List<SharedNote> =
        postNotes(JSONObject().put("action", "delete").put("id", id))

    private fun postNotes(payload: JSONObject): List<SharedNote> {
        val connection = open("/sd/notes", 5000)
        try {
            connection.post(payload.toString().toByteArray(Charsets.UTF_8))
            if (connection.responseCode != 200) throw IOException("Notes update failed")
            return parseNotes(connection.readBoardJson(65536))
        } finally {
            connection.disconnect()
        }
    }

    private fun parseNotes(json: JSONObject): List<SharedNote> {
        val arr = json.optJSONArray("notes") ?: return emptyList()
        return List(arr.length()) { idx ->
            val obj = arr.getJSONObject(idx)
            SharedNote(
                id = obj.optLong("id", 0L),
                title = obj.optString("title", ""),
                text = obj.optString("text", ""),
                ts = obj.optLong("ts", 0L)
            )
        }
    }

    private fun parseSdStatus(json: JSONObject): SdStatus = SdStatus(
        mounted = json.optBoolean("mounted", false),
        totalMb = json.optLong("total_mb", 0L),
        usedMb = json.optLong("used_mb", 0L),
        freeMb = json.optLong("free_mb", 0L),
        mode = SdRecordMode.fromKey(json.optString("mode")),
        active = json.optBoolean("active", false),
        pausedForLive = json.optBoolean("paused_for_live", false),
        autoStart = json.optBoolean("auto_start", false),
        segmentSeconds = json.optInt("segment_s", 180),
        intervalSeconds = json.optInt("interval_s", 5),
        frames = json.optInt("frames", 0),
        elapsedSeconds = json.optInt("elapsed_s", 0),
        currentFile = json.optString("file", ""),
        error = json.optString("error", "")
    )

    private fun optionsRequest(body: ByteArray?): BoardOptions {
        val connection = open("/settings", 3000)
        try {
            if (body != null) connection.post(body)
            if (connection.responseCode != 200) throw IOException("Board settings unavailable")
            val json = connection.readBoardJson()
            return BoardOptions(
                lowBandwidth = json.getBoolean("low_bandwidth"),
                rotate180 = json.getBoolean("rotate_180"),
                resolution = VideoResolution.entries.firstOrNull { it.key == json.optString("resolution") } ?: VideoResolution.VGA,
                nightMode = json.optBoolean("night_mode", false)
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpURLConnection.post(body: ByteArray) {
        requestMethod = "POST"
        doOutput = true
        setFixedLengthStreamingMode(body.size)
        setRequestProperty("Content-Type", "application/json")
        outputStream.use { it.write(body) }
    }
}

internal fun HttpURLConnection.readBoardJson(maxBytes: Int = 512): JSONObject {
    val buf = ByteArray(2048)
    val out = ByteArrayOutputStream()
    inputStream.use { stream ->
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            if (out.size() + n > maxBytes) throw IOException("Response exceeds limit")
            out.write(buf, 0, n)
        }
    }
    if (out.size() == 0) throw IOException("Empty JSON response")
    return JSONObject(out.toByteArray().toString(Charsets.UTF_8))
}
