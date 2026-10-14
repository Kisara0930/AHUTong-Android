package com.ahu.ahutong.plugin.appearance

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
import com.ahu.ahutong.ui.components.*

@Composable
internal fun RadiantPageScaffoldImpl(
    title: String,
    modifier: Modifier,
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
    Box(
        modifier = modifier
            .fillMaxSize()
            .appLiquidGlassSceneBackground(96.n1 withNight 10.n1)
    ) {
        if (search?.visible == true) {
            RadiantSearchHeader(search, Modifier.align(Alignment.TopCenter).zIndex(20f))
        } else {
            SecondaryPageHeader(
                title = title,
                actions = actions,
                subtitle = subtitle,
                trailingContent = trailingContent,
                modifier = Modifier.align(Alignment.TopCenter).zIndex(20f)
            )
        }
        val topPadding = if (subtitle != null) 76.dp else 72.dp
        if (lazyContent != null) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
                contentPadding = PaddingValues(
                    top = topPadding,
                    bottom = bottomPadding
                ),
                verticalArrangement = verticalArrangement,
                userScrollEnabled = scrollEnabled,
                content = lazyContent
            )
        } else if (content != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState(), enabled = scrollEnabled)
                    .padding(
                        top = topPadding,
                        start = 16.dp,
                        end = 16.dp,
                        bottom = bottomPadding
                    ),
                verticalArrangement = verticalArrangement,
                content = content
            )
        } else if (freeContent != null) {
            // 页面自管滚动；壳只负责把内容停在标题栏之下（页面可自行决定穿透或停靠）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(top = topPadding),
                content = freeContent
            )
        }
    }
}

@Composable
internal fun MiuixPageScaffoldImpl(
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
    val scrollBehavior = MiuixScrollBehavior()
    val haptic = LocalHapticFeedback.current
    MiuixScaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            MiuixTopAppBar(
                title = title,
                largeTitle = title,
                color = if (LocalAppBackground.current != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    onBack?.let { callback ->
                        MiuixIconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                callback()
                            }
                        ) {
                            MiuixIcon(
                                imageVector = MiuixIcons.Useful.Back,
                                contentDescription = "返回",
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                actions = {
                    actions.forEach { action ->
                        val painter = action.painter
                        if (painter != null) {
                            androidx.compose.material3.IconButton(onClick = action.onClick) {
                                androidx.compose.material3.Icon(
                                    painter = painter,
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
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding() + 20.dp)
                .navigationBarsPadding()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .scrollEndHaptic()
        ) {
            if (search?.visible == true) {
                ClassicPageScaffoldSearchRow(search)
            } else if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = AppComponentTokens.HeaderHorizontalPadding,
                        vertical = 4.dp
                    )
                )
            }
            if (lazyContent != null) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = bottomPadding),
                    verticalArrangement = verticalArrangement,
                    userScrollEnabled = scrollEnabled,
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
}

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
