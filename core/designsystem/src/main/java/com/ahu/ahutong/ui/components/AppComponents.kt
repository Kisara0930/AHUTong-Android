package com.ahu.ahutong.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.ButtonDefaults as MaterialButtonDefaults
import androidx.compose.material3.Card as MaterialCard
import androidx.compose.material3.CardDefaults as MaterialCardDefaults
import androidx.compose.material3.CircularProgressIndicator as MaterialCircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton as MaterialFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch as MaterialSwitch
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet as MaterialModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.ahu.ahutong.ui.shape.SmoothRoundedCornerShape
import com.ahu.ahutong.data.model.AppUiTheme
import com.ahu.ahutong.ui.theme.LiquidGlassSurfaceLevel
import com.kyant.backdrop.Backdrop
import com.ahu.ahutong.ui.theme.pack.LocalComponentPack
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonColors as MiuixButtonColors
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator as MiuixCircularProgressIndicator
import top.yukonga.miuix.kmp.basic.FloatingActionButton as MiuixFloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults as MiuixProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.Surface as MiuixSurface
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.extra.SuperBottomSheet as MiuixSuperBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.useful.Back
import top.yukonga.miuix.kmp.icon.icons.useful.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Shared geometry for app-level controls and surfaces. */
object AppComponentTokens {
    val TouchTarget = 48.dp
    val SearchFieldHeight = 56.dp
    val ChipHeight = 40.dp
    val HeaderHorizontalPadding = 20.dp
    val HeaderVerticalPadding = 14.dp
    val ControlShape = SmoothRoundedCornerShape(24.dp)
    val CardShape = SmoothRoundedCornerShape(24.dp)
    val LargeCardShape = SmoothRoundedCornerShape(32.dp)
    val DialogShape = SmoothRoundedCornerShape(28.dp)
    val DialogMaxWidth = 560.dp
}

enum class AppButtonVariant {
    Primary,
    Secondary,
    Destructive
}

data class AppSelectOption<T>(
    val value: T,
    val label: String
)

/**
 * AppSectionCard：内容分区卡（阶段④ 模式A 的统一出口）。
 * 消灭页面层「if (radiant) GlassCard{...} else 裸 Column{...}」结构分叉：
 * 全主题统一卡片化；卡片质感按主题分发（Radiant 实色 GlassCard / 其他主题 AppCard）。
 */
@Composable
fun AppSectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    if (isRadiantUi) {
        GlassCard(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(contentPadding),
                verticalArrangement = verticalArrangement,
                content = content
            )
        }
    } else {
        AppCard(
            modifier = modifier.fillMaxWidth(),
            contentPadding = contentPadding
        ) {
            Column(verticalArrangement = verticalArrangement, content = content)
        }
    }
}

