package com.sharpcamera.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.sharpcamera.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService

    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var isGridVisible = true
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var timerSeconds = 0
    private var countDownTimer: CountDownTimer? = null
    private var isCountingDown = false
    private var currentZoomRatio = 1f
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    // AI Modes (giống Huawei)
    private var aiMode = 0 // 0=AI Auto, 1=Beauty, 2=Portrait, 3=Night, 4=Food, 5=Landscape
    private val aiModeNames = arrayOf("AI Auto", "Làm đẹp", "Chân dung", "Đêm", "Đồ ăn", "Phong cảnh")

    private var currentFilter = 0
    private val filterNames = arrayOf("Thường", "Trắng đen", "Ấm", "Lạnh", "Sống động")
    private var currentRatio = 1
    private val ratioNames = arrayOf("1:1", "4:3", "16:9")
    private var isBurstMode = false
    private var isSmileShutter = false
    private var isRecordingVideo = false

    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
        FaceDetection.getClient(options)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) startCamera()
        else {
            Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)

        cameraExecutor = Executors.newSingleThreadExecutor()

        scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return false
                    val zoomState = cam.cameraInfo.zoomState.value ?: return false
                    val newZoom = (currentZoomRatio * detector.scaleFactor)
                        .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(newZoom)
                    currentZoomRatio = newZoom
                    binding.tvZoom.text = String.format("%.1fx", currentZoomRatio)
                    return true
                }
            })

        if (allPermissionsGranted()) startCamera()
        else requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))

        setupButtons()
        setupTouch()
        updateUI()
    }

    private fun setupButtons() {
        binding.btnCapture.setOnClickListener {
            if (isCountingDown || isRecordingVideo) return@setOnClickListener
            if (timerSeconds > 0) startCountdown() else {
                if (isBurstMode) takeBurst() else takePhoto()
            }
            animateShutter()
        }

        binding.btnVideo.setOnClickListener {
            if (isCountingDown) return@setOnClickListener
            if (isRecordingVideo) stopVideo() else startVideo()
        }

        binding.btnFlash.setOnClickListener { cycleFlashMode() }
        binding.btnGrid.setOnClickListener {
            isGridVisible = !isGridVisible
            binding.gridOverlay.visibility = if (isGridVisible) View.VISIBLE else View.GONE
        }
        binding.btnSwitchCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            currentZoomRatio = 1f
            binding.tvZoom.text = "1.0x"
            startCamera()
        }
        binding.btnTimer.setOnClickListener {
            timerSeconds = when (timerSeconds) { 0 -> 3; 3 -> 5; 5 -> 10; else -> 0 }
            updateUI()
        }

        // AI Modes
        binding.btnAIAuto.setOnClickListener { setAIMode(0) }
        binding.btnBeauty.setOnClickListener { setAIMode(1) }
        binding.btnPortrait.setOnClickListener { setAIMode(2) }
        binding.btnNight.setOnClickListener { setAIMode(3) }
        binding.btnFood.setOnClickListener { setAIMode(4) }
        binding.btnLandscape.setOnClickListener { setAIMode(5) }

        binding.btnFilter.setOnClickListener {
            currentFilter = (currentFilter + 1) % filterNames.size
            updateUI()
            Toast.makeText(this, "Bộ lọc: ${filterNames[currentFilter]}", Toast.LENGTH_SHORT).show()
        }
        binding.btnRatio.setOnClickListener {
            currentRatio = (currentRatio + 1) % ratioNames.size
            updateUI()
            startCamera()
            Toast.makeText(this, "Tỷ lệ: ${ratioNames[currentRatio]}", Toast.LENGTH_SHORT).show()
        }
        binding.btnBurst.setOnClickListener {
            isBurstMode = !isBurstMode
            updateUI()
            Toast.makeText(this, if (isBurstMode) "Chụp liên tục: Bật" else "Chụp liên tục: Tắt", Toast.LENGTH_SHORT).show()
        }
        binding.btnSmile.setOnClickListener {
            isSmileShutter = !isSmileShutter
            updateUI()
            Toast.makeText(this, if (isSmileShutter) "Nụ cười tự chụp: Bật" else "Nụ cười tự chụp: Tắt", Toast.LENGTH_SHORT).show()
        }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
    }

    private fun setAIMode(mode: Int) {
        aiMode = mode
        updateUI()
        val msg = when (mode) {
            0 -> "AI Auto – Tự nhận cảnh"
            1 -> "Làm đẹp – Làm mịn da (Beauty)"
            2 -> "Chân dung – Làm mờ nền"
            3 -> "Đêm – Chụp thiếu sáng tốt hơn"
            4 -> "Đồ ăn – Màu sắc tươi, nổi bật"
            5 -> "Phong cảnh – Rộng, rõ chi tiết"
            else -> aiModeNames[mode]
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        // Rebind to apply quality settings
        startCamera()
    }

    private fun setupTouch() {
        binding.previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.pointerCount == 1 && event.action == MotionEvent.ACTION_UP) {
                val factory = binding.previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                camera?.cameraControl?.startFocusAndMetering(action)
                showFocusIndicator(event.x, event.y)
            }
            true
        }
    }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return
        val aspect = when (currentRatio) {
            0, 1 -> AspectRatio.RATIO_4_3
            else -> AspectRatio.RATIO_16_9
        }

        val preview = Preview.Builder().setTargetAspectRatio(aspect).build()
            .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

        // AI mode ảnh hưởng chất lượng
        val quality = when (aiMode) {
            3 -> 98 // Night
            1, 2 -> 100 // Beauty / Portrait
            else -> 100
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetAspectRatio(aspect)
            .setFlashMode(flashMode)
            .setJpegQuality(quality)
            .build()

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture)
            currentZoomRatio = 1f
            binding.tvZoom.text = "1.0x"
        } catch (e: Exception) {
            Log.e(TAG, "Bind failed", e)
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture)
            } catch (e2: Exception) {
                Toast.makeText(this, "Không mở được camera", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCountdown() {
        isCountingDown = true
        binding.tvCountdown.visibility = View.VISIBLE
        binding.btnCapture.isEnabled = false
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(timerSeconds * 1000L, 1000) {
            override fun onTick(ms: Long) { binding.tvCountdown.text = ((ms / 1000) + 1).toString() }
            override fun onFinish() {
                binding.tvCountdown.visibility = View.GONE
                binding.btnCapture.isEnabled = true
                isCountingDown = false
                if (isBurstMode) takeBurst() else takePhoto()
            }
        }.start()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "AI_${aiModeNames[aiMode]}_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraNetProAI")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()

        capture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(baseContext, getString(R.string.error_saving), Toast.LENGTH_SHORT).show()
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(baseContext, "Đã lưu ảnh ${aiModeNames[aiMode]}!", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun takeBurst() {
        Toast.makeText(this, "AI Burst: 5 ảnh...", Toast.LENGTH_SHORT).show()
        var count = 0
        val handler = Handler(Looper.getMainLooper())
        fun next() {
            if (count >= 5) return
            takePhoto()
            count++
            handler.postDelayed({ next() }, 350)
        }
        next()
    }

    private fun startVideo() {
        val vc = videoCapture ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Cần quyền Micro", Toast.LENGTH_SHORT).show()
            return
        }
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "AI_Video_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P)
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraNetProAI")
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()

        recording = vc.output.prepareRecording(this, output)
            .apply {
                if (PermissionChecker.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED)
                    withAudioEnabled()
            }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecordingVideo = true
                        binding.tvRecording.visibility = View.VISIBLE
                        binding.tvMode.text = "Video"
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecordingVideo = false
                        binding.tvRecording.visibility = View.GONE
                        binding.tvMode.text = "Ảnh"
                        if (!event.hasError()) Toast.makeText(baseContext, getString(R.string.video_saved), Toast.LENGTH_SHORT).show()
                        else Toast.makeText(baseContext, "Lỗi video", Toast.LENGTH_SHORT).show()
                        recording = null
                    }
                }
            }
    }

    private fun stopVideo() {
        recording?.stop()
        recording = null
    }

    private fun cycleFlashMode() {
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            Toast.makeText(this, "Camera trước không có flash", Toast.LENGTH_SHORT).show()
            return
        }
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        val t = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> "Flash: Bật"
            ImageCapture.FLASH_MODE_AUTO -> "Flash: Auto"
            else -> "Flash: Tắt"
        }
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        binding.tvAIMode.text = aiModeNames[aiMode]
        binding.tvTimerStatus.text = when (timerSeconds) {
            3 -> "Timer: 3s"; 5 -> "Timer: 5s"; 10 -> "Timer: 10s"; else -> "Timer: Tắt"
        }
        binding.tvFilter.text = "Filter: ${filterNames[currentFilter]}"
        binding.tvRatio.text = "Tỷ lệ: ${ratioNames[currentRatio]}"

        // Highlight active AI mode
        val buttons = listOf(binding.btnAIAuto, binding.btnBeauty, binding.btnPortrait,
            binding.btnNight, binding.btnFood, binding.btnLandscape)
        buttons.forEachIndexed { index, btn ->
            btn.setBackgroundColor(if (index == aiMode) 0xAA00E5FF.toInt() else 0x55FFFFFF)
            btn.setTextColor(if (index == aiMode) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        binding.btnBurst.alpha = if (isBurstMode) 1f else 0.65f
        binding.btnSmile.alpha = if (isSmileShutter) 1f else 0.65f
    }

    private fun showSettingsDialog() {
        val items = arrayOf(
            "AI Mode: ${aiModeNames[aiMode]}",
            "Timer: ${if (timerSeconds == 0) "Tắt" else "${timerSeconds}s"}",
            "Bộ lọc: ${filterNames[currentFilter]}",
            "Tỷ lệ: ${ratioNames[currentRatio]}",
            "Chụp liên tục: ${if (isBurstMode) "Bật" else "Tắt"}",
            "Nụ cười: ${if (isSmileShutter) "Bật" else "Tắt"}"
        )
        AlertDialog.Builder(this).setTitle("Cài đặt AI Camera")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> setAIMode((aiMode + 1) % aiModeNames.size)
                    1 -> binding.btnTimer.performClick()
                    2 -> binding.btnFilter.performClick()
                    3 -> binding.btnRatio.performClick()
                    4 -> binding.btnBurst.performClick()
                    5 -> binding.btnSmile.performClick()
                }
            }.setPositiveButton("Đóng", null).show()
    }

    private fun showFocusIndicator(x: Float, y: Float) {
        val ind = binding.focusIndicator
        ind.x = x - ind.width / 2f
        ind.y = y - ind.height / 2f
        ind.visibility = View.VISIBLE
        ind.alpha = 1f
        ind.scaleX = 1.4f; ind.scaleY = 1.4f
        ind.animate().scaleX(1f).scaleY(1f).setDuration(180)
            .withEndAction {
                ind.animate().alpha(0f).setDuration(350).setStartDelay(500)
                    .withEndAction { ind.visibility = View.GONE }.start()
            }.start()
    }

    private fun animateShutter() {
        binding.btnCapture.animate().scaleX(0.82f).scaleY(0.82f).setDuration(70)
            .withEndAction { binding.btnCapture.animate().scaleX(1f).scaleY(1f).setDuration(70).start() }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        recording?.stop()
        cameraExecutor.shutdown()
        faceDetector.close()
    }

    companion object {
        private const val TAG = "CameraNetProAI"
    }
}
