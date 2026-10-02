package com.papr.scanner.presentation.home.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FilterChipsRow(
    folders: List<String>,
    selectedFolder: String?,
    onFolderSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (folders.isEmpty()) return

    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(selected = selectedFolder == null, onClick = { onFolderSelected(null) }, label = { Text("All") })
        }
        items(folders) { folder ->
            FilterChip(selected = selectedFolder == folder, onClick = { onFolderSelected(folder) }, label = { Text(folder) })
        }
    }
}
