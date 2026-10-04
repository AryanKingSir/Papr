package com.papr.scanner.presentation.home

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.papr.scanner.R
import com.papr.scanner.presentation.common.MainToolbar
import com.papr.scanner.presentation.common.ToolButton
import com.papr.scanner.presentation.common.paprViewModel
import com.papr.scanner.presentation.home.components.ActiveFiltersRow
import com.papr.scanner.presentation.home.components.DocumentGridItem
import com.papr.scanner.presentation.home.components.DocumentSearchBar
import com.papr.scanner.presentation.home.components.EmptyState
import com.papr.scanner.presentation.home.components.FilterButton
import com.papr.scanner.presentation.home.components.FilterSheet
import com.papr.scanner.presentation.home.components.NoMatchesState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onDocumentClick: (Long) -> Unit,
    onScanClick: () -> Unit,
    onUpgradeClick: () -> Unit,
    onMergeClick: (documentIds: List<Long>) -> Unit,
    onSettingsClick: () -> Unit,
) {
    val viewModel = paprViewModel { container ->
        HomeViewModel(container.documentRepository, container.documentDeleter, container.usageTracker.isPro)
    }
    val uiState by viewModel.uiState.collectAsState()
    val isPro by viewModel.isPro.collectAsState()
    var pendingDeleteSelection by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.errors.collect { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }
    // A changed search or filter starts the results from the top. Without this the grid keeps
    // its first visible document in place, so clearing a filter left newer documents hidden
    // above it. drop(1): coming back to Home keeps the scroll position it was left at.
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState) {
        snapshotFlow { Triple(uiState.searchQuery.trim(), uiState.selectedFolder, uiState.selectedDateRange) }
            .distinctUntilChanged()
            .drop(1)
            .collect { gridState.scrollToItem(0) }
    }
    // Back ends a selection, like the Cancel button, rather than leaving the app.
    BackHandler(enabled = uiState.isSelectionMode) { viewModel.clearSelection() }

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
                        if (!isPro) {
                            FilledTonalButton(
                                onClick = onUpgradeClick,
                                contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Icon(Icons.Outlined.WorkspacePremium, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Pro", modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (uiState.isSelectionMode) {
                val selected = uiState.selectedDocumentIds
                // Edge to edge: the bar's colour runs under the navigation bar, its buttons sit above it.
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Box(modifier = Modifier.navigationBarsPadding()) {
                        MainToolbar(vertical = false) { weight ->
                            ToolButton(
                                Icons.Outlined.Delete,
                                "Delete",
                                { pendingDeleteSelection = true },
                                weight,
                                enabled = selected.isNotEmpty(),
                            )
                            ToolButton(
                                Icons.AutoMirrored.Outlined.MergeType,
                                // Says what it needs rather than sitting there disabled with no reason.
                                if (selected.size < 2) "Merge (pick 2+)" else "Merge",
                                {
                                    onMergeClick(selected)
                                    // Like Delete, acting on the selection ends it - otherwise Home is
                                    // still mid-selection when you come back from the merged document.
                                    viewModel.clearSelection()
                                },
                                weight,
                                enabled = selected.size >= 2,
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!uiState.isSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = onScanClick,
                    icon = { Icon(Icons.Default.DocumentScanner, contentDescription = null) },
                    text = { Text("Scan") },
                )
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DocumentSearchBar(
                    query = viewModel.searchFieldText,
                    onQueryChange = viewModel::onSearchQueryChange,
                    modifier = Modifier.weight(1f),
                )
                FilterButton(
                    activeCount = listOfNotNull(uiState.selectedDateRange, uiState.selectedFolder).size,
                    onClick = { showFilters = true },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            ActiveFiltersRow(
                dateRange = uiState.selectedDateRange,
                folder = uiState.selectedFolder,
                onClearDate = { viewModel.onDateRangeSelected(null) },
                onClearFolder = { viewModel.onFolderSelected(null) },
                modifier = Modifier.padding(bottom = 4.dp),
            )

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (uiState.documents.isEmpty()) {
                if (uiState.hasActiveFilters) NoMatchesState() else EmptyState(onScanClick = onScanClick)
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    // Two columns on a portrait phone (down to 360dp wide), more in landscape or on tablets.
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    // Extra bottom room so the last row can scroll clear of the scan button.
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
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

    if (showFilters) {
        FilterSheet(
            dateRange = uiState.selectedDateRange,
            folders = uiState.folders,
            selectedFolder = uiState.selectedFolder,
            onDateRangeSelected = viewModel::onDateRangeSelected,
            onFolderSelected = viewModel::onFolderSelected,
            onDismiss = { showFilters = false },
        )
    }

    if (pendingDeleteSelection) {
        val count = uiState.selectedDocumentIds.size
        AlertDialog(
            onDismissRequest = { pendingDeleteSelection = false },
            title = { Text(if (count == 1) "Delete this document?" else "Delete $count documents?") },
            text = { Text("This removes all ${if (count == 1) "its" else "their"} pages. This can't be undone.") },
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
