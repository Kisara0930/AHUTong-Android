package com.ahu.ahutong.plugin.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.ahu.ahutong.personalization.recorder.BehaviorRecorder
import com.ahu.ahutong.personalization.semantic.ContentStateBucket
import com.ahu.ahutong.personalization.semantic.ErrorTypeBucket
import com.ahu.ahutong.personalization.semantic.MutationId
import com.ahu.ahutong.personalization.semantic.ResultCountBucket
import com.ahu.ahutong.personalization.semantic.SemanticDomain

class WeatherViewModel(
    private val host: com.ahu.ahutong.core.plugin.PluginHostServices,
    private val behavior: () -> BehaviorRecorder? = { null }
) : ViewModel() {
    private val storage = host.storage()
    private val api = WeatherApi.create(host)


    var weather by mutableStateOf<WeatherResponse?>(null)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var lastCity by mutableStateOf<String?>(null)
        private set

    var lastAdcode by mutableStateOf<String?>(null)
        private set

    var lastLocationName by mutableStateOf<String?>(null)
        private set

    var homeConfig by mutableStateOf(WeatherHomeConfig())
        private set

    init {
        homeConfig = WeatherHomeConfig.fromCache(storage)
    }

    fun fetchWeather(city: String? = null) {
        if (isLoading) return
        isLoading = true
        errorMessage = null

        viewModelScope.launch {
            try {
                val result = api.getWeather(city = city)
                weather = result
                lastCity = city
                lastAdcode = null
                Log.d("Weather", "Weather content loaded")
                reportReady()
            } catch (e: Exception) {
                Log.e("Weather", "Failed to fetch weather", e)
                errorMessage = e.message ?: "获取天气失败"
                reportError()
            } finally {
                isLoading = false
            }
        }
    }

    fun refresh() {
        if (lastAdcode != null) {
            fetchWeatherByAdcode(lastAdcode!!)
        } else {
            fetchWeather(lastCity)
        }
    }

    private fun fetchWeatherByAdcode(adcode: String) {
        if (isLoading) return
        isLoading = true
        errorMessage = null

        viewModelScope.launch {
            try {
                val result = api.getWeather(adcode = adcode)
                weather = result
                lastAdcode = adcode
                lastCity = null
                result.adcode?.let { storage.putString("weather_adcode", it) }
                Log.d("Weather", "Weather content loaded by saved location")
                reportReady()
            } catch (e: Exception) {
                Log.e("Weather", "Failed to fetch weather by saved location", e)
                errorMessage = e.message ?: "获取天气失败"
                reportError()
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * 通过 GPS 定位获取天气（比 IP 定位精准）
     * 降级策略：GPS → 网络定位 → Geocoder 反查城市名 → 调用 API
     */
    fun fetchWeatherByLocation(context: Context) {
        if (isLoading) return
        isLoading = true
        errorMessage = null

        viewModelScope.launch {
            try {
                val cachedAdcode = storage.getString("weather_adcode")
                if (cachedAdcode != null) {
                    // 有缓存的区级 adcode，直接用
                    val result = api.getWeather(adcode = cachedAdcode)
                    weather = result
                    lastAdcode = cachedAdcode
                    lastCity = null
                    // 刷新缓存
                    result.adcode?.let { storage.putString("weather_adcode", it) }
                    reportReady()
                    return@launch
                }

                // 无缓存，GPS 定位获取城市名
                val city = withContext(Dispatchers.IO) { getCityNameFromGps(context) }
                if (city != null) {
                    lastCity = city
                    lastAdcode = null
                    lastLocationName = city
                    val result = api.getWeather(city = city)
                    weather = result
                    // 保存 adcode 供后续精准查询
                    result.adcode?.let { storage.putString("weather_adcode", it) }
                    reportReady()
                } else {
                    Log.w("Weather", "GPS failed, fallback to IP")
                    val result = api.getWeather()
                    weather = result
                    result.adcode?.let { storage.putString("weather_adcode", it) }
                    reportReady()
                }
            } catch (e: Exception) {
                Log.e("Weather", "Failed to fetch weather by location", e)
                try {
                    val result = api.getWeather()
                    weather = result
                    errorMessage = null
                    reportReady()
                } catch (e2: Exception) {
                    Log.e("Weather", "IP weather fallback failed", e2)
                    errorMessage = e2.message ?: "获取天气失败"
                    reportError()
                }
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * GPS 定位 → Geocoder 反查城市名
     * 尝试获取区级名称（locality = 蜀山区），否则市（subAdminArea = 合肥市）
     */
    private fun getCityNameFromGps(context: Context): String? {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFineLocation && !hasCoarseLocation) return null

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = runCatching {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }.getOrNull() ?: runCatching {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull() ?: return null

        val geocoder = Geocoder(context, Locale.CHINA)
        val addresses = runCatching {
            geocoder.getFromLocation(location.latitude, location.longitude, 1)
        }.getOrNull() ?: return null

        val address = addresses.firstOrNull() ?: return null
        // 优先市名（API 更稳定），区名做 fallback
        return address.subAdminArea ?: address.locality
    }

    fun updateHomeConfig(config: WeatherHomeConfig) {
        val previous = homeConfig
        if (previous == config) return
        config.saveToCache(storage)
        val committed = WeatherHomeConfig.fromCache(storage)
        homeConfig = committed
        if (committed != config) return
        behavior()?.reportCommittedMutation(MutationId.WEATHER_HOME_CONFIG_CHANGED, previous, committed, "CONFIG_CHANGED")
    }

    private fun reportReady() {
        behavior()?.recordContentState(SemanticDomain.WEATHER, ContentStateBucket.READY, 0,
            ResultCountBucket.ONE_TO_FIVE, ErrorTypeBucket.NONE)
    }

    private fun reportError() {
        behavior()?.recordContentState(SemanticDomain.WEATHER, ContentStateBucket.ERROR, 7,
            ResultCountBucket.ZERO, ErrorTypeBucket.NETWORK)
    }

    val locationName: String
        get() {
            // 优先 GPS 反查的区位名
            lastLocationName?.let { return it }
            val w = weather ?: return ""
            return listOfNotNull(w.district, w.city, w.province)
                .firstOrNull { it.isNotBlank() } ?: ""
        }
}
