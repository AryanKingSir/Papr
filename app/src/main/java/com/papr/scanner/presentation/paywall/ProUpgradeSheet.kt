package com.papr.scanner.presentation.paywall

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.papr.scanner.billing.BillingState
import com.papr.scanner.presentation.common.Haptic
import com.papr.scanner.presentation.common.LocalPaprHaptics
import com.papr.scanner.presentation.common.paprViewModel

/** [reason] says why the sheet opened when a free limit stopped the user, e.g. a used-up monthly allowance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProUpgradeSheet(onDismiss: () -> Unit, reason: String? = null) {
    val viewModel = paprViewModel { container -> ProUpgradeViewModel(container.billingManager) }
    val state by viewModel.state.collectAsState()
    val isProUnlocked by viewModel.isProUnlocked.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activity = LocalActivity.current
    val haptics = LocalPaprHaptics.current
    LaunchedEffect(Unit) { viewModel.retry() }
    // Only the purchase made in this sheet: a Purchased state left over from earlier stays quiet.
    var previousState by remember { mutableStateOf(state) }
    LaunchedEffect(state) {
        if (isFreshPurchase(previousState, state)) haptics.perform(Haptic.CONFIRM)
        previousState = state
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Scrolls so the button stays reachable in landscape or with large text.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.WorkspacePremium,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Text("Papr Pro", style = MaterialTheme.typography.titleLarge)
            if (reason != null && !isProUnlocked) {
                Text(
                    reason,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Text(
                "Unlimited searchable-text, password and compressed exports, unlimited merges, and confidential marks.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )

            when {
                // Before the "already own" case: buying unlocks Pro a moment before this state lands.
                state is BillingState.Purchased -> {
                    Text(
                        "Thanks for upgrading!",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                isProUnlocked -> {
                    Text(
                        "You already own Papr Pro.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                state is BillingState.Ready && (state as BillingState.Ready).product != null -> {
                    val product = (state as BillingState.Ready).product
                    Text(
                        "One-time purchase, ${product?.formattedPrice}",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    Button(
                        onClick = { activity?.let(viewModel::purchase) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp),
                    ) { Text("Upgrade") }
                }
                state is BillingState.Pending -> {
                    Text(
                        "Your payment is pending. Pro unlocks as soon as Google Play confirms it.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                state is BillingState.Purchasing -> {
                    CircularProgressIndicator(modifier = Modifier.padding(vertical = 16.dp))
                }
                // Connected but no price came back (Play busy, or the product not loaded).
                state is BillingState.Ready || state is BillingState.Disconnected -> {
                    Text(
                        "Couldn't reach Google Play. Check your connection and try again.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    OutlinedButton(
                        onClick = viewModel::retry,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp),
                    ) { Text("Try again") }
                }
                else -> {
                    CircularProgressIndicator(modifier = Modifier.padding(vertical = 16.dp))
                }
            }
        }
    }
}

/** True when a purchase started from the sheet has just gone through. */
internal fun isFreshPurchase(previous: BillingState, current: BillingState): Boolean =
    previous is BillingState.Purchasing && current is BillingState.Purchased
