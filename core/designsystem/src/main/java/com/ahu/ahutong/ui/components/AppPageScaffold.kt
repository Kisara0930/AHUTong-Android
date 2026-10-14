package com.ahu.ahutong.ui.components

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ahu.ahutong.ui.theme.pack.LocalComponentPack
import com.kyant.monet.n1
import com.kyant.monet.withNight
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.useful.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 统一页面壳（P0 组件）：一套 API 覆盖所有二级页。
 *
 * 历史双壳（SecondaryPageScaffold / AppXxxPageLayout）已整体删除——
 * 页面声明语义参数（标题/副标题/操作/搜索态）+ 内容（[content] 滚动流 / [lazyContent] 懒列表 /
 * [freeContent] 页面自管滚动的自由容器，三选一），头部形态与滚动行为由当前主题的
 * ComponentPack 决定：
 * - Radiant：固定渐变悬浮标题栏，内容从其下穿过
 * - Miuix：大标题收起式 TopAppBar（滚动联动）
 * - 其他：内联标题行（AppPageHeader）
 *
 * 页面自管滚动的场景（课表/校历等）继续使用 AppPageLayout。
 *
 * 注意：壳统一提供 [content] 的水平内边距（Radiant 16dp / 其他主题 20dp），页面内容勿再自带；
 * [lazyContent] 则不加水平 padding（列表项按惯例自带）；[freeContent] 完全自管（课表/校历/学习资料用）。
 */
@Composable
fun AppPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: List<TrailingAction> = emptyList(),
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    search: SecondarySearchState? = null,
    scrollEnabled: Boolean = true,
    bottomPadding: Dp = 112.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(24.dp),
    listState: LazyListState = rememberLazyListState(),
    content: (@Composable ColumnScope.() -> Unit)? = null,
    lazyContent: (LazyListScope.() -> Unit)? = null,
    freeContent: (@Composable BoxScope.() -> Unit)? = null
) {
    require(
        listOfNotNull(content, lazyContent, freeContent).size == 1
    ) { "AppPageScaffold 需要 content / lazyContent / freeContent 恰好其一" }
    LocalComponentPack.current.PageScaffold(
        title, modifier, onBack, subtitle, actions, trailingContent, search,
        scrollEnabled, bottomPadding, verticalArrangement, listState, content, lazyContent, freeContent
    )
}

/** Material/兜底：内联标题行（AppPageHeader）+ 搜索内联 + 内容区。 */
@Composable
internal fun ClassicPageScaffoldImpl(
    title: String,
    modifier: Modifier,
    onBack: (() -> Unit)?,
    subtitle: String?,
    actions: List<TrailingAction>,
    trailingContent: (@Composable RowScope.() -> Unit)?,
    search: SecondarySearchState?,
    scrollEnabled: Boolean,
    bottomPadding: Dp,
    verticalArrangement: Arrangement.Vertical,
    listState: LazyListState,
    content: (@Composable ColumnScope.() -> Unit)?,
    lazyContent: (LazyListScope.() -> Unit)?,
    freeContent: (@Composable BoxScope.() -> Unit)?
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        ClassicPageScaffoldHeader(
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            actions = actions,
            trailingContent = trailingContent,
            search = search
        )
        if (lazyContent != null) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = bottomPadding),
                verticalArrangement = verticalArrangement,
                content = lazyContent
            )
        } else if (content != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState(), enabled = scrollEnabled)
                    .padding(
                        start = AppComponentTokens.HeaderHorizontalPadding,
                        end = AppComponentTokens.HeaderHorizontalPadding,
                        bottom = bottomPadding
                    ),
                verticalArrangement = verticalArrangement,
                content = content
            )
        } else if (freeContent != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                content = freeContent
            )
        }
    }
}

/** Radiant：固定渐变悬浮标题栏（搜索态切搜索栏），内容穿透其下滚动。 */


/** Miuix：大标题收起式 TopAppBar + 滚动联动；搜索态在顶栏下内联。 */


/** Classic/Miuix 共用的头部：搜索态内联搜索行，否则 AppPageHeader（含返回）+ 可选副标题。 */
@Composable
private fun ClassicPageScaffoldHeader(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    actions: List<TrailingAction>,
    trailingContent: (@Composable RowScope.() -> Unit)?,
    search: SecondarySearchState?
) {
    if (search?.visible == true) {
        ClassicPageScaffoldSearchRow(search)
    } else {
        AppPageHeader(
            title = title,
            onBack = onBack,
            actions = {
                actions.forEach { action ->
                    if (action.painter != null) {
                        androidx.compose.material3.IconButton(onClick = action.onClick) {
                            androidx.compose.material3.Icon(
                                painter = action.painter,
                                contentDescription = action.contentDescription
                            )
                        }
                    } else {
                        AppHeaderIconButton(
                            imageVector = action.icon,
                            contentDescription = action.contentDescription,
                            onClick = action.onClick
                        )
                    }
                }
                trailingContent?.invoke(this)
            }
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AppComponentTokens.HeaderHorizontalPadding)
            )
        }
    }
}

/** 内联搜索行（返回 + 输入框 + 清空/提交），Classic 与 Miuix 壳共用。 */
@Composable
private fun ClassicPageScaffoldSearchRow(search: SecondarySearchState) {
    val borderlessWallpaper = LocalAppBackground.current != null &&
        LocalAppUiTheme.current == com.ahu.ahutong.data.model.AppUiTheme.MATERIAL
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = search.onClose) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "关闭搜索"
            )
        }
        if (LocalAppUiTheme.current == com.ahu.ahutong.data.model.AppUiTheme.MIUIX) {
            AppTextField(
                value = search.query,
                onValueChange = search.onQueryChange,
                label = search.placeholder,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search.onSubmit() })
            )
            MiuixIconButton(onClick = if (search.query.isNotEmpty()) {
                { search.onQueryChange("") }
            } else search.onSubmit) {
                MiuixIcon(
                    imageVector = if (search.query.isNotEmpty()) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = if (search.query.isNotEmpty()) "清空" else "搜索"
                )
            }
        } else OutlinedTextField(
            value = search.query,
            onValueChange = search.onQueryChange,
            modifier = Modifier.weight(1f).then(
                if (borderlessWallpaper) Modifier.appWallpaperFrostedSurface(
                    AppComponentTokens.ControlShape,
                    MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.66f)
                ) else Modifier
            ),
            singleLine = true,
            placeholder = { Text(search.placeholder) },
            shape = if (borderlessWallpaper) AppComponentTokens.ControlShape
                else OutlinedTextFieldDefaults.shape,
            colors = if (borderlessWallpaper) OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                errorBorderColor = Color.Transparent
            ) else OutlinedTextFieldDefaults.colors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search.onSubmit() }),
            trailingIcon = {
                if (search.query.isNotEmpty()) {
                    IconButton(onClick = { search.onQueryChange("") }) {
                        Icon(Icons.Default.Close, "清空")
                    }
                } else {
                    IconButton(onClick = search.onSubmit) {
                        Icon(Icons.Default.Search, "搜索")
                    }
                }
            }
        )
    }
}
