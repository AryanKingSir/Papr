package com.papr.scanner.presentation.merge

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import com.papr.scanner.domain.model.Document
import com.papr.scanner.presentation.common.PageImage
import com.papr.scanner.presentation.common.paprViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeConfirmScreen(
    documentIds: List<Long>,
    onBack: () -> Unit,
    onMergeComplete: (documentId: Long) -> Unit,
) {
    val viewModel = paprViewModel { container ->
        MergeConfirmViewModel(documentIds, createSavedStateHandle(), container.documentRepository, container.documentMerger)
    }
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.mergeComplete.collect { newDocumentId -> onMergeComplete(newDocumentId) }
    }
    LaunchedEffect(Unit) {
        viewModel.errors.collect { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Merge documents") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            OutlinedTextField(
                value = uiState.title,
                onValueChange = viewModel::onTitleChange,
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(uiState.documents, key = { _, document -> document.id }) { index, document ->
                    MergeDocumentRow(
                        document = document,
                        canMoveUp = index > 0,
                        canMoveDown = index < uiState.documents.size - 1,
                        onMoveUp = { viewModel.moveDocument(document, -1) },
                        onMoveDown = { viewModel.moveDocument(document, 1) },
                        onRemove = { viewModel.removeDocument(document) },
                    )
                }
            }
            Button(
                onClick = viewModel::merge,
                enabled = uiState.documents.size >= 2 && uiState.title.isNotBlank() && !uiState.isMerging,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(if (uiState.isMerging) "Merging…" else "Merge")
            }
        }
    }
}

@Composable
private fun MergeDocumentRow(
    document: Document,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
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
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Remove from merge")
        }
    }
}
