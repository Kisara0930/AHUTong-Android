package com.ahu.ahutong.plugin.weather

import com.ahu.ahutong.core.plugin.PluginStorage

enum class WeatherHomeMode(val cacheValue: String) {
    Detailed("detailed"), Compact("compact");
    companion object {
        fun fromCacheValue(value: String?): WeatherHomeMode =
            values().firstOrNull { it.cacheValue == value } ?: Detailed
    }
}

data class WeatherHomeConfig(
    val showOnHome: Boolean = false,
    val mode: WeatherHomeMode = WeatherHomeMode.Detailed,
    val showTemp: Boolean = true,
    val showWeather: Boolean = true,
    val showAqi: Boolean = true,
    val showLocation: Boolean = true,
) {
    fun saveToCache(storage: PluginStorage) {
        storage.putBoolean("weather_show_on_home", showOnHome)
        storage.putString("weather_home_mode", mode.cacheValue)
        storage.putBoolean("weather_home_show_temp", showTemp)
        storage.putBoolean("weather_home_show_weather", showWeather)
        storage.putBoolean("weather_home_show_aqi", showAqi)
        storage.putBoolean("weather_home_show_location", showLocation)
    }

    companion object {
        fun fromCache(storage: PluginStorage) = WeatherHomeConfig(
            showOnHome = storage.getBoolean("weather_show_on_home") ?: false,
            mode = WeatherHomeMode.fromCacheValue(storage.getString("weather_home_mode")),
            showTemp = storage.getBoolean("weather_home_show_temp") ?: true,
            showWeather = storage.getBoolean("weather_home_show_weather") ?: true,
            showAqi = storage.getBoolean("weather_home_show_aqi") ?: true,
            showLocation = storage.getBoolean("weather_home_show_location") ?: true,
        )
    }
}
