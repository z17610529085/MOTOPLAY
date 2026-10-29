package com.motoplay.dashcam.ui
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
/**
 * 将 CameraX 预览绑定到 UI 的 PreviewView。
 * 服务负责录像，UI 负责预览显示，两者共享同一个 ProcessCameraProvider。
 */
object PreviewBinder {
    private const val TAG = "PreviewBinder"
    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        frontPreviewView: PreviewView?,
        dual: Boolean
    ) {
        val future = ProcessCameraProvider.getInstance(previewView.context)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                if (dual && frontPreviewView != null) {
                    val frontPreview = Preview.Builder().build().also {
                        it.setSurfaceProvider(frontPreviewView.surfaceProvider)
                    }
                    // 仅绑定预览，录制用例已由 Service 绑定
                    try {
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "后置预览绑定失败", e)
                    }
                    try {
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            frontPreview
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "前置预览绑定失败", e)
                    }
                } else {
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "预览绑定失败", e)
            }
        }, ContextCompat.getMainExecutor(previewView.context))
    }
}