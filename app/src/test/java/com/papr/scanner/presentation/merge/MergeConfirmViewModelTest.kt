package com.papr.scanner.presentation.merge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeConfirmViewModelTest {

    @Test
    fun `joins titles with a plus sign`() {
        val result = defaultMergeTitle(listOf("Front", "Back"))
        assertEquals("Front + Back", result)
    }

    @Test
    fun `truncates a very long joined title`() {
        val longTitles = listOf("A".repeat(40), "B".repeat(40))
        val result = defaultMergeTitle(longTitles)
        assertEquals(61, result.length) // 60 chars + the trailing ellipsis character
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun `swaps an item with its next neighbor`() {
        val result = reordered(listOf("a", "b", "c"), index = 0, direction = 1)
        assertEquals(listOf("b", "a", "c"), result)
    }

    @Test
    fun `swaps an item with its previous neighbor`() {
        val result = reordered(listOf("a", "b", "c"), index = 2, direction = -1)
        assertEquals(listOf("a", "c", "b"), result)
    }

    @Test
    fun `returns the list unchanged when moving the first item earlier`() {
        val result = reordered(listOf("a", "b", "c"), index = 0, direction = -1)
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `returns the list unchanged when moving the last item later`() {
        val result = reordered(listOf("a", "b", "c"), index = 2, direction = 1)
        assertEquals(listOf("a", "b", "c"), result)
    }
}
