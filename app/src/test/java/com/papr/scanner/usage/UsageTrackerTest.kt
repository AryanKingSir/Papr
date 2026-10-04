@file:OptIn(ExperimentalCoroutinesApi::class)

package com.papr.scanner.usage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UsageTrackerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val isPro = MutableStateFlow(false)
    private var now = millis(2026, Calendar.OCTOBER, 4)

    private fun millis(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, 12, 0)
        }.timeInMillis

    // DataStore on the test's own dispatcher, as in BillingManagerStateMachineTest.
    private fun TestScope.newTracker(): UsageTracker {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(coroutineContext + Job()),
            produceFile = { tempFolder.newFile("${System.nanoTime()}.preferences_pb") },
        )
        return UsageTracker(dataStore, isPro, now = { now })
    }

    private suspend fun UsageTracker.remaining(feature: MeteredFeature): Int? = allowances.first().getValue(feature).remaining

    @Test
    fun `a new install has every feature's full limit`() = runTest {
        val tracker = newTracker()
        MeteredFeature.entries.forEach { assertEquals(FREE_MONTHLY_LIMIT, tracker.remaining(it)) }
    }

    @Test
    fun `a recorded use counts against only its own features`() = runTest {
        val tracker = newTracker()
        tracker.recordUse(setOf(MeteredFeature.OCR_EXPORT, MeteredFeature.PASSWORD_EXPORT))

        assertEquals(4, tracker.remaining(MeteredFeature.OCR_EXPORT))
        assertEquals(4, tracker.remaining(MeteredFeature.PASSWORD_EXPORT))
        assertEquals(5, tracker.remaining(MeteredFeature.COMPRESSED_EXPORT))
        assertEquals(5, tracker.remaining(MeteredFeature.MERGE))
    }

    @Test
    fun `the fifth use leaves the feature used up`() = runTest {
        val tracker = newTracker()
        repeat(4) { tracker.recordUse(setOf(MeteredFeature.MERGE)) }
        assertNull(tracker.firstUsedUp(setOf(MeteredFeature.MERGE)))

        tracker.recordUse(setOf(MeteredFeature.MERGE))

        assertEquals(MeteredFeature.MERGE, tracker.firstUsedUp(setOf(MeteredFeature.MERGE)))
        assertEquals(0, tracker.remaining(MeteredFeature.MERGE))
    }

    @Test
    fun `a new month starts every count again`() = runTest {
        val tracker = newTracker()
        repeat(5) { tracker.recordUse(setOf(MeteredFeature.MERGE)) }
        tracker.recordUse(setOf(MeteredFeature.OCR_EXPORT))

        now = millis(2026, Calendar.NOVEMBER, 1)

        assertNull(tracker.firstUsedUp(setOf(MeteredFeature.MERGE)))
        assertEquals(5, tracker.remaining(MeteredFeature.MERGE))
        assertEquals(5, tracker.remaining(MeteredFeature.OCR_EXPORT))
    }

    @Test
    fun `the first use in a new month counts from zero`() = runTest {
        val tracker = newTracker()
        repeat(3) { tracker.recordUse(setOf(MeteredFeature.MERGE)) }
        tracker.recordUse(setOf(MeteredFeature.OCR_EXPORT))

        now = millis(2027, Calendar.JANUARY, 2)
        tracker.recordUse(setOf(MeteredFeature.MERGE))

        assertEquals(4, tracker.remaining(MeteredFeature.MERGE))
        assertEquals(5, tracker.remaining(MeteredFeature.OCR_EXPORT))
    }

    @Test
    fun `pro is unlimited and never counted`() = runTest {
        val tracker = newTracker()
        repeat(5) { tracker.recordUse(setOf(MeteredFeature.MERGE)) }

        isPro.value = true
        tracker.recordUse(setOf(MeteredFeature.OCR_EXPORT))

        assertNull(tracker.firstUsedUp(setOf(MeteredFeature.MERGE)))
        assertNull(tracker.remaining(MeteredFeature.MERGE))

        isPro.value = false
        assertEquals(5, tracker.remaining(MeteredFeature.OCR_EXPORT))
    }
}
