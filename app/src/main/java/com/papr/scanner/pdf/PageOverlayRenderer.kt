package com.papr.scanner.pdf

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withRotation
import com.papr.scanner.domain.model.ConfidentialMarkStroke
import com.papr.scanner.domain.model.InkStroke
import com.papr.scanner.domain.model.InkTool
import com.papr.scanner.domain.model.Page
import com.papr.scanner.domain.model.PageAnnotation
import com.papr.scanner.domain.model.PageSignature
import com.papr.scanner.domain.model.TextNote

/** Everything drawn over a page image, in the page image's own normalized coordinates. */
data class PageOverlays(
    val annotations: List<PageAnnotation> = emptyList(),
    val signature: PageSignature? = null,
    val confidentialMarks: List<ConfidentialMarkStroke> = emptyList(),
) {
    val isEmpty: Boolean get() = annotations.isEmpty() && signature == null && confidentialMarks.isEmpty()
}

fun Page.overlays() = PageOverlays(annotations, signature, confidentialMarks)

/**
 * The one place that decides how a page's overlays look and stack, shared by the
 * PDF export, image export, thumbnails and the editor, so they always match.
 */
class PageOverlayRenderer(
    private val signatureStamper: SignatureStamper = SignatureStamper(),
    private val confidentialMarkRenderer: ConfidentialMarkRenderer = ConfidentialMarkRenderer(),
) {

    /**
     * Draws [overlays] onto a page drawn at [widthPx]x[heightPx]: markup first,
     * then the signature, then confidential marks on top so a mark always hides
     * everything under it.
     */
    fun draw(canvas: Canvas, overlays: PageOverlays, widthPx: Int, heightPx: Int) {
        drawAnnotations(canvas, overlays.annotations, widthPx, heightPx)
        overlays.signature?.let { signatureStamper.draw(canvas, it, widthPx, heightPx) }
        if (overlays.confidentialMarks.isNotEmpty()) {
            confidentialMarkRenderer.draw(canvas, overlays.confidentialMarks, widthPx, heightPx)
        }
    }

    /** Highlighter under pen under text, so writing stays readable over a highlight. */
    fun drawAnnotations(canvas: Canvas, annotations: List<PageAnnotation>, widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0 || annotations.isEmpty()) return
        val strokes = annotations.filterIsInstance<InkStroke>()
        strokes.filter { it.tool == InkTool.HIGHLIGHTER }.forEach { drawStroke(canvas, it, widthPx, heightPx) }
        strokes.filter { it.tool == InkTool.PEN }.forEach { drawStroke(canvas, it, widthPx, heightPx) }
        annotations.filterIsInstance<TextNote>().forEach { drawText(canvas, it, widthPx, heightPx) }
    }

    /**
     * Whether ([xPx], [yPx]) falls on [note]'s text (plus [slopPx] around it), for
     * tapping, dragging and erasing a note. Measured with the same paint it's drawn with.
     */
    fun isOnTextNote(note: TextNote, xPx: Float, yPx: Float, widthPx: Int, heightPx: Int, slopPx: Float): Boolean {
        val paint = textPaint(note, widthPx, heightPx)
        val anchorX = note.x * widthPx
        val anchorY = note.y * heightPx
        // Undo the note's rotation around its anchor, then test against the upright text box.
        val local = rotateAround(PointF(xPx, yPx), anchorX, anchorY, -note.quarterTurns * QUARTER_TURN_DEGREES)
        val lines = note.text.split('\n')
        val textWidth = lines.maxOf { paint.measureText(it) }
        val textHeight = lines.size * paint.textSize * LINE_HEIGHT
        return local.x in (anchorX - slopPx)..(anchorX + textWidth + slopPx) &&
            local.y in (anchorY - slopPx)..(anchorY + textHeight + slopPx)
    }

    private fun drawStroke(canvas: Canvas, stroke: InkStroke, widthPx: Int, heightPx: Int) {
        if (stroke.points.size < 2) return
        val path = Path()
        stroke.points.forEachIndexed { index, point ->
            val x = point.x * widthPx
            val y = point.y * heightPx
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke.width * minOf(widthPx, heightPx)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            // One path drawn once, so a highlighter crossing itself doesn't get darker there.
            color = if (stroke.tool == InkTool.HIGHLIGHTER) {
                ColorUtils.setAlphaComponent(stroke.color, HIGHLIGHTER_ALPHA)
            } else {
                stroke.color
            }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawText(canvas: Canvas, note: TextNote, widthPx: Int, heightPx: Int) {
        if (note.text.isBlank()) return
        val paint = textPaint(note, widthPx, heightPx)
        val anchorX = note.x * widthPx
        val anchorY = note.y * heightPx
        canvas.withRotation(note.quarterTurns * QUARTER_TURN_DEGREES, anchorX, anchorY) {
            note.text.split('\n').forEachIndexed { line, text ->
                drawText(text, anchorX, anchorY - paint.ascent() + line * paint.textSize * LINE_HEIGHT, paint)
            }
        }
    }

    private fun textPaint(note: TextNote, widthPx: Int, heightPx: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = note.color
        textSize = note.size * minOf(widthPx, heightPx)
    }

    private fun rotateAround(point: PointF, cx: Float, cy: Float, degrees: Float): PointF {
        val radians = Math.toRadians(degrees.toDouble())
        val cos = kotlin.math.cos(radians).toFloat()
        val sin = kotlin.math.sin(radians).toFloat()
        val dx = point.x - cx
        val dy = point.y - cy
        return PointF(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos)
    }

    private companion object {
        const val HIGHLIGHTER_ALPHA = 90
        const val LINE_HEIGHT = 1.2f
        const val QUARTER_TURN_DEGREES = 90f
    }
}
