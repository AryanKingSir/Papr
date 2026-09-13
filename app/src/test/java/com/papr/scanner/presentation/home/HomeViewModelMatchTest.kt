package com.papr.scanner.presentation.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeViewModelMatchTest {

    @Test
    fun `matches by title when the query is found in the title`() {
        val result = matchDocument(query = "license", title = "Drivers License", pageTexts = emptyList())
        assertEquals(SearchMatch.TITLE, result)
    }

    @Test
    fun `matches by content when the query is only found in page text`() {
        val result = matchDocument(query = "license", title = "7sept", pageTexts = listOf("STATE DRIVERS LICENSE"))
        assertEquals(SearchMatch.CONTENT, result)
    }

    @Test
    fun `title match wins even when content also matches`() {
        val result = matchDocument(query = "license", title = "My License", pageTexts = listOf("license terms"))
        assertEquals(SearchMatch.TITLE, result)
    }

    @Test
    fun `does not match when neither title nor any page contains the query`() {
        val result = matchDocument(query = "passport", title = "Drivers License", pageTexts = listOf("STATE DRIVERS LICENSE"))
        assertEquals(SearchMatch.NONE, result)
    }

    @Test
    fun `is case-insensitive`() {
        val result = matchDocument(query = "LICENSE", title = "drivers license", pageTexts = emptyList())
        assertEquals(SearchMatch.TITLE, result)
    }

    @Test
    fun `ignores null page text without crashing`() {
        val result = matchDocument(query = "license", title = "7sept", pageTexts = listOf(null, "license"))
        assertEquals(SearchMatch.CONTENT, result)
    }
}
