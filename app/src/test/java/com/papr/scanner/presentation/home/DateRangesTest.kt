package com.papr.scanner.presentation.home

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DateRangesTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** What Material3's date picker hands back for a picked day: that date's midnight in UTC. */
    private fun pickerDate(year: Int, month: Int, day: Int) =
        Calendar.getInstance(utc).apply { clear(); set(year, month - 1, day) }.timeInMillis

    private fun localTime(zone: TimeZone, year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0) =
        Calendar.getInstance(zone).apply { clear(); set(year, month - 1, day, hour, minute) }.timeInMillis

    @Test
    fun `a single picked day covers that whole local day`() {
        val zone = TimeZone.getTimeZone("Asia/Kolkata")

        val range = localDayRange(pickerDate(2026, 9, 7), null, zone)

        assertEquals(localTime(zone, 2026, 9, 7), range.first)
        assertEquals(localTime(zone, 2026, 9, 8) - 1, range.last)
    }

    @Test
    fun `the end day is included in full`() {
        val zone = TimeZone.getTimeZone("America/Los_Angeles")

        val range = localDayRange(pickerDate(2026, 9, 1), pickerDate(2026, 9, 3), zone)

        assertTrue(localTime(zone, 2026, 9, 3, hour = 21, minute = 30) in range)
        assertFalse(localTime(zone, 2026, 9, 4) in range)
    }

    @Test
    fun `a western timezone doesn't shift the start a day early`() {
        val zone = TimeZone.getTimeZone("America/Los_Angeles")

        val range = localDayRange(pickerDate(2026, 9, 7), null, zone)

        assertFalse(localTime(zone, 2026, 9, 6, hour = 20) in range)
        assertTrue(localTime(zone, 2026, 9, 7, hour = 0, minute = 1) in range)
    }
}
