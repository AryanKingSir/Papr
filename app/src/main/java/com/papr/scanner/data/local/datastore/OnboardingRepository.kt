package com.papr.scanner.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.papr.scanner.domain.model.OnboardingState
import com.papr.scanner.domain.model.Tip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Remembers the welcome slides and one-time tips, in the user_prefs DataStore next to the settings. */
class OnboardingRepository(private val dataStore: DataStore<Preferences>) {

    val state: Flow<OnboardingState> = dataStore.data.map { prefs ->
        OnboardingState(
            welcomeSeen = prefs[WELCOME_SEEN] ?: false,
            seenTips = Tip.entries.filter { prefs[tipKey(it)] == true }.toSet(),
        )
    }

    suspend fun markWelcomeSeen() = dataStore.edit { it[WELCOME_SEEN] = true }

    suspend fun markTipSeen(tip: Tip) = dataStore.edit { it[tipKey(tip)] = true }

    /** "Show tour again": everything the tour remembers is forgotten, and nothing else is touched. */
    suspend fun resetTour() = dataStore.edit { prefs ->
        prefs.remove(WELCOME_SEEN)
        Tip.entries.forEach { prefs.remove(tipKey(it)) }
    }

    private companion object {
        val WELCOME_SEEN = booleanPreferencesKey("welcome_seen")

        fun tipKey(tip: Tip) = booleanPreferencesKey("tip_seen_${tip.name.lowercase()}")
    }
}
