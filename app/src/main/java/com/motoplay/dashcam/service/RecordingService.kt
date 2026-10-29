package com.motoplay.dashcam.service
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.motoplay.dashcam.MotoPlayApp
import com.motoplay.dashcam.R
import com.motoplay.dashcam.data.GpsState
import com.motoplay.dashcam.data.GpsTracker
import com.motoplay.dashcam.data.StorageManager
import com.motoplay.dashcam.ui.MainActivity
import com.motoplay.dashcam.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
/**
 * 录像前台服务。
 * 负责：CameraX 生命周期、分段循环录像、双摄降级、GPS 采集、存储清理。
 */
class RecordingService : LifecycleService() {
    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_START = "com.motoplay.dashcam.START"
        const val ACTION_STOP = "com.motoplay.dashcam.STOP"
        const val ACTION_LOCK = "com.motoplay.dashcam.LOCK"
        const val NOTIF_ID = 1001
        @Volatile
        var isRunning: Boolean = false
            private set
        private val _recordingState = MutableStateFlow(RecordingState())
        val recordingState: StateFlow<RecordingState> = _recordingState
        private val _gpsState = MutableStateFlow(GpsState())
        val gpsState: StateFlow<GpsState> = _gpsState
        /** 供 UI 绑定 CameraX 预览 */
        var cameraProvider: ProcessCameraProvider? = null
            private set
        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        fun stop(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
        fun lockCurrent(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_LOCK }
            context.startService(intent)
        }
    }
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var gpsTracker: GpsTracker? = null
    private var wakeLock: PowerManager.WakeLock? = null
    // 录像相关
    private var videoCapture: VideoCapture<Recorder>? = null
    private var frontVideoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var frontRecording: Recording? = null
    private var currentFile: File? = null
    private var frontFile: File? = null
    private var segmentJob: Job? = null
    private var isDualCamera = false
    private var isRecording = false
    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }
    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        gpsTracker = GpsTracker(this).also {
            it.start()
            serviceScope.launch {
                it.state.collect { s ->
                    _gpsState.value = s
                    _recordingState.value = _recordingState.value.copy(gps = s)
                }
            }
        }
        acquireWakeLock()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                startForegroundSafely()
                if (!isRecording) startRecording()
            }
            ACTION_STOP -> {
                stopRecording()
                stopSelfSafely()
            }
            ACTION_LOCK -> lockCurrentSegment()
            else -> {
                startForegroundSafely()
                if (!isRecording) startRecording()
            }
        }
        return Service.START_STICKY
    }
    /** Android 14+ 必须带 foregroundServiceType 启动 */
    private fun startForegroundSafely() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            try {
                startForeground(NOTIF_ID, notification)
            } catch (ex: Exception) {
                Log.e(TAG, "startForeground fallback failed", ex)
            }
        }
    }
    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MotoPlayApp.CHANNEL_RECORDING)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .addAction(R.drawable.ic_stop, getString(R.string.btn_stop), stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MOTOPLAY::Recording")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(12 * 60 * 60 * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "wakeLock failed", e)
        }
    }
    /** 初始化相机并开始录像 */
    private fun startRecording() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                bindAndRecord(provider)
            } catch (e: Exception) {
                Log.e(TAG, "camera init failed", e)
                _recordingState.value = _recordingState.value.copy(error = "相机初始化失败: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun bindAndRecord(provider: ProcessCameraProvider) {
        provider.unbindAll()
        val wantDual = Prefs.useFrontCamera
        var dualSuccess = false
        if (wantDual) {
            dualSuccess = tryBindDual(provider)
        }
        if (!dualSuccess) {
            Log.i(TAG, "双摄不可用，降级为单后置摄像头")
            bindSingle(provider)
        }
        isDualCamera = dualSuccess
        _recordingState.value = _recordingState.value.copy(
            dualCamera = dualSuccess,
            cameraMode = if (dualSuccess) "双摄并发" else "单后置"
        )
        startSegmentLoop()
    }
    /** 尝试绑定双摄，任何异常都返回 false 触发降级 */
    private fun tryBindDual(provider: ProcessCameraProvider): Boolean {
        return try {
            val concurrentInfos = provider.availableConcurrentCameraInfos
            if (concurrentInfos.isNullOrEmpty()) return false
            var backSelector: CameraSelector? = null
            var frontSelector: CameraSelector? = null
            for (pair in concurrentInfos) {
                val back = pair.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                val front = pair.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
                if (back != null && front != null) {
                    backSelector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
                    frontSelector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT).build()
                    break
                }
            }
            if (backSelector == null || frontSelector == null) return false
            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )
            val backRecorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
            val frontRecorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
            val backCapture = VideoCapture.withOutput(backRecorder)
            val frontCapture = VideoCapture.withOutput(frontRecorder)
            // CameraX 双摄并发绑定：
            // bindToLifecycle(lifecycleOwner, primarySelector, secondarySelector, vararg useCases)
            provider.bindToLifecycle(
                this,
                backSelector,
                frontSelector,
                backCapture,
                frontCapture
            )
            videoCapture = backCapture
            frontVideoCapture = frontCapture
            true
        } catch (e: Throwable) {
            Log.w(TAG, "双摄绑定失败，降级单摄", e)
            try {
                provider.unbindAll()
            } catch (ignored: Exception) {
            }
            false
        }
    }
    private fun bindSingle(provider: ProcessCameraProvider) {
        try {
            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )
            val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
            val capture = VideoCapture.withOutput(recorder)
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, capture
            )
            videoCapture = capture
            frontVideoCapture = null
        } catch (e: Exception) {
            Log.e(TAG, "单摄绑定失败", e)
            _recordingState.value = _recordingState.value.copy(error = "相机绑定失败")
        }
    }
    /** 分段循环：每 N 分钟切一个文件 */
    private fun startSegmentLoop() {
        segmentJob?.cancel()
        segmentJob = serviceScope.launch {
            while (true) {
                recordSegment()
                val minutes = Prefs.segmentMinutes
                delay(minutes * 60_000L)
                stopCurrentSegment()
            }
        }
    }
    private fun recordSegment() {
        val capture = videoCapture ?: return
        val file = StorageManager.newSegmentFile(this)
        currentFile = file
        val options = FileOutputOptions.Builder(file).build()
        var pendingRecording = capture.output.prepareRecording(this, options)
        if (Prefs.recordAudio) {
            try {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    pendingRecording = pendingRecording.withAudioEnabled()
                }
            } catch (e: Exception) {
                Log.w(TAG, "音频启用失败", e)
            }
        }
        try {
            activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        _recordingState.value = _recordingState.value.copy(
                            recording = true, currentFile = file.absolutePath
                        )
                        updateNotification()
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            Log.e(TAG, "录像结束错误: ${event.error}")
                        }
                        StorageManager.publishToGallery(this, file)
                        StorageManager.enforceStorageLimit(this)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动录像失败", e)
            _recordingState.value = _recordingState.value.copy(error = "录像启动失败: ${e.message}")
        }
        // 前置摄像头（若双摄可用）
        frontVideoCapture?.let { front ->
            val fFile = File(file.parentFile, "FRONT_${file.name}")
            frontFile = fFile
            val fOptions = FileOutputOptions.Builder(fFile).build()
            try {
                frontRecording = front.output.prepareRecording(this, fOptions)
                    .start(ContextCompat.getMainExecutor(this)) { ev ->
                        if (ev is VideoRecordEvent.Finalize) {
                            StorageManager.publishToGallery(this, fFile)
                        }
                    }
            } catch (e: Exception) {
                Log.w(TAG, "前置录像失败", e)
            }
        }
    }
    private fun stopCurrentSegment() {
        try {
            activeRecording?.stop()
            activeRecording = null
        } catch (e: Exception) {
            Log.w(TAG, "stop segment error", e)
        }
        try {
            frontRecording?.stop()
            frontRecording = null
        } catch (e: Exception) {
            Log.w(TAG, "stop front segment error", e)
        }
        isRecording = false
        _recordingState.value = _recordingState.value.copy(recording = false)
    }
    /** 锁定当前正在录制的文件 */
    private fun lockCurrentSegment() {
        val file = currentFile
        stopCurrentSegment()
        if (file != null && file.exists()) {
            val locked = StorageManager.lockFile(this, file)
            _recordingState.value = _recordingState.value.copy(
                lastLocked = locked?.absolutePath,
                lockedCount = _recordingState.value.lockedCount + 1
            )
        }
        recordSegment()
    }
    private fun stopRecording() {
        segmentJob?.cancel()
        segmentJob = null
        stopCurrentSegment()
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "unbind error", e)
        }
        isRecording = false
        _recordingState.value = _recordingState.value.copy(recording = false)
    }
    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            // ignore
        }
    }
    private fun stopSelfSafely() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // ignore
        }
        stopSelf()
    }
    override fun onDestroy() {
        isRunning = false
        stopRecording()
        gpsTracker?.stop()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // ignore
        }
        serviceScope.cancel()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
data class RecordingState(
    val recording: Boolean = false,
    val dualCamera: Boolean = false,
    val cameraMode: String = "初始化中",
    val currentFile: String? = null,
    val lastLocked: String? = null,
    val lockedCount: Int = 0,
    val error: String? = null,
    val gps: GpsState = GpsState()
)