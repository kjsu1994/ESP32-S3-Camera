package io.github.kjsu1994.workbenchcam

import android.net.Network
import java.io.DataInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

internal enum class VideoResolution(val key: String, val label: String) {
    QVGA("qvga", "320 × 240"),
    VGA("vga", "640 × 480"),
    SVGA("svga", "800 × 600"),
    HD("hd", "1280 × 720")
}

internal data class BoardTelemetry(val chipTempC: Double?, val powerMw: Int?)

internal data class BoardOptions(
    val lowBandwidth: Boolean,
    val rotate180: Boolean,
    val resolution: VideoResolution
)

internal class BoardApi(private val network: Network) {
    fun open(path: String, readTimeoutMs: Int = 6000): HttpURLConnection {
        val connection = network.openConnection(URL("http://192.168.4.1" + path)) as HttpURLConnection
        connection.connectTimeout = 3000
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

internal fun HttpURLConnection.readBoardJson(): JSONObject {
    val size = contentLength
    if (size !in 1..128) throw IOException("Invalid response length")
    val data = ByteArray(size)
    DataInputStream(inputStream).use { it.readFully(data) }
    return JSONObject(data.toString(Charsets.UTF_8))
}
