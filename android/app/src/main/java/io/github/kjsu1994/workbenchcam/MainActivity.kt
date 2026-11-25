package io.github.kjsu1994.workbenchcam

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.RadioGroup.LayoutParams
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var connectivity: ConnectivityManager
    private lateinit var screen: CameraScreen
    private lateinit var secrets: ConnectionSecret
    private var password = ""
    private var rememberPassword = false
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var network: Network? = null
    private var preview: Preview? = null
    private var stoppingPreview = false
    private var displayed: Preview.Frame? = null
    private var waitingForNetwork = false
    private var visible = false
    private var previewOnReturn = false
    private var reconnectAttempted = false
    private var streamRetries = 0
    private var telemetryEnabled = false
    private var telemetryPending = false
    private var nextTelemetryAt = 0L
    private var telemetryText: String? = null
    private var cooldownEndsAt = 0L
    private var message = "보드 Wi-Fi에 연결하세요"
    private var countdown = ""
    private val framePending = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val retryPreview = Runnable {
        if (visible && preview == null && !stoppingPreview) {
            network?.let(::startPreview)
        }
    }
    private val renderFrame = Runnable {
        framePending.set(false)
        val current = preview ?: return@Runnable
        val frame = current.latest.getAndSet(null) ?: return@Runnable
        val first = displayed == null
        displayed = frame
        screen.showFrame(frame.bitmap)
        if (first) {
            reconnectAttempted = false
            streamRetries = 0
            updateControls()
        }
        val timer = if (current.endsAt == 0L) "" else asClock(remainingSeconds(current.endsAt, SystemClock.elapsedRealtime()))
        setStatus("실시간 영상", timer)
    }
    private val pauseInBackground = Runnable {
        if (!visible && preview != null) {
            stopPreview()
            setStatus("백그라운드에서 영상을 중지했습니다", countdown)
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (telemetryEnabled && !telemetryPending && now >= nextTelemetryAt) {
                network?.let { refreshTelemetry(it, now) }
            }
            val current = preview
            if (current != null && displayed != null) {
                val timer = if (current.endsAt == 0L) "" else asClock(remainingSeconds(current.endsAt, now))
                setStatus("실시간 영상", timer)
            } else if (cooldownEndsAt != 0L) {
                val remaining = remainingSeconds(cooldownEndsAt, now)
                if (remaining == 0L) {
                    cooldownEndsAt = 0L
                    setStatus("다시 볼 수 있습니다")
                    updateControls()
                } else {
                    setStatus(message, asClock(remaining))
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectivity = requireNotNull(getSystemService(ConnectivityManager::class.java))
        secrets = ConnectionSecret(this)
        password = secrets.read().orEmpty()
        rememberPassword = password.isNotEmpty()
        telemetryEnabled = getPreferences(MODE_PRIVATE).getBoolean("board_telemetry", false)
        createScreen(resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        createScreen(newConfig)
    }

    private fun createScreen(config: Configuration) {
        screen = CameraScreen(this, config, ::requestStart, ::stopPreview, ::savePhoto, ::showSettingsMenu)
        setContentView(screen.root)
        screen.root.requestApplyInsets()
        screen.showFrame(displayed?.bitmap)
        screen.showStatus(message, countdown)
        renderTelemetry()
        updateControls()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        handler.removeCallbacks(pauseInBackground)
        if (previewOnReturn) {
            previewOnReturn = false
            network?.let(::startPreview)
        }
        handler.post(refresh)
    }

    override fun onStop() {
        visible = false
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(retryPreview)
        if (isFinishing) {
            releaseNetwork()
        } else {
            handler.postDelayed(pauseInBackground, 2000)
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(pauseInBackground)
        handler.removeCallbacks(retryPreview)
        handler.removeCallbacks(renderFrame)
        releaseNetwork()
        super.onDestroy()
    }

    private fun requestStart() {
        reconnectAttempted = false
        streamRetries = 0
        handler.removeCallbacks(retryPreview)
        network?.let {
            startPreview(it)
            return
        }
        if (password.isEmpty()) {
            showSettings(true)
            return
        }
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (checkSelfPermission(permissions.first()) == PackageManager.PERMISSION_GRANTED) {
            requestBoardNetwork()
        } else {
            requestPermissions(permissions, 10)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != 10) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            requestBoardNetwork()
        } else {
            setStatus("Wi-Fi 연결 권한을 허용한 뒤 다시 시도하세요")
        }
    }

    private fun showSettingsMenu() {
        val choices = mutableListOf<Pair<String, () -> Unit>>()
        network?.let { connected -> choices += "보드 설정" to { showBoardSettings(connected) } }
        choices += "Wi-Fi 설정" to { showSettings(false) }
        choices += (if (telemetryEnabled) "온도·전력 숨기기" else "온도·전력 표시") to {
            telemetryEnabled = !telemetryEnabled
            getPreferences(MODE_PRIVATE).edit().putBoolean("board_telemetry", telemetryEnabled).apply()
            telemetryText = null
            nextTelemetryAt = 0
            renderTelemetry()
        }
        AlertDialog.Builder(this).setTitle("설정")
            .setItems(choices.map { it.first }.toTypedArray()) { _, option -> choices[option].second() }
            .show()
    }

    private fun showBoardSettings(connected: Network) {
        val settings = BoardApi(connected)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(6))
        }
        val hint = TextView(this).apply { text = "보드 설정을 불러오는 중입니다" }
        content.addView(hint)
        val dialog = AlertDialog.Builder(this)
            .setTitle("보드 설정")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("닫기", null)
            .setPositiveButton("저장", null)
            .show()
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save.isEnabled = false
        Thread({
            val current = runCatching(settings::getOptions).getOrNull()
            runOnUiThread {
                if (!dialog.isShowing || isDestroyed) return@runOnUiThread
                if (current == null || network != connected) {
                    hint.text = "보드 설정을 불러올 수 없습니다"
                    return@runOnUiThread
                }
                val low = addOption(content, "전송 절전 · 최대 6fps", current.lowBandwidth)
                val rotate = addOption(content, "영상 180° 회전", current.rotate180)
                content.addView(TextView(this).apply {
                    text = "영상 해상도"
                    setPadding(0, dp(12), 0, 0)
                })
                val sizes = RadioGroup(this)
                val sizeIds = VideoResolution.entries.associateWith { size ->
                    val option = RadioButton(this).apply {
                        id = View.generateViewId()
                        text = size.label
                        minimumHeight = dp(48)
                    }
                    sizes.addView(option, LayoutParams(-1, -2))
                    option.id
                }
                sizes.check(sizeIds.getValue(current.resolution))
                content.addView(sizes)
                hint.text = "해상도, 전송 속도와 회전은 다음 시청부터 적용됩니다"
                save.isEnabled = true
                save.setOnClickListener {
                    val size = sizeIds.entries.first { it.value == sizes.checkedRadioButtonId }.key
                    val requested = BoardOptions(low.isChecked, rotate.isChecked, size)
                    save.isEnabled = false
                    Thread({
                        val saved = runCatching { settings.setOptions(requested) }.getOrNull() == requested
                        runOnUiThread {
                            if (!dialog.isShowing || isDestroyed) return@runOnUiThread
                            if (saved) {
                                dialog.dismiss()
                                Toast.makeText(this, "보드 설정을 저장했습니다", Toast.LENGTH_SHORT).show()
                            } else {
                                hint.text = "설정을 저장할 수 없습니다. 연결을 확인하세요"
                                save.isEnabled = true
                            }
                        }
                    }, "board-settings-save").start()
                }
            }
        }, "board-settings-load").start()
    }

    private fun addOption(parent: LinearLayout, label: String, checked: Boolean): CheckBox {
        return CheckBox(this).apply {
            text = label
            isChecked = checked
            minimumHeight = dp(48)
            parent.addView(this)
        }
    }

    private fun showSettings(connectAfterSave: Boolean) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(6))
        }
        content.addView(TextView(this).apply { text = "WorkbenchCam  ·  192.168.4.1" })
        val field = EditText(this).apply {
            id = View.generateViewId()
            hint = "Wi-Fi 비밀번호"
            setText(password)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            minimumHeight = dp(52)
        }
        content.addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        content.addView(CheckBox(this).apply {
            text = "비밀번호 표시"
            minimumHeight = dp(48)
            setOnCheckedChangeListener { _, checked ->
                val mode = if (checked) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    else InputType.TYPE_TEXT_VARIATION_PASSWORD
                field.inputType = InputType.TYPE_CLASS_TEXT or mode
                field.setSelection(field.text.length)
            }
        })
        val remember = CheckBox(this).apply {
            text = "이 휴대폰에 저장"
            isChecked = rememberPassword
            minimumHeight = dp(48)
        }
        content.addView(remember)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Wi-Fi 설정")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("취소", null)
            .setPositiveButton(if (connectAfterSave) "연결" else "저장", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = field.text.toString()
                if (value.length !in 8..63 || value.any { it !in ' '..'~' }) {
                    field.error = "영문, 숫자 또는 기호로 8–63자를 입력하세요"
                    return@setOnClickListener
                }
                if (remember.isChecked) {
                    if (runCatching { secrets.save(value) }.isFailure) {
                        field.error = "비밀번호를 저장할 수 없습니다. 저장 선택을 해제하고 연결할 수 있습니다."
                        return@setOnClickListener
                    }
                } else {
                    secrets.forget()
                }
                if (password != value && (network != null || waitingForNetwork)) releaseNetwork()
                password = value
                rememberPassword = remember.isChecked
                dialog.dismiss()
                if (connectAfterSave) requestStart()
            }
        }
        dialog.show()
    }

    private fun requestBoardNetwork(reconnecting: Boolean = false) {
        if (waitingForNetwork || network != null) return
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(available: Network) = runOnUiThread {
                if (callback !== this || isDestroyed) return@runOnUiThread
                waitingForNetwork = false
                network = available
                nextTelemetryAt = 0
                renderTelemetry()
                if (visible) startPreview(available) else previewOnReturn = true
            }

            override fun onUnavailable() = runOnUiThread {
                if (callback !== this) return@runOnUiThread
                releaseNetwork()
                setStatus("연결 실패. 보드 전원과 Wi-Fi 비밀번호를 확인하세요")
            }

            override fun onLost(lost: Network) = runOnUiThread {
                if (callback !== this || network != lost) return@runOnUiThread
                val beforeLimit = preview?.let {
                    it.endsAt == 0L || SystemClock.elapsedRealtime() + 2000 < it.endsAt
                } == true
                val retry = visible && beforeLimit && !reconnectAttempted
                releaseNetwork()
                if (retry) {
                    reconnectAttempted = true
                    requestBoardNetwork(reconnecting = true)
                } else {
                    setStatus("보드 Wi-Fi 연결이 끊어졌습니다")
                }
            }
        }
        try {
            val wifi = WifiNetworkSpecifier.Builder()
                .setSsid("WorkbenchCam")
                .setWpa2Passphrase(password)
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(wifi)
                .build()
            callback = listener
            waitingForNetwork = true
            setStatus(if (reconnecting) "보드 Wi-Fi에 다시 연결하는 중입니다"
                else "Android의 Wi-Fi 연결 요청을 승인하세요")
            updateControls()
            connectivity.requestNetwork(request, listener, 30_000)
        } catch (_: SecurityException) {
            releaseNetwork()
            setStatus("Wi-Fi와 연결 권한 설정을 확인하세요")
        } catch (_: IllegalArgumentException) {
            releaseNetwork()
            setStatus("Wi-Fi 비밀번호와 설정을 확인하세요")
        }
    }

    private fun startPreview(available: Network) {
        handler.removeCallbacks(retryPreview)
        handler.removeCallbacks(renderFrame)
        framePending.set(false)
        val session = Preview(available)
        preview = session
        displayed = null
        screen.showFrame(null)
        setStatus("카메라 시작 중")
        updateControls()
        session.start(
            onFrame = {
                if (framePending.compareAndSet(false, true)) {
                    handler.post(renderFrame)
                }
            },
            onEnd = { result ->
                runOnUiThread {
                    if (preview !== session) return@runOnUiThread
                    preview = null
                    handler.removeCallbacks(renderFrame)
                    framePending.set(false)
                    val retryable = result.reason in setOf("transport", "disconnected", "stream_ended", "camera")
                    if (visible && network == available && !stoppingPreview && retryable && streamRetries < 3) {
                        streamRetries += 1
                        setStatus("영상에 다시 연결하는 중입니다 ($streamRetries/3)")
                        updateControls()
                        handler.postDelayed(retryPreview, 800)
                        return@runOnUiThread
                    }
                    val reason = when (result.reason) {
                        "limit" -> "시청 시간이 끝났습니다"
                        "camera" -> "카메라 오류"
                        "cooldown" -> "카메라 냉각 중"
                        "stopped" -> "시청을 중지했습니다"
                        "stream_ended" -> "영상이 종료됐습니다"
                        else -> "영상 수신이 중단됐습니다"
                    }
                    setCooldown(reason, result.cooldownSeconds)
                }
            }
        )
    }

    private fun stopPreview() {
        handler.removeCallbacks(retryPreview)
        handler.removeCallbacks(renderFrame)
        framePending.set(false)
        streamRetries = 0
        val session = preview ?: return
        preview = null
        stoppingPreview = true
        session.stop(requestStop = true) {
            runOnUiThread {
                stoppingPreview = false
                if (!isDestroyed) updateControls()
            }
        }
        setCooldown("시청을 중지했습니다", 0)
    }

    private fun setCooldown(reason: String, seconds: Int) {
        cooldownEndsAt = if (seconds > 0) SystemClock.elapsedRealtime() + seconds * 1000L else 0L
        setStatus(reason, if (seconds > 0) asClock(seconds.toLong()) else "")
        updateControls()
    }

    private fun releaseNetwork() {
        handler.removeCallbacks(retryPreview)
        handler.removeCallbacks(renderFrame)
        framePending.set(false)
        streamRetries = 0
        preview?.stop()
        preview = null
        val listener = callback
        callback = null
        if (listener != null) {
            try {
                connectivity.unregisterNetworkCallback(listener)
            } catch (_: IllegalArgumentException) {
                // Android may have released a request that already timed out.
            }
        }
        network = null
        telemetryText = null
        nextTelemetryAt = 0
        renderTelemetry()
        waitingForNetwork = false
        previewOnReturn = false
        cooldownEndsAt = 0L
        setStatus("보드 Wi-Fi에 연결하세요")
        updateControls()
    }

    private fun refreshTelemetry(connected: Network, now: Long) {
        telemetryPending = true
        nextTelemetryAt = now + 5000
        Thread({
            val sample = runCatching { BoardApi(connected).getTelemetry() }.getOrNull()
            runOnUiThread {
                telemetryPending = false
                if (isDestroyed || !telemetryEnabled || network != connected) return@runOnUiThread
                telemetryText = if (sample == null) {
                    "보드 상태를 읽을 수 없습니다"
                } else {
                    val temperature = sample.chipTempC?.let { String.format(Locale.getDefault(), "%.1f °C", it) } ?: "측정 불가"
                    val power = sample.powerMw?.let { "$it mW" } ?: "센서 미연결"
                    "칩 내부 온도 $temperature\n소비전력 $power"
                }
                renderTelemetry()
            }
        }, "board-telemetry").start()
    }

    private fun renderTelemetry() {
        screen.showTelemetry(if (!telemetryEnabled) null else telemetryText ?:
            if (network == null) "보드에 연결하면 온도와 전력을 읽습니다" else "보드 상태 읽는 중")
    }

    private fun savePhoto() {
        val jpeg = displayed?.jpeg ?: return
        Thread({
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "WorkbenchCam_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WorkbenchCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val saved = try {
                val uri = checkNotNull(contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                try {
                    checkNotNull(contentResolver.openOutputStream(uri)).use { it.write(jpeg) }
                    val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                    check(contentResolver.update(uri, ready, null, null) == 1)
                } catch (failure: Exception) {
                    contentResolver.delete(uri, null, null)
                    throw failure
                }
                true
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (!isDestroyed) Toast.makeText(this, if (saved) "사진을 저장했습니다" else "사진을 저장하지 못했습니다",
                    Toast.LENGTH_SHORT).show()
            }
        }, "camera-photo").start()
    }

    private fun updateControls() {
        if (preview != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screen.showControls(network != null, !waitingForNetwork && !stoppingPreview && preview == null && cooldownEndsAt == 0L,
            preview != null, displayed != null)
    }

    private fun setStatus(label: String, time: String = "") {
        message = label
        countdown = time
        screen.showStatus(message, countdown)
    }

    private fun remainingSeconds(deadline: Long, now: Long) = ((deadline - now + 999) / 1000).coerceAtLeast(0L)
    private fun asClock(seconds: Long) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
