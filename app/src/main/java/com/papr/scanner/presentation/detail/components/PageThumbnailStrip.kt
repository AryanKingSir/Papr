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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.papr.scanner.domain.model.Page
import com.papr.scanner.presentation.common.PageImage
import java.io.File

@Composable
fun PageThumbnailStrip(
    pages: List<Page>,
    currentPageIndex: Int,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(pages, key = { it.id }) { page ->
            val isSelected = page.pageIndex == currentPageIndex
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
                    .clickable { onPageSelected(page.pageIndex) },
            ) {
                PageImage(
                    file = File(page.imageUri),
                    contentDescription = "Page ${page.pageIndex + 1}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (page.confidentialMarks.isNotEmpty()) {
                    ConfidentialMarkOverlay(
                        imageFile = File(page.imageUri),
                        marks = page.confidentialMarks,
                        editingEnabled = false,
                        crop = true,
                        onStrokeAdded = {},
                        onStrokeErased = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
