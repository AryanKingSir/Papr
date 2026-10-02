package com.papr.scanner.pdf

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.papr.scanner.domain.model.PageSignature

class SignatureStamper {

    /**
     * Draws a placed [signature] onto a page drawn at [pageWidthPx]x[pageHeightPx]
     * (the page's own drawn size - a PDF page bitmap or a thumbnail - since the
     * placement is normalized to the page image). A missing signature file is
     * skipped rather than failing the whole export/thumbnail.
     */
    fun draw(canvas: Canvas, signature: PageSignature, pageWidthPx: Int, pageHeightPx: Int) {
        val bitmap = BitmapFactory.decodeFile(signature.imagePath) ?: return
        try {
            val left = signature.x * pageWidthPx
            val top = signature.y * pageHeightPx
            val width = signature.width * pageWidthPx
            canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + width * signature.aspectRatio), PAINT)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        val PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    }
}