/** A theme-native content card without leaking Material ripple or geometry into other themes. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    shape: Shape = AppComponentTokens.CardShape,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    backdrop: Backdrop? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    LocalComponentPack.current.Card(
        modifier = modifier,
        shape = shape,
        contentPadding = contentPadding,
        enabled = enabled,
        onClick = onClick,
        backdrop = backdrop,
        content = content
    )
}

@Composable
internal fun MaterialCardImpl(
    modifier: Modifier,
    shape: Shape,
    contentPadding: PaddingValues,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit
) {
    val wallpaperEnabled = LocalAppBackground.current != null &&
        LocalAppUiTheme.current == AppUiTheme.MATERIAL
    val cardModifier = if (wallpaperEnabled) {
        modifier.appWallpaperFrostedSurface(
            shape,
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.66f)
        )
    } else modifier
    val containerColor = if (wallpaperEnabled) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer
    if (onClick == null) {
        MaterialCard(
            modifier = cardModifier,
            shape = shape,
            colors = MaterialCardDefaults.cardColors(
                containerColor = containerColor
            )
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    } else {
        MaterialCard(
            onClick = onClick,
            modifier = cardModifier,
            enabled = enabled,
            shape = shape,
            colors = MaterialCardDefaults.cardColors(
                containerColor = containerColor
            )
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}

@Composable
fun AppHeaderIconButton(
    imageVector: ImageVector,
    miuixImageVector: ImageVector = imageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    tint: Color? = null
) {
    LocalComponentPack.current.HeaderIconButton(
        imageVector, miuixImageVector, contentDescription, onClick, modifier, backdrop, tint
    )
}

@Composable
internal fun MaterialHeaderIconButtonImpl(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier,
    tint: Color?
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(AppComponentTokens.TouchTarget)
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun AppPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backdrop: Backdrop? = null,
    horizontalPadding: Dp = AppComponentTokens.HeaderHorizontalPadding,
    verticalPadding: Dp = AppComponentTokens.HeaderVerticalPadding,
    actions: @Composable RowScope.() -> Unit = {}
) {
    LocalComponentPack.current.PageHeader(
        title, modifier, onBack, backdrop, horizontalPadding, verticalPadding, actions
    )
}

/** 行式页面标题（返回按钮 + 大标题 + 操作位）：Material / Radiant 共用的默认形态。 */
@Composable
internal fun RowPageHeaderImpl(
    title: String,
    modifier: Modifier,
    onBack: (() -> Unit)?,
    backdrop: Backdrop?,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    actions: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = horizontalPadding,
                vertical = verticalPadding
            ),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        onBack?.let {
            AppHeaderIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                onClick = it,
                backdrop = backdrop
            )
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            style = if (onBack == null) {
                MaterialTheme.typography.headlineLarge
            } else {
                MaterialTheme.typography.headlineMedium
            },
            fontWeight = FontWeight.SemiBold
        )
        actions()
    }
}

/** Miuix 大标题可折叠顶栏。 */

/** Theme-native indeterminate loading control. */
@Composable
fun AppCircularProgressIndicator(
    progress: (() -> Float)? = null,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    strokeWidth: Dp = 4.dp,
    color: Color? = null
) {
    LocalComponentPack.current.CircularProgressIndicator(progress, modifier, size, strokeWidth, color)
}

@Composable
internal fun MaterialProgressImpl(
    progress: (() -> Float)?,
    modifier: Modifier,
    size: Dp,
    strokeWidth: Dp,
    color: Color?
) {
    if (progress == null) {
        MaterialCircularProgressIndicator(
            modifier = modifier.size(size),
            color = color ?: MaterialTheme.colorScheme.primary,
            strokeWidth = strokeWidth
        )
    } else {
        MaterialCircularProgressIndicator(
            progress = progress,
            modifier = modifier.size(size),
            color = color ?: MaterialTheme.colorScheme.primary,
            strokeWidth = strokeWidth
        )
    }
}

@Composable
fun AppFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    LocalComponentPack.current.FloatingActionButton(onClick, modifier, content)
}

@Composable
internal fun MaterialFabImpl(
    onClick: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    MaterialFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        content = content
    )
}

@Composable
fun AppSearchHeader(
    title: String,
    searchActive: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchOpen: () -> Unit,
    onSearchClose: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    edgePadding: Dp = AppComponentTokens.HeaderHorizontalPadding,
    actions: @Composable RowScope.() -> Unit = {}
) {
    if (!searchActive) {
        AppPageHeader(
            title = title,
            modifier = modifier,
            horizontalPadding = edgePadding,
            actions = {
                AppHeaderIconButton(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "搜索",
                    onClick = onSearchOpen
                )
                actions()
            }
        )
        return
    }

    BackHandler(onBack = onSearchClose)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = edgePadding,
                vertical = AppComponentTokens.HeaderVerticalPadding
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppHeaderIconButton(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "关闭搜索",
            onClick = onSearchClose
        )
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .height(AppComponentTokens.SearchFieldHeight),
            singleLine = true,
            placeholder = { Text(placeholder) },
            shape = AppComponentTokens.ControlShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
            trailingIcon = {
                when {
                    query.isNotEmpty() -> IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "清空")
                    }
                    onSearch != null -> IconButton(onClick = onSearch) {
                        Icon(Icons.Rounded.Search, contentDescription = "搜索")
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
fun AppSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (String) -> Unit = {}
) {
    LocalComponentPack.current.SearchField(value, onValueChange, placeholder, modifier, onSearch)
}

@Composable
internal fun OutlinedSearchFieldImpl(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier,
    onSearch: (String) -> Unit,
    liquid: Boolean
) {
    val wallpaperEnabled = !liquid && LocalAppBackground.current != null
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = if (wallpaperEnabled) modifier.appWallpaperFrostedSurface(
            AppComponentTokens.ControlShape,
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.66f)
        ) else modifier,
        singleLine = true,
        placeholder = { Text(placeholder) },
        shape = AppComponentTokens.ControlShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(value) }),
        colors = if (wallpaperEnabled) {
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                errorBorderColor = Color.Transparent
            )
        } else if (liquid) {
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.28f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.28f),
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.48f)
            )
        } else {
            OutlinedTextFieldDefaults.colors()
        }
    )
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    LocalComponentPack.current.TextField(
        value, onValueChange, label, modifier, enabled, singleLine,
        keyboardOptions, keyboardActions, visualTransformation
    )
}

