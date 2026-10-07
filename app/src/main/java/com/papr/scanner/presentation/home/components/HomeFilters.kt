package com.papr.scanner.presentation.home.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Opens the filters; a badge counts the ones in use. Sized to sit beside the search field. */
@Composable
fun FilterButton(activeCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier
            .height(56.dp)
            .semantics { if (activeCount > 0) contentDescription = "Filter, $activeCount in use" },
    ) {
        BadgedBox(badge = { if (activeCount > 0) Badge { Text("$activeCount") } }) {
            Icon(Icons.Outlined.FilterList, contentDescription = null)
        }
        Text("Filter", modifier = Modifier.padding(start = 8.dp))
    }
}

/** The filters in use, each removable on its own. Shows nothing when none are. */
@Composable
fun ActiveFiltersRow(
    dateRange: LongRange?,
    folder: String?,
    onClearDate: () -> Unit,
    onClearFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (dateRange == null && folder == null) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        dateRange?.let { range ->
            ActiveFilterChip(dateRangeLabel(range), clearDescription = "Clear date filter", onClear = onClearDate) {
                Icon(Icons.Outlined.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        folder?.let {
            ActiveFilterChip(it, clearDescription = "Clear folder filter", onClear = onClearFolder) {
                Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ActiveFilterChip(label: String, clearDescription: String, onClear: () -> Unit, icon: @Composable () -> Unit) {
    InputChip(
        selected = true,
        onClick = onClear,
        label = { Text(label) },
        leadingIcon = icon,
        trailingIcon = { Icon(Icons.Default.Close, contentDescription = clearDescription, modifier = Modifier.size(18.dp)) },
    )
}

/** Date and folder filters together, applied as soon as they're picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    dateRange: LongRange?,
    folders: List<String>,
    selectedFolder: String?,
    onDateRangeSelected: (LongRange?) -> Unit,
    onFolderSelected: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Scrolls so nothing is cut off in landscape or with large text.
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Filter documents",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            SectionTitle("Date scanned")
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { showDatePicker = true }) {
                    Icon(Icons.Outlined.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(dateRange?.let(::dateRangeLabel) ?: "Any date", modifier = Modifier.padding(start = 8.dp))
                }
                if (dateRange != null) {
                    TextButton(onClick = { onDateRangeSelected(null) }) { Text("Clear") }
                }
            }

            // Folders only exist once documents have been filed into them.
            if (folders.isNotEmpty()) {
                SectionTitle("Folder")
                FilterChipsRow(
                    folders = folders,
                    selectedFolder = selectedFolder,
                    onFolderSelected = onFolderSelected,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 24.dp),
            ) { Text("Show documents") }
        }
    }

    if (showDatePicker) {
        DateRangePickerDialog(onRangeSelected = onDateRangeSelected, onDismiss = { showDatePicker = false })
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}
