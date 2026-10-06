package com.papr.scanner.presentation.detail

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.HistoryEdu
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.papr.scanner.core.FileUtils
import com.papr.scanner.domain.model.NormalizedPoint
import com.papr.scanner.domain.model.Page
import com.papr.scanner.domain.model.TextNote
import com.papr.scanner.presentation.common.MainToolbar
import com.papr.scanner.presentation.common.SignatureOverlayEditor
import com.papr.scanner.presentation.common.ToolButton
import com.papr.scanner.presentation.common.paprViewModel
import com.papr.scanner.presentation.detail.components.EraserIcon
import com.papr.scanner.presentation.detail.components.ExportOptionsSheet
import com.papr.scanner.presentation.detail.components.MarkupOptionsRow
import com.papr.scanner.presentation.detail.components.OrganizePagesScreen
import com.papr.scanner.presentation.detail.components.PagePager
import com.papr.scanner.presentation.detail.components.PageThumbnailStrip
import com.papr.scanner.presentation.detail.components.SubToolbar
import com.papr.scanner.presentation.detail.components.TextNoteDialog
import com.papr.scanner.presentation.detail.markup.defaultStyle
import com.papr.scanner.presentation.detail.markup.sizeFraction
import com.papr.scanner.presentation.paywall.ProUpgradeSheet
import kotlinx.coroutines.flow.map

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
            container.pageImageExporter,
            container.usageTracker,
            container.savedSignatureStore,
            container.userPreferencesRepository.settings.map { it.defaultExportFormat },
            container.appContext,
        )
    }
    val uiState by viewModel.uiState.collectAsState()
    val allowances by viewModel.allowances.collectAsState()
    val isPro by viewModel.isPro.collectAsState()
    val defaultExportFormat by viewModel.defaultExportFormat.collectAsState()
    val context = LocalContext.current
    val currentOnBack by rememberUpdatedState(onBack)

    var isEditingTitle by remember { mutableStateOf(false) }
    var titleDraft by remember { mutableStateOf(TextFieldValue()) }
    val titleFocus = remember { FocusRequester() }
    var showSignatureEditor by remember { mutableStateOf(false) }
    var showExportOptions by remember { mutableStateOf(false) }
    var pendingDeletePage by remember { mutableStateOf(false) }
    var textEdit by remember { mutableStateOf<TextEditRequest?>(null) }
    var showOrganize by remember { mutableStateOf(false) }
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
    // Only while the screen is started: Android won't open the share sheet from the background,
    // so a finished export waits until the user is back.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
    }
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.imagesReady.collect { files ->
                showExportOptions = false
                val uris = files.map { FileUtils.getShareableUri(context, it) }
                val shareIntent = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }.apply {
                    type = "image/jpeg"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, if (uris.size == 1) "Share image" else "Share images"))
            }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.errors.collect { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(Unit) {
        viewModel.proSheetRequests.collect { reason -> paywallReason = reason }
    }

    if (showOrganize) {
        OrganizePagesScreen(
            pages = uiState.pages,
            revision = uiState.revision,
            onReorder = viewModel::reorderPages,
            onRotate = viewModel::rotatePages,
            onDelete = viewModel::deletePages,
            onOpenPage = { index ->
                viewModel.selectPage(index)
                viewModel.closeMode()
                showOrganize = false
            },
            onDone = { showOrganize = false },
        )
        return
    }

    val currentPage = uiState.currentPage
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
                        // The pencil says the title can be renamed; tapping anywhere on it does that.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClickLabel = "Rename") {
                                    val title = uiState.document?.title.orEmpty()
                                    // All selected, so typing replaces it; arrow keys or a tap keep it.
                                    titleDraft = TextFieldValue(title, selection = TextRange(0, title.length))
                                    isEditingTitle = true
                                }
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = uiState.document?.title.orEmpty(),
                                // One line, like the home grid: a long title would otherwise grow the bar and push the page down.
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .size(18.dp),
                            )
                        }
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

        val inMarkup = uiState.mode == EditorMode.MARKUP
        val toolStyle = uiState.toolStyles[uiState.markupTool]

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
                markupTool = uiState.markupTool.takeIf { inMarkup },
                toolStyle = toolStyle,
                isPlacingSignature = uiState.isPlacingSignature,
                canAddMarks = isPro,
                // Outside Markup, tapping a signature opens the Sign tools to move it.
                onSignatureTap = { _: Page -> viewModel.openMode(EditorMode.SIGN) }.takeIf { !inMarkup },
                onSignaturePlacementChange = { page, x, y, width -> viewModel.updateSignaturePlacement(page.id, x, y, width) },
                onSignatureRemove = { page -> viewModel.removeSignature(page.id) },
                onMarkStrokeAdded = { page, stroke -> viewModel.addConfidentialMarkStroke(page.id, stroke) },
                onMarkStrokeErased = { page, stroke -> viewModel.eraseConfidentialMarkStroke(page.id, stroke) },
                onStrokeDrawn = { page, stroke -> viewModel.addAnnotation(page.id, stroke) },
                onErase = { page, annotations, marks -> viewModel.eraseMarkup(page.id, annotations, marks) },
                onTextTap = { page, note, anchor -> textEdit = TextEditRequest(page.id, note, anchor) },
                onTextMoved = { page, note, x, y -> viewModel.saveTextNote(page.id, note, note.copy(x = x, y = y)) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            when {
                uiState.isPlacingSignature -> HintRow("Drag the signature to move it. Drag its corner to resize.")
                uiState.mode == EditorMode.SIGN -> HintRow(
                    if (uiState.hasSavedSignature) {
                        "Use your saved signature, or draw a one-off. Change the saved one in Settings."
                    } else {
                        "Draw your signature, then drag it into place."
                    }
                )
                inMarkup && uiState.markupTool == MarkupTool.TEXT ->
                    HintRow("Tap to add text. Tap text to edit it, drag to move it.")
            }

            if (inMarkup) {
                MarkupOptionsRow(
                    tool = uiState.markupTool,
                    style = toolStyle,
                    hint = when (uiState.markupTool) {
                        MarkupTool.ERASER -> "Drag across markup to erase it."
                        MarkupTool.CONFIDENTIAL -> if (isPro) {
                            "Drag to cover content, tap a mark to remove it. Papr's search still finds covered text."
                        } else {
                            "Tap a mark to remove it. Adding marks needs Papr Pro."
                        }
                        else -> null
                    },
                    canUndo = uiState.canUndo,
                    canRedo = uiState.canRedo,
                    onStyleChange = { style -> viewModel.setToolStyle(uiState.markupTool, style) },
                    onUndo = viewModel::undoMarkup,
                    onRedo = viewModel::redoMarkup,
                )
            } else {
                // Hidden while marking up, so the page gets the room.
                PageThumbnailStrip(
                    pages = uiState.pages,
                    revision = uiState.revision,
                    currentPageIndex = uiState.currentPageIndex,
                    onPageSelected = viewModel::selectPage,
                )
            }
        }

        val toolbar: @Composable (vertical: Boolean) -> Unit = { vertical ->
            val hasPage = currentPage != null
            when (uiState.mode) {
                EditorMode.NONE -> MainToolbar(vertical) { weight ->
                    ToolButton(Icons.Outlined.Gesture, "Markup", { viewModel.openMode(EditorMode.MARKUP) }, weight, enabled = hasPage)
                    ToolButton(Icons.Outlined.HistoryEdu, "Sign", { viewModel.openMode(EditorMode.SIGN) }, weight, enabled = hasPage)
                    ToolButton(Icons.Outlined.FileCopy, "Pages", { viewModel.openMode(EditorMode.PAGES) }, weight, enabled = hasPage)
                    ToolButton(Icons.Outlined.IosShare, "Export", { showExportOptions = true }, weight, enabled = hasPage)
                }
                EditorMode.MARKUP -> SubToolbar(vertical, onDone = viewModel::closeMode) {
                    val pick = { tool: MarkupTool ->
                        viewModel.selectMarkupTool(tool, pageHasMarks = currentPage?.confidentialMarks?.isNotEmpty() == true)
                    }
                    val tool = uiState.markupTool
                    ToolButton(Icons.Outlined.Create, "Pen", { pick(MarkupTool.PEN) }, selected = tool == MarkupTool.PEN)
                    ToolButton(Icons.Outlined.BorderColor, "Highlighter", { pick(MarkupTool.HIGHLIGHTER) }, selected = tool == MarkupTool.HIGHLIGHTER)
                    ToolButton(Icons.Outlined.TextFields, "Text", { pick(MarkupTool.TEXT) }, selected = tool == MarkupTool.TEXT)
                    ToolButton(EraserIcon, "Eraser", { pick(MarkupTool.ERASER) }, selected = tool == MarkupTool.ERASER)
                    ToolButton(
                        Icons.Outlined.Brush,
                        "Confidential",
                        { pick(MarkupTool.CONFIDENTIAL) },
                        selected = tool == MarkupTool.CONFIDENTIAL,
                        locked = !isPro,
                    )
                }
                EditorMode.SIGN -> SubToolbar(vertical, onDone = viewModel::closeMode) {
                    if (uiState.hasSavedSignature) {
                        ToolButton(Icons.Outlined.BookmarkBorder, "Use saved", { currentPage?.let { viewModel.useSavedSignature(it.id) } }, enabled = hasPage)
                    }
                    ToolButton(Icons.Outlined.Draw, "Draw new", { showSignatureEditor = true }, enabled = hasPage)
                    ToolButton(
                        Icons.Outlined.Delete,
                        "Remove from page",
                        { currentPage?.let { viewModel.removeSignature(it.id) } },
                        enabled = currentPage?.signature != null,
                    )
                }
                EditorMode.PAGES -> SubToolbar(vertical, onDone = viewModel::closeMode) {
                    ToolButton(Icons.AutoMirrored.Outlined.RotateRight, "Rotate", { currentPage?.let { viewModel.rotatePage(it.id) } }, enabled = hasPage)
                    ToolButton(Icons.Outlined.GridView, "Organize", { showOrganize = true }, enabled = hasPage)
                    ToolButton(Icons.Outlined.FindReplace, "Replace", { currentPage?.let { onReplacePageClick(it.id) } }, enabled = hasPage)
                    ToolButton(Icons.AutoMirrored.Outlined.NoteAdd, "Add", onAddPagesClick, enabled = uiState.document != null)
                    ToolButton(Icons.Outlined.Delete, "Delete", { pendingDeletePage = true }, enabled = hasPage)
                }
            }
        }

        // Back steps out of a group of tools before it leaves the document.
        BackHandler(enabled = uiState.mode != EditorMode.NONE) { viewModel.closeMode() }

        BoxWithConstraints(modifier = Modifier.padding(innerPadding)) {
            if (maxWidth > maxHeight) {
                // Landscape: the tools move to a rail on the right so the page gets the full height.
                Row {
                    Column(modifier = Modifier.weight(1f), content = pageArea)
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxHeight()) {
                        Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) { toolbar(true) }
                    }
                }
            } else {
                Column {
                    pageArea()
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.padding(vertical = 4.dp)) { toolbar(false) }
                    }
                }
            }
        }
    }

    textEdit?.let { request ->
        val existing = request.existing
        TextNoteDialog(
            initialText = existing?.text.orEmpty(),
            onSave = { text ->
                if (existing != null) {
                    viewModel.saveTextNote(request.pageId, existing, existing.copy(text = text))
                } else {
                    val style = uiState.toolStyles[MarkupTool.TEXT] ?: MarkupTool.TEXT.defaultStyle()
                    viewModel.saveTextNote(
                        request.pageId,
                        null,
                        TextNote(
                            text = text,
                            x = request.anchor.x,
                            y = request.anchor.y.coerceAtLeast(0f),
                            color = style.color,
                            size = MarkupTool.TEXT.sizeFraction(style.size),
                        ),
                    )
                }
                textEdit = null
            },
            onDelete = existing?.let { note -> { viewModel.saveTextNote(request.pageId, note, null); textEdit = null } },
            onDismiss = { textEdit = null },
        )
    }

    if (showSignatureEditor) {
        SignatureOverlayEditor(
            onSave = { bitmap, remember ->
                currentPage?.let { viewModel.setSignature(it.id, bitmap, remember) }
                showSignatureEditor = false
            },
            onDismiss = { showSignatureEditor = false },
            showRememberOption = !uiState.hasSavedSignature,
        )
    }

    if (showExportOptions) {
        ExportOptionsSheet(
            isExporting = uiState.isExporting,
            initialFormat = defaultExportFormat,
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

/** Explains how to use the active tool. */
@Composable
private fun HintRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** A text note being added (no [existing]) at [anchor], or [existing] being edited. */
private data class TextEditRequest(val pageId: Long, val existing: TextNote?, val anchor: NormalizedPoint)
