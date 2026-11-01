package com.example.indoornavigation

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import android.os.Build
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.media.AudioManager
import android.webkit.JavascriptInterface
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var wifiManager: WifiManager
    private lateinit var navigationWebView: WebView
    private lateinit var scanButton: Button
    private lateinit var statusText: TextView
    private lateinit var sensorManager: SensorManager

    private lateinit var audioManager: AudioManager
    private lateinit var cameraManager: CameraManager
    private var vibrator: Vibrator? = null

    private var sosMusicVolumeBefore: Int? = null
    private var sosAlarmVolumeBefore: Int? = null
    private var sosVolumeBoosted = false
    private var sosActive = false

    private val sosHandler = Handler(Looper.getMainLooper())
    private var sosTorchCameraId: String? = null
    private var sosTorchOn = false

    private val sosTorchRunnable = object : Runnable {
        override fun run() {
            if (!sosActive) return

            val cameraId = sosTorchCameraId ?: return

            try {
                sosTorchOn = !sosTorchOn
                cameraManager.setTorchMode(cameraId, sosTorchOn)
            } catch (e: Exception) {
                android.util.Log.e("SOS_TORCH", "手電筒切換失敗", e)
                stopTorchBlinking()
                return
            }

            sosHandler.postDelayed(this, 320L)
        }
    }

    private var stepDetectorSensor: Sensor? = null
    private var stepCounterSensor: Sensor? = null
    private var rotationVectorSensor: Sensor? = null

    private var stepCounterBaseline: Float? = null
    private var stepCounterSessionSteps = 0
    private var detectorBaselineCount = 0

    private var testStepCount = 0
    private var currentHeadingDeg = 0f
    private var lastHeadingSentAt = 0L
    private var linearAccelerationSensor: Sensor? = null

    private var linearStepCount = 0
    private var lastLinearStepTime = 0L
    private var linearStepArmed = true
    private var latestStepCounterTotal: Float? = null

    private var hybridDeliveredSteps = 0
    private var hybridCounterActive = false
    private var hybridSessionStarted = false

    private val linearHighThreshold = 1.8f
    private val linearLowThreshold = 0.7f
    private val linearMinStepIntervalMs = 420L
    private val webUrl =
        "https://smart-underground-wifi-navigation.onrender.com/?sos_native=1"

    private var webPageReady = false

    private var lastRealSubmitAtMs: Long = 0L
    private val minRealSubmitIntervalMs = 8_000L

    private val autoScanIntervalMs = 40_000L
    private val scanHandler = Handler(Looper.getMainLooper())

    private val wifiScanFallbackDelayMs = 5_000L

    private val wifiScanFallbackRunnable = Runnable {
        if (webPageReady) {
            sendScanToWebView(false)
        }
    }

    private val autoScanRunnable = object : Runnable {
        override fun run() {

            if (webPageReady) {
                startWifiScan()
            }

            scanHandler.postDelayed(
                this,
                autoScanIntervalMs
            )
        }
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted && sosActive) {
                startTorchBlinking()
            } else if (!granted) {
                Toast.makeText(
                    this,
                    "未允許相機權限：SOS 聲音與震動仍會正常運作，但手電筒不會閃爍",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val fineGranted =
                permissions[
                    Manifest.permission.ACCESS_FINE_LOCATION
                ] == true

            if (fineGranted) {

                Toast.makeText(
                    this,
                    "定位權限已取得",
                    Toast.LENGTH_SHORT
                ).show()

                startAutoLocation()

            } else {

                Toast.makeText(
                    this,
                    "需要精確定位權限才能掃描 Wi-Fi",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val wifiScanReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                val success =
                    intent?.getBooleanExtra(
                        WifiManager.EXTRA_RESULTS_UPDATED,
                        false
                    ) ?: false

                scanHandler.removeCallbacks(
                    wifiScanFallbackRunnable
                )

                sendScanToWebView(success)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_main
        )
        sensorManager =
            getSystemService(Context.SENSOR_SERVICE) as SensorManager

        stepDetectorSensor =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_STEP_DETECTOR
            )
        stepCounterSensor =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_STEP_COUNTER
            )

        rotationVectorSensor =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_GAME_ROTATION_VECTOR
            )
        linearAccelerationSensor =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_LINEAR_ACCELERATION
            )

        wifiManager =
            applicationContext.getSystemService(
                Context.WIFI_SERVICE
            ) as WifiManager

        audioManager =
            getSystemService(
                Context.AUDIO_SERVICE
            ) as AudioManager

        cameraManager =
            getSystemService(
                Context.CAMERA_SERVICE
            ) as CameraManager

        vibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager =
                    getSystemService(
                        Context.VIBRATOR_MANAGER_SERVICE
                    ) as VibratorManager

                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(
                    Context.VIBRATOR_SERVICE
                ) as Vibrator
            }

        navigationWebView =
            findViewById(
                R.id.navigationWebView
            )

        scanButton =
            findViewById(
                R.id.scanButton
            )

        statusText =
            findViewById(
                R.id.statusText
            )

        setupWebView()

        checkLocationPermission()

        scanButton.setOnClickListener {

            resetHybridPdrBaseline()
            startWifiScan()
        }
    }

    private fun setupWebView() {

        navigationWebView.settings.javaScriptEnabled =
            true

        navigationWebView.settings.domStorageEnabled =
            true

        navigationWebView.addJavascriptInterface(
            SosJsBridge(),
            "AndroidSOS"
        )

        navigationWebView.webViewClient =
            object : WebViewClient() {

                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {

                    super.onPageFinished(
                        view,
                        url
                    )

                    webPageReady = true

                    statusText.text =
                        " 地圖完成載入，自動 REAL 定位準備中"

                    navigationWebView.evaluateJavascript(
                        """
                        if (
                            typeof setLocationMode === 'function'
                        ) {
                            setLocationMode('real');
                        }
                        """.trimIndent(),
                        null
                    )

                    startAutoLocation()
                }
            }

        navigationWebView.loadUrl(
            webUrl
        )
    }

    private fun startAutoLocation() {

        if (!webPageReady) {
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        scanHandler.removeCallbacks(
            autoScanRunnable
        )

        scanHandler.removeCallbacks(
            wifiScanFallbackRunnable
        )

        statusText.text =
            " 自動 REAL 定位已啟動，每 40 秒要求一次 Wi-Fi 掃描"

        scanHandler.postDelayed(
            autoScanRunnable,
            2_000L
        )
    }
    private fun resetHybridPdrBaseline() {

        stepCounterBaseline =
            latestStepCounterTotal

        stepCounterSessionSteps = 0
        detectorBaselineCount = testStepCount

        linearStepCount = 0
        lastLinearStepTime = 0L
        linearStepArmed = true

        hybridDeliveredSteps = 0
        hybridCounterActive = false
        hybridSessionStarted = true

        if (webPageReady) {

            val js =
                "if (typeof calibratePdrHeading === 'function') {" +
                        "calibratePdrHeading(" +
                        currentHeadingDeg.toString() +
                        ");" +
                        "}"

            navigationWebView.evaluateJavascript(
                js,
                null
            )
        }

        android.util.Log.d(
            "PDR_HYBRID",
            "RESET counterBaseline=$stepCounterBaseline heading=$currentHeadingDeg"
        )
    }

    private fun sendHybridPdrStep() {

        hybridDeliveredSteps++
        statusText.text =
            " PDR $hybridDeliveredSteps 步 |  ${currentHeadingDeg.toInt()}°"

        if (webPageReady) {

            val js =
                "if (typeof submitPdrStep === 'function') {" +
                        "submitPdrStep(" +
                        currentHeadingDeg.toString() +
                        "," +
                        hybridDeliveredSteps.toString() +
                        ");" +
                        "}"

            navigationWebView.evaluateJavascript(
                js,
                null
            )
        }

        android.util.Log.d(
            "PDR_HYBRID",
            "MOVE=$hybridDeliveredSteps heading=$currentHeadingDeg"
        )
    }

    override fun onStart() {
        super.onStart()
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            stepDetectorSensor?.let { sensor ->
                stepCounterSensor?.let { sensor ->

                    sensorManager.registerListener(
                        this,
                        sensor,
                        SensorManager.SENSOR_DELAY_NORMAL,
                        0
                    )
                }

                sensorManager.registerListener(
                    this,
                    sensor,
                    SensorManager.SENSOR_DELAY_NORMAL
                )
            }
            rotationVectorSensor?.let { sensor ->

                sensorManager.registerListener(
                    this,
                    sensor,
                    SensorManager.SENSOR_DELAY_UI
                )
            }
            linearAccelerationSensor?.let { sensor ->

                sensorManager.registerListener(
                    this,
                    sensor,
                    SensorManager.SENSOR_DELAY_GAME
                )
            }
        }

        val filter =
            IntentFilter(
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION
            )

        ContextCompat.registerReceiver(
            this,
            wifiScanReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (webPageReady) {
            startAutoLocation()
        }
    }

    override fun onStop() {
        super.onStop()
        sensorManager.unregisterListener(this)

        scanHandler.removeCallbacks(
            autoScanRunnable
        )
        scanHandler.removeCallbacks(
            wifiScanFallbackRunnable
        )

        try {

            unregisterReceiver(
                wifiScanReceiver
            )

        } catch (_: Exception) {
        }
    }

    private fun checkLocationPermission() {

        val permissionsToRequest =
            mutableListOf<String>()

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(
                Manifest.permission.ACTIVITY_RECOGNITION
            )
        }

        if (permissionsToRequest.isNotEmpty()) {

            locationPermissionLauncher.launch(
                permissionsToRequest.toTypedArray()
            )
        }
    }

    private fun startWifiScan() {

        if (!webPageReady) {

            statusText.text =
                "網頁尚未載入完成"

            return
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            statusText.text =
                "尚未取得定位權限"

            checkLocationPermission()

            return
        }

        statusText.text =
            "正在取得 Wi-Fi 定位資料..."

        try {

            scanHandler.removeCallbacks(
                wifiScanFallbackRunnable
            )

            val started =
                wifiManager.startScan()

            if (started) {
                scanHandler.postDelayed(
                    wifiScanFallbackRunnable,
                    wifiScanFallbackDelayMs
                )
            } else {

                statusText.text =
                    " 主動掃描受限，使用目前可取得的 Wi-Fi scan"

                sendScanToWebView(false)
            }

        } catch (e: SecurityException) {

            statusText.text =
                "Wi-Fi 權限錯誤：${e.message}"
        }
    }

    private fun sendScanToWebView(
        isNewScan: Boolean
    ) {

        try {

            val results =
                wifiManager.scanResults
                    .sortedByDescending {
                        it.level
                    }

            if (results.isEmpty()) {

                statusText.text =
                    " 沒有取得任何 Wi-Fi AP"

                return
            }

            val nowMs =
                SystemClock.elapsedRealtime()

            val elapsedSinceLastSubmit =
                nowMs - lastRealSubmitAtMs

            if (
                lastRealSubmitAtMs != 0L &&
                elapsedSinceLastSubmit < minRealSubmitIntervalMs
            ) {

                statusText.text =
                    " 略過過密 Wi-Fi 掃描 ${results.size} AP"

                return
            }

            lastRealSubmitAtMs = nowMs

            val signals =
                JSONArray()

            results.forEach { result ->

                val signal =
                    JSONObject()

                signal.put(
                    "bssid",
                    result.BSSID.lowercase()
                )

                signal.put(
                    "level",
                    result.level
                )

                signals.put(
                    signal
                )
            }

            val javascript =
                """
                (async function() {
                    try {

                        if (
                            typeof setLocationMode === 'function'
                        ) {
                            await setLocationMode('real');
                        }

                        if (
                            typeof window.submitRealWifiScan === 'function'
                        ) {

                            const result =
                                await window.submitRealWifiScan(
                                    $signals
                                );

                            return JSON.stringify(result);

                        } else {

                            return "submitRealWifiScan not found";
                        }

                    } catch (e) {

                        return "ERROR: " + e.toString();
                    }
                })();
                """.trimIndent()

            runOnUiThread {

                navigationWebView.evaluateJavascript(
                    javascript
                ) {

                    statusText.text =
                        if (isNewScan) {
                            " 新 Wi-Fi 掃描 ${results.size} AP  已送出定位"
                        } else {
                            " Wi-Fi 掃描可能為快取 ${results.size} AP  已送出定位"
                        }
                }
            }

        } catch (e: SecurityException) {

            statusText.text =
                "無法讀取 Wi-Fi：${e.message}"
        }
    }

    private inner class SosJsBridge {

        @JavascriptInterface
        fun startSosMode() {
            runOnUiThread {
                startNativeSos()
            }
        }

        @JavascriptInterface
        fun stopSosMode() {
            runOnUiThread {
                stopNativeSos()
            }
        }
    }

    private fun startNativeSos() {

        if (sosActive) return

        sosActive = true

        enableSosMaxVolume()
        startSosVibration()

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startTorchBlinking()
        } else {
            cameraPermissionLauncher.launch(
                Manifest.permission.CAMERA
            )
        }

        Toast.makeText(
            this,
            "SOS 緊急警報已啟動",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun stopNativeSos() {

        if (!sosActive && !sosVolumeBoosted) return

        sosActive = false

        stopSosVibration()
        stopTorchBlinking()
        restoreSosVolume()

        Toast.makeText(
            this,
            "SOS 緊急警報已停止",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun enableSosMaxVolume() {

        if (!::audioManager.isInitialized || sosVolumeBoosted) {
            return
        }

        try {
            sosMusicVolumeBefore =
                audioManager.getStreamVolume(
                    AudioManager.STREAM_MUSIC
                )

            sosAlarmVolumeBefore =
                audioManager.getStreamVolume(
                    AudioManager.STREAM_ALARM
                )

            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audioManager.getStreamMaxVolume(
                    AudioManager.STREAM_MUSIC
                ),
                0
            )

            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(
                    AudioManager.STREAM_ALARM
                ),
                0
            )

            sosVolumeBoosted = true

        } catch (e: Exception) {

            android.util.Log.e(
                "SOS_AUDIO",
                "SOS 音量調整失敗",
                e
            )
        }
    }

    private fun restoreSosVolume() {

        if (!::audioManager.isInitialized || !sosVolumeBoosted) {
            return
        }

        try {
            sosMusicVolumeBefore?.let { volume ->
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    volume.coerceIn(
                        0,
                        audioManager.getStreamMaxVolume(
                            AudioManager.STREAM_MUSIC
                        )
                    ),
                    0
                )
            }

            sosAlarmVolumeBefore?.let { volume ->
                audioManager.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    volume.coerceIn(
                        0,
                        audioManager.getStreamMaxVolume(
                            AudioManager.STREAM_ALARM
                        )
                    ),
                    0
                )
            }

        } catch (e: Exception) {

            android.util.Log.e(
                "SOS_AUDIO",
                "SOS 音量恢復失敗",
                e
            )

        } finally {

            sosMusicVolumeBefore = null
            sosAlarmVolumeBefore = null
            sosVolumeBoosted = false
        }
    }

    private fun startSosVibration() {

        val currentVibrator = vibrator ?: return

        if (!currentVibrator.hasVibrator()) {
            return
        }

        val pattern =
            longArrayOf(
                0L,
                450L,
                150L,
                450L,
                150L,
                800L,
                250L
            )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                currentVibrator.vibrate(
                    VibrationEffect.createWaveform(
                        pattern,
                        0
                    )
                )

            } else {

                @Suppress("DEPRECATION")
                currentVibrator.vibrate(
                    pattern,
                    0
                )
            }
        } catch (e: Exception) {

            android.util.Log.e(
                "SOS_VIBRATE",
                "SOS 震動啟動失敗",
                e
            )
        }
    }

    private fun stopSosVibration() {

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            android.util.Log.e(
                "SOS_VIBRATE",
                "SOS 震動停止失敗",
                e
            )
        }
    }

    private fun findTorchCameraId(): String? {

        if (!::cameraManager.isInitialized) {
            return null
        }

        return try {

            cameraManager.cameraIdList.firstOrNull { cameraId ->

                val characteristics =
                    cameraManager.getCameraCharacteristics(
                        cameraId
                    )

                characteristics.get(
                    CameraCharacteristics.FLASH_INFO_AVAILABLE
                ) == true
            }

        } catch (e: Exception) {

            android.util.Log.e(
                "SOS_TORCH",
                "找不到可用閃光燈",
                e
            )

            null
        }
    }

    private fun startTorchBlinking() {

        if (!sosActive) return

        val cameraId =
            sosTorchCameraId
                ?: findTorchCameraId()
                ?: run {

                    Toast.makeText(
                        this,
                        "此裝置沒有可用的手電筒；SOS 聲音與震動仍會正常運作",
                        Toast.LENGTH_LONG
                    ).show()

                    return
                }

        sosTorchCameraId = cameraId

        sosHandler.removeCallbacks(
            sosTorchRunnable
        )

        sosTorchOn = false

        sosHandler.post(
            sosTorchRunnable
        )
    }

    private fun stopTorchBlinking() {

        sosHandler.removeCallbacks(
            sosTorchRunnable
        )

        val cameraId =
            sosTorchCameraId

        if (
            cameraId != null &&
            ::cameraManager.isInitialized
        ) {

            try {
                cameraManager.setTorchMode(
                    cameraId,
                    false
                )
            } catch (_: Exception) {
            }
        }

        sosTorchOn = false
    }

    private fun resetPdrStepBaseline() {

        stepCounterBaseline = null
        stepCounterSessionSteps = 0
        detectorBaselineCount = testStepCount
        linearStepCount = 0
        lastLinearStepTime = 0L
        linearStepArmed = true

        android.util.Log.d(
            "PDR_COUNTER",
            "RESET detectorBaseline=$detectorBaselineCount"
        )
    }
    override fun onSensorChanged(
        event: SensorEvent?
    ) {

        if (event == null) {
            return
        }

        when (event.sensor.type) {

            Sensor.TYPE_STEP_DETECTOR -> {

                testStepCount++

                val now =
                    System.currentTimeMillis()

                android.util.Log.d(
                    "PDR_TEST",
                    "STEP count=$testStepCount time=$now heading=$currentHeadingDeg"
                )
            }

            Sensor.TYPE_STEP_COUNTER -> {

                val total =
                    event.values[0]

                latestStepCounterTotal =
                    total

                if (
                    stepCounterBaseline != null &&
                    hybridSessionStarted
                ) {

                    stepCounterSessionSteps =
                        (total - stepCounterBaseline!!)
                            .toInt()
                            .coerceAtLeast(0)

                    if (stepCounterSessionSteps > 0) {

                        hybridCounterActive = true

                        val missingSteps =
                            stepCounterSessionSteps -
                                    hybridDeliveredSteps

                        if (missingSteps > 0) {

                            repeat(missingSteps) {
                                sendHybridPdrStep()
                            }
                        }
                    }

                    android.util.Log.d(
                        "PDR_HYBRID",
                        "COUNTER=$stepCounterSessionSteps " +
                                "DELIVERED=$hybridDeliveredSteps"
                    )
                }
            }

            Sensor.TYPE_GAME_ROTATION_VECTOR -> {

                val rotationMatrix =
                    FloatArray(9)

                val adjustedMatrix =
                    FloatArray(9)

                SensorManager.getRotationMatrixFromVector(
                    rotationMatrix,
                    event.values
                )

                val screenRotation =
                    windowManager.defaultDisplay.rotation

                when (screenRotation) {

                    Surface.ROTATION_0 -> {

                        System.arraycopy(
                            rotationMatrix,
                            0,
                            adjustedMatrix,
                            0,
                            9
                        )
                    }

                    Surface.ROTATION_90 -> {

                        SensorManager.remapCoordinateSystem(
                            rotationMatrix,
                            SensorManager.AXIS_Y,
                            SensorManager.AXIS_MINUS_X,
                            adjustedMatrix
                        )
                    }

                    Surface.ROTATION_180 -> {

                        SensorManager.remapCoordinateSystem(
                            rotationMatrix,
                            SensorManager.AXIS_MINUS_X,
                            SensorManager.AXIS_MINUS_Y,
                            adjustedMatrix
                        )
                    }

                    Surface.ROTATION_270 -> {

                        SensorManager.remapCoordinateSystem(
                            rotationMatrix,
                            SensorManager.AXIS_MINUS_Y,
                            SensorManager.AXIS_X,
                            adjustedMatrix
                        )
                    }
                }


                val topEast =
                    adjustedMatrix[1]

                val topNorth =
                    adjustedMatrix[4]

                val topHorizontal =
                    kotlin.math.sqrt(
                        (
                                topEast * topEast +
                                        topNorth * topNorth
                                ).toDouble()
                    ).toFloat()


                val screenForwardEast =
                    -adjustedMatrix[2]

                val screenForwardNorth =
                    -adjustedMatrix[5]

                val screenForwardHorizontal =
                    kotlin.math.sqrt(
                        (
                                screenForwardEast * screenForwardEast +
                                        screenForwardNorth * screenForwardNorth
                                ).toDouble()
                    ).toFloat()


                val headingEast: Float
                val headingNorth: Float
                val headingMode: String

                if (
                    topHorizontal >=
                    screenForwardHorizontal
                ) {

                    headingEast =
                        topEast

                    headingNorth =
                        topNorth

                    headingMode =
                        "TOP_EDGE"

                } else {

                    headingEast =
                        screenForwardEast

                    headingNorth =
                        screenForwardNorth

                    headingMode =
                        "SCREEN_NORMAL"
                }


                var heading = Math.toDegrees(
                    Math.atan2(headingEast.toDouble(), -headingNorth.toDouble())
                ).toFloat()

                if (heading < 0f) {
                    heading += 360f
                }

                currentHeadingDeg =
                    heading

                android.util.Log.d(
                    "PDR_HEADING",
                    "mode=$headingMode heading=$heading " +
                            "topH=$topHorizontal " +
                            "screenH=$screenForwardHorizontal"
                )
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {

                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                val magnitude =
                    kotlin.math.sqrt(
                        (x * x + y * y + z * z).toDouble()
                    ).toFloat()

                val now =
                    System.currentTimeMillis()

                if (magnitude <= linearLowThreshold) {
                    linearStepArmed = true
                }

                if (
                    linearStepArmed &&
                    magnitude >= linearHighThreshold &&
                    now - lastLinearStepTime >= linearMinStepIntervalMs
                ) {

                    linearStepCount++
                    lastLinearStepTime = now
                    linearStepArmed = false

                    android.util.Log.d(
                        "PDR_LINEAR",
                        "LINEAR=$linearStepCount magnitude=$magnitude"
                    )

                    if (
                        hybridSessionStarted &&
                        !hybridCounterActive &&
                        hybridDeliveredSteps < 5
                    ) {

                        sendHybridPdrStep()
                    }
                }
            }
        }
    }

    override fun onDestroy() {

        stopNativeSos()

        super.onDestroy()
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int
    ) {
    }
}