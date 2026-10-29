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
import android.view.TextureView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.motoplay.dashcam.MotoPlayApp
import com.motoplay.dashcam.R
import com.motoplay.dashcam.camera.Camera2Engine
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
/**
 * 录像前台服务（Camera2 原生引擎）。
 *
 * 相机由服务统一持有（Camera2Engine），界面只提供 TextureView 作为画面输出目标。
 * TextureView 自己持有 SurfaceTexture，切后台/锁屏/界面重建都不会导致画面定格。
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
        /** 运行中的服务实例，供界面挂载预览控件 */
        @Volatile
        var INSTANCE: RecordingService? = null
            private set
        private val _recordingState = MutableStateFlow(RecordingState())
        val recordingState: StateFlow<RecordingState> = _recordingState
        private val _gpsState = MutableStateFlow(GpsState())
        val gpsState: StateFlow<GpsState> = _gpsState
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
    private var gpsTracker: GpsTracker? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var engine: Camera2Engine? = null
    private var currentFile: File? = null
    private var segmentJob: Job? = null
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
        INSTANCE = this
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
        initEngine()
    }
    private fun initEngine() {
        val e = Camera2Engine(this)
        e.onError = { msg ->
            Log.e(TAG, "engine error: $msg")
            _recordingState.value = _recordingState.value.copy(error = msg)
        }
        e.onRecordingStarted = { file ->
            isRecording = true
            _recordingState.value = _recordingState.value.copy(
                recording = true, currentFile = file.absolutePath
            )
            updateNotification()
        }
        e.onRecordingStopped = { file ->
            StorageManager.publishToGallery(this, file)
            StorageManager.enforceStorageLimit(this)
        }
        e.start()
        engine = e
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                startForegroundSafely()
                startRecording()
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
    /**
     * 界面挂载 / 重新挂载预览控件。
     * TextureView 自己持有 Surface，服务只把画面推到它上面，不会定格。
     */
    fun attachPreview(view: TextureView?) {
        engine?.setPreviewTarget(view)
        if (view != null) {
            engine?.openCamera()
        }
    }
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
    private fun startRecording() {
        engine?.openCamera()
        _recordingState.value = _recordingState.value.copy(cameraMode = "单后置 Camera2")
        startSegmentLoop()
    }
    private fun startSegmentLoop() {
        segmentJob?.cancel()
        segmentJob = serviceScope.launch {
            // 等相机就绪
            delay(1500)
            while (true) {
                recordSegment()
                val minutes = Prefs.segmentMinutes
                delay(minutes * 60_000L)
                engine?.stopRecording()
                isRecording = false
                _recordingState.value = _recordingState.value.copy(recording = false)
            }
        }
    }
    private fun recordSegment() {
        val e = engine ?: return
        val file = StorageManager.newSegmentFile(this)
        currentFile = file
        val ok = e.startRecording(file, Prefs.recordAudio)
        if (!ok) {
            Log.w(TAG, "分段录制启动失败，稍后重试")
        }
    }
    private fun lockCurrentSegment() {
        val file = currentFile
        engine?.stopRecording()
        isRecording = false
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
        engine?.stopRecording()
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
        INSTANCE = null
        stopRecording()
        engine?.release()
        engine = null
        gpsTracker?.stop()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // ignore
        }
        serviceScope.cancel()
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