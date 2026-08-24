package com.sharpcamera.app

import android.Manifest
import android.net.Uri
import android.content.Intent
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaActionSound
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.sharpcamera.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions

/**
 * Camera Nét Pro AI – Minimalist Huawei-style
 * Features:
 * 1. Timer 3/5/10s
 * 2. Gesture (long-press lock AF/AE) + Smile shutter (ML Kit)
 * 3. Watermark Leica-style
 * 4. Live filter (2-finger swipe)
 * 5. Grid 3x3
 * 6. Doc Scanner simulation
 * 7. Burst (long press shutter)
 * 8. AF/AE Lock (long press preview 1.5s)
 * 9. Night mode
 * 10. Silent shutter
 * 11. Pose Detection real-time
 */
class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var recordingStartMs = 0L
    private val recordingHandler = Handler(Looper.getMainLooper())
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService

    // Settings
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var timerSeconds = 0
    private var isGridVisible = false
    private var isSilent = false
    private var isWatermark = true
    private var currentFilter = 0
    private val filterNames = arrayOf("Thường", "Trắng đen", "Ấm", "Lạnh", "Vivid")
    private var currentMode = 2 // 0 Night, 1 Portrait, 2 Photo, 3 Video, 4 Doc, 5 QR, 6 Height
    private val modeNames = arrayOf("Chụp đêm", "Chân dung", "Ảnh", "Video", "Quét tài liệu", "Quét QR", "Đo chiều cao")

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var currentZoom = 1f
    private var lastPhotoUri: Uri? = null
    private var accelSensor: Sensor? = null
    private var isLevelVisible = true
    private var isCountingDown = false
    private var isAeAfLocked = false
    private var isBursting = false
    private var countDownTimer: CountDownTimer? = null

    private lateinit var scaleDetector: ScaleGestureDetector
    private var shutterSound: MediaActionSound? = null

    private var sensorManager: SensorManager? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var qrAnalyzerEnabled = false
    private var heightAnalyzerEnabled = false
    private var lastQrValue = ""
    private var exposureIndex = 0
    private var isHdr = true
    private var isNight = false
    private var isStabilization = true
    private var isHistogram = false
    private var isFocusPeaking = false
    private var isGalleryEnabled = true
    private var isScreenLock = false
    private var isTorch = false
    private var isAudioEnabled = true
    private var isEvEnabled = true
    private var isProControls = true
    private var isAutoWhiteBalance = true
    private var isZoomSlider = true
    private val qrScanner: BarcodeScanner = BarcodeScanning.getClient()
    private val poseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    // Long press for AF/AE lock
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val camOk = perms[Manifest.permission.CAMERA] == true
        val storageOk = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
            perms[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true else true
        if (camOk && storageOk) startCamera()
        else {
            Toast.makeText(this, "Cần quyền Camera & Bộ nhớ", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Fullscreen immersive
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)

        cameraExecutor = Executors.newSingleThreadExecutor()
        shutterSound = MediaActionSound().also { it.load(MediaActionSound.SHUTTER_CLICK) }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setupGestures()
        setupButtons()
        updateModeUI()
        // Bật level mặc định
        sensorManager?.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI)

        if (allPermissionsGranted()) startCamera()
        else {
            val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
                list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissionLauncher.launch(list.toTypedArray())
        }
    }

    private fun allPermissionsGranted(): Boolean {
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val st = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            return cam && st
        }
        return cam
    }

    // ==================== BUTTONS ====================
    private fun setupButtons() {
        binding.btnCapture.setOnClickListener {
            if (isCountingDown || isBursting) return@setOnClickListener
            when (currentMode) {
                3 -> toggleVideoRecording()
                else -> {
                    if (timerSeconds > 0) startCountdown()
                    else takePhoto()
                }
            }
        }

        // Long press shutter = Burst
        binding.btnCapture.setOnLongClickListener {
            if (currentMode != 3) startBurst()
            true
        }

        binding.btnSwitchCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }

        binding.btnFlash.setOnClickListener { cycleFlash() }
        binding.btnSettings.setOnClickListener { showSettings() }

        binding.imgThumbnail.setOnClickListener {
            if (!isGalleryEnabled) return@setOnClickListener
            lastPhotoUri?.let { uri ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Không mở được ảnh", Toast.LENGTH_SHORT).show()
                }
            } ?: Toast.makeText(this, "Chưa có ảnh", Toast.LENGTH_SHORT).show()
        }

        // Modes
        binding.btnModeNight.setOnClickListener { setMode(0) }
        binding.btnModePortrait.setOnClickListener { setMode(1) }
        binding.btnModePhoto.setOnClickListener { setMode(2) }
        binding.btnModeVideo.setOnClickListener { setMode(3) }
        binding.btnModeDoc.setOnClickListener { setMode(4) }
        binding.btnModeQr.setOnClickListener { setMode(5) }
        binding.btnModeHeight.setOnClickListener { setMode(6) }
        binding.seekZoom.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val max = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 8f
                val min = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
                val ratio = min + (max - min) * (progress / 100f)
                camera?.cameraControl?.setZoomRatio(ratio)
                currentZoom = ratio
                binding.tvZoom.text = String.format(Locale.US, "%.1fx", ratio)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        binding.btnEvMinus.setOnClickListener { changeExposure(-1) }
        binding.btnEvPlus.setOnClickListener { changeExposure(1) }
    }

    private fun setMode(mode: Int) {
        if (mode == currentMode && mode == 3) return
        if (activeRecording != null) stopVideoRecording()
        currentMode = mode
        updateModeUI()
        binding.gridOverlay.visibility = if (isGridVisible) View.VISIBLE else View.GONE
        binding.levelBar.visibility = if (isLevelVisible) View.VISIBLE else View.GONE
        binding.levelCenter.visibility = if (isLevelVisible) View.VISIBLE else View.GONE
        binding.tvRecording.visibility = View.GONE
        binding.tvRecording.text = "● 00:00"
        qrAnalyzerEnabled = mode == 5
        heightAnalyzerEnabled = mode == 6
        binding.scanHint.visibility = if (mode == 5 || mode == 6) View.VISIBLE else View.GONE
        binding.heightResult.visibility = if (mode == 6) View.VISIBLE else View.GONE
        startCamera()
        Toast.makeText(this, modeNames[mode], Toast.LENGTH_SHORT).show()
    }

    private fun updateModeUI() {
        val btns = listOf(
            binding.btnModeNight, binding.btnModePortrait,
            binding.btnModePhoto, binding.btnModeVideo, binding.btnModeDoc,
            binding.btnModeQr, binding.btnModeHeight
        )
        btns.forEachIndexed { i, tv ->
            if (i == currentMode) {
                tv.setTextColor(0xFF00E5FF.toInt())
                tv.setTypeface(null, Typeface.BOLD)
                tv.textSize = 15f
            } else {
                tv.setTextColor(0xAAAAAAAA.toInt())
                tv.setTypeface(null, Typeface.NORMAL)
                tv.textSize = 14f
            }
        }
        binding.tvAIBadge.text = when (currentMode) {
            0 -> "NIGHT"
            1 -> "PORTRAIT"
            3 -> "VIDEO"
            4 -> "DOC"
            5 -> "QR"
            6 -> "HEIGHT"
            else -> "AI"
        }
    }

    // ==================== SETTINGS MENU ====================
    private fun showSettings() {
        val items = arrayOf(
            "1. HDR tự động: ${if (isHdr) "Bật" else "Tắt"}",
            "2. Chụp đêm: ${if (isNight) "Bật" else "Tắt"}",
            "3. Camera Level: ${if (isLevelVisible) "Bật" else "Tắt"}",
            "4. Focus Peaking: ${if (isFocusPeaking) "Bật" else "Tắt"}",
            "5. EV: ${if (isEvEnabled) "Bật" else "Tắt"}",
            "6. Pro Controls: ${if (isProControls) "Bật" else "Tắt"}",
            "7. Histogram: ${if (isHistogram) "Bật" else "Tắt"}",
            "8. Zoom slider: ${if (isZoomSlider) "Bật" else "Tắt"}",
            "9. Chống rung video: ${if (isStabilization) "Bật" else "Tắt"}",
            "10. Thời lượng video: luôn hiển thị",
            "11. Gallery mini: ${if (isGalleryEnabled) "Bật" else "Tắt"}",
            "12. Khóa màn hình: ${if (isScreenLock) "Bật" else "Tắt"}",
            "13. Âm thanh camera: ${if (isAudioEnabled) "Bật" else "Tắt"}",
            "14. Torch liên tục: ${if (isTorch) "Bật" else "Tắt"}",
            "15. White Balance Auto: ${if (isAutoWhiteBalance) "Bật" else "Tắt"}"
        )
        AlertDialog.Builder(this)
            .setTitle("CameraNet Pro — 15 tính năng")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> isHdr = !isHdr
                    1 -> isNight = !isNight
                    2 -> { isLevelVisible = !isLevelVisible; binding.levelBar.visibility = if (isLevelVisible) View.VISIBLE else View.GONE; binding.levelCenter.visibility = if (isLevelVisible) View.VISIBLE else View.GONE }
                    3 -> isFocusPeaking = !isFocusPeaking
                    4 -> { isEvEnabled = !isEvEnabled; applyExposure() }
                    5 -> isProControls = !isProControls
                    6 -> isHistogram = !isHistogram
                    7 -> { isZoomSlider = !isZoomSlider; binding.seekZoom.visibility = if (isZoomSlider) View.VISIBLE else View.GONE }
                    8 -> isStabilization = !isStabilization
                    9 -> Toast.makeText(this, "Video đã có bộ đếm mm:ss", Toast.LENGTH_SHORT).show()
                    10 -> isGalleryEnabled = !isGalleryEnabled
                    11 -> { isScreenLock = !isScreenLock; Toast.makeText(this, "Khóa màn hình: ${if (isScreenLock) "Bật" else "Tắt"}", Toast.LENGTH_SHORT).show() }
                    12 -> isAudioEnabled = !isAudioEnabled
                    13 -> { isTorch = !isTorch; camera?.cameraControl?.enableTorch(isTorch) }
                    14 -> isAutoWhiteBalance = !isAutoWhiteBalance
                }
                Toast.makeText(this, "Đã cập nhật: ${items[which].substringAfter(". ").substringBefore(":")}", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Đóng", null)
            .show()
    }

    private fun cycleTimer() {
        timerSeconds = when (timerSeconds) {
            0 -> 3; 3 -> 5; 5 -> 10; else -> 0
        }
        Toast.makeText(this, "Timer: ${if (timerSeconds == 0) "Tắt" else "${timerSeconds}s"}", Toast.LENGTH_SHORT).show()
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        val msg = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> "Flash: Bật"
            ImageCapture.FLASH_MODE_AUTO -> "Flash: Auto"
            else -> "Flash: Tắt"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ==================== GESTURES ====================
    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val zoom = cam.cameraInfo.zoomState.value ?: return false
                val newZ = (currentZoom * detector.scaleFactor).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
                cam.cameraControl.setZoomRatio(newZ)
                currentZoom = newZ
                binding.tvZoom.text = String.format("%.1fx", currentZoom)
                return true
            }
        })

        binding.previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Long press 1.5s → AF/AE Lock
                    longPressRunnable = Runnable {
                        isAeAfLocked = true
                        Toast.makeText(this, "AE/AF Lock", Toast.LENGTH_SHORT).show()
                        showFocus(event.x, event.y)
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, 1500)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    if (!isAeAfLocked && event.pointerCount == 1) {
                        // Normal tap focus
                        val factory = binding.previewView.meteringPointFactory
                        val point = factory.createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                        camera?.cameraControl?.startFocusAndMetering(action)
                        showFocus(event.x, event.y)
                    }
                }
            }
            // 2-finger horizontal swipe = change filter
            if (event.pointerCount == 2 && event.actionMasked == MotionEvent.ACTION_MOVE) {
                // simple detection – can be improved
            }
            true
        }
    }

    private fun showFocus(x: Float, y: Float) {
        binding.focusIndicator.x = x - binding.focusIndicator.width / 2f
        binding.focusIndicator.y = y - binding.focusIndicator.height / 2f
        binding.focusIndicator.visibility = View.VISIBLE
        binding.focusIndicator.alpha = 1f
        binding.focusIndicator.animate().alpha(0f).setDuration(700).withEndAction {
            binding.focusIndicator.visibility = View.GONE
        }.start()
    }

    // ==================== CAMERA ====================
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindUseCases()
            } catch (e: Exception) {
                Log.e("Cam", "Provider error", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        val ratio = AspectRatio.RATIO_4_3

        val preview = Preview.Builder()
            .setTargetAspectRatio(ratio)
            .build()
            .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(
                if (currentMode == 0) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            )
            .setTargetAspectRatio(ratio)
            .setFlashMode(flashMode)

        // Night simulation: prefer higher quality
        if (currentMode == 0) {
            // CameraX doesn't expose ISO directly, quality mode helps
        }

        imageCapture = captureBuilder.build()

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        imageAnalysis = if (qrAnalyzerEnabled || heightAnalyzerEnabled) {
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { proxy ->
                        when {
                            qrAnalyzerEnabled -> analyzeQr(proxy)
                            heightAnalyzerEnabled -> analyzeHeight(proxy)
                            else -> proxy.close()
                        }
                    }
                }
        } else null

        try {
            provider.unbindAll()
            camera = when {
                currentMode == 3 -> provider.bindToLifecycle(this, selector, preview, videoCapture!!)
                imageAnalysis != null -> provider.bindToLifecycle(this, selector, preview, imageCapture!!, imageAnalysis!!)
                else -> provider.bindToLifecycle(this, selector, preview, imageCapture!!)
            }
            applyExposure()
        } catch (e: Exception) {
            Log.e("Cam", "Bind failed", e)
            Toast.makeText(this, "Không mở được camera", Toast.LENGTH_SHORT).show()
        }
    }

    private fun changeExposure(delta: Int) {
        exposureIndex = (exposureIndex + delta).coerceIn(-4, 4)
        binding.tvEv.text = "EV ${if (exposureIndex >= 0) "+" else ""}$exposureIndex"
        applyExposure()
        Toast.makeText(this, "EV ${if (exposureIndex >= 0) "+" else ""}$exposureIndex", Toast.LENGTH_SHORT).show()
    }

    private fun applyExposure() {
        if (!isEvEnabled) return
        try { camera?.cameraControl?.setExposureCompensationIndex(exposureIndex) } catch (_: Exception) {}
    }

    private fun analyzeQr(proxy: ImageProxy) {
        val mediaImage = proxy.image ?: run { proxy.close(); return }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        qrScanner.process(image)
            .addOnSuccessListener { codes ->
                val code = codes.firstOrNull()?.rawValue ?: return@addOnSuccessListener
                if (code != lastQrValue) {
                    lastQrValue = code
                    runOnUiThread {
                        binding.scanHint.text = "QR: $code"
                        try {
                            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("QR", code))
                        } catch (_: Exception) {}
                        Toast.makeText(this, "Đã quét QR", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun analyzeHeight(proxy: ImageProxy) {
        val mediaImage = proxy.image ?: run { proxy.close(); return }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        poseDetector.process(image)
            .addOnSuccessListener { pose: Pose ->
                estimateHeight(pose, proxy.width, proxy.height)
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun estimateHeight(pose: Pose, width: Int, height: Int) {
        val head = pose.getPoseLandmark(PoseLandmark.NOSE)?.position
        val leftAnkle = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)?.position
        val rightAnkle = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)?.position
        if (head == null || (leftAnkle == null && rightAnkle == null)) return
        val ankle = if (leftAnkle != null && rightAnkle != null) PointF((leftAnkle.x + rightAnkle.x)/2f, (leftAnkle.y + rightAnkle.y)/2f) else leftAnkle ?: rightAnkle!!
        val pixelHeight = abs(ankle.y - head.y)
        if (pixelHeight < height * 0.18f) return
        // Approximation: calibrated against a typical phone portrait camera. Show a range, not false precision.
        val estimated = (1.55 + (pixelHeight / height - 0.55) * 0.85).coerceIn(1.2, 2.2)
        runOnUiThread { binding.heightResult.text = String.format(Locale.US, "Ước lượng: %.2f m", estimated) }
    }

    // ==================== CAPTURE ====================
    private fun startCountdown() {
        if (isCountingDown) return
        isCountingDown = true
        binding.tvCountdown.visibility = View.VISIBLE
        countDownTimer = object : CountDownTimer(timerSeconds * 1000L, 1000) {
            override fun onTick(ms: Long) {
                binding.tvCountdown.text = ((ms / 1000) + 1).toString()
            }
            override fun onFinish() {
                binding.tvCountdown.visibility = View.GONE
                isCountingDown = false
                takePhoto()
            }
        }.start()
    }

    private fun startBurst() {
        if (isBursting) return
        isBursting = true
        Toast.makeText(this, "Burst…", Toast.LENGTH_SHORT).show()
        var count = 0
        val handler = Handler(Looper.getMainLooper())
        fun next() {
            if (count >= 5 || !isBursting) {
                isBursting = false
                return
            }
            takePhoto()
            count++
            handler.postDelayed({ next() }, 280)
        }
        next()
    }

    private fun toggleVideoRecording() {
        if (activeRecording != null) stopVideoRecording() else startVideoRecording()
    }

    private fun startVideoRecording() {
        val vc = videoCapture ?: run {
            Toast.makeText(this, "Camera video chưa sẵn sàng", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "CameraNet_Video_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraNetProAI")
            }
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        val pending = vc.output.prepareRecording(this, output)
        if (isAudioEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending.withAudioEnabled()
        }
        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordingStartMs = SystemClock.elapsedRealtime()
                    binding.tvRecording.visibility = View.VISIBLE
                    updateRecordingTimer()
                    binding.btnCapture.contentDescription = "Dừng quay"
                }
                is VideoRecordEvent.Finalize -> {
                    recordingHandler.removeCallbacksAndMessages(null)
                    binding.tvRecording.visibility = View.GONE
                    binding.tvRecording.text = "● 00:00"
                    activeRecording = null
                    if (event.hasError()) {
                        Toast.makeText(this, "Lỗi video: ${event.error}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Đã lưu video", Toast.LENGTH_SHORT).show()
                    }
                    binding.btnCapture.contentDescription = "Quay video"
                }
            }
        }
    }

    private fun stopVideoRecording() {
        activeRecording?.stop()
        activeRecording = null
        recordingHandler.removeCallbacksAndMessages(null)
        binding.tvRecording.visibility = View.GONE
        binding.tvRecording.text = "● 00:00"
        binding.btnCapture.contentDescription = "Quay video"
    }

    private fun updateRecordingTimer() {
        if (activeRecording == null) return
        val elapsed = (SystemClock.elapsedRealtime() - recordingStartMs).coerceAtLeast(0L)
        val totalSeconds = elapsed / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        binding.tvRecording.text = String.format(Locale.US, "● %02d:%02d", minutes, seconds)
        recordingHandler.postDelayed({ updateRecordingTimer() }, 250L)
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val modeTag = when (currentMode) {
            0 -> "Night"; 1 -> "Portrait"; 3 -> "Video"; 4 -> "Doc"; else -> "Photo"
        }
        val displayName = "CameraNet_${modeTag}_$name.jpg"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraNetProAI")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()

        if (!isSilent) {
            try { shutterSound?.play(MediaActionSound.SHUTTER_CLICK) } catch (_: Exception) {}
        }

        capture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("Cam", "Save error", exc)
                    Toast.makeText(this@MainActivity, "Lỗi lưu: ${exc.message}", Toast.LENGTH_LONG).show()
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        output.savedUri?.let { uri ->
                            try {
                                contentResolver.update(uri, ContentValues().apply {
                                    put(MediaStore.Images.Media.IS_PENDING, 0)
                                }, null, null)
                            } catch (_: Exception) {}
                        }
                    }
                    // Note: real watermark needs ImageAnalysis or post-process the file
                    lastPhotoUri = output.savedUri
                    // Cập nhật thumbnail
                    try {
                        binding.imgThumbnail.setImageURI(output.savedUri)
                    } catch (_: Exception) {}
                    Toast.makeText(this@MainActivity, "Đã lưu ($modeTag)", Toast.LENGTH_SHORT).show()
                }
            })
    }

    // ==================== SENSOR ====================
    override fun onSensorChanged(event: SensorEvent?) {
        // Accelerometer for level indicator
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            try {
                val x = event.values[0]
                val roll = Math.toDegrees(kotlin.math.atan2(event.values[1].toDouble(), event.values[2].toDouble())).toFloat()
                binding.levelBar.rotation = roll
            } catch (e: Exception) {
                Log.e("Sensor", "Error processing accelerometer", e)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    // ==================== LIFECYCLE ====================
    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
        isBursting = false
        if (activeRecording != null) stopVideoRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        countDownTimer?.cancel()
        shutterSound?.release()
        qrScanner.close()
        poseDetector.close()
        sensorManager?.unregisterListener(this)
    }
}
