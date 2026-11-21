package com.papr.scanner.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.papr.scanner.domain.model.AppSettings
import com.papr.scanner.domain.model.ExportFormat
import com.papr.scanner.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class UserPreferencesRepository(private val dataStore: DataStore<Preferences>) {

    /** Anything not set yet - or stored by a newer version with a value this one doesn't know - reads as the default. */
    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            themeMode = enumOrNull<ThemeMode>(prefs[THEME_MODE]) ?: defaults.themeMode,
            dynamicColor = prefs[DYNAMIC_COLOR] ?: defaults.dynamicColor,
            deleteOriginalsAfterMerge = prefs[DELETE_ORIGINALS_AFTER_MERGE] ?: defaults.deleteOriginalsAfterMerge,
            defaultExportFormat = enumOrNull<ExportFormat>(prefs[DEFAULT_EXPORT_FORMAT]) ?: defaults.defaultExportFormat,
            hapticsEnabled = prefs[HAPTICS_ENABLED] ?: defaults.hapticsEnabled,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = dataStore.edit { it[THEME_MODE] = mode.name }

    suspend fun setDynamicColor(enabled: Boolean) = dataStore.edit { it[DYNAMIC_COLOR] = enabled }

    suspend fun setDeleteOriginalsAfterMerge(enabled: Boolean) = dataStore.edit { it[DELETE_ORIGINALS_AFTER_MERGE] = enabled }

    suspend fun setDefaultExportFormat(format: ExportFormat) = dataStore.edit { it[DEFAULT_EXPORT_FORMAT] = format.name }

    suspend fun setHapticsEnabled(enabled: Boolean) = dataStore.edit { it[HAPTICS_ENABLED] = enabled }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        enumValues<T>().firstOrNull { it.name == name }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DELETE_ORIGINALS_AFTER_MERGE = booleanPreferencesKey("delete_originals_after_merge")
        val DEFAULT_EXPORT_FORMAT = stringPreferencesKey("default_export_format")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
    }
}
