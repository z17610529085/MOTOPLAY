package com.motoplay.dashcam.ui
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.MediaController
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.motoplay.dashcam.databinding.ActivityVideoPlayerBinding
import java.io.File
class VideoPlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PATH = "video_path"
    }
    private lateinit var binding: ActivityVideoPlayerBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null || !File(path).exists()) {
            finish()
            return
        }
        binding.tvTitle.text = File(path).name
        val controller = MediaController(this)
        controller.setAnchorView(binding.videoView)
        binding.videoView.setMediaController(controller)
        binding.videoView.setVideoURI(Uri.fromFile(File(path)))
        binding.videoView.setOnPreparedListener { it.isLooping = false }
        binding.videoView.start()
        binding.btnClose.setOnClickListener { finish() }
    }
    override fun onPause() {
        super.onPause()
        binding.videoView.pause()
    }
    override fun onDestroy() {
        super.onDestroy()
        binding.videoView.stopPlayback()
    }
}