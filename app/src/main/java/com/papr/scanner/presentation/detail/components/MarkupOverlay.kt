package com.papr.scanner.presentation.detail.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.withTranslation
import com.papr.scanner.domain.model.PageAnnotation
import com.papr.scanner.pdf.PageOverlayRenderer
import com.papr.scanner.pdf.fittedImageBounds

private val renderer = PageOverlayRenderer()

/**
 * Draws a page's pen, highlighter and text markup over its displayed image,
 * aligned to the image's fitted/cropped bounds within this composable's size -
 * [imageSize] is the page image's pixel size and [crop] must match the page
 * image's content scale (see [ConfidentialMarkOverlay]).
 */
@Composable
fun MarkupOverlay(
    imageSize: IntSize,
    annotations: List<PageAnnotation>,
    modifier: Modifier = Modifier,
    crop: Boolean = false,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    Canvas(modifier = modifier.onSizeChanged { containerSize = it }) {
        if (annotations.isEmpty() || containerSize.width == 0 || containerSize.height == 0) return@Canvas
        val bounds = fittedImageBounds(
            containerWidth = containerSize.width.toFloat(),
            containerHeight = containerSize.height.toFloat(),
            imageWidth = imageSize.width.toFloat(),
            imageHeight = imageSize.height.toFloat(),
            crop = crop,
        )
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.withTranslation(bounds.left, bounds.top) {
                renderer.drawAnnotations(this, annotations, bounds.width.toInt(), bounds.height.toInt())
            }
        }
    }
}
