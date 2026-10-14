package com.ahu.ahutong.ui.shell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.ahu.ahutong.personalization.action.AppActionId
import com.ahu.ahutong.personalization.runtime.PersonalizationController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Exposure is confirmed after real layout; merely receiving a prediction never counts as a view. */
@Composable
internal fun PluginSuggestionCard(controller: PersonalizationController?, blocked: Boolean, onAction: (AppActionId) -> Unit) {
    if (controller == null || blocked) return
    val suggestion by controller.suggestion.collectAsState()
    val current = suggestion ?: return
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var laidOut by remember(current.executionId) { mutableStateOf(false) }
    var confirmed by remember(current.executionId) { mutableStateOf(false) }
    var accepting by remember(current.executionId) { mutableStateOf(false) }
    var error by remember(current.executionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(current.executionId, laidOut) {
        if (laidOut && !confirmed) {
            withFrameNanos { }
            if (view.isAttachedToWindow && view.isShown) confirmed = controller.confirmSuggestionVisible(current.executionId)
        }
    }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        laidOut = coordinates.isAttached && bounds.width > 0 && bounds.height > 0 &&
            bounds.top >= 0 && bounds.bottom <= view.rootView.height && bounds.right > 0 && bounds.left < view.rootView.width
    }) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(current.title, style = MaterialTheme.typography.titleSmall)
            Text(current.reason, style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { controller.dismissSuggestion() }, enabled = !accepting) { Text("暂不需要") }
                Button(onClick = {
                    accepting = true
                    scope.launch {
                        try {
                            val action = controller.acceptSuggestion(current.executionId)
                            if (action != null) onAction(action) else { error = "这条建议已更新，请稍后重试。"; controller.dismissSuggestion() }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "暂时无法打开建议功能" }
                        finally { accepting = false }
                    }
                }, enabled = confirmed && !accepting) { Text(if (accepting) "正在打开…" else "打开") }
            }
        }
    }
}
