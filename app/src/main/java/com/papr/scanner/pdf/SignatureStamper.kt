package com.papr.scanner.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF

class SignatureStamper {

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
    }
}
