package com.motoplay.dashcam.receiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.motoplay.dashcam.ui.MainActivity
import com.motoplay.dashcam.util.Prefs
/**
 * 开机自启。
 * Android 10+ 限制后台启动 Activity，这里优先尝试启动 Activity，
 * 失败则静默（用户手动打开 App 后会自动开始录像）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return
        if (!Prefs.autoStartOnBoot) return
        try {
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_AUTO_START, true)
            }
            context.startActivity(launch)
        } catch (e: Exception) {
            Log.w("BootReceiver", "开机自启 Activity 失败（系统限制）", e)
        }
    }
}