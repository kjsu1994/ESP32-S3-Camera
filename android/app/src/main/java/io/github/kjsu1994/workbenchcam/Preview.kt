package io.github.kjsu1994.workbenchcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Network
import android.os.SystemClock
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicReference

internal class Preview(network: Network) {
    private val board = BoardApi(network)
    data class Frame(val jpeg: ByteArray, val bitmap: Bitmap)
    data class Result(val reason: String, val cooldownSeconds: Int)

    val latest = AtomicReference<Frame?>(null)
    @Volatile var endsAt = 0L
        private set
    @Volatile private var stopped = false
    private var active: HttpURLConnection? = null

    fun start(onFrame: () -> Unit, onEnd: (Result) -> Unit) {
        Thread({
            var result: Result? = null
            var cleanEnd = false
            try {
                val startedAt = SystemClock.elapsedRealtime()
                val stream = open(":81/stream")
                try {
                    if (stream.responseCode != 200) {
                        val retry = stream.getHeaderField("Retry-After")?.toIntOrNull() ?: 0
                        result = when (stream.responseCode) {
                            429 -> Result("cooldown", retry.coerceIn(0, 60))
                            503 -> Result("camera", 0)
                            else -> throw IOException("Unexpected stream response")
                        }
                    } else {
                        val duration = stream.getHeaderField("X-Stream-Limit-Seconds")?.toIntOrNull()
                        if (duration != null && duration > 0) {
                            endsAt = startedAt + 1000L * duration.coerceAtMost(900)
                        }
                        stream.inputStream.use { input ->
                            val reader = MjpegReader(input)
                            while (!stopped) {
                                val jpeg = reader.nextFrame()
                                if (jpeg == null) {
                                    cleanEnd = true
                                    break
                                }
                                val bitmap = decode(jpeg) ?: continue
                                latest.set(Frame(jpeg, bitmap))
                                onFrame()
                            }
                        }
                    }
                } finally {
                    close(stream)
                }
            } catch (_: IOException) {
                // The status request below distinguishes a camera stop from a lost connection.
            } finally {
                if (!stopped) {
                    val status = result ?: readStatus()
                    val nearLimit = endsAt > 0L && SystemClock.elapsedRealtime() + 2000 >= endsAt
                    onEnd(Result(streamEndReason(status?.reason, cleanEnd, nearLimit),
                        status?.cooldownSeconds ?: if (nearLimit) 60 else 0))
                }
            }
        }, "camera-preview").start()
    }

    fun stop(requestStop: Boolean = false, onClosed: () -> Unit = {}) {
        val connection = synchronized(this) {
            stopped = true
            active
        }
        if (requestStop || connection != null) {
            Thread({
                try {
                    if (requestStop) runCatching { board.stop() }
                    connection?.disconnect()
                } finally {
                    onClosed()
                }
            }, "camera-close").start()
        }
    }

    @Synchronized
    private fun open(path: String, readTimeoutMs: Int = 6000): HttpURLConnection {
        if (stopped) throw IOException("Preview stopped")
        val connection = board.open(path, readTimeoutMs)
        active = connection
        return connection
    }

    private fun close(connection: HttpURLConnection) {
        connection.disconnect()
        synchronized(this) {
            if (active === connection) active = null
        }
    }

    private fun readStatus(): Result? {
        return try {
            val status = open("/status", 2000)
            try {
                if (status.responseCode != 200) throw IOException("Unavailable status")
                val json = status.readBoardJson()
                val seconds = if (json.optString("state") == "cooldown") {
                    json.optInt("remaining_s").coerceIn(0, 60)
                } else 0
                Result(json.optString("reason"), seconds)
            } finally {
                close(status)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth !in 1..1280 || bounds.outHeight !in 1..720) return null
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    }
}

internal fun streamEndReason(reported: String?, cleanEnd: Boolean, nearLimit: Boolean): String {
    if (reported in setOf("limit", "camera", "cooldown", "stopped", "disconnected")) return requireNotNull(reported)
    if (nearLimit) return "limit"
    return if (cleanEnd) "stream_ended" else "transport"
}
