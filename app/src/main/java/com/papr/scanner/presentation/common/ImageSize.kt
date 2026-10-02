package com.papr.scanner.presentation.common

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.IntSize
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An image file's pixel size, read from its header off the main thread; null
 * until known. Keyed on lastModified as well as the path because a rotate
 * rewrites the same file in place with width and height swapped.
 */
@Composable
fun rememberImageSize(file: File): IntSize? {
    val size by produceState<IntSize?>(initialValue = null, file.absolutePath, file.lastModified()) {
        value = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            IntSize(bounds.outWidth, bounds.outHeight).takeIf { it.width > 0 && it.height > 0 }
        }
    }
    return size
}
