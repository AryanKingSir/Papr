package com.papr.scanner.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class UserPreferencesRepository(private val dataStore: DataStore<Preferences>) {

    val isProUnlocked: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[IS_PRO_UNLOCKED] ?: false
    }

    suspend fun setProUnlocked(unlocked: Boolean) {
        dataStore.edit { prefs ->
            prefs[IS_PRO_UNLOCKED] = unlocked
        }
    }

    private companion object {
        val IS_PRO_UNLOCKED = booleanPreferencesKey("is_pro_unlocked")
    }
}
