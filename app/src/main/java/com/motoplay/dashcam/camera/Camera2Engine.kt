package com.motoplay.dashcam.camera
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
/**
 * Camera2 原生录像引擎（单后置摄像头）。
 *
 * 为什么不用 CameraX：
 * CameraX 的 Preview 用例依赖 PreviewView 的 SurfaceProvider 生命周期，
 * 在 Service 中绑定时极易出现"画面定格"问题。Camera2 + TextureView 由
 * TextureView 自己持有 SurfaceTexture，画面输出稳定，切后台/锁屏也不卡。
 *
 * 职责：
 *  - 打开后置摄像头
 *  - 输出预览到 TextureView
 *  - 用 MediaRecorder 分段录制（每 N 分钟切一个文件）
 */
class Camera2Engine(private val context: Context) {
    companion object {
        private const val TAG = "Camera2Engine"
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
    }
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var previewSurface: Surface? = null
    private var textureView: TextureView? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var videoSize: Size = Size(PREVIEW_WIDTH, PREVIEW_HEIGHT)
    private var currentFile: File? = null
    private var isRecording = false
    private val isOpening = AtomicBoolean(false)
    /** 状态回调 */
    var onError: ((String) -> Unit)? = null
    var onRecordingStarted: ((File) -> Unit)? = null
    var onRecordingStopped: ((File) -> Unit)? = null
    /** 预览目标控件 */
    fun setPreviewTarget(view: TextureView?) {
        textureView = view
        if (view != null && view.isAvailable) {
            startPreviewIfReady()
        }
    }
    /** 启动后台线程 */
    fun start() {
        backgroundThread = HandlerThread("Camera2Engine").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }
    /** 打开摄像头并开始预览 */
    fun openCamera() {
        if (cameraDevice != null) {
            startPreviewIfReady()
            return
        }
        if (!isOpening.compareAndSet(false, true)) return
        val camOk = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (!camOk) {
            isOpening.set(false)
            onError?.invoke("缺少相机权限")
            return
        }
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = findBackCameraId(manager) ?: run {
                isOpening.set(false)
                onError?.invoke("未找到后置摄像头")
                return
            }
            // 选择录像尺寸
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            videoSize = chooseVideoSize(map) ?: Size(PREVIEW_WIDTH, PREVIEW_HEIGHT)
            Log.i(TAG, "使用录像尺寸: $videoSize")
            manager.openCamera(cameraId, deviceCallback, backgroundHandler)
        } catch (e: Exception) {
            isOpening.set(false)
            Log.e(TAG, "打开相机失败", e)
            onError?.invoke("打开相机失败: ${e.message}")
        }
    }
    private fun findBackCameraId(manager: CameraManager): String? {
        return try {
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                val facing = c.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
            }
            manager.cameraIdList.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }
    private fun chooseVideoSize(map: StreamConfigurationMap?): Size? {
        map ?: return null
        val sizes = map.getOutputSizes(MediaRecorder::class.java) ?: return null
        // 优先 720p（横屏 1280x720），兼顾清晰度与体积
        val target = sizes.filter { it.width <= 1920 && it.height <= 1080 }
            .sortedByDescending { it.width * it.height }
        return target.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: target.firstOrNull()
            ?: sizes.firstOrNull()
    }
    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            Log.i(TAG, "相机已打开")
            isOpening.set(false)
            cameraDevice = device
            startPreviewIfReady()
        }
        override fun onDisconnected(device: CameraDevice) {
            Log.w(TAG, "相机断开")
            isOpening.set(false)
            device.close()
            cameraDevice = null
        }
        override fun onError(device: CameraDevice, error: Int) {
            Log.e(TAG, "相机错误: $error")
            isOpening.set(false)
            device.close()
            cameraDevice = null
            onError?.invoke("相机错误码: $error")
        }
    }
    /** TextureView 就绪后建立预览会话 */
    private fun startPreviewIfReady() {
        val device = cameraDevice ?: return
        val view = textureView ?: return
        if (!view.isAvailable) return
        val surfaceTexture: SurfaceTexture = view.surfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(videoSize.width, videoSize.height)
        val surface = Surface(surfaceTexture)
        previewSurface?.release()
        previewSurface = surface
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            val request = builder.build()
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(request, null, backgroundHandler)
                            Log.i(TAG, "预览已启动")
                        } catch (e: Exception) {
                            Log.e(TAG, "预览请求失败", e)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "预览会话配置失败")
                        onError?.invoke("预览会话配置失败")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "创建预览会话失败", e)
            onError?.invoke("创建预览会话失败: ${e.message}")
        }
    }
    /**
     * 开始录制一个分段文件。
     * 会重建会话：预览 Surface + MediaRecorder Surface 同时输出。
     */
    fun startRecording(file: File, withAudio: Boolean): Boolean {
        val device = cameraDevice ?: return false
        val view = textureView
        if (view == null || !view.isAvailable) {
            Log.w(TAG, "TextureView 未就绪，无法录制")
            return false
        }
        stopRecordingInternal(notify = false)
        currentFile = file
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            // 只有需要且已授权时才设置音频源；不设置即为纯视频录制
            val audioEnabled = withAudio && ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (audioEnabled) {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(file.absolutePath)
            recorder.setVideoEncodingBitRate(8_000_000)
            recorder.setVideoFrameRate(30)
            recorder.setVideoSize(videoSize.width, videoSize.height)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (audioEnabled) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioChannels(1)
                recorder.setAudioSamplingRate(44100)
                recorder.setAudioEncodingBitRate(96000)
            }
            recorder.prepare()
            val recorderSurface = recorder.surface
            val surfaceTexture = view.surfaceTexture ?: return false
            surfaceTexture.setDefaultBufferSize(videoSize.width, videoSize.height)
            val preview = Surface(surfaceTexture)
            previewSurface?.release()
            previewSurface = preview
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(preview)
            builder.addTarget(recorderSurface)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            val request = builder.build()
            device.createCaptureSession(
                listOf(preview, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            captureSession?.close()
                        } catch (e: Exception) {
                            // ignore
                        }
                        captureSession = session
                        try {
                            session.setRepeatingRequest(request, null, backgroundHandler)
                            recorder.start()
                            mediaRecorder = recorder
                            isRecording = true
                            onRecordingStarted?.invoke(file)
                            Log.i(TAG, "开始录制: ${file.name}")
                        } catch (e: Exception) {
                            Log.e(TAG, "启动录制失败", e)
                            onError?.invoke("启动录制失败: ${e.message}")
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "录制会话配置失败")
                        onError?.invoke("录制会话配置失败")
                    }
                },
                backgroundHandler
            )
            return true
        } catch (e: Exception) {
            Log.e(TAG, "准备录制失败", e)
            onError?.invoke("准备录制失败: ${e.message}")
            try {
                recorder.release()
            } catch (ignored: Exception) {
            }
            return false
        }
    }
    /** 停止当前分段（并回到纯预览） */
    fun stopRecording() {
        stopRecordingInternal(notify = true)
    }
    private fun stopRecordingInternal(notify: Boolean) {
        val file = currentFile
        if (!isRecording) {
            try {
                mediaRecorder?.release()
            } catch (e: Exception) {
                // ignore
            }
            mediaRecorder = null
            return
        }
        isRecording = false
        try {
            captureSession?.stopRepeating()
        } catch (e: Exception) {
            // ignore
        }
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "停止录制异常", e)
        }
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            // ignore
        }
        mediaRecorder = null
        if (notify && file != null) {
            onRecordingStopped?.invoke(file)
        }
        // 回到纯预览
        startPreviewIfReady()
    }
    /** 释放全部资源 */
    fun release() {
        stopRecordingInternal(notify = false)
        try {
            captureSession?.close()
        } catch (e: Exception) {
            // ignore
        }
        captureSession = null
        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            // ignore
        }
        cameraDevice = null
        try {
            previewSurface?.release()
        } catch (e: Exception) {
            // ignore
        }
        previewSurface = null
        backgroundThread?.let {
            it.quitSafely()
            try {
                it.join(1000)
            } catch (e: Exception) {
                // ignore
            }
        }
        backgroundThread = null
        backgroundHandler = null
    }
}