package com.papr.scanner.presentation.paywall

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.papr.scanner.billing.BillingState
import com.papr.scanner.presentation.common.paprViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProUpgradeSheet(onDismiss: () -> Unit) {
    val viewModel = paprViewModel { container -> ProUpgradeViewModel(container.billingManager) }
    val state by viewModel.state.collectAsState()
    val isProUnlocked by viewModel.isProUnlocked.collectAsState()
    val sheetState = rememberModalBottomSheetState()
    val activity = LocalActivity.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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

            when {
                isProUnlocked -> {
                    Text(
                        "You already own Papr Pro.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                state is BillingState.Ready -> {
                    val product = (state as BillingState.Ready).product
                    Text(
                        product?.formattedPrice?.let { "One-time purchase, $it" } ?: "Unavailable right now",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    Button(
                        onClick = { activity?.let(viewModel::purchase) },
                        enabled = product != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp),
                    ) { Text("Upgrade") }
                }
                state is BillingState.Purchasing -> {
                    CircularProgressIndicator(modifier = Modifier.padding(vertical = 16.dp))
                }
                state is BillingState.Purchased -> {
                    Text(
                        "Thanks for upgrading!",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                else -> {
                    CircularProgressIndicator(modifier = Modifier.padding(vertical = 16.dp))
                }
            }
        }
    }
}
