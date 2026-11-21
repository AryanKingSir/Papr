package com.papr.scanner.presentation.detail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.papr.scanner.domain.model.ConfidentialMarkStroke
import com.papr.scanner.domain.model.InkStroke
import com.papr.scanner.domain.model.NormalizedPoint
import com.papr.scanner.domain.model.Page
import com.papr.scanner.domain.model.PageAnnotation
import com.papr.scanner.domain.model.TextNote
import com.papr.scanner.presentation.common.PageImage
import com.papr.scanner.presentation.common.rememberImageSize
import com.papr.scanner.presentation.detail.MarkupTool
import com.papr.scanner.presentation.detail.markup.ToolStyle
import java.io.File

@Composable
fun PagePager(
    pages: List<Page>,
    revision: Int,
    currentPageIndex: Int,
    onPageChanged: (Int) -> Unit,
    // Non-null while Markup is open: the tool the current page's touches go to.
    markupTool: MarkupTool?,
    toolStyle: ToolStyle?,
    isPlacingSignature: Boolean,
    // Null when tapping a signature should do nothing (e.g. while marking up over it).
    onSignatureTap: ((Page) -> Unit)?,
    onSignaturePlacementChange: (page: Page, x: Float, y: Float, width: Float) -> Unit,
    onSignatureRemove: (Page) -> Unit,
    onMarkStrokeAdded: (Page, ConfidentialMarkStroke) -> Unit,
    onMarkStrokeErased: (Page, ConfidentialMarkStroke) -> Unit,
    onStrokeDrawn: (Page, InkStroke) -> Unit,
    onErase: (Page, List<PageAnnotation>, List<ConfidentialMarkStroke>) -> Unit,
    onTextTap: (Page, TextNote?, NormalizedPoint) -> Unit,
    onTextMoved: (Page, TextNote, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(initialPage = currentPageIndex) { pages.size }
    val currentOnPageChanged by rememberUpdatedState(onPageChanged)

    LaunchedEffect(currentPageIndex) {
        if (pagerState.currentPage != currentPageIndex) {
            pagerState.scrollToPage(currentPageIndex)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { currentOnPageChanged(it) }
    }

    // Wide enough to keep the page out of the screen-edge zones where gesture navigation's Back
    // swipe starts: a stroke or signature drag beginning there would close the tool instead.
    // Android honours gesture exclusion for only 200dp of each edge, so excluding the page
    // isn't enough on a tall screen.
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val gestureInsets = WindowInsets.systemGestures
    val startPadding = with(density) { maxOf(16.dp, gestureInsets.getLeft(this, layoutDirection).toDp()) }
    val endPadding = with(density) { maxOf(16.dp, gestureInsets.getRight(this, layoutDirection).toDp()) }

    HorizontalPager(
        state = pagerState,
        // Drawing or dragging a signature must never turn into a page swipe.
        userScrollEnabled = markupTool == null && !isPlacingSignature,
        modifier = modifier,
    ) { index ->
        val page = pages.getOrNull(index)
        // A rotate rewrites the page file in place without changing its row, so `pages`
        // can compare equal and this page would be skipped, still showing the old image.
        // Keying on the revision re-runs it, re-reading the file.
        key(revision) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = startPadding, top = 16.dp, end = endPadding, bottom = 16.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                PageImage(
                    file = page?.let { File(it.imageUri) },
                    contentDescription = "Page ${index + 1}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                if (page != null) {
                    val imageSize = rememberImageSize(File(page.imageUri))
                    val isCurrent = index == currentPageIndex
                    if (imageSize != null) {
                        // Same layering as the PDF export: markup, then the signature, then marks.
                        val tool = markupTool.takeIf { isCurrent }
                        MarkupOverlay(
                            imageSize = imageSize,
                            annotations = page.annotations,
                            tool = tool,
                            style = toolStyle,
                            confidentialMarks = page.confidentialMarks,
                            onStrokeDrawn = { stroke -> onStrokeDrawn(page, stroke) },
                            onErase = { annotations, marks -> onErase(page, annotations, marks) },
                            onTextTap = { note, anchor -> onTextTap(page, note, anchor) },
                            onTextMoved = { note, x, y -> onTextMoved(page, note, x, y) },
                            modifier = Modifier.fillMaxSize(),
                        )
                        page.signature?.let { signature ->
                            SignatureOverlay(
                                signature = signature,
                                imageSize = imageSize,
                                isPlacing = isPlacingSignature && isCurrent,
                                onTap = onSignatureTap?.takeIf { isCurrent }?.let { tap -> { tap(page) } },
                                onPlacementChange = { x, y, width -> onSignaturePlacementChange(page, x, y, width) },
                                onRemove = { onSignatureRemove(page) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        ConfidentialMarkOverlay(
                            imageSize = imageSize,
                            marks = page.confidentialMarks,
                            editingEnabled = tool == MarkupTool.CONFIDENTIAL,
                            onStrokeAdded = { stroke -> onMarkStrokeAdded(page, stroke) },
                            onStrokeErased = { stroke -> onMarkStrokeErased(page, stroke) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
