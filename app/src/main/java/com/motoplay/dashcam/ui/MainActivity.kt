package com.motoplay.dashcam.ui
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.motoplay.dashcam.data.StorageManager
import com.motoplay.dashcam.databinding.ActivityMainBinding
import com.motoplay.dashcam.service.RecordingService
import com.motoplay.dashcam.util.Prefs
import kotlinx.coroutines.launch
import java.io.File
class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_AUTO_START = "auto_start"
        private val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    /** 用户手动停止过录像时，不再自动重启 */
    private var userStopped = false
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(this, "部分权限未授予，功能可能受限：$denied", Toast.LENGTH_LONG).show()
        }
        startRecordingIfPermitted()
    }
    private val wallpaperLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // 部分来源不支持持久化
            }
            Prefs.wallpaperPath = it.toString()
            applyWallpaper(it.toString())
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 全屏沉浸
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        applyWallpaper(Prefs.wallpaperPath)
        setupButtons()
        observeState()
        // 预览控件交给录像服务统一管理（服务负责绑定相机，避免重复绑定卡死）
        RecordingService.uiPreviewView = binding.previewView
        requestPermissionsIfNeeded()
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            startRecordingIfPermitted()
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            startRecordingIfPermitted()
        }
    }
    override fun onStart() {
        super.onStart()
        // 自动开始录制：用户没有手动停止过、当前也没在录，就自动开录
        if (!userStopped && !RecordingService.recordingState.value.recording) {
            startRecordingIfPermitted()
        }
    }
    override fun onDestroy() {
        // 防止持有已销毁界面的引用
        if (RecordingService.uiPreviewView === binding.previewView) {
            RecordingService.uiPreviewView = null
        }
        super.onDestroy()
    }
    private fun applyWallpaper(path: String?) {
        if (path.isNullOrBlank()) {
            binding.ivWallpaper.setImageResource(com.motoplay.dashcam.R.drawable.bg_default)
            return
        }
        try {
            when {
                path.startsWith("content://") -> {
                    contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                        binding.ivWallpaper.setImageBitmap(BitmapFactory.decodeStream(input))
                    }
                }
                else -> {
                    val f = File(path)
                    if (f.exists()) {
                        binding.ivWallpaper.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
                    }
                }
            }
        } catch (e: Exception) {
            binding.ivWallpaper.setImageResource(com.motoplay.dashcam.R.drawable.bg_default)
        }
    }
    private fun setupButtons() {
        binding.btnRecord.setOnClickListener {
            if (RecordingService.recordingState.value.recording) {
                userStopped = true
                RecordingService.stop(this)
            } else {
                userStopped = false
                startRecordingIfPermitted()
            }
        }
        binding.btnLock.setOnClickListener {
            if (RecordingService.isRunning) {
                RecordingService.lockCurrent(this)
                Toast.makeText(this, "已锁定当前视频 🔒", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先开始录像", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnList.setOnClickListener {
            startActivity(Intent(this, VideoListActivity::class.java))
        }
        binding.btnWallpaper.setOnClickListener {
            wallpaperLauncher.launch("image/*")
        }
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }
    }
    private fun showSettingsDialog() {
        val options = arrayOf(
            "分段时长：${Prefs.segmentMinutes} 分钟",
            "存储上限：${Prefs.maxStorageGb} GB",
            "录音：${if (Prefs.recordAudio) "开" else "关"}",
            "水印：${if (Prefs.watermarkEnabled) "开" else "关"}",
            "开机自启：${if (Prefs.autoStartOnBoot) "开" else "关"}",
            "恢复默认壁纸"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> cycleSegment()
                    1 -> cycleStorage()
                    2 -> Prefs.setBoolean(Prefs.KEY_RECORD_AUDIO, !Prefs.recordAudio)
                    3 -> Prefs.setBoolean(Prefs.KEY_WATERMARK_ENABLED, !Prefs.watermarkEnabled)
                    4 -> Prefs.setBoolean(Prefs.KEY_AUTO_START_ON_BOOT, !Prefs.autoStartOnBoot)
                    5 -> {
                        Prefs.wallpaperPath = null
                        binding.ivWallpaper.setImageResource(com.motoplay.dashcam.R.drawable.bg_default)
                    }
                }
                Toast.makeText(this, "已保存，重启录像后生效", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }
    private fun cycleSegment() {
        val list = listOf(1, 2, 3, 5, 10, 15, 30)
        val idx = list.indexOf(Prefs.segmentMinutes).let { if (it < 0) 3 else it }
        Prefs.setInt(Prefs.KEY_SEGMENT_MINUTES, list[(idx + 1) % list.size])
    }
    private fun cycleStorage() {
        val list = listOf(10, 20, 32, 50, 64, 100, 128, 256)
        val idx = list.indexOf(Prefs.maxStorageGb).let { if (it < 0) 5 else it }
        Prefs.setInt(Prefs.KEY_MAX_STORAGE_GB, list[(idx + 1) % list.size])
    }
    private fun requestPermissionsIfNeeded() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startRecordingIfPermitted()
        }
    }
    private fun startRecordingIfPermitted() {
        val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (!camOk) {
            Toast.makeText(this, "需要相机权限才能录像", Toast.LENGTH_LONG).show()
            return
        }
        RecordingService.start(this)
    }
    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    RecordingService.recordingState.collect { s ->
                        binding.tvRecStatus.text = if (s.recording) "录像中" else "未录像"
                        binding.recDot.setBackgroundResource(
                            if (s.recording) com.motoplay.dashcam.R.drawable.shape_dot_red
                            else com.motoplay.dashcam.R.drawable.shape_dot_gray
                        )
                        binding.btnRecord.text = if (s.recording) "停止录像" else "开始录像"
                        binding.tvCameraMode.text = s.cameraMode
                        binding.tvPreviewHint.visibility =
                            if (s.recording) View.GONE else View.VISIBLE
                        s.error?.let {
                            Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                launch {
                    RecordingService.gpsState.collect { g ->
                        binding.tvSpeed.text = String.format(java.util.Locale.US, "%.0f", g.speedKmh)
                        binding.tvCoord.text = if (g.valid) {
                            String.format(
                                java.util.Locale.US,
                                "%.5f, %.5f", g.latitude, g.longitude
                            )
                        } else "GPS 搜索中…"
                        binding.tvWatermark.text = g.watermarkText()
                        binding.tvWatermark.visibility =
                            if (Prefs.watermarkEnabled) View.VISIBLE else View.GONE
                    }
                }
            }
        }
        viewModel.startClock()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.timeText.collect { binding.tvTime.text = it }
                }
                launch {
                    viewModel.secondsText.collect { binding.tvSeconds.text = it }
                }
                launch {
                    viewModel.dateText.collect { binding.tvDate.text = it }
                }
                launch {
                    viewModel.weekdayText.collect { binding.tvWeekday.text = it }
                }
                launch {
                    viewModel.weather.collect { w ->
                        if (w != null) {
                            binding.tvWeatherCity.text = w.city
                            binding.tvWeatherTemp.text = w.temperature
                            binding.tvWeatherDesc.text = w.description
                            binding.tvWeatherDetail.text =
                                "体感 ${w.feelsLike}\n湿度 ${w.humidity}\n风速 ${w.wind}"
                        } else {
                            binding.tvWeatherDesc.text = "天气获取失败"
                        }
                    }
                }
            }
        }
    }
}