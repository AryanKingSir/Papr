package com.papr.scanner.presentation.detail

import com.papr.scanner.domain.model.Page
import org.junit.Assert.assertEquals
import org.junit.Test

class PageOrderTest {

    private fun page(id: Long, index: Int) = Page(id = id, documentId = 1, pageIndex = index, imageUri = "p$id.jpg", extractedText = null)

    private val pages = listOf(page(10, 0), page(11, 1), page(12, 2), page(13, 3))

    @Test
    fun `returns only the pages whose position changed, numbered from zero`() {
        val changed = pagesInNewOrder(pages, orderedIds = listOf(10, 12, 11, 13))

        assertEquals(listOf(page(12, 1), page(11, 2)), changed)
    }

    @Test
    fun `moving the last page first renumbers every page`() {
        val changed = pagesInNewOrder(pages, orderedIds = listOf(13, 10, 11, 12))

        assertEquals(listOf(page(13, 0), page(10, 1), page(11, 2), page(12, 3)), changed)
    }

    @Test
    fun `the same order changes nothing`() {
        assertEquals(emptyList<Page>(), pagesInNewOrder(pages, orderedIds = listOf(10, 11, 12, 13)))
    }

    @Test
    fun `ignores ids that are no longer pages, and keeps pages missing from the order at the end`() {
        // A page deleted, and another added, while the grid was being dragged.
        val changed = pagesInNewOrder(pages, orderedIds = listOf(99, 12, 10, 11))

        assertEquals(listOf(page(12, 0), page(10, 1), page(11, 2)), changed)
    }
}
