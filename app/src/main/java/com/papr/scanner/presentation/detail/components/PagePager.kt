package com.papr.scanner.presentation.detail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.papr.scanner.domain.model.Page
import com.papr.scanner.presentation.common.PageImage
import java.io.File

@Composable
fun PagePager(
    pages: List<Page>,
    currentPageIndex: Int,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(initialPage = currentPageIndex) { pages.size }

    LaunchedEffect(currentPageIndex) {
        if (pagerState.currentPage != currentPageIndex) {
            pagerState.scrollToPage(currentPageIndex)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { onPageChanged(it) }
    }

    HorizontalPager(state = pagerState, modifier = modifier) { index ->
        PageImage(
            file = pages.getOrNull(index)?.let { File(it.imageUri) },
            contentDescription = "Page ${index + 1}",
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Fit,
        )
    }
}
