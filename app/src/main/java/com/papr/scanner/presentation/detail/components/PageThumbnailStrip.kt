package com.papr.scanner.presentation.detail.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.papr.scanner.domain.model.Page
import com.papr.scanner.presentation.common.PageImage
import com.papr.scanner.presentation.common.rememberImageSize
import java.io.File

@Composable
fun PageThumbnailStrip(
    pages: List<Page>,
    revision: Int,
    currentPageIndex: Int,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Keeps the current page's thumbnail in view as the pages are swiped.
    LaunchedEffect(currentPageIndex, pages.size) {
        if (currentPageIndex !in pages.indices) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val fullyVisible = visible.any { item ->
            item.index == currentPageIndex &&
                item.offset >= listState.layoutInfo.viewportStartOffset &&
                item.offset + item.size <= listState.layoutInfo.viewportEndOffset
        }
        if (!fullyVisible) listState.animateScrollToItem(currentPageIndex)
    }
    LazyRow(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
            // List position, not pageIndex - the two only match while page
            // indices stay contiguous, and selection is by position.
            val isSelected = index == currentPageIndex
            // Re-read in-place rewritten (rotated) page files; see PagePager.
            key(revision) {
                Box(
                    modifier = Modifier
                        .width(56.dp)
                        .height(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            BorderStroke(
                                width = if (isSelected) 2.dp else 0.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { onPageSelected(index) }
                        .semantics { selected = isSelected },
                ) {
                    PageThumbnail(page = page, contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** A page's image cropped to fill [modifier]'s box, with its markup, signature and marks drawn over it. */
@Composable
fun PageThumbnail(page: Page, contentDescription: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        PageImage(
            file = File(page.imageUri),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (page.signature != null || page.confidentialMarks.isNotEmpty() || page.annotations.isNotEmpty()) {
            val imageSize = rememberImageSize(File(page.imageUri))
            if (imageSize != null) {
                MarkupOverlay(
                    imageSize = imageSize,
                    annotations = page.annotations,
                    crop = true,
                    modifier = Modifier.fillMaxSize(),
                )
                page.signature?.let { signature ->
                    SignatureOverlay(
                        signature = signature,
                        imageSize = imageSize,
                        isPlacing = false,
                        crop = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                ConfidentialMarkOverlay(
                    imageSize = imageSize,
                    marks = page.confidentialMarks,
                    editingEnabled = false,
                    crop = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
