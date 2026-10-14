package com.ahu.ahutong.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.core.view.WindowCompat
import com.ahu.ahutong.ui.components.LocalIsLiquidGlassEnabled
import com.ahu.ahutong.ui.components.LocalAppUiTheme
import com.ahu.ahutong.ui.theme.pack.LocalComponentPack
import com.ahu.ahutong.ui.theme.pack.componentPack
import com.ahu.ahutong.ui.theme.pack.mixing
import com.ahu.ahutong.data.model.AppUiTheme
import com.ahu.ahutong.data.model.AppThemeMode
import com.ahu.ahutong.data.model.DEFAULT_THEME_COLOR
import com.kyant.monet.LocalTonalPalettes
import com.kyant.monet.TonalPalettes.Companion.toTonalPalettes
import com.kyant.monet.dynamicColorScheme
import com.kyant.monet.n1
import com.kyant.monet.toColor
import com.kyant.monet.toSrgb

/**
 * 主题的输入：由宿主（`:app`）从设置里读出后传进来。
 *
 * 设计系统不认识 ViewModel 与设置存储，只认识这几个值——这层解耦既是设计系统能独立成模块的前提，
 * 也让主题可以在预览与测试里被直接构造。
 */
data class AhuThemeConfig(
    val appUiTheme: AppUiTheme,
    val themeColorHex: String?,
    val themeMode: AppThemeMode,
    val isPreferenceReady: Boolean = true,
    /** Theme Park：组件槽位覆盖（槽位 → 实现来源），由宿主从设置读入。 */
    val componentSlotOverrides: Map<com.ahu.ahutong.ui.theme.pack.ComponentSlotId, com.ahu.ahutong.ui.theme.pack.SlotSource> = emptyMap()
)

@Composable
fun AHUTheme(config: AhuThemeConfig, content: @Composable () -> Unit) {
    val provider = com.ahu.ahutong.ui.theme.pack.AppearanceRegistry.provider
    val effectiveConfig = com.ahu.ahutong.ui.theme.pack.AppearanceRegistry.effective(config)
    val themeColorHex = effectiveConfig.themeColorHex
    val themeMode = effectiveConfig.themeMode
    val appUiTheme = effectiveConfig.appUiTheme
    val componentSlotOverrides = effectiveConfig.componentSlotOverrides
    val isUiThemePreferenceReady = effectiveConfig.isPreferenceReady
    val isDarkTheme = themeMode.resolve(isSystemInDarkTheme())
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val themeConfiguration = remember(configuration, isDarkTheme) {
        Configuration(configuration).apply {
            val nightMode = if (isDarkTheme) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        }
    }
    val view = LocalView.current

    SideEffect {
        view.context.findActivity()?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDarkTheme
                isAppearanceLightNavigationBars = !isDarkTheme
            }
        }
    }

    val usesBuiltInDefaultColor =
        appUiTheme == AppUiTheme.MIUIX && themeColorHex == DEFAULT_THEME_COLOR
    val customKeyColor = remember(themeColorHex, usesBuiltInDefaultColor) {
        themeColorHex
            ?.takeUnless { it == DEFAULT_THEME_COLOR }
            ?.let { value ->
            runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrNull()
        }
    }
    val keyColor = when {
        usesBuiltInDefaultColor -> Color(0xFF3482FF)
        customKeyColor != null -> customKeyColor
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            colorResource(id = android.R.color.system_accent1_500)
        else -> Color(0xFF007FAC)
    }
    val tonalPalettes = remember(keyColor) {
        keyColor.toSrgb().toColor().toTonalPalettes()
    }

    CompositionLocalProvider(
        LocalConfiguration provides themeConfiguration,
        LocalTonalPalettes provides tonalPalettes
    ) {
        val colorScheme = if (
            customKeyColor == null &&
            !usesBuiltInDefaultColor &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            if (isDarkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        } else {
            val generated = dynamicColorScheme(isLight = !isDarkTheme)
            if (isDarkTheme) {
                generated.copy(
                    background = 6.n1,
                    onBackground = 90.n1,
                    surface = 6.n1,
                    onSurface = 90.n1,
                    surfaceVariant = 30.n1,
                    onSurfaceVariant = 80.n1,
                    inverseSurface = 90.n1,
                    inverseOnSurface = 20.n1,
                    outline = 60.n1,
                    outlineVariant = 30.n1,
                    surfaceBright = 24.n1,
                    surfaceDim = 6.n1,
                    surfaceContainerLowest = 4.n1,
                    surfaceContainerLow = 10.n1,
                    surfaceContainer = 12.n1,
                    surfaceContainerHigh = 17.n1,
                    surfaceContainerHighest = 22.n1
                )
            } else {
                generated.copy(
                    background = 98.n1,
                    onBackground = 10.n1,
                    surface = 98.n1,
                    onSurface = 10.n1,
                    surfaceVariant = 90.n1,
                    onSurfaceVariant = 30.n1,
                    inverseSurface = 20.n1,
                    inverseOnSurface = 95.n1,
                    outline = 50.n1,
                    outlineVariant = 80.n1,
                    surfaceBright = 98.n1,
                    surfaceDim = 87.n1,
                    surfaceContainerLowest = 100.n1,
                    surfaceContainerLow = 96.n1,
                    surfaceContainer = 94.n1,
                    surfaceContainerHigh = 92.n1,
                    surfaceContainerHighest = 90.n1
                )
            }
        }
        MaterialTheme(colorScheme = colorScheme) {
            val liquidGlassTokens = rememberLiquidGlassTokens(
                enabled = isUiThemePreferenceReady && appUiTheme.usesLiquidGlass
            )
            val componentPack = remember(appUiTheme, componentSlotOverrides, provider) {
                appUiTheme.componentPack.mixing(componentSlotOverrides)
            }
            val themedContent: @Composable () -> Unit = {
                CompositionLocalProvider(
                    LocalContentColor provides if (isDarkTheme) 100.n1 else 0.n1,
                    LocalAppUiTheme provides appUiTheme,
                    LocalIsLiquidGlassEnabled provides liquidGlassTokens.enabled,
                    LocalLiquidGlassTokens provides liquidGlassTokens,
                    LocalComponentPack provides componentPack
                ) { content() }
            }
            if (provider != null) provider.Wrap(effectiveConfig, themedContent) else themedContent()
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
