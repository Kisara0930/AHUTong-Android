package com.ahu.ahutong.plugin.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.monet.a1
import com.kyant.monet.n1
import com.kyant.monet.withNight
import com.ahu.ahutong.ui.theme.pack.LocalComponentPack
import com.ahu.ahutong.ui.components.*

@Composable
internal fun RadiantSearchHeader(
    search: SecondarySearchState,
    modifier: Modifier = Modifier
) {
    val headerBg = if (LocalIsLiquidGlassEnabled.current) {
        MaterialTheme.colorScheme.surfaceContainerLowest
    } else {
        MaterialTheme.colorScheme.surface
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to headerBg,
                        0.35f to headerBg,
                        0.68f to headerBg.copy(alpha = 0.85f),
                        1f to headerBg.copy(alpha = 0f)
                    )
                )
            )
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = search.onClose,
                modifier = Modifier.padding(start = 2.dp, end = 4.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    "关闭搜索",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            OutlinedTextField(
                value = search.query,
                onValueChange = search.onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                singleLine = true,
                placeholder = { Text(search.placeholder) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = 0.n1 withNight 100.n1,
                    unfocusedTextColor = 0.n1 withNight 100.n1,
                    cursorColor = 90.a1 withNight 90.a1
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search.onSubmit() }),
                trailingIcon = {
                    if (search.query.isNotEmpty()) {
                        IconButton(onClick = { search.onQueryChange("") }) {
                            Icon(Icons.Default.Close, "清空", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    } else {
                        IconButton(onClick = search.onSubmit) {
                            Icon(Icons.Default.Search, "搜索", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            )
        }
    }
}
