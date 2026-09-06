package com.papr.scanner.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF

class SignatureStamper {

    fun stamp(canvas: Canvas, signature: Bitmap, rect: Rect) {
        canvas.drawBitmap(signature, null, RectF(rect), null)
    }
}
