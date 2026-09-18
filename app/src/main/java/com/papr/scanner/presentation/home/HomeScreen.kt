package com.papr.scanner.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.papr.scanner.R
import com.papr.scanner.presentation.common.paprViewModel
import com.papr.scanner.presentation.home.components.DateRangeChip
import com.papr.scanner.presentation.home.components.DateRangeFilterButton
import com.papr.scanner.presentation.home.components.DocumentGridItem
import com.papr.scanner.presentation.home.components.DocumentSearchBar
import com.papr.scanner.presentation.home.components.EmptyState
import com.papr.scanner.presentation.home.components.FilterChipsRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onDocumentClick: (Long) -> Unit,
    onScanClick: () -> Unit,
    onUpgradeClick: () -> Unit,
    onMergeClick: (documentIds: List<Long>) -> Unit,
) {
    val viewModel = paprViewModel { container -> HomeViewModel(container.documentRepository, container.appContext) }
    val uiState by viewModel.uiState.collectAsState()
    var pendingDeleteSelection by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (uiState.isSelectionMode) {
                TopAppBar(
                    title = { Text("${uiState.selectedDocumentIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { pendingDeleteSelection = true },
                            enabled = uiState.selectedDocumentIds.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                        }
                        IconButton(
                            onClick = { onMergeClick(uiState.selectedDocumentIds) },
                            enabled = uiState.selectedDocumentIds.size >= 2,
                        ) {
                            Icon(Icons.Default.MergeType, contentDescription = "Merge selected")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_paper),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Papr")
                        }
                    },
                    actions = {
                        DateRangeFilterButton(onDateRangeSelected = viewModel::onDateRangeSelected)
                        IconButton(onClick = onUpgradeClick) {
                            Icon(Icons.Default.WorkspacePremium, contentDescription = "Go Pro")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onScanClick) {
                Icon(Icons.Default.DocumentScanner, contentDescription = "Scan a document")
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            DocumentSearchBar(query = uiState.searchQuery, onQueryChange = viewModel::onSearchQueryChange)
            uiState.selectedDateRange?.let { range ->
                DateRangeChip(
                    range = range,
                    onClear = { viewModel.onDateRangeSelected(null) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            FilterChipsRow(
                folders = uiState.folders,
                selectedFolder = uiState.selectedFolder,
                onFolderSelected = viewModel::onFolderSelected,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (uiState.documents.isEmpty()) {
                EmptyState(onScanClick = onScanClick)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(uiState.documents, key = { it.id }) { document ->
                        DocumentGridItem(
                            document = document,
                            onClick = {
                                if (uiState.isSelectionMode) {
                                    viewModel.toggleSelection(document.id)
                                } else {
                                    onDocumentClick(document.id)
                                }
                            },
                            onLongClick = { viewModel.onDocumentLongPress(document) },
                            isContentMatch = document.id in uiState.contentMatchedIds,
                            selectionMode = uiState.isSelectionMode,
                            isSelected = document.id in uiState.selectedDocumentIds,
                        )
                    }
                }
            }
        }
    }

    if (pendingDeleteSelection) {
        val count = uiState.selectedDocumentIds.size
        AlertDialog(
            onDismissRequest = { pendingDeleteSelection = false },
            title = { Text(if (count == 1) "Delete this document?" else "Delete $count documents?") },
            text = { Text("This removes all their pages. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelected()
                    pendingDeleteSelection = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSelection = false }) { Text("Cancel") }
            },
        )
    }
}
