package com.papr.scanner.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.io.File

/**
 * A page/thumbnail file gets rotated or regenerated in place (same path, new
 * bytes) rather than renamed, so Coil's default path-based cache key would
 * otherwise keep showing a stale image after a rotate. Keying on
 * lastModified() forces a reload whenever the file actually changes.
 */
@Composable
fun PageImage(
    file: File?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current
    val request = file?.let {
        // The size too: before Android 8 lastModified() only has whole seconds, so two quick
        // rotations would share a key and show the first one's image.
        val cacheKey = "${it.absolutePath}-${it.lastModified()}-${it.length()}"
        ImageRequest.Builder(context)
            .data(it)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}
