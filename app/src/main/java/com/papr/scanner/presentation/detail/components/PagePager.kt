package com.papr.scanner.presentation.detail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.papr.scanner.domain.model.ConfidentialMarkStroke
import com.papr.scanner.domain.model.Page
import com.papr.scanner.presentation.common.PageImage
import com.papr.scanner.presentation.common.rememberImageSize
import java.io.File

@Composable
fun PagePager(
    pages: List<Page>,
    revision: Int,
    currentPageIndex: Int,
    onPageChanged: (Int) -> Unit,
    isMarkingConfidential: Boolean,
    isPlacingSignature: Boolean,
    onMarkStrokeAdded: (Page, ConfidentialMarkStroke) -> Unit,
    onMarkStrokeErased: (Page, ConfidentialMarkStroke) -> Unit,
    onSignatureTap: (Page) -> Unit,
    onSignaturePlacementChange: (page: Page, x: Float, y: Float, width: Float) -> Unit,
    onSignatureRemove: (Page) -> Unit,
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

    HorizontalPager(
        state = pagerState,
        // Drawing a mark or dragging a signature must never turn into a page swipe.
        userScrollEnabled = !isMarkingConfidential && !isPlacingSignature,
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
                    .padding(16.dp)
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
                        MarkupOverlay(
                            imageSize = imageSize,
                            annotations = page.annotations,
                            modifier = Modifier.fillMaxSize(),
                        )
                        page.signature?.let { signature ->
                            SignatureOverlay(
                                signature = signature,
                                imageSize = imageSize,
                                isPlacing = isPlacingSignature && isCurrent,
                                onTap = { onSignatureTap(page) }.takeIf { isCurrent && !isMarkingConfidential },
                                onPlacementChange = { x, y, width -> onSignaturePlacementChange(page, x, y, width) },
                                onRemove = { onSignatureRemove(page) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        ConfidentialMarkOverlay(
                            imageSize = imageSize,
                            marks = page.confidentialMarks,
                            editingEnabled = isMarkingConfidential && isCurrent,
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
