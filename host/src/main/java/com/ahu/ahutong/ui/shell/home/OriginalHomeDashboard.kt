package com.ahu.ahutong.ui.shell.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material3.MaterialTheme
import com.ahu.ahutong.data.session.AccountGeneration
import com.ahu.ahutong.ui.plugin.PluginRegistry
import com.ahu.ahutong.ui.components.*
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.monet.n1
import com.kyant.monet.withNight
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private data class ActiveDrag(val id: String, val slot: Int?, val topLeft: Offset, val size: IntSize) {
    val center get() = topLeft + Offset(size.width / 2f, size.height / 2f)
}

/** Original header, hero placement, icon grid and drag library; only business card providers changed. */
@Composable
internal fun OriginalHomeDashboard(
    onNavigate: (String) -> Unit,
    schedule: @Composable () -> Unit,
    campusCard: @Composable () -> Unit,
    weather: @Composable () -> Unit
) {
    val accountGeneration by AccountGeneration.changes.collectAsState()
    val plugins by PluginRegistry.plugins.collectAsState()
    val noticeUi = com.ahu.ahutong.ui.shell.pluginPresentation("campus_notices")
    val unread = runCatching {
        if (noticeUi?.get("account")?.asString == com.ahu.ahutong.ui.shell.currentUiAccountKey())
            noticeUi.getAsJsonObject("badges")?.get("campus_notices")?.asInt ?: 0 else 0
    }.getOrDefault(0).coerceAtLeast(0)
    var slots by remember(accountGeneration, plugins.map { it.meta.id }) { mutableStateOf(HomeWidgetPlacement.currentSlots()) }
    var editing by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<ActiveDrag?>(null) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    val slotBounds = remember { mutableStateMapOf<Int, Rect>() }
    var libraryBounds by remember { mutableStateOf<Rect?>(null) }
    var date by remember { mutableStateOf(SimpleDateFormat("MM-dd / EE", Locale.CHINA).format(Date())) }
    LaunchedEffect(Unit) { while (true) { date = SimpleDateFormat("MM-dd / EE", Locale.CHINA).format(Date()); delay(30_000) } }
    BackHandler(editing) { editing = false; drag = null }

    fun save(next: List<String?>) {
        LegacyHomeLayoutStore.saveHomeWidgetSlots(HomeWidgetLayoutFamily.RADIANT, next)
        slots = next
    }
    fun remove(slot: Int) { save(slots.toMutableList().apply { this[slot - 1] = null }) }
    fun add(id: String) {
        val empty = slots.indexOfFirst { it == null }
        if (empty >= 0 && id !in slots) save(slots.toMutableList().apply { this[empty] = id })
    }
    fun begin(id: String, slot: Int?, bounds: Rect) {
        drag = ActiveDrag(id, slot, bounds.topLeft, IntSize(bounds.width.roundToInt(), bounds.height.roundToInt()))
    }
    val highlighted = drag?.let { current -> slotBounds.entries.firstOrNull { it.value.contains(current.center) }?.key }
    fun stop() {
        val current = drag ?: return
        val target = slotBounds.entries.firstOrNull { it.value.contains(current.center) }?.key
        val next = slots.toMutableList()
        if (current.slot != null && libraryBounds?.contains(current.center) == true) {
            next[current.slot - 1] = null; save(next)
        } else if (target != null) {
            if (current.slot == null && next[target - 1] == null) { next[target - 1] = current.id; save(next) }
            else if (current.slot != null && current.slot != target) {
                next[current.slot - 1] = next[target - 1]; next[target - 1] = current.id; save(next)
            }
        }
        drag = null
    }
    GlassBackdropContainer(Modifier.fillMaxSize()) { backdrop ->
        CompositionLocalProvider(LocalLiquidGlassAmbientBackdrop provides backdrop) {
            val headerBlurLayer = rememberLayerBackdrop()
            Box(Modifier.fillMaxSize().appLiquidGlassSceneBackground(96.n1 withNight 10.n1)
                .onGloballyPositioned { rootOrigin = it.boundsInRoot().topLeft }
                .pointerInput(editing) { detectTapGestures(onLongPress = { editing = true }, onTap = { if (editing) { editing = false; drag = null } }) }) {
                Column(Modifier.fillMaxSize().headerBackdropSource(headerBlurLayer)
                    .verticalScroll(rememberScrollState()).systemBarsPadding()
                    .padding(top = 48.dp, bottom = if (editing) 520.dp else 96.dp),
                    verticalArrangement = Arrangement.Center) {
                    schedule()
                    Spacer(Modifier.height(20.dp))
                    HomeWidgetSlotLayout(
                        cardContent = campusCard, onNavigate = onNavigate, slots = slots, noticeUnreadCount = unread,
                        isEditing = editing, highlightedSlot = highlighted, draggingWidgetId = drag?.id,
                        onEnterEdit = { editing = true }, onHomeWidgetClick = ::remove,
                        onSlotPositioned = { index, bounds -> slotBounds[index] = bounds },
                        onHomeWidgetDragStarted = { id, index, bounds -> begin(id, index, bounds) },
                        onHomeWidgetDragged = { change -> drag = drag?.let { it.copy(topLeft = it.topLeft + change) } },
                        onHomeWidgetDragStopped = ::stop
                    )
                }
                Box(Modifier.fillMaxWidth().zIndex(20f)
                    .headerScrim(if (LocalIsLiquidGlassEnabled.current) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surface, headerBlurLayer)
                    .statusBarsPadding().padding(top = 12.dp)) { HomeDateRow(date) { weather() } }
                HomeWidgetLibrarySheet(
                    visible = editing, hiddenDuringLibraryDrag = drag?.slot == null && drag != null,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                    availableWidgets = HomeWidgetRegistry.availableWidgets(true).filter { it.id !in slots },
                    onDismiss = { editing = false; drag = null }, onBoundsChanged = { libraryBounds = it },
                    onLibraryWidgetClick = ::add,
                    onLibraryWidgetDragStarted = { id, bounds -> begin(id, null, bounds) },
                    onLibraryWidgetDragged = { change -> drag = drag?.let { it.copy(topLeft = it.topLeft + change) } },
                    onLibraryWidgetDragStopped = ::stop
                )
                drag?.let { current -> HomeWidgetRegistry.widgetById[current.id]?.let { spec ->
                    HomeWidgetDragOverlay(spec, current.topLeft, current.size, rootOrigin)
                } }
            }
        }
    }
}
