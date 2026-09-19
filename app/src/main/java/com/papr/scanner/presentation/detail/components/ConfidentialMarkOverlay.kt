package com.papr.scanner.presentation.detail.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import java.io.File

private val renderer = ConfidentialMarkRenderer()

/**
 * Draws a page's confidential marks over its displayed image, aligned to the
 * image's actual fitted/cropped bounds within this composable's own size -
 * [crop] must match the content scale the page image itself is drawn with
 * (false for `ContentScale.Fit`, true for `ContentScale.Crop`). When
 * [editingEnabled], also turns drags into new marks and single taps on an
 * existing mark into an erase.
 */
@Composable
fun ConfidentialMarkOverlay(
    imageFile: File,
    marks: List<ConfidentialMarkStroke>,
    editingEnabled: Boolean,
    onStrokeAdded: (ConfidentialMarkStroke) -> Unit,
    onStrokeErased: (ConfidentialMarkStroke) -> Unit,
    modifier: Modifier = Modifier,
    crop: Boolean = false,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember(imageFile) { mutableStateOf(IntSize.Zero) }
    var currentStroke by remember(imageFile) { mutableStateOf(listOf<Offset>()) }

    LaunchedEffect(imageFile) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        imageSize = IntSize(bounds.outWidth, bounds.outHeight)
    }

    val fitted = if (containerSize.width > 0 && containerSize.height > 0 && imageSize.width > 0 && imageSize.height > 0) {
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

    Canvas(
        modifier = modifier
            .onSizeChanged { containerSize = it }
            .pointerInput(editingEnabled, fitted, marks) {
                val bounds = fitted
                if (!editingEnabled || bounds == null) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset -> currentStroke = listOf(offset) },
                    onDrag = { change, _ -> currentStroke = currentStroke + change.position },
                    onDragEnd = {
                        val normalized = currentStroke.map { bounds.normalize(it) }
                        currentStroke = emptyList()
                        if (normalized.size >= 2) {
                            onStrokeAdded(ConfidentialMarkStroke(normalized))
                        } else if (normalized.size == 1) {
                            val tapped = marks.firstOrNull { stroke ->
                                isPointNearStroke(normalized[0], stroke, CONFIDENTIAL_MARK_STROKE_WIDTH_FRACTION)
                            }
                            tapped?.let(onStrokeErased)
                        }
                    },
                )
            },
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
