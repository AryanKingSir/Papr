package com.papr.scanner.presentation.home

import java.util.Calendar
import java.util.TimeZone

/**
 * Turns a date-picker selection into the epoch-millis range it means to the
 * user: from the start of the first picked day to the end of the last one, in
 * [zone]. Material3's picker reports each picked date as that date's midnight
 * in UTC - used directly, the end day was excluded entirely (a single-day pick
 * matched almost nothing) and the days shifted by the user's UTC offset. A
 * null [endUtcMillis] means a single day.
 */
internal fun localDayRange(startUtcMillis: Long, endUtcMillis: Long?, zone: TimeZone): LongRange {
    val start = localStartOfDay(startUtcMillis, zone)
    val nextDayAfterEnd = localStartOfDay(endUtcMillis ?: startUtcMillis, zone, plusDays = 1)
    return start until nextDayAfterEnd
}

private fun localStartOfDay(utcMidnightMillis: Long, zone: TimeZone, plusDays: Int = 0): Long {
    val utcDate = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnightMillis }
    return Calendar.getInstance(zone).apply {
        clear()
        set(utcDate.get(Calendar.YEAR), utcDate.get(Calendar.MONTH), utcDate.get(Calendar.DAY_OF_MONTH))
        add(Calendar.DAY_OF_MONTH, plusDays)
    }.timeInMillis
}
