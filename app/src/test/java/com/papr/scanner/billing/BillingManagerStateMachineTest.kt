@file:OptIn(ExperimentalCoroutinesApi::class)

package com.papr.scanner.billing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.papr.scanner.data.local.datastore.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BillingManagerStateMachineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val productId = "pro_lifetime_unlock"

    /**
     * A scope on the test's own dispatcher (so it drives correctly with
     * virtual time) but with a Job unrelated to the test's structured
     * concurrency, so BillingManager's never-ending purchaseUpdates collector
     * doesn't trip runTest's "no uncompleted coroutines" check. This project's
     * kotlinx-coroutines-test setup was empirically found to never actually
     * run TestScope.backgroundScope's launched coroutine bodies at all, so
     * backgroundScope - the usual recommended tool for exactly this - isn't
     * used here.
     *
     * DataStore is built on this SAME scope (rather than its own default
     * Dispatchers.IO-backed one): otherwise its real internal write
     * coroutine runs on a foreign dispatcher advanceUntilIdle() can't drive,
     * which only broke tests where setProUnlocked() was reached through the
     * async purchaseUpdates collector rather than a directly-awaited
     * connect() call - a real, reproducible flakiness source, not a guess.
     */
    private fun TestScope.newManagerScope(): CoroutineScope = CoroutineScope(coroutineContext + Job())

    private fun TestScope.newUserPreferencesRepository(scope: CoroutineScope): UserPreferencesRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("${System.nanoTime()}.preferences_pb") }
        )
        return UserPreferencesRepository(dataStore)
    }

    @Test
    fun `connect settles into Ready with the queried product`() = runTest {
        val client = FakePlayBillingClient().apply { product = BillingProduct(productId, "$4.99") }
        val scope = newManagerScope()
        val manager = BillingManager(client, newUserPreferencesRepository(scope), productId, scope = scope)
        advanceUntilIdle() // let the manager's init-block collector subscribe before anything emits

        manager.connect()

        val state = manager.state.value
        assertTrue(state is BillingState.Ready)
        assertEquals(BillingProduct(productId, "$4.99"), (state as BillingState.Ready).product)
        assertEquals(false, manager.isProUnlocked.value)
    }

    @Test
    fun `full purchase flow acknowledges and unlocks entitlement`() = runTest {
        val client = FakePlayBillingClient().apply { product = BillingProduct(productId, "$4.99") }
        val scope = newManagerScope()
        val manager = BillingManager(client, newUserPreferencesRepository(scope), productId, scope = scope)
        advanceUntilIdle()
        manager.connect()

        client.emitPurchaseUpdate(
            PurchaseFlowResult.Success(
                listOf(BillingPurchase(productId, "token-1", BillingPurchaseState.PURCHASED, isAcknowledged = false))
            )
        )
        advanceUntilIdle()

        assertEquals(BillingState.Purchased, manager.state.value)
        assertTrue(manager.isProUnlocked.value)
    }

    @Test
    fun `cancelled purchase returns to Ready without unlocking entitlement`() = runTest {
        val client = FakePlayBillingClient().apply { product = BillingProduct(productId, "$4.99") }
        val scope = newManagerScope()
        val manager = BillingManager(client, newUserPreferencesRepository(scope), productId, scope = scope)
        advanceUntilIdle()
        manager.connect()
        val queryCountAfterConnect = client.queryProductCallCount

        client.emitPurchaseUpdate(PurchaseFlowResult.UserCancelled)
        advanceUntilIdle()

        // Proves the event was actually processed (re-settled to Ready), not just
        // that Ready was already the state left over from connect() alone.
        assertTrue(client.queryProductCallCount > queryCountAfterConnect)
        assertTrue(manager.state.value is BillingState.Ready)
        assertEquals(false, manager.isProUnlocked.value)
    }

    @Test
    fun `error path returns to Ready without unlocking entitlement`() = runTest {
        val client = FakePlayBillingClient().apply { product = BillingProduct(productId, "$4.99") }
        val scope = newManagerScope()
        val manager = BillingManager(client, newUserPreferencesRepository(scope), productId, scope = scope)
        advanceUntilIdle()
        manager.connect()
        val queryCountAfterConnect = client.queryProductCallCount

        client.emitPurchaseUpdate(PurchaseFlowResult.Error("boom"))
        advanceUntilIdle()

        assertTrue(client.queryProductCallCount > queryCountAfterConnect)
        assertTrue(manager.state.value is BillingState.Ready)
        assertEquals(false, manager.isProUnlocked.value)
    }

    @Test
    fun `startup reconciliation unlocks entitlement for an already-owned purchase`() = runTest {
        val client = FakePlayBillingClient().apply {
            product = BillingProduct(productId, "$4.99")
            ownedPurchases = listOf(BillingPurchase(productId, "token-2", BillingPurchaseState.PURCHASED, isAcknowledged = true))
        }
        val scope = newManagerScope()
        val manager = BillingManager(client, newUserPreferencesRepository(scope), productId, scope = scope)
        advanceUntilIdle()

        manager.connect()

        assertTrue(manager.state.value is BillingState.Ready)
        assertTrue(manager.isProUnlocked.value)
    }

    @Test
    fun `a failure while connecting settles into Disconnected instead of hanging in Connecting`() = runTest {
        val client = FakePlayBillingClient().apply { queryProductError = IllegalStateException("Play Store unavailable") }
        val scope = newManagerScope()
        val manager = BillingManager(client, newUserPreferencesRepository(scope), productId, scope = scope)
        advanceUntilIdle()

        manager.connect()

        assertEquals(BillingState.Disconnected, manager.state.value)
    }
}
