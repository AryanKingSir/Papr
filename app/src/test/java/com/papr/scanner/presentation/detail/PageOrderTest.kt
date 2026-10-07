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

    @Test
    fun `moving a page earlier swaps it with the one before`() {
        assertEquals(listOf(10L, 12L, 11L, 13L), idsWithPageMoved(listOf(10L, 11L, 12L, 13L), index = 2, step = -1))
    }

    @Test
    fun `moving a page later swaps it with the one after`() {
        assertEquals(listOf(11L, 10L, 12L, 13L), idsWithPageMoved(listOf(10L, 11L, 12L, 13L), index = 0, step = 1))
    }

    @Test
    fun `a page can't move past either end`() {
        assertEquals(listOf(10L, 11L), idsWithPageMoved(listOf(10L, 11L), index = 1, step = 1))
        assertEquals(listOf(10L, 11L), idsWithPageMoved(listOf(10L, 11L), index = 0, step = -1))
    }

    @Test
    fun `deleting earlier pages keeps the shown page on screen`() {
        // Showing page 6 (id 15) of 10; pages 1 and 2 deleted.
        val ids = (10L..19L).toList()
        assertEquals(3, indexShownAfterDeleting(ids, shownIndex = 5, deleted = setOf(10L, 11L)))
    }

    @Test
    fun `deleting the shown page shows the page that took its place`() {
        val ids = (10L..19L).toList()
        assertEquals(4, indexShownAfterDeleting(ids, shownIndex = 5, deleted = setOf(15L, 11L)))
    }

    @Test
    fun `deleting the shown last page shows the new last page`() {
        val ids = listOf(10L, 11L, 12L)
        assertEquals(1, indexShownAfterDeleting(ids, shownIndex = 2, deleted = setOf(12L)))
    }
}
