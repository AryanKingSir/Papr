package com.papr.scanner.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CompressionIteratorTest {

    private val iterator = CompressionIterator()

    /** Fake encoder: quality N produces N bytes, mirroring a real encoder shrinking with quality. */
    private fun fakeEncode(quality: Int): ByteArray = ByteArray(quality)

    @Test
    fun `returns first encode when already under target`() {
        val result = iterator.compress(targetSizeBytes = 100) { quality -> fakeEncode(quality) }

        assertArrayEquals(fakeEncode(100), result)
    }

    @Test
    fun `steps quality down until under target`() {
        val qualitiesTried = mutableListOf<Int>()
        val result = iterator.compress(targetSizeBytes = 55) { quality ->
            qualitiesTried += quality
            fakeEncode(quality)
        }

        assertEquals(listOf(100, 90, 80, 70, 60, 50), qualitiesTried)
        assertEquals(50, result.size)
    }

    @Test
    fun `stops at minimum quality even if never under target`() {
        val qualitiesTried = mutableListOf<Int>()
        val result = iterator.compress(targetSizeBytes = 1) { quality ->
            qualitiesTried += quality
            fakeEncode(quality)
        }

        assertEquals(listOf(100, 90, 80, 70, 60, 50, 40, 30, 20, 10), qualitiesTried)
        assertEquals(10, result.size)
    }
}
