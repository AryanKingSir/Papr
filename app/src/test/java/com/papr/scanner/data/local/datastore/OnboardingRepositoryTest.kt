@file:OptIn(ExperimentalCoroutinesApi::class)

package com.papr.scanner.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.papr.scanner.domain.model.OnboardingState
import com.papr.scanner.domain.model.ThemeMode
import com.papr.scanner.domain.model.Tip
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

class OnboardingRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>

    // DataStore on the test's own dispatcher.
    private fun TestScope.newRepository(): OnboardingRepository {
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(coroutineContext + Job()),
            produceFile = { tempFolder.newFile("${System.nanoTime()}.preferences_pb") },
        )
        return OnboardingRepository(dataStore)
    }

    @Test
    fun `a new install has seen nothing`() = runTest {
        assertEquals(OnboardingState(welcomeSeen = false, seenTips = emptySet()), newRepository().state.first())
    }

    @Test
    fun `the welcome and each tip are remembered on their own`() = runTest {
        val repository = newRepository()

        repository.markWelcomeSeen()
        repository.markTipSeen(Tip.ORGANIZE_DRAG)

        assertEquals(OnboardingState(welcomeSeen = true, seenTips = setOf(Tip.ORGANIZE_DRAG)), repository.state.first())
    }

    @Test
    fun `resetting the tour brings the tips back but keeps the welcome marked as seen`() = runTest {
        // The replay is opened from Settings directly. Clearing the welcome too would leave it
        // cleared if the replay is left with Back, and the slides would then open every launch.
        val repository = newRepository()
        repository.markWelcomeSeen()
        Tip.entries.forEach { repository.markTipSeen(it) }

        repository.resetTour()

        assertEquals(OnboardingState(welcomeSeen = true, seenTips = emptySet()), repository.state.first())
    }

    @Test
    fun `resetting the tour clears only the tour`() = runTest {
        val repository = newRepository()
        val settings = UserPreferencesRepository(dataStore)
        settings.setThemeMode(ThemeMode.DARK)
        settings.setHapticsEnabled(false)
        repository.markWelcomeSeen()
        Tip.entries.forEach { repository.markTipSeen(it) }

        repository.resetTour()

        assertEquals(emptySet<Tip>(), repository.state.first().seenTips)
        assertEquals(ThemeMode.DARK, settings.settings.first().themeMode)
        assertEquals(false, settings.settings.first().hapticsEnabled)
    }
}
