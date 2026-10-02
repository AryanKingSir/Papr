package com.papr.scanner.presentation.detail.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.withTranslation
import com.papr.scanner.domain.model.ConfidentialMarkStroke
import com.papr.scanner.domain.model.NormalizedPoint
import com.papr.scanner.pdf.CONFIDENTIAL_MARK_STROKE_WIDTH_FRACTION
import com.papr.scanner.pdf.ConfidentialMarkRenderer
import com.papr.scanner.pdf.FittedBounds
import com.papr.scanner.pdf.fittedImageBounds
import com.papr.scanner.pdf.isPointNearStroke

private val renderer = ConfidentialMarkRenderer()

/**
 * Draws a page's confidential marks over its displayed image, aligned to the
 * image's actual fitted/cropped bounds within this composable's own size -
 * [imageSize] is the page image's pixel size, and [crop] must match the
 * content scale the page image itself is drawn with (false for
 * `ContentScale.Fit`, true for `ContentScale.Crop`). When [editingEnabled],
 * drags draw new marks and taps on an existing mark erase it; otherwise this
 * takes no touch input at all, so whatever is beneath it stays tappable.
 */
@Composable
fun ConfidentialMarkOverlay(
    imageSize: IntSize,
    marks: List<ConfidentialMarkStroke>,
    editingEnabled: Boolean,
    modifier: Modifier = Modifier,
    crop: Boolean = false,
    onStrokeAdded: (ConfidentialMarkStroke) -> Unit = {},
    onStrokeErased: (ConfidentialMarkStroke) -> Unit = {},
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var currentStroke by remember { mutableStateOf(listOf<Offset>()) }
    val currentMarks by rememberUpdatedState(marks)
    val currentOnStrokeAdded by rememberUpdatedState(onStrokeAdded)
    val currentOnStrokeErased by rememberUpdatedState(onStrokeErased)

    val fitted = if (containerSize.width > 0 && containerSize.height > 0) {
        fittedImageBounds(
            containerWidth = containerSize.width.toFloat(),
            containerHeight = containerSize.height.toFloat(),
            imageWidth = imageSize.width.toFloat(),
            imageHeight = imageSize.height.toFloat(),
            crop = crop,
        )
    } else {
        null
    }

    val editing = if (editingEnabled && fitted != null) {
        Modifier
            // A tap never becomes a drag (drags only start past touch slop), so
            // erasing needs its own tap detector rather than a 1-point "drag".
            .pointerInput(fitted) {
                detectTapGestures(onTap = { offset ->
                    val point = fitted.normalize(offset)
                    currentMarks
                        .firstOrNull { isPointNearStroke(point, it, CONFIDENTIAL_MARK_STROKE_WIDTH_FRACTION) }
                        ?.let(currentOnStrokeErased)
                })
            }
            .pointerInput(fitted) {
                detectDragGestures(
                    onDragStart = { offset -> currentStroke = listOf(offset) },
                    onDrag = { change, _ ->
                        change.consume()
                        currentStroke = currentStroke + change.position
                    },
                    onDragEnd = {
                        val normalized = currentStroke.map { fitted.normalize(it) }
                        currentStroke = emptyList()
                        if (normalized.size >= 2) currentOnStrokeAdded(ConfidentialMarkStroke(normalized))
                    },
                    onDragCancel = { currentStroke = emptyList() },
                )
            }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { containerSize = it }
            .then(editing),
    ) {
        val bounds = fitted ?: return@Canvas
        val liveStroke = currentStroke.takeIf { it.size >= 2 }
            ?.let { ConfidentialMarkStroke(it.map { offset -> bounds.normalize(offset) }) }
        val allMarks = if (liveStroke != null) marks + liveStroke else marks
        if (allMarks.isEmpty()) return@Canvas

        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.withTranslation(bounds.left, bounds.top) {
                renderer.draw(this, allMarks, bounds.width.toInt(), bounds.height.toInt())
            }
        }
    }
}

private fun FittedBounds.normalize(offset: Offset): NormalizedPoint = NormalizedPoint(
    x = ((offset.x - left) / width).coerceIn(0f, 1f),
    y = ((offset.y - top) / height).coerceIn(0f, 1f),
)
