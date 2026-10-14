package com.ahu.ahutong.ui.shell

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.ahu.ahutong.core.storage.HomeBackgroundStore
import com.ahu.ahutong.ui.components.*
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.monet.n1
import com.kyant.monet.withNight

/** Reuses the original wallpaper/backdrop host for login, main navigation and feature screens. */
@Composable
fun OriginalAppPresentation(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val revision by HomeBackgroundStore.revision.collectAsState()
    val background = remember(revision) {
        if (HomeBackgroundStore.isEnabled) HomeBackgroundStore.blurredFile(context).takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
            ?.let { AppBackground(it, HomeBackgroundStore.maskPercent / 100f) } else null
    }
    CompositionLocalProvider(LocalAppBackground provides background,
        LocalGlassReadabilityBoost provides (background != null),
        LocalGlassEffectsReduced provides HomeBackgroundStore.reduceGlassEffects) {
        LiquidGlassAppHost(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
internal fun OriginalMainFrame(route: String, onSelect: (String) -> Unit, content: @Composable () -> Unit) {
    val learningUi = pluginPresentation("chaoxing")
    LaunchedEffect(learningUi?.get("primaryMode")?.asString) {
        learningUi?.get("primaryMode")?.asString?.let { ShellLearningDockState.showSchedule = it == "schedule" }
    }
    val contentBackdrop = LocalLiquidGlassContentBackdrop.current
    val backdrop = if (LocalAppBackground.current != null)
        rememberCombinedBackdrop(LocalLiquidGlassAmbientBackdrop.current, contentBackdrop) else contentBackdrop
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().captureLiquidGlassContent().appLiquidGlassSceneBackground(96.n1 withNight 10.n1)) { content() }
        BottomNavBar(backdrop, route, onSelect)
    }
}
