package com.papr.scanner.pdf

class CompressionIterator {

    fun compress(targetSizeBytes: Long, encode: (quality: Int) -> ByteArray): ByteArray {
        var quality = MAX_QUALITY
        var result = encode(quality)
        while (result.size > targetSizeBytes && quality > MIN_QUALITY) {
            quality -= QUALITY_STEP
            result = encode(quality)
        }
        return result
    }

    companion object {
        private const val MAX_QUALITY = 100
        private const val MIN_QUALITY = 10
        private const val QUALITY_STEP = 10
    }
}
