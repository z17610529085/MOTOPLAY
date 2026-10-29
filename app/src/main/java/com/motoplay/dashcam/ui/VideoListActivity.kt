package com.motoplay.dashcam.ui
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.motoplay.dashcam.data.StorageManager
import com.motoplay.dashcam.data.VideoItem
import com.motoplay.dashcam.databinding.ActivityVideoListBinding
import com.motoplay.dashcam.databinding.ItemVideoBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
class VideoListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVideoListBinding
    private lateinit var adapter: VideoAdapter
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        adapter = VideoAdapter(
            onPlay = { play(it) },
            onShare = { share(it) },
            onDelete = { confirmDelete(it) }
        )
        binding.rvVideos.layoutManager = LinearLayoutManager(this)
        binding.rvVideos.adapter = adapter
        loadVideos()
    }
    override fun onResume() {
        super.onResume()
        loadVideos()
    }
    private fun loadVideos() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { StorageManager.listVideos(this@VideoListActivity) }
            adapter.submit(list)
            binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            val used = withContext(Dispatchers.IO) { StorageManager.usedBytes(this@VideoListActivity) }
            binding.tvStorageInfo.text = String.format(
                Locale.US, "已用 %.2f GB / 上限 %d GB",
                used / 1024.0 / 1024.0 / 1024.0,
                com.motoplay.dashcam.util.Prefs.maxStorageGb
            )
        }
    }
    private fun play(item: VideoItem) {
        val intent = Intent(this, VideoPlayerActivity::class.java)
        intent.putExtra(VideoPlayerActivity.EXTRA_PATH, item.file.absolutePath)
        startActivity(intent)
    }
    private fun share(item: VideoItem) {
        try {
            val uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", item.file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享视频"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    private fun confirmDelete(item: VideoItem) {
        AlertDialog.Builder(this)
            .setTitle("删除视频")
            .setMessage("确定删除 ${item.file.name} 吗？删除后无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                if (StorageManager.deleteFile(item.file)) {
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                    loadVideos()
                } else {
                    Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
class VideoAdapter(
    private val onPlay: (VideoItem) -> Unit,
    private val onShare: (VideoItem) -> Unit,
    private val onDelete: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {
    private val items = mutableListOf<VideoItem>()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    fun submit(list: List<VideoItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }
    override fun getItemCount(): Int = items.size
    inner class VH(private val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: VideoItem) {
            b.tvName.text = item.file.name
            val sizeMb = item.file.length() / 1024.0 / 1024.0
            b.tvMeta.text = String.format(
                Locale.US, "%s  |  %.1f MB",
                fmt.format(Date(item.file.lastModified())), sizeMb
            )
            b.tvLocked.visibility = if (item.locked) View.VISIBLE else View.GONE
            b.btnPlay.setOnClickListener { onPlay(item) }
            b.btnShare.setOnClickListener { onShare(item) }
            b.btnDelete.setOnClickListener { onDelete(item) }
        }
    }
}