package com.papr.scanner.presentation.merge

import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import com.papr.scanner.domain.model.Document
import com.papr.scanner.presentation.common.Haptic
import com.papr.scanner.presentation.common.LocalPaprHaptics
import com.papr.scanner.presentation.common.PageImage
import com.papr.scanner.presentation.common.paprViewModel
import com.papr.scanner.presentation.paywall.ProUpgradeSheet
import com.papr.scanner.usage.MeteredFeature
import java.io.File
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeConfirmScreen(
    documentIds: List<Long>,
    onBack: () -> Unit,
    onMergeComplete: (documentId: Long) -> Unit,
) {
    val viewModel = paprViewModel { container ->
        MergeConfirmViewModel(
            documentIds,
            createSavedStateHandle(),
            container.documentRepository,
            container.documentMerger,
            container.documentDeleter,
            container.usageTracker,
            container.userPreferencesRepository.settings.map { it.deleteOriginalsAfterMerge },
        )
    }
    val uiState by viewModel.uiState.collectAsState()
    val mergeAllowance by viewModel.mergeAllowance.collectAsState()
    // Non-null while the Pro sheet is open: why it opened.
    var paywallReason by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val haptics = LocalPaprHaptics.current

    LaunchedEffect(Unit) {
        viewModel.mergeComplete.collect { newDocumentId ->
            haptics.perform(Haptic.CONFIRM)
            onMergeComplete(newDocumentId)
        }
    }
    LaunchedEffect(Unit) {
        viewModel.errors.collect { message ->
            haptics.perform(Haptic.REJECT)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.proSheetRequests.collect { reason ->
            haptics.perform(Haptic.REJECT)
            paywallReason = reason
        }
    }

    // Leaving would cancel the merge halfway and quietly throw away the new document.
    BackHandler(enabled = uiState.isMerging) {}

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Merge documents") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !uiState.isMerging) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        // Everything scrolls except the button, so the documents stay visible and the button
        // reachable in landscape, with large text, or with the keyboard up.
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .fillMaxSize(),
        ) {
            // Null while it's unknown or unlimited (Pro): no count is shown then.
            val allowance = mergeAllowance
            val mergesLeft = allowance?.remaining
            // Nothing changes once merging has started, so nothing looks changeable.
            val editable = !uiState.isMerging
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    OutlinedTextField(
                        value = uiState.title,
                        onValueChange = viewModel::onTitleChange,
                        label = { Text("Title") },
                        singleLine = true,
                        enabled = editable,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    )
                }
                itemsIndexed(uiState.documents, key = { _, document -> document.id }) { index, document ->
                    MergeDocumentRow(
                        document = document,
                        canMoveUp = editable && index > 0,
                        canMoveDown = editable && index < uiState.documents.size - 1,
                        canRemove = editable,
                        onMoveUp = { viewModel.moveDocument(document, -1) },
                        onMoveDown = { viewModel.moveDocument(document, 1) },
                        onRemove = { viewModel.removeDocument(document) },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("Delete the originals") },
                        supportingContent = { Text("Remove these documents once the merged copy is saved") },
                        trailingContent = { Switch(checked = uiState.deleteOriginals, onCheckedChange = null, enabled = editable) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.toggleable(
                            value = uiState.deleteOriginals,
                            enabled = editable,
                            role = Role.Switch,
                            onValueChange = { on ->
                                haptics.toggle(on)
                                viewModel.setDeleteOriginals(on)
                            },
                        ),
                    )
                }
                if (allowance != null && mergesLeft != null) {
                    item {
                        Text(
                            if (mergesLeft == 0) {
                                "No free merges left this month"
                            } else {
                                "$mergesLeft of ${allowance.limit} free merges left this month"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (mergesLeft == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        )
                    }
                }
            }
            if (mergesLeft == 0) {
                Button(
                    onClick = { paywallReason = MeteredFeature.MERGE.limitReachedMessage },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text("Go Pro to merge")
                }
            } else {
                Button(
                    onClick = viewModel::merge,
                    enabled = uiState.documents.size >= 2 && uiState.title.isNotBlank() && !uiState.isMerging,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(
                        when {
                            uiState.isMerging -> "Merging…"
                            // Says what the tap will do, so deleting is never a surprise.
                            uiState.deleteOriginals -> "Merge and delete originals"
                            else -> "Merge"
                        }
                    )
                }
            }
        }
    }

    paywallReason?.let { reason ->
        ProUpgradeSheet(onDismiss = { paywallReason = null }, reason = reason)
    }
}

@Composable
private fun MergeDocumentRow(
    document: Document,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PageImage(
            file = document.thumbnailUri.takeIf { it.isNotBlank() }?.let { File(it) },
            contentDescription = document.title,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
        Text(
            text = document.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        )
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move earlier")
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move later")
        }
        IconButton(onClick = onRemove, enabled = canRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Remove from merge")
        }
    }
}
