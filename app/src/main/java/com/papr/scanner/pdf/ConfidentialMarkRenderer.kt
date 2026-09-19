package com.papr.scanner.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.papr.scanner.domain.model.ConfidentialMarkStroke

class ConfidentialMarkRenderer {

    /** Draws [strokes] (points normalized 0f..1f) onto [canvas], scaled to a page whose drawn size is [widthPx]x[heightPx]. */
    fun draw(canvas: Canvas, strokes: List<ConfidentialMarkStroke>, widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return

        val strokeWidthPx = widthPx * CONFIDENTIAL_MARK_STROKE_WIDTH_FRACTION
        val strokePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
        val backgroundPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.parseColor(BACKGROUND_COLOR_HEX)
            isAntiAlias = true
        }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = strokeWidthPx * TEXT_SIZE_FRACTION_OF_STROKE_WIDTH
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
        }

        for (stroke in strokes) {
            if (stroke.points.size < 2) continue

            val path = Path()
            stroke.points.forEachIndexed { index, point ->
                val x = point.x * widthPx
                val y = point.y * heightPx
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            val fillPath = Path()
            strokePaint.getFillPath(path, fillPath)

            val bounds = RectF()
            fillPath.computeBounds(bounds, true)

            canvas.save()
            canvas.clipPath(fillPath)
            canvas.drawRect(bounds, backgroundPaint)
            drawTiledLabel(canvas, bounds, textPaint)
            canvas.restore()
        }
    }

    // Anchored to the canvas origin (not each stroke's own bounds) so rows line
    // up continuously across every stroke on the same page, not just within one.
    private fun drawTiledLabel(canvas: Canvas, bounds: RectF, textPaint: Paint) {
        val tileWidth = textPaint.measureText(LABEL)
        val lineHeight = textPaint.textSize * LINE_HEIGHT_FRACTION
        if (tileWidth <= 0f || lineHeight <= 0f) return

        var y = bounds.top - (bounds.top % lineHeight)
        while (y < bounds.bottom + lineHeight) {
            var x = bounds.left - (bounds.left % tileWidth)
            while (x < bounds.right) {
                canvas.drawText(LABEL, x, y, textPaint)
                x += tileWidth
            }
            y += lineHeight
        }
    }

    private companion object {
        const val LABEL = "CONFIDENTIAL   "
        const val BACKGROUND_COLOR_HEX = "#1A1A1A"
        const val TEXT_SIZE_FRACTION_OF_STROKE_WIDTH = 0.4f
        const val LINE_HEIGHT_FRACTION = 1.6f
    }
}
