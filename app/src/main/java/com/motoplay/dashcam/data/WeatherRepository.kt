package com.motoplay.dashcam.data
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
/**
 * 天气数据源：使用 wttr.in 免费接口（无需 API Key）。
 */
object WeatherRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    suspend fun fetch(city: String): WeatherInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "https://wttr.in/${java.net.URLEncoder.encode(city, "UTF-8")}?format=j1&lang=zh"
            val request = Request.Builder().url(url).header("User-Agent", "curl/8.0").build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val current = json.getJSONArray("current_condition").getJSONObject(0)
                val temp = current.optString("temp_C", "--")
                val feels = current.optString("FeelsLikeC", "--")
                val humidity = current.optString("humidity", "--")
                val wind = current.optString("windspeedKmph", "--")
                val descArr = current.optJSONArray("lang_zh")
                val desc = if (descArr != null && descArr.length() > 0)
                    descArr.getJSONObject(0).optString("value", "")
                else current.optJSONArray("weatherDesc")?.getJSONObject(0)?.optString("value", "") ?: ""
                WeatherInfo(
                    city = city,
                    temperature = "$temp°C",
                    feelsLike = "$feels°C",
                    humidity = "$humidity%",
                    wind = "$wind km/h",
                    description = desc
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
data class WeatherInfo(
    val city: String,
    val temperature: String,
    val feelsLike: String,
    val humidity: String,
    val wind: String,
    val description: String
)