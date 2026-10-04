package com.papr.scanner.presentation.detail

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.papr.scanner.core.FileUtils
import com.papr.scanner.presentation.common.paprViewModel
import com.papr.scanner.presentation.detail.components.ExportOptionsSheet
import com.papr.scanner.presentation.detail.components.PagePager
import com.papr.scanner.presentation.detail.components.PageThumbnailStrip
import com.papr.scanner.presentation.detail.components.SignatureOverlayEditor
import com.papr.scanner.presentation.paywall.ProUpgradeSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(
    documentId: Long,
    onBack: () -> Unit,
    onAddPagesClick: () -> Unit,
    onReplacePageClick: (pageId: Long) -> Unit,
) {
    val viewModel = paprViewModel { container ->
        DocumentDetailViewModel(
            documentId,
            container.documentRepository,
            container.documentDeleter,
            container.pdfEngine,
            container.usageTracker,
            container.appContext,
        )
    }
    val uiState by viewModel.uiState.collectAsState()
    val allowances by viewModel.allowances.collectAsState()
    val context = LocalContext.current
    val currentOnBack by rememberUpdatedState(onBack)

    var isEditingTitle by remember { mutableStateOf(false) }
    var titleDraft by remember { mutableStateOf(TextFieldValue()) }
    val titleFocus = remember { FocusRequester() }
    var showSignatureEditor by remember { mutableStateOf(false) }
    var showExportOptions by remember { mutableStateOf(false) }
    var pendingDeletePage by remember { mutableStateOf(false) }
    // Non-null while the Pro sheet is open: why it opened.
    var paywallReason by remember { mutableStateOf<String?>(null) }

    // Leave exactly once, whether the document was deleted here (its last
    // page removed) or turns out not to exist - never sit on an empty screen.
    val shouldLeave = uiState.isDeleted || (!uiState.isLoading && uiState.document == null)
    LaunchedEffect(shouldLeave) {
        if (shouldLeave) currentOnBack()
    }
    // Tapping the title should go straight to typing, not need a second tap on the field.
    LaunchedEffect(isEditingTitle) {
        if (isEditingTitle) titleFocus.requestFocus()
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
    LaunchedEffect(Unit) {
        viewModel.errors.collect { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(Unit) {
        viewModel.proSheetRequests.collect { reason -> paywallReason = reason }
    }

    val currentPage = uiState.pages.getOrNull(uiState.currentPageIndex)
    val saveTitle = {
        if (titleDraft.text.isNotBlank()) {
            viewModel.updateTitle(titleDraft.text)
            isEditingTitle = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isEditingTitle) {
                        OutlinedTextField(
                            value = titleDraft,
                            onValueChange = { titleDraft = it },
                            singleLine = true,
                            // The keyboard's Done key saves, like the Save button.
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { saveTitle() }),
                            modifier = Modifier.focusRequester(titleFocus),
                        )
                    } else {
                        Text(
                            text = uiState.document?.title.orEmpty(),
                            // One line, like the home grid: a long title would otherwise grow the bar and push the page down.
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable {
                                val title = uiState.document?.title.orEmpty()
                                // All selected, so typing replaces it; arrow keys or a tap keep it.
                                titleDraft = TextFieldValue(title, selection = TextRange(0, title.length))
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
                        TextButton(
                            onClick = saveTitle,
                            enabled = titleDraft.text.isNotBlank(),
                        ) { Text("Save") }
                    } else {
                        IconButton(onClick = onAddPagesClick, enabled = uiState.document != null) {
                            Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = "Add pages")
                        }
                        IconButton(onClick = { showExportOptions = true }, enabled = uiState.pages.isNotEmpty()) {
                            Icon(Icons.Filled.IosShare, contentDescription = "Export")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        // The page and its tools as pieces, so landscape can lay them out side by side.
        val pageArea: @Composable ColumnScope.() -> Unit = {
            if (uiState.isExporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            PagePager(
                pages = uiState.pages,
                revision = uiState.revision,
                currentPageIndex = uiState.currentPageIndex,
                onPageChanged = viewModel::selectPage,
                isMarkingConfidential = uiState.isMarkingConfidential,
                isPlacingSignature = uiState.isPlacingSignature,
                onMarkStrokeAdded = { page, stroke -> viewModel.addConfidentialMarkStroke(page.id, stroke) },
                onMarkStrokeErased = { page, stroke -> viewModel.eraseConfidentialMarkStroke(page.id, stroke) },
                onSignatureTap = { viewModel.setPlacingSignature(true) },
                onSignaturePlacementChange = { page, x, y, width -> viewModel.updateSignaturePlacement(page.id, x, y, width) },
                onSignatureRemove = { page -> viewModel.removeSignature(page.id) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            when {
                uiState.isMarkingConfidential -> HintRow(
                    text = "Drag to cover content, tap a mark to remove it. Covered text can still be found " +
                        "by searching in Papr.",
                    onDone = viewModel::toggleMarkingConfidential,
                )
                uiState.isPlacingSignature -> HintRow(
                    text = "Drag the signature to move it. Drag its corner to resize.",
                    onDone = { viewModel.setPlacingSignature(false) },
                )
            }

            PageThumbnailStrip(
                pages = uiState.pages,
                revision = uiState.revision,
                currentPageIndex = uiState.currentPageIndex,
                onPageSelected = viewModel::selectPage,
            )
        }
        val pageActions: @Composable () -> Unit = {
            IconButton(
                onClick = { currentPage?.let { viewModel.movePage(it.id, -1) } },
                enabled = currentPage != null && uiState.currentPageIndex > 0,
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Move page earlier") }
            IconButton(onClick = { currentPage?.let { viewModel.rotatePage(it.id) } }, enabled = currentPage != null) {
                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate page")
            }
            IconButton(onClick = { showSignatureEditor = true }, enabled = currentPage != null) {
                Icon(
                    Icons.Filled.Draw,
                    contentDescription = "Sign page",
                    tint = if (uiState.isPlacingSignature) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
            IconButton(onClick = { viewModel.toggleMarkingConfidential() }, enabled = currentPage != null) {
                Icon(
                    Icons.Filled.Brush,
                    contentDescription = "Mark confidential",
                    tint = if (uiState.isMarkingConfidential) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
            IconButton(
                onClick = { currentPage?.let { onReplacePageClick(it.id) } },
                enabled = currentPage != null,
            ) {
                Icon(Icons.Filled.FindReplace, contentDescription = "Replace page")
            }
            IconButton(onClick = { pendingDeletePage = true }, enabled = currentPage != null) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete page")
            }
            IconButton(
                onClick = { currentPage?.let { viewModel.movePage(it.id, 1) } },
                enabled = currentPage != null && uiState.currentPageIndex < uiState.pages.size - 1,
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Move page later") }
        }

        BoxWithConstraints(modifier = Modifier.padding(innerPadding)) {
            if (maxWidth > maxHeight) {
                // Landscape: the tools move to a rail on the right so the page gets the full height.
                Row {
                    Column(modifier = Modifier.weight(1f), content = pageArea)
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) { pageActions() }
                }
            } else {
                val screenWidth = maxWidth
                Column {
                    pageArea()
                    // Scrolls sideways when the seven buttons don't fit (narrow phones, or the
                    // largest display size) instead of squashing the last ones; at least
                    // screen-wide so they still spread out evenly when they do fit.
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .widthIn(min = screenWidth)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) { pageActions() }
                }
            }
        }
    }

    if (showSignatureEditor) {
        SignatureOverlayEditor(
            onSave = { bitmap ->
                currentPage?.let { viewModel.setSignature(it.id, bitmap) }
                showSignatureEditor = false
            },
            onDismiss = { showSignatureEditor = false },
        )
    }

    if (showExportOptions) {
        ExportOptionsSheet(
            isExporting = uiState.isExporting,
            allowances = allowances,
            onExport = viewModel::export,
            onLockedOptionTap = { feature -> paywallReason = feature.limitReachedMessage },
            onDismiss = { showExportOptions = false },
        )
    }

    paywallReason?.let { reason ->
        ProUpgradeSheet(onDismiss = { paywallReason = null }, reason = reason)
    }

    if (pendingDeletePage) {
        AlertDialog(
            onDismissRequest = { pendingDeletePage = false },
            // Deleting the only page deletes the document too - say so up front.
            title = { Text(if (uiState.pages.size == 1) "Delete this document?" else "Delete this page?") },
            text = {
                Text(
                    if (uiState.pages.size == 1) {
                        "This is its only page, so the whole document will be deleted. This can't be undone."
                    } else {
                        "This can't be undone."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    currentPage?.let { viewModel.deletePage(it.id) }
                    pendingDeletePage = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletePage = false }) { Text("Cancel") }
            },
        )
    }
}

/** Explains the active editing mode, with a way out of it. */
@Composable
private fun HintRow(text: String, onDone: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDone) { Text("Done") }
    }
}
