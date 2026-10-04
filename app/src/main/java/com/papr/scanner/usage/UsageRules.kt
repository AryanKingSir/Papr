package com.papr.scanner.usage

import java.util.Calendar
import java.util.TimeZone

/**
 * The calendar month [millis] falls in, in [timeZone], as `year * 12 + month`.
 * Calendar rather than java.time: minSdk is 24 and the build has no desugaring.
 */
internal fun monthKey(millis: Long, timeZone: TimeZone = TimeZone.getDefault()): Int {
    val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = millis }
    return calendar.get(Calendar.YEAR) * 12 + calendar.get(Calendar.MONTH)
}

/** The stored [counts] if they belong to [currentMonth]; otherwise the month has changed and everything is back to zero. */
internal fun countsForMonth(storedMonth: Int?, counts: Map<MeteredFeature, Int>, currentMonth: Int): Map<MeteredFeature, Int> =
    if (storedMonth == currentMonth) counts else emptyMap()

internal fun allowanceFor(feature: MeteredFeature, counts: Map<MeteredFeature, Int>, isPro: Boolean): Allowance =
    Allowance(
        remaining = if (isPro) null else (FREE_MONTHLY_LIMIT - (counts[feature] ?: 0)).coerceAtLeast(0),
        limit = FREE_MONTHLY_LIMIT,
    )

/** The first of [features] with no uses left this month, or null if all of them can be used. */
internal fun firstUsedUp(features: Set<MeteredFeature>, counts: Map<MeteredFeature, Int>, isPro: Boolean): MeteredFeature? =
    if (isPro) null else features.firstOrNull { (counts[it] ?: 0) >= FREE_MONTHLY_LIMIT }

internal fun countsAfterUse(counts: Map<MeteredFeature, Int>, features: Set<MeteredFeature>): Map<MeteredFeature, Int> =
    counts + features.associateWith { (counts[it] ?: 0) + 1 }
