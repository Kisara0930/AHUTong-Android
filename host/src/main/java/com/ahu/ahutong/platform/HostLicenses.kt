package com.ahu.ahutong.platform


import com.ahu.ahutong.data.model.License

/**
 * @Author: SinkDev
 * @Date: 2021/8/26-下午6:30
 * @Email: 468766131@qq.com
 */
object HostLicenses {
    // Existing project acknowledgements, retained by the host settings screen.
    val license by lazy {
        listOf(
            License(
                "AndroidX",
                "Google",
                "https://source.android.com",
                "Apache Software License 2.0"
            ),
            License(
                "Material",
                "Google",
                "https://source.android.com",
                "Apache Software License 2.0"
            ),
            License(
                "Miuix",
                "compose-miuix-ui contributors",
                "https://github.com/compose-miuix-ui/miuix",
                "Apache License 2.0"
            ),
            License(
                "Gson",
                "Google",
                "https://github.com/google/gson",
                "Apache Software License 2.0"
            ),
            License(
                "Okhttp",
                "Square",
                "https://github.com/square/okhttp",
                "Apache Software License 2.0"
            ),
            License(
                "Retrofit",
                "Square",
                "https://github.com/square/retrofit",
                "Apache Software License 2.0"
            ),
            License(
                "Jsoup",
                "jsoup.org",
                "https://jsoup.org/",
                "MIT License."
            ),
            License(
                "MMKV",
                "Tencent",
                "https://github.com/Tencent/MMKV",
                "BSD 3-Clause License."
            ),
            License(
                "Coil",
                "Coil Contributors",
                "https://github.com/coil-kt/coil",
                "Apache Software License 2.0"
            ),
            License(
                "PersistentCookieJar",
                "Fran Montiel ",
                "https://github.com/franmontiel/PersistentCookieJar",
                "Apache Software License 2.0"
            ),
            License(
                "ZXing Android Embedded",
                "JourneyApps",
                "https://github.com/journeyapps/zxing-android-embedded",
                "Apache Software License 2.0"
            ),
            License(
                "Monet",
                "Kyant0",
                "https://github.com/Kyant0/Monet",
                "Apache Software License 2.0"
            ),
            License(
                "Backdrop",
                "Kyant0",
                "https://github.com/Kyant0/AndroidLiquidGlass",
                "Apache Software License 2.0"
            ),
            License(
                "Capsule",
                "Kyant0",
                "https://github.com/Kyant0/Capsule",
                "Apache Software License 2.0"
            ),
            License(
                "GuiXu (Rust rewrite)",
                "Delsart and GuiXu Rust contributors",
                "https://github.com/Yukon163/GuiXu/tree/rust-rewrite",
                "Apache License 2.0",
                "licenses/guixu/LICENSE",
                "licenses/guixu/NOTICE"
            ),
            License(
                "学小通",
                "InChange-Jiang",
                "https://github.com/InChange-Jiang/Xuexiaotong",
                "Apache License 2.0"
            ),
            License(
                "Ahu_Plus",
                "yoki-Clark",
                "https://github.com/yoki-Clark/Ahu_Plus",
                "该仓库由于作者策略暂时关闭，更新后会同步到新仓库"
            ),
            License(
                "More...",
                "Various Developers",
                "https://github.com/",
                "..."
            )

        )
    }
}
