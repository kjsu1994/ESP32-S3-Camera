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
    private var displayed: Preview.Frame? = null
    private var waitingForNetwork = false
    private var visible = false
    private var previewOnReturn = false
    private var cooldownEndsAt = 0L
    private var message = "보드 Wi-Fi에 연결하세요"
    private var countdown = ""
    private val handler = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val current = preview
            current?.latest?.getAndSet(null)?.let { frame ->
                val first = displayed == null
                displayed = frame
                screen.showFrame(frame.bitmap)
                if (first) updateControls()
            }
            if (current != null && displayed != null && current.endsAt != 0L) {
                val remaining = remainingSeconds(current.endsAt, now)
                setStatus("실시간 영상", asClock(remaining))
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
            handler.postDelayed(this, if (preview != null) 100 else 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectivity = requireNotNull(getSystemService(ConnectivityManager::class.java))
        secrets = ConnectionSecret(this)
        password = secrets.read().orEmpty()
        rememberPassword = password.isNotEmpty()
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
        updateControls()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        if (previewOnReturn) {
            previewOnReturn = false
            network?.let(::startPreview)
        }
        handler.post(refresh)
    }

    override fun onStop() {
        visible = false
        handler.removeCallbacks(refresh)
        if (!isChangingConfigurations && (network != null || isFinishing)) releaseNetwork()
        super.onStop()
    }

    override fun onDestroy() {
        releaseNetwork()
        super.onDestroy()
    }

    private fun requestStart() {
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
        val connected = network
        if (connected == null) {
            showSettings(false)
            return
        }
        AlertDialog.Builder(this).setTitle("설정")
            .setItems(arrayOf("보드 설정", "Wi-Fi 설정")) { _, option ->
                if (option == 0) showBoardSettings(connected) else showSettings(false)
            }
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
                val low = addOption(content, "전송 절전 · 최대 3fps", current.lowBandwidth)
                val rotate = addOption(content, "영상 180° 회전", current.rotate180)
                val dim = addOption(content, "OLED 밝기 항상 낮게", current.dimOled)
                hint.text = "전송 속도와 회전은 다음 시청부터 적용됩니다"
                save.isEnabled = true
                save.setOnClickListener {
                    val requested = BoardOptions(low.isChecked, rotate.isChecked, dim.isChecked)
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

    private fun requestBoardNetwork() {
        if (waitingForNetwork || network != null) return
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(available: Network) = runOnUiThread {
                if (callback !== this || isDestroyed) return@runOnUiThread
                waitingForNetwork = false
                network = available
                if (visible) startPreview(available) else previewOnReturn = true
            }

            override fun onUnavailable() = runOnUiThread {
                if (callback !== this) return@runOnUiThread
                releaseNetwork()
                setStatus("연결 실패. 보드 전원과 Wi-Fi 비밀번호를 확인하세요")
            }

            override fun onLost(lost: Network) = runOnUiThread {
                if (callback !== this || network != lost) return@runOnUiThread
                releaseNetwork()
                setStatus("보드 Wi-Fi 연결이 끊어졌습니다")
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
            setStatus("Android의 Wi-Fi 연결 요청을 승인하세요")
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
        val session = Preview(available)
        preview = session
        displayed = null
        screen.showFrame(null)
        setStatus("카메라 시작 중")
        updateControls()
        session.start { result ->
            runOnUiThread {
                if (preview !== session) return@runOnUiThread
                preview = null
                val reason = when (result.reason) {
                    "limit" -> "시청 시간이 끝났습니다"
                    "camera" -> "카메라 오류"
                    "cooldown" -> "카메라 냉각 중"
                    "stopped" -> "시청을 중지했습니다"
                    else -> "영상 연결이 끊어졌습니다"
                }
                setCooldown(reason, result.cooldownSeconds)
            }
        }
    }

    private fun stopPreview() {
        val session = preview ?: return
        preview = null
        session.stop(requestStop = true)
        setCooldown("시청을 중지했습니다", 60)
    }

    private fun setCooldown(reason: String, seconds: Int) {
        cooldownEndsAt = if (seconds > 0) SystemClock.elapsedRealtime() + seconds * 1000L else 0L
        setStatus(reason, if (seconds > 0) asClock(seconds.toLong()) else "")
        updateControls()
    }

    private fun releaseNetwork() {
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
        waitingForNetwork = false
        previewOnReturn = false
        cooldownEndsAt = 0L
        setStatus("보드 Wi-Fi에 연결하세요")
        updateControls()
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
        screen.showControls(network != null, !waitingForNetwork && preview == null && cooldownEndsAt == 0L,
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
