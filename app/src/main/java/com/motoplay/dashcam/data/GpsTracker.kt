package com.motoplay.dashcam.data
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
/**
 * GPS 速度与坐标采集，供水印使用。
 */
class GpsTracker(private val context: Context) {
    private val _state = MutableStateFlow(GpsState())
    val state: StateFlow<GpsState> = _state
    private var locationManager: LocationManager? = null
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
            _state.value = GpsState(
                valid = true,
                latitude = location.latitude,
                longitude = location.longitude,
                speedKmh = speedKmh,
                altitude = if (location.hasAltitude()) location.altitude else 0.0,
                bearing = if (location.hasBearing()) location.bearing else 0f,
                timestamp = location.time
            )
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) return
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager = lm
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            for (p in providers) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
                    lm.getLastKnownLocation(p)?.let { listener.onLocationChanged(it) }
                }
            }
        } catch (e: Exception) {
            // 忽略定位初始化失败
        }
    }
    fun stop() {
        try {
            locationManager?.removeUpdates(listener)
        } catch (e: Exception) {
            // ignore
        }
        locationManager = null
    }
    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
}
data class GpsState(
    val valid: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val speedKmh: Float = 0f,
    val altitude: Double = 0.0,
    val bearing: Float = 0f,
    val timestamp: Long = 0L
) {
    /** 水印文本：速度 + 坐标 */
    fun watermarkText(): String {
        if (!valid) return "GPS 搜索中…"
        val latDir = if (latitude >= 0) "N" else "S"
        val lonDir = if (longitude >= 0) "E" else "W"
        return String.format(
            java.util.Locale.US,
            "%.0f km/h  |  %.6f°%s  %.6f°%s",
            speedKmh, kotlin.math.abs(latitude), latDir, kotlin.math.abs(longitude), lonDir
        )
    }
}