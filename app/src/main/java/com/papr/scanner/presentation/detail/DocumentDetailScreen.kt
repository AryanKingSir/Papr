package com.papr.scanner.presentation.detail

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.papr.scanner.core.FileUtils
import com.papr.scanner.presentation.common.paprViewModel
import com.papr.scanner.presentation.detail.components.ExportOptionsSheet
import com.papr.scanner.presentation.detail.components.PagePager
import com.papr.scanner.presentation.detail.components.PageThumbnailStrip
import com.papr.scanner.presentation.detail.components.SignatureOverlayEditor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(documentId: Long, onBack: () -> Unit) {
    val viewModel = paprViewModel { container ->
        DocumentDetailViewModel(documentId, container.documentRepository, container.pdfEngine, container.appContext)
    }
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var isEditingTitle by remember { mutableStateOf(false) }
    var titleDraft by remember { mutableStateOf("") }
    var showSignatureEditor by remember { mutableStateOf(false) }
    var showExportOptions by remember { mutableStateOf(false) }
    var pendingDeletePage by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isDeleted) {
        if (uiState.isDeleted) onBack()
    }
    LaunchedEffect(Unit) {
        viewModel.pdfReady.collect { file ->
            showExportOptions = false
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, FileUtils.getShareableUri(context, file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share PDF"))
        }
    }

    val currentPage = uiState.pages.getOrNull(uiState.currentPageIndex)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isEditingTitle) {
                        OutlinedTextField(
                            value = titleDraft,
                            onValueChange = { titleDraft = it },
                            singleLine = true,
                        )
                    } else {
                        Text(
                            text = uiState.document?.title.orEmpty(),
                            modifier = Modifier.clickable {
                                titleDraft = uiState.document?.title.orEmpty()
                                isEditingTitle = true
                            },
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isEditingTitle) {
                        TextButton(onClick = {
                            viewModel.updateTitle(titleDraft)
                            isEditingTitle = false
                        }) { Text("Save") }
                    } else {
                        IconButton(onClick = { showExportOptions = true }, enabled = uiState.pages.isNotEmpty()) {
                            Icon(Icons.Filled.IosShare, contentDescription = "Export")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            if (uiState.isExporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            PagePager(
                pages = uiState.pages,
                currentPageIndex = uiState.currentPageIndex,
                onPageChanged = viewModel::selectPage,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            PageThumbnailStrip(
                pages = uiState.pages,
                currentPageIndex = uiState.currentPageIndex,
                onPageSelected = viewModel::selectPage,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(
                    onClick = { currentPage?.let { viewModel.movePage(it, -1) } },
                    enabled = currentPage != null && uiState.currentPageIndex > 0,
                ) { Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Move page earlier") }
                IconButton(onClick = { currentPage?.let { viewModel.rotatePage(it) } }, enabled = currentPage != null) {
                    Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate page")
                }
                IconButton(onClick = { showSignatureEditor = true }, enabled = currentPage != null) {
                    Icon(Icons.Filled.Draw, contentDescription = "Sign page")
                }
                IconButton(onClick = { pendingDeletePage = true }, enabled = currentPage != null) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete page")
                }
                IconButton(
                    onClick = { currentPage?.let { viewModel.movePage(it, 1) } },
                    enabled = currentPage != null && uiState.currentPageIndex < uiState.pages.size - 1,
                ) { Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Move page later") }
            }
        }
    }

    if (showSignatureEditor) {
        SignatureOverlayEditor(
            onSave = { bitmap ->
                viewModel.setPendingSignature(bitmap)
                showSignatureEditor = false
            },
            onDismiss = { showSignatureEditor = false },
        )
    }

    if (showExportOptions) {
        ExportOptionsSheet(
            hasSignature = uiState.pendingSignature != null,
            onExport = viewModel::export,
            onDismiss = { showExportOptions = false },
        )
    }

    if (pendingDeletePage) {
        AlertDialog(
            onDismissRequest = { pendingDeletePage = false },
            title = { Text("Delete this page?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    currentPage?.let { viewModel.deletePage(it) }
                    pendingDeletePage = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletePage = false }) { Text("Cancel") }
            },
        )
    }
}
