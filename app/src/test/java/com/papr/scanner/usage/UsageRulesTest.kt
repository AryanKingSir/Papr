package com.papr.scanner.usage

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageRulesTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun millis(year: Int, month: Int, day: Int, hour: Int = 12, timeZone: TimeZone = utc): Long =
        Calendar.getInstance(timeZone).apply {
            clear()
            set(year, month, day, hour, 0)
        }.timeInMillis

    @Test
    fun `days in the same month share a key`() {
        assertEquals(
            monthKey(millis(2026, Calendar.OCTOBER, 1, hour = 0), utc),
            monthKey(millis(2026, Calendar.OCTOBER, 31, hour = 23), utc),
        )
    }

    @Test
    fun `december and the following january get consecutive keys`() {
        val december = monthKey(millis(2026, Calendar.DECEMBER, 31), utc)
        val january = monthKey(millis(2027, Calendar.JANUARY, 1), utc)
        assertEquals(december + 1, january)
    }

    @Test
    fun `the month follows the device's local time`() {
        // 20:00 UTC on 31 October is already 1 November in India (UTC+5:30).
        val instant = millis(2026, Calendar.OCTOBER, 31, hour = 20)
        val india = TimeZone.getTimeZone("Asia/Kolkata")
        assertEquals(monthKey(millis(2026, Calendar.NOVEMBER, 1), utc), monthKey(instant, india))
    }

    @Test
    fun `counts from the current month are kept`() {
        val counts = mapOf(MeteredFeature.MERGE to 3)
        assertEquals(counts, countsForMonth(storedMonth = 100, counts = counts, currentMonth = 100))
    }

    @Test
    fun `counts from another month start again from zero`() {
        val counts = mapOf(MeteredFeature.MERGE to 3)
        assertEquals(emptyMap<MeteredFeature, Int>(), countsForMonth(storedMonth = 99, counts = counts, currentMonth = 100))
    }

    @Test
    fun `nothing stored yet means zero counts`() {
        assertEquals(emptyMap<MeteredFeature, Int>(), countsForMonth(storedMonth = null, counts = emptyMap(), currentMonth = 100))
    }

    @Test
    fun `a free user has the limit minus what they used`() {
        val allowance = allowanceFor(MeteredFeature.OCR_EXPORT, mapOf(MeteredFeature.OCR_EXPORT to 2), isPro = false)
        assertEquals(Allowance(remaining = 1, limit = FREE_MONTHLY_LIMIT), allowance)
    }

    @Test
    fun `an unused feature has the full limit`() {
        val allowance = allowanceFor(MeteredFeature.MERGE, mapOf(MeteredFeature.OCR_EXPORT to 2), isPro = false)
        assertEquals(Allowance(remaining = FREE_MONTHLY_LIMIT, limit = FREE_MONTHLY_LIMIT), allowance)
    }

    @Test
    fun `remaining never goes below zero`() {
        val allowance = allowanceFor(MeteredFeature.MERGE, mapOf(MeteredFeature.MERGE to 9), isPro = false)
        assertEquals(0, allowance.remaining)
    }

    @Test
    fun `pro is unlimited whatever was used`() {
        val allowance = allowanceFor(MeteredFeature.MERGE, mapOf(MeteredFeature.MERGE to 9), isPro = true)
        assertNull(allowance.remaining)
    }

    @Test
    fun `below the limit nothing is used up`() {
        val counts = mapOf(MeteredFeature.OCR_EXPORT to 2, MeteredFeature.PASSWORD_EXPORT to 2)
        assertNull(firstUsedUp(setOf(MeteredFeature.OCR_EXPORT, MeteredFeature.PASSWORD_EXPORT), counts, isPro = false))
    }

    @Test
    fun `at the limit the feature is used up`() {
        val counts = mapOf(MeteredFeature.OCR_EXPORT to 2, MeteredFeature.PASSWORD_EXPORT to 3)
        assertEquals(
            MeteredFeature.PASSWORD_EXPORT,
            firstUsedUp(setOf(MeteredFeature.OCR_EXPORT, MeteredFeature.PASSWORD_EXPORT), counts, isPro = false),
        )
    }

    @Test
    fun `pro never runs out`() {
        val counts = mapOf(MeteredFeature.MERGE to 3)
        assertNull(firstUsedUp(setOf(MeteredFeature.MERGE), counts, isPro = true))
    }

    @Test
    fun `using several features adds one to each`() {
        val counts = mapOf(MeteredFeature.OCR_EXPORT to 2)
        assertEquals(
            mapOf(MeteredFeature.OCR_EXPORT to 3, MeteredFeature.COMPRESSED_EXPORT to 1),
            countsAfterUse(counts, setOf(MeteredFeature.OCR_EXPORT, MeteredFeature.COMPRESSED_EXPORT)),
        )
    }
}