@Composable
internal fun OutlinedTextFieldImpl(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    enabled: Boolean,
    singleLine: Boolean,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    visualTransformation: VisualTransformation,
    liquid: Boolean
) {
    val wallpaperEnabled = !liquid && LocalAppBackground.current != null
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = if (wallpaperEnabled) modifier.appWallpaperFrostedSurface(
            AppComponentTokens.ControlShape,
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.66f)
        ) else modifier,
        enabled = enabled,
        singleLine = singleLine,
        label = { Text(label) },
        shape = AppComponentTokens.ControlShape,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        colors = if (wallpaperEnabled) {
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                errorBorderColor = Color.Transparent
            )
        } else if (liquid) {
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.28f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.28f),
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.48f)
            )
        } else {
            OutlinedTextFieldDefaults.colors()
        }
    )
}

@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: AppButtonVariant = AppButtonVariant.Primary,
    content: @Composable RowScope.() -> Unit
) {
    LocalComponentPack.current.Button(onClick, modifier, enabled, variant, content)
}

@Composable
internal fun MaterialButtonImpl(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    variant: AppButtonVariant,
    content: @Composable RowScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val materialModifier = modifier.heightIn(min = AppComponentTokens.TouchTarget)
    when (variant) {
        AppButtonVariant.Secondary -> FilledTonalButton(
            onClick = onClick,
            modifier = materialModifier,
            enabled = enabled,
            content = content
        )
        AppButtonVariant.Primary, AppButtonVariant.Destructive -> MaterialButton(
            onClick = onClick,
            modifier = materialModifier,
            enabled = enabled,
            colors = if (variant == AppButtonVariant.Destructive) {
                MaterialButtonDefaults.buttonColors(
                    containerColor = colors.error,
                    contentColor = colors.onError
                )
            } else {
                MaterialButtonDefaults.buttonColors()
            },
            content = content
        )
    }
}

@Composable
fun AppToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null
) {
    LocalComponentPack.current.Toggle(checked, onCheckedChange, modifier, enabled, contentDescription)
}

@Composable
internal fun MaterialToggleImpl(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier,
    enabled: Boolean,
    contentDescription: String? = null
) {
    MaterialSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled
    )
}

/** 主题无感的下拉选择：分发到当前 [LocalComponentPack] 的实现。 */
@Composable
fun <T> AppSelectField(
    label: String,
    selected: T?,
    options: List<AppSelectOption<T>>,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "请选择",
    enabled: Boolean = true,
    valueTextAlign: TextAlign = TextAlign.Start,
    miuixInsideMargin: PaddingValues = PaddingValues(16.dp),
    miuixStandalone: Boolean = false,
    liquidLabelWeight: Float = 1f,
    liquidValueWeight: Float = 1f
) {
    LocalComponentPack.current.SelectField(
        label = label,
        selected = selected,
        options = options,
        onSelected = onSelected,
        modifier = modifier,
        placeholder = placeholder,
        enabled = enabled,
        valueTextAlign = valueTextAlign,
        miuixInsideMargin = miuixInsideMargin,
        miuixStandalone = miuixStandalone,
        liquidLabelWeight = liquidLabelWeight,
        liquidValueWeight = liquidValueWeight
    )
}

