package com.motoplay.dashcam.ui
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.motoplay.dashcam.data.WeatherInfo
import com.motoplay.dashcam.data.WeatherRepository
import com.motoplay.dashcam.util.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
class MainViewModel : ViewModel() {
    private val _timeText = MutableStateFlow("00:00")
    val timeText: StateFlow<String> = _timeText
    private val _secondsText = MutableStateFlow("00")
    val secondsText: StateFlow<String> = _secondsText
    private val _dateText = MutableStateFlow("")
    val dateText: StateFlow<String> = _dateText
    private val _weekdayText = MutableStateFlow("")
    val weekdayText: StateFlow<String> = _weekdayText
    private val _weather = MutableStateFlow<WeatherInfo?>(null)
    val weather: StateFlow<WeatherInfo?> = _weather
    private var clockStarted = false
    private var weatherStarted = false
    fun startClock() {
        if (clockStarted) return
        clockStarted = true
        viewModelScope.launch {
            val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
            val ss = SimpleDateFormat("ss", Locale.getDefault())
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val weekdays = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
            while (isActive) {
                val now = Date()
                _timeText.value = hm.format(now)
                _secondsText.value = ss.format(now)
                _dateText.value = dateFmt.format(now)
                val cal = Calendar.getInstance().apply { time = now }
                _weekdayText.value = weekdays[cal.get(Calendar.DAY_OF_WEEK) - 1]
                delay(1000L - (System.currentTimeMillis() % 1000L))
            }
        }
        loadWeather()
    }
    private fun loadWeather() {
        if (weatherStarted) return
        weatherStarted = true
        viewModelScope.launch {
            while (isActive) {
                val info = WeatherRepository.fetch(Prefs.weatherCity)
                if (info != null) _weather.value = info
                delay(30 * 60 * 1000L)
            }
        }
    }
}