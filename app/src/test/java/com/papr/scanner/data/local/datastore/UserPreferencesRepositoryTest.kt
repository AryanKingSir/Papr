@file:OptIn(ExperimentalCoroutinesApi::class)

package com.papr.scanner.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.papr.scanner.domain.model.AppSettings
import com.papr.scanner.domain.model.ExportFormat
import com.papr.scanner.domain.model.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserPreferencesRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>

    // DataStore on the test's own dispatcher, as in UsageTrackerTest.
    private fun TestScope.newRepository(): UserPreferencesRepository {
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(coroutineContext + Job()),
            produceFile = { tempFolder.newFile("${System.nanoTime()}.preferences_pb") },
        )
        return UserPreferencesRepository(dataStore)
    }

    @Test
    fun `a new install has the settings the app always had`() = runTest {
        assertEquals(AppSettings(), newRepository().settings.first())
    }

    @Test
    fun `every setting is saved and read back`() = runTest {
        val repository = newRepository()

        repository.setThemeMode(ThemeMode.DARK)
        repository.setDynamicColor(false)
        repository.setDeleteOriginalsAfterMerge(true)
        repository.setDefaultExportFormat(ExportFormat.IMAGES)

        assertEquals(
            AppSettings(
                themeMode = ThemeMode.DARK,
                dynamicColor = false,
                deleteOriginalsAfterMerge = true,
                defaultExportFormat = ExportFormat.IMAGES,
            ),
            repository.settings.first(),
        )
    }

    @Test
    fun `a stored value this version doesn't know falls back to the default`() = runTest {
        val repository = newRepository()
        dataStore.edit {
            it[stringPreferencesKey("theme_mode")] = "SEPIA"
            it[stringPreferencesKey("default_export_format")] = "TIFF"
        }

        assertEquals(AppSettings(), repository.settings.first())
    }
}