@Composable
internal fun <T> MaterialSelectFieldImpl(
    label: String,
    selected: T?,
    options: List<AppSelectOption<T>>,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "请选择",
    enabled: Boolean = true,
    valueTextAlign: TextAlign = TextAlign.Start
) {
    val selectedLabel = remember(options, selected, placeholder) {
        options.find { it.value == selected }?.label ?: placeholder
    }
    MaterialSelectField(
        label = label,
        selected = selected,
        selectedLabel = selectedLabel,
        options = options,
        onSelected = onSelected,
        modifier = modifier,
        enabled = enabled,
        valueTextAlign = valueTextAlign
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> MaterialSelectField(
    label: String,
    selected: T?,
    selectedLabel: String,
    options: List<AppSelectOption<T>>,
    onSelected: (T) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    valueTextAlign: TextAlign
) {
    var expanded by remember { mutableStateOf(false) }
    val wallpaperEnabled = LocalAppBackground.current != null &&
        LocalAppUiTheme.current == AppUiTheme.MATERIAL
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = valueTextAlign),
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .menuAnchor(
                    type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                    enabled = enabled
                )
                .fillMaxWidth()
                .then(if (wallpaperEnabled) Modifier.appWallpaperFrostedSurface(
                    AppComponentTokens.ControlShape,
                    MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.66f)
                ) else Modifier),
            colors = if (wallpaperEnabled) OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                errorBorderColor = Color.Transparent
            ) else OutlinedTextFieldDefaults.colors()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            matchAnchorWidth = true,
            shape = MenuDefaults.shape,
            containerColor = MenuDefaults.containerColor,
            tonalElevation = MenuDefaults.TonalElevation,
            shadowElevation = MenuDefaults.ShadowElevation
        ) {
            options.forEach { option ->
                val isSelected = option.value == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = valueTextAlign
                        )
                    },
                    trailingIcon = {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    modifier = Modifier.background(
                        if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                    ),
                    onClick = {
                        onSelected(option.value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun LiquidGlassDropdownIndicator(
    expanded: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "liquid settings dropdown arrow"
    )
    Canvas(
        modifier = modifier
            .size(18.dp)
            .graphicsLayer { rotationZ = rotation }
    ) {
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(size.width * 0.2f, size.height * 0.38f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.68f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.68f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.8f, size.height * 0.38f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun <T> LiquidGlassDropdownPopup(
    expanded: Boolean,
    anchorBoundsInWindow: IntRect,
    popupWidth: Dp,
    selected: T?,
    options: List<AppSelectOption<T>>,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit
) {
    val visibility = remember { MutableTransitionState(false) }
    val density = LocalDensity.current
    val popupGapPx = with(density) { 6.dp.roundToPx() }
    val popupBackdrop = LocalLiquidGlassContentBackdrop.current
    val popupShape = SmoothRoundedCornerShape(20.dp)
    val haptic = LocalHapticFeedback.current
    val positionProvider = remember(popupGapPx, anchorBoundsInWindow) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val fieldBounds = anchorBoundsInWindow.takeIf { it.width > 0 && it.height > 0 }
                    ?: anchorBounds
                val preferredX = fieldBounds.right - popupContentSize.width
                val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                val x = preferredX.coerceIn(0, maxX)
                val below = fieldBounds.bottom + popupGapPx
                val above = fieldBounds.top - popupContentSize.height - popupGapPx
                val y = if (below + popupContentSize.height <= windowSize.height) {
                    below
                } else {
                    above.coerceAtLeast(0)
                }
                return IntOffset(x, y)
            }
        }
    }
    LaunchedEffect(expanded) {
        visibility.targetState = expanded
    }
    if (
        (visibility.currentState || visibility.targetState) &&
        anchorBoundsInWindow.width > 0 &&
        popupWidth > 0.dp
    ) {
        Popup(
            onDismissRequest = onDismiss,
            popupPositionProvider = positionProvider,
            properties = PopupProperties(focusable = true)
        ) {
            val selectedColor = MaterialTheme.colorScheme.primary
            AnimatedVisibility(
                visibleState = visibility,
                enter = fadeIn(tween(durationMillis = 120)) + scaleIn(
                    initialScale = 0.96f,
                    transformOrigin = TransformOrigin(1f, 0f),
                    animationSpec = tween(durationMillis = 160)
                ),
                exit = fadeOut(tween(durationMillis = 90)) + scaleOut(
                    targetScale = 0.98f,
                    transformOrigin = TransformOrigin(1f, 0f),
                    animationSpec = tween(durationMillis = 110)
                )
            ) {
                Column(
                    modifier = Modifier
                        .width(popupWidth)
                        .heightIn(max = 360.dp)
                        .shadow(
                            elevation = 10.dp,
                            shape = popupShape,
                            clip = false,
                            ambientColor = Color.Black.copy(alpha = 0.08f),
                            spotColor = Color.Black.copy(alpha = 0.12f)
                        )
                        .appLiquidGlassSurface(
                            shape = popupShape,
                            fallbackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            level = LiquidGlassSurfaceLevel.Floating,
                            backdrop = popupBackdrop,
                            backdropSamplingEnabled = true,
                            blurRadiusMultiplier = 1.6f,
                            tintAlphaMultiplier = 1.5f
                        )
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 6.dp)
                ) {
                    options.forEach { option ->
                        val isSelected = option.value == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(
                                    interactionSource = remember(option.value) {
                                        MutableInteractionSource()
                                    },
                                    indication = null,
                                    role = Role.Button
                                ) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelected(option.value)
                                    onDismiss()
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(22.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Canvas(modifier = Modifier.size(18.dp)) {
                                        drawLine(
                                            color = selectedColor,
                                            start = androidx.compose.ui.geometry.Offset(
                                                size.width * 0.12f,
                                                size.height * 0.55f
                                            ),
                                            end = androidx.compose.ui.geometry.Offset(
                                                size.width * 0.4f,
                                                size.height * 0.8f
                                            ),
                                            strokeWidth = 2.2.dp.toPx(),
                                            cap = StrokeCap.Round
                                        )
                                        drawLine(
                                            color = selectedColor,
                                            start = androidx.compose.ui.geometry.Offset(
                                                size.width * 0.4f,
                                                size.height * 0.8f
                                            ),
                                            end = androidx.compose.ui.geometry.Offset(
                                                size.width * 0.9f,
                                                size.height * 0.2f
                                            ),
                                            strokeWidth = 2.2.dp.toPx(),
                                            cap = StrokeCap.Round
                                        )
                                    }
                                }
                            }
                            Text(
                                text = option.label,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    LocalComponentPack.current.FilterChip(selected, onClick, label, modifier, enabled)
}

@Composable
internal fun MaterialFilterChipImpl(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier,
    enabled: Boolean
) {
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier.heightIn(min = AppComponentTokens.ChipHeight),
        enabled = enabled,
        shape = AppComponentTokens.ControlShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surfaceContainerHigh,
            labelColor = colors.onSurfaceVariant,
            selectedContainerColor = colors.secondaryContainer,
            selectedLabelColor = colors.onSecondaryContainer,
            disabledContainerColor = colors.surfaceContainer,
            disabledLabelColor = colors.onSurface.copy(alpha = 0.38f)
        )
    )
}

@Composable
fun AppDialogSurface(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = modifier
                    .fillMaxWidth(0.9f)
                    .widthIn(max = AppComponentTokens.DialogMaxWidth)
                    .appLiquidGlassSurface(
                        shape = AppComponentTokens.DialogShape,
                        fallbackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        level = LiquidGlassSurfaceLevel.Floating,
                        backdropSamplingEnabled = false
                    ),
                content = content
            )
        }
    }
}

/**
 * Theme-native modal sheet host.
 *
 * Miuix delegates to the library's SuperBottomSheet, Material keeps the M3
 * implementation, and LiquidGlass owns its scrim, surface, and motion instead
 * of wrapping a transparent Material sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppModalBottomSheet(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    LocalComponentPack.current.ModalBottomSheet(title, onDismissRequest, modifier, content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaterialModalBottomSheetImpl(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    MaterialModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}
