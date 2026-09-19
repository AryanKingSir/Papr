package com.papr.scanner.pdf

/** Fraction of the page's width used as the confidential-mark stroke's on-screen/export width. */
internal const val CONFIDENTIAL_MARK_STROKE_WIDTH_FRACTION = 0.04f

data class FittedBounds(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Where an image of [imageWidth]x[imageHeight] lands inside a container of
 * [containerWidth]x[containerHeight], matching Compose's `ContentScale.Fit`
 * (default, [crop] = false - scaled down to fit entirely inside, centered,
 * letterboxed) or `ContentScale.Crop` ([crop] = true - scaled up to cover
 * the whole container, centered, overflow clipped by the caller).
 */
internal fun fittedImageBounds(
    containerWidth: Float,
    containerHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
    crop: Boolean = false,
): FittedBounds {
    if (containerWidth <= 0f || containerHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) {
        return FittedBounds(0f, 0f, 0f, 0f)
    }
    val widthScale = containerWidth / imageWidth
    val heightScale = containerHeight / imageHeight
    val scale = if (crop) maxOf(widthScale, heightScale) else minOf(widthScale, heightScale)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return FittedBounds(
        left = (containerWidth - width) / 2f,
        top = (containerHeight - height) / 2f,
        width = width,
        height = height,
    )
}
