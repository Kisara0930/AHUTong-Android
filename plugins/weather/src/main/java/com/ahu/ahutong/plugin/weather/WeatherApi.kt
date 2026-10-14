package com.ahu.ahutong.plugin.weather

import retrofit2.http.GET
import com.ahu.ahutong.data.network.retrofit
import retrofit2.http.Query

interface WeatherApi {

    @GET("api/v1/misc/weather")
    suspend fun getWeather(
        @Query("city") city: String? = null,
        @Query("adcode") adcode: String? = null,
        @Query("extended") extended: Boolean = true,
        @Query("indices") indices: Boolean = true,
        @Query("forecast") forecast: Boolean = true,
        @Query("hourly") hourly: Boolean = true,
        @Query("lang") lang: String = "zh"
    ): WeatherResponse

    companion object {
        fun create(host: com.ahu.ahutong.core.plugin.PluginHostServices): WeatherApi =
            retrofit("https://uapis.cn/", host.http()).create(WeatherApi::class.java)
    }
}
