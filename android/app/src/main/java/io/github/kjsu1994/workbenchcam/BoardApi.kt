package io.github.kjsu1994.workbenchcam

import android.net.Network
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
    val resolution: VideoResolution
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
    val uptimeSeconds: Long = 0L
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
    val sizeBytes: Long
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
                uptimeSeconds = json.optLong("uptime_s", 0L)
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

    fun listSdFiles(category: String = "all"): List<SdFileItem> {
        val encCat = URLEncoder.encode(category, "UTF-8")
        val connection = open("/sd/files?cat=$encCat", 5000)
        try {
            if (connection.responseCode != 200) throw IOException("SD files unavailable")
            val json = connection.readBoardJson(65536)
            val arr = json.optJSONArray("files") ?: return emptyList()
            return List(arr.length()) { idx ->
                val obj = arr.getJSONObject(idx)
                SdFileItem(
                    category = obj.optString("cat"),
                    name = obj.optString("name"),
                    sizeBytes = obj.optLong("size", 0L)
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    fun openSdFile(category: String, name: String, readTimeoutMs: Int = 10000): HttpURLConnection {
        val encCat = URLEncoder.encode(category, "UTF-8")
        val encName = URLEncoder.encode(name, "UTF-8")
        return open("/sd/file?cat=$encCat&name=$encName", readTimeoutMs)
    }

    fun deleteSdFile(category: String, name: String) {
        val connection = open("/sd/delete", 4000)
        try {
            val body = JSONObject().put("cat", category).put("name", name).toString().toByteArray()
            connection.post(body)
            if (connection.responseCode !in 200..204) throw IOException("Delete failed")
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
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): SdFileItem {
        val encName = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        val connection = open("/sd/upload?name=$encName", readTimeoutMs = 30000, connectTimeoutMs = 5000)
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
                sizeBytes = json.optLong("size", sent)
            )
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
            return BoardOptions(json.getBoolean("low_bandwidth"), json.getBoolean("rotate_180"),
                VideoResolution.entries.firstOrNull { it.key == json.optString("resolution") } ?: VideoResolution.VGA)
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
