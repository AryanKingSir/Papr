package com.papr.scanner.usage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * Counts the free plan's monthly uses of each [MeteredFeature] - the one place
 * every screen asks what's left. Counts live on the device in the user_prefs
 * DataStore, with the month they belong to; Pro users are never counted or blocked.
 */
class UsageTracker(
    private val dataStore: DataStore<Preferences>,
    val isPro: Flow<Boolean>,
    // Injectable so tests can move to another month.
    private val now: () -> Long = System::currentTimeMillis,
) {
    // The month, checked once a minute while a screen is watching: a screen left open over the end
    // of a month (or a clock set forward) must not keep showing last month's used-up features.
    private val currentMonth: Flow<Int> = flow {
        while (true) {
            emit(monthKey(now()))
            delay(MONTH_CHECK_INTERVAL_MS)
        }
    }.distinctUntilChanged()

    val allowances: Flow<Map<MeteredFeature, Allowance>> = combine(dataStore.data, isPro, currentMonth) { prefs, isPro, month ->
        val counts = currentCounts(prefs, month)
        MeteredFeature.entries.associateWith { allowanceFor(it, counts, isPro) }
    }

    /** The first of [features] with no uses left this month, or null if all of them can be used. */
    suspend fun firstUsedUp(features: Set<MeteredFeature>): MeteredFeature? =
        firstUsedUp(features, currentCounts(dataStore.data.first()), isPro.first())

    /** Counts one use of each of [features]. Call only once the export or merge has succeeded. */
    suspend fun recordUse(features: Set<MeteredFeature>) {
        if (features.isEmpty() || isPro.first()) return
        dataStore.edit { prefs ->
            val month = monthKey(now())
            // Written for every feature, so a new month's counts replace the old month's.
            val counts = countsAfterUse(currentCounts(prefs, month), features)
            prefs[USAGE_MONTH] = month
            MeteredFeature.entries.forEach { prefs[countKey(it)] = counts[it] ?: 0 }
        }
    }

    private fun currentCounts(prefs: Preferences, month: Int = monthKey(now())): Map<MeteredFeature, Int> =
        countsForMonth(
            storedMonth = prefs[USAGE_MONTH],
            counts = MeteredFeature.entries.associateWith { prefs[countKey(it)] ?: 0 },
            currentMonth = month,
        )

    private companion object {
        val USAGE_MONTH = intPreferencesKey("usage_month")
        const val MONTH_CHECK_INTERVAL_MS = 60_000L

        fun countKey(feature: MeteredFeature) = intPreferencesKey("usage_${feature.name.lowercase()}")
    }
}
