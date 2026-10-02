package com.papr.scanner.core

/**
 * The inSampleSize to decode an image whose longest side is [longestSide] so it
 * lands near (never far below) [maxDimension]. BitmapFactory silently rounds a
 * non-power-of-two sample size down to a power of two, so this does the same
 * rounding up front - callers can then predict the decoded size instead of
 * assuming a divisor the decoder never actually used.
 */
internal fun sampleSizeFor(longestSide: Int, maxDimension: Int): Int =
    if (longestSide <= maxDimension) 1 else Integer.highestOneBit(longestSide / maxDimension)
