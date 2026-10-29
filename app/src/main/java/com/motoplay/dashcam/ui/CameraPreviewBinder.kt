package com.motoplay.dashcam.ui
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
/**
 * 独立的后置摄像头预览绑定器。
 *
 * 与 RecordingService 的录像用例共用同一个 ProcessCameraProvider，
 * 但只绑定 Preview，不碰 VideoCapture，避免互相干扰导致预览卡死。
 */
object CameraPreviewBinder {
    private const val TAG = "CameraPreviewBinder"
    private var boundPreview: Preview? = null
    private var boundProvider: ProcessCameraProvider? = null
    /**
     * 绑定预览。必须在 PreviewView 已 attach 到窗口后调用。
     */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onReady: (() -> Unit)? = null
    ) {
        if (!previewView.isAttachedToWindow) {
            Log.w(TAG, "PreviewView 尚未 attach，延迟绑定")
            previewView.post { bind(lifecycleOwner, previewView, onReady) }
            return
        }
        val future = ProcessCameraProvider.getInstance(previewView.context)
        future.addListener({
            try {
                val provider = future.get()
                boundProvider = provider
                // 先解绑旧的预览，避免重复绑定
                boundPreview?.let {
                    try {
                        provider.unbind(it)
                    } catch (e: Exception) {
                        Log.w(TAG, "解绑旧预览失败", e)
                    }
                }
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview
                )
                boundPreview = preview
                Log.i(TAG, "预览绑定成功")
                onReady?.invoke()
            } catch (e: Throwable) {
                Log.e(TAG, "预览绑定失败", e)
            }
        }, ContextCompat.getMainExecutor(previewView.context))
    }
    /** 解绑预览（界面销毁时调用） */
    fun unbind() {
        try {
            boundPreview?.let { boundProvider?.unbind(it) }
        } catch (e: Exception) {
            Log.w(TAG, "解绑预览失败", e)
        }
        boundPreview = null
        boundProvider = null
    }
}