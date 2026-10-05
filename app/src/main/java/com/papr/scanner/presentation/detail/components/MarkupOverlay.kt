package com.papr.scanner.presentation.detail.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withTranslation
import com.papr.scanner.domain.model.ConfidentialMarkStroke
import com.papr.scanner.domain.model.InkStroke
import com.papr.scanner.domain.model.InkTool
import com.papr.scanner.domain.model.NormalizedPoint
import com.papr.scanner.domain.model.PageAnnotation
import com.papr.scanner.domain.model.TextNote
import com.papr.scanner.pdf.CONFIDENTIAL_MARK_STROKE_WIDTH_FRACTION
import com.papr.scanner.pdf.FittedBounds
import com.papr.scanner.pdf.PageOverlayRenderer
import com.papr.scanner.pdf.fittedImageBounds
import com.papr.scanner.pdf.isOnInkStroke
import com.papr.scanner.pdf.isPointNearStroke
import com.papr.scanner.presentation.common.detectStrokes
import com.papr.scanner.presentation.detail.MarkupTool
import com.papr.scanner.presentation.detail.markup.ToolStyle
import com.papr.scanner.presentation.detail.markup.sizeFraction

private val renderer = PageOverlayRenderer()

/** Room around a line or note that still counts as touching it - roughly a fingertip. */
private val TOUCH_SLOP = 12.dp

/**
 * Draws a page's pen, highlighter and text markup over its displayed image,
 * aligned to the image's fitted/cropped bounds within this composable's size -
 * [imageSize] is the page image's pixel size and [crop] must match the page
 * image's content scale (see [ConfidentialMarkOverlay]).
 *
 * With a [tool], it also takes that tool's touches: Pen and Highlighter draw
 * with [style]; Eraser removes whatever a drag passes over (markup and
 * [confidentialMarks] alike), reported once on release; Text reports taps
 * (on a note to edit it, elsewhere to add one) and drags notes to move them.
 * With no tool it takes no touches, so whatever is beneath stays tappable.
 */
@Composable
fun MarkupOverlay(
    imageSize: IntSize,
    annotations: List<PageAnnotation>,
    modifier: Modifier = Modifier,
    crop: Boolean = false,
    tool: MarkupTool? = null,
    style: ToolStyle? = null,
    confidentialMarks: List<ConfidentialMarkStroke> = emptyList(),
    onStrokeDrawn: (InkStroke) -> Unit = {},
    onErase: (annotations: List<PageAnnotation>, marks: List<ConfidentialMarkStroke>) -> Unit = { _, _ -> },
    onTextTap: (existing: TextNote?, anchor: NormalizedPoint) -> Unit = { _, _ -> },
    onTextMoved: (note: TextNote, x: Float, y: Float) -> Unit = { _, _, _ -> },
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var liveStroke by remember { mutableStateOf(listOf<Offset>()) }
    // Hidden as soon as the eraser touches them, removed for real when the finger lifts.
    var erasing by remember { mutableStateOf(setOf<Any>()) }
    var dragging by remember { mutableStateOf<Pair<TextNote, Offset>?>(null) }
    val currentAnnotations by rememberUpdatedState(annotations)
    val currentMarks by rememberUpdatedState(confidentialMarks)
    val currentStyle by rememberUpdatedState(style)
    val currentOnStrokeDrawn by rememberUpdatedState(onStrokeDrawn)
    val currentOnErase by rememberUpdatedState(onErase)
    val currentOnTextTap by rememberUpdatedState(onTextTap)
    val currentOnTextMoved by rememberUpdatedState(onTextMoved)
    val slopPx = with(LocalDensity.current) { TOUCH_SLOP.toPx() }

    val bounds = if (containerSize.width > 0 && containerSize.height > 0) {
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

    fun FittedBounds.noteAt(offset: Offset): TextNote? = currentAnnotations.filterIsInstance<TextNote>().lastOrNull {
        renderer.isOnTextNote(it, offset.x - left, offset.y - top, width.toInt(), height.toInt(), slopPx)
    }

    val input = when {
        bounds == null || tool == null || tool == MarkupTool.CONFIDENTIAL -> Modifier
        tool == MarkupTool.TEXT -> Modifier
            .pointerInput(bounds) {
                detectTapGestures(onTap = { offset ->
                    val textSize = MarkupTool.TEXT.sizeFraction(currentStyle?.size ?: return@detectTapGestures) *
                        minOf(bounds.width, bounds.height)
                    // A new note's first line is centred on the tap, the way a cursor would be.
                    val anchor = bounds.normalize(offset.copy(y = offset.y - textSize / 2f))
                    currentOnTextTap(bounds.noteAt(offset), anchor)
                })
            }
            .pointerInput(bounds) {
                // Hand-rolled rather than detectDragGestures, whose start position is where the
                // finger crossed the touch slop - often already off a small note.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val note = bounds.noteAt(down.position) ?: return@awaitEachGesture
                    val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                    var moved = start.position - down.position
                    dragging = note to moved
                    val finished = drag(start.id) { change ->
                        moved += change.positionChange()
                        change.consume()
                        dragging = note to moved
                    }
                    if (finished) {
                        currentOnTextMoved(
                            note,
                            (note.x + moved.x / bounds.width).coerceIn(0f, 1f),
                            (note.y + moved.y / bounds.height).coerceIn(0f, 1f),
                        )
                    }
                    dragging = null
                }
            }
        tool == MarkupTool.ERASER -> Modifier.pointerInput(bounds) {
            fun eraseAt(offset: Offset) {
                val x = offset.x - bounds.left
                val y = offset.y - bounds.top
                val w = bounds.width.toInt()
                val h = bounds.height.toInt()
                val hits = currentAnnotations.filter { annotation ->
                    when (annotation) {
                        is InkStroke -> isOnInkStroke(annotation, x, y, w, h, slopPx)
                        is TextNote -> renderer.isOnTextNote(annotation, x, y, w, h, slopPx)
                    }
                } + currentMarks.filter {
                    isPointNearStroke(bounds.normalize(offset), it, CONFIDENTIAL_MARK_STROKE_WIDTH_FRACTION)
                }
                if (hits.isNotEmpty()) erasing = erasing + hits
            }
            fun finish() {
                val erased = erasing
                erasing = emptySet()
                val annotationsHit = currentAnnotations.filter { it in erased }
                val marksHit = currentMarks.filter { it in erased }
                if (annotationsHit.isNotEmpty() || marksHit.isNotEmpty()) currentOnErase(annotationsHit, marksHit)
            }
            awaitEachGestureErasing(onPoint = ::eraseAt, onEnd = ::finish)
        }
        else -> Modifier.pointerInput(bounds, tool) {
            detectStrokes(
                drawDots = true,
                onStroke = { liveStroke = it },
                onEnd = {
                    val points = liveStroke.map { bounds.normalize(it) }
                    liveStroke = emptyList()
                    val drawStyle = currentStyle
                    if (points.size >= 2 && drawStyle != null) currentOnStrokeDrawn(inkStroke(tool, drawStyle, points))
                },
                onCancel = { liveStroke = emptyList() },
            )
        }
    }

    Canvas(modifier = modifier.onSizeChanged { containerSize = it }.then(input)) {
        val fitted = bounds ?: return@Canvas
        val live = if (liveStroke.size >= 2 && tool != null && style != null) {
            inkStroke(tool, style, liveStroke.map { fitted.normalize(it) })
        } else {
            null
        }
        val moving = dragging
        val shown = annotations
            .filterNot { it in erasing }
            .map { annotation ->
                if (moving != null && annotation == moving.first) {
                    moving.first.copy(
                        x = moving.first.x + moving.second.x / fitted.width,
                        y = moving.first.y + moving.second.y / fitted.height,
                    )
                } else {
                    annotation
                }
            }
            .let { if (live != null) it + live else it }
        if (shown.isEmpty()) return@Canvas
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.withTranslation(fitted.left, fitted.top) {
                renderer.drawAnnotations(this, shown, fitted.width.toInt(), fitted.height.toInt())
            }
        }
    }
}

/** Reports every point a finger touches, from the first down (so a tap erases too) until it lifts, then [onEnd]. */
private suspend fun PointerInputScope.awaitEachGestureErasing(onPoint: (Offset) -> Unit, onEnd: () -> Unit) {
    awaitEachGesture {
        onPoint(awaitFirstDown().position)
        do {
            val event = awaitPointerEvent()
            event.changes.filter { it.pressed }.forEach {
                onPoint(it.position)
                it.consume()
            }
        } while (event.changes.any { it.pressed })
        onEnd()
    }
}

private fun inkStroke(tool: MarkupTool, style: ToolStyle, points: List<NormalizedPoint>) = InkStroke(
    tool = if (tool == MarkupTool.HIGHLIGHTER) InkTool.HIGHLIGHTER else InkTool.PEN,
    color = style.color,
    width = tool.sizeFraction(style.size),
    points = points,
)

private fun FittedBounds.normalize(offset: Offset): NormalizedPoint = NormalizedPoint(
    x = ((offset.x - left) / width).coerceIn(0f, 1f),
    y = ((offset.y - top) / height).coerceIn(0f, 1f),
)
