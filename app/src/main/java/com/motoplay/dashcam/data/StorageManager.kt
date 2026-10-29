package com.motoplay.dashcam.data
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.motoplay.dashcam.util.Prefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
/**
 * 录像文件存储管理：
 *  - 分段落盘到 Movies/MOTOPLAY
 *  - 锁定文件移动到 Movies/MOTOPLAY/Locked
 *  - 超出上限自动删除最旧未锁定文件
 *  - Android 10+ 通过 MediaStore 写入相册
 */
object StorageManager {
    private const val DIR_NAME = "MOTOPLAY"
    private const val LOCKED_DIR = "Locked"
    private val tsFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    /** 录像主目录（App 专属外部目录，无需存储权限） */
    fun baseDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
    /** 锁定目录 */
    fun lockedDir(context: Context): File {
        val dir = File(baseDir(context), LOCKED_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
    /** 生成新分段文件名 */
    fun newSegmentFile(context: Context): File {
        val name = "MOTO_${tsFormat.format(Date())}.mp4"
        return File(baseDir(context), name)
    }
    /** 列出所有录像（含锁定），按时间倒序 */
    fun listVideos(context: Context): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        baseDir(context).listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
            ?.forEach { result.add(VideoItem(it, false)) }
        lockedDir(context).listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
            ?.forEach { result.add(VideoItem(it, true)) }
        return result.sortedByDescending { it.file.lastModified() }
    }
    /** 锁定当前文件：移动到 Locked 目录 */
    fun lockFile(context: Context, file: File): File? {
        if (!file.exists()) return null
        val target = File(lockedDir(context), file.name)
        return try {
            if (file.renameTo(target)) target else {
                file.copyTo(target, overwrite = true)
                file.delete()
                target
            }
        } catch (e: Exception) {
            null
        }
    }
    /** 删除文件 */
    fun deleteFile(file: File): Boolean = try {
        file.delete()
    } catch (e: Exception) {
        false
    }
    /**
     * 循环清理：若未锁定文件总大小超过上限，删除最旧的直到低于上限。
     * 返回删除的文件数。
     */
    fun enforceStorageLimit(context: Context): Int {
        val maxBytes = Prefs.maxStorageGb.toLong() * 1024L * 1024L * 1024L
        val unlocked = baseDir(context)
            .listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
            ?.sortedBy { it.lastModified() }
            ?: return 0
        var total = unlocked.sumOf { it.length() }
        var deleted = 0
        for (f in unlocked) {
            if (total <= maxBytes) break
            val len = f.length()
            if (f.delete()) {
                total -= len
                deleted++
            }
        }
        return deleted
    }
    /** 当前已用空间（字节，仅未锁定） */
    fun usedBytes(context: Context): Long =
        baseDir(context).listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
            ?.sumOf { it.length() } ?: 0L
    /**
     * 将完成的录像同步到系统相册（Android 10+ MediaStore）。
     * 失败不影响本地文件。
     */
    fun publishToGallery(context: Context, file: File) {
        if (!file.exists()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$DIR_NAME")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri: Uri? = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { input -> input.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            }
        } catch (e: Exception) {
            // 忽略相册同步失败
        }
    }
}
data class VideoItem(val file: File, val locked: Boolean)