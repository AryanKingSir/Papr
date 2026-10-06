package com.papr.scanner.presentation.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.papr.scanner.PaprApplication
import com.papr.scanner.domain.model.Tip
import kotlinx.coroutines.launch

/** Whether a one-time [Tip] should show, and how to make it go away for good. */
@Stable
class TipState(visible: () -> Boolean, private val onDismiss: () -> Unit) {
    private val isVisible = visible
    val visible: Boolean get() = isVisible()

    fun dismiss() = onDismiss()
}

/**
 * The stored state of [tip]. Dismissing hides it at once (before the DataStore write lands) and
 * remembers it on the app scope, so leaving the screen straight away can't lose the write.
 */
@Composable
fun rememberTip(tip: Tip): TipState {
    val container = (LocalContext.current.applicationContext as PaprApplication).container
    val onboarding by container.onboardingState.collectAsState()
    var dismissedHere by rememberSaveable(tip) { mutableStateOf(false) }
    return remember(tip) {
        TipState(
            visible = { !dismissedHere && onboarding?.seenTips?.contains(tip) == false },
            onDismiss = {
                if (!dismissedHere) {
                    dismissedHere = true
                    container.applicationScope.launch { container.onboardingRepository.markTipSeen(tip) }
                }
            },
        )
    }
}

/** A one-line hint that disappears for good once tapped. [visible] lets the caller add its own conditions. */
@Composable
fun TipCard(tip: TipState, text: String, modifier: Modifier = Modifier, visible: Boolean = true) {
    AnimatedVisibility(
        visible = visible && tip.visible,
        enter = EnterTransition.None,
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable(role = Role.Button, onClickLabel = "Dismiss tip") { tip.dismiss() }
                    .padding(start = 16.dp),
            ) {
                Icon(Icons.Outlined.Lightbulb, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
                IconButton(onClick = tip::dismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss tip", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
