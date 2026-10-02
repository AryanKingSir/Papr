package com.papr.scanner.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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

    /**
     * Stamps [signature] into the bottom-right corner of a page whose drawn
     * bitmap is [pageBitmapWidth]x[pageBitmapHeight] - the caller's own final
     * dimensions for that page, not the original scan file's dimensions,
     * since a page's drawn bitmap can be downsampled or further shrunk for a
     * compression target.
     */
    fun stamp(canvas: Canvas, signature: Bitmap, pageBitmapWidth: Int, pageBitmapHeight: Int) {
        val width = pageBitmapWidth / 3
        val height = (width * signature.height / signature.width.toFloat()).toInt().coerceAtLeast(1)
        val rect = Rect(
            pageBitmapWidth - width - MARGIN_PX,
            pageBitmapHeight - height - MARGIN_PX,
            pageBitmapWidth - MARGIN_PX,
            pageBitmapHeight - MARGIN_PX,
        )
        canvas.drawBitmap(signature, null, RectF(rect), null)
    }

    private companion object {
        const val MARGIN_PX = 40
        val PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    }
}
