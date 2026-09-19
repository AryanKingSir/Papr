package com.papr.scanner.billing

import android.app.Activity
import android.util.Log
import com.papr.scanner.data.local.datastore.UserPreferencesRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface BillingState {
    data object Disconnected : BillingState
    data object Connecting : BillingState
    data class Ready(val product: BillingProduct?) : BillingState
    data object Purchasing : BillingState
    data object Purchased : BillingState
}

/**
 * Disconnected -> Connecting -> Ready -> Purchasing -> Purchased(+acknowledge).
 * Any purchase-flow error or cancellation returns to Ready rather than a
 * dedicated error state - there's nothing for the UI to recover from beyond
 * trying again. Startup reconciliation (an already-owned purchase found on
 * connect) is a silent side effect on isProUnlocked, not its own state:
 * it persists entitlement for a reinstall/new-device case with no purchase
 * event to celebrate, and connect() always settles in Ready afterward.
 */
class BillingManager(
    private val playBillingClient: PlayBillingClient,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val productId: String,
    // Injectable so tests can pass a TestScope and drive this deterministically
    // with virtual time, instead of racing a real background dispatcher.
    // SupervisorJob only isolates sibling coroutines from each other - it
    // doesn't stop an exception from propagating and killing the process, so
    // this also needs its own handler since this scope lives for the app's
    // whole lifetime, independent of any screen.
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            Log.e("Papr", "Unhandled billing error", throwable)
        }
    ),
) {
    private val _state = MutableStateFlow<BillingState>(BillingState.Disconnected)
    val state: StateFlow<BillingState> = _state.asStateFlow()

    val isProUnlocked: StateFlow<Boolean> =
        userPreferencesRepository.isProUnlocked.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch {
            playBillingClient.purchaseUpdates.collect { result -> handlePurchaseUpdate(result) }
        }
    }

    suspend fun connect() {
        _state.value = BillingState.Connecting
        if (!playBillingClient.connect()) {
            _state.value = BillingState.Disconnected
            return
        }
        reconcileOwnedPurchases()
        settleToReady()
    }

    fun purchase(activity: Activity) {
        if (_state.value !is BillingState.Ready) return
        _state.value = BillingState.Purchasing
        playBillingClient.launchPurchaseFlow(activity, productId)
    }

    private suspend fun handlePurchaseUpdate(result: PurchaseFlowResult) {
        val purchase = (result as? PurchaseFlowResult.Success)?.purchases
            ?.firstOrNull { it.productId == productId && it.state == BillingPurchaseState.PURCHASED }

        if (purchase != null && acknowledgeAndPersist(purchase)) {
            _state.value = BillingState.Purchased
        } else {
            settleToReady()
        }
    }

    private suspend fun reconcileOwnedPurchases() {
        playBillingClient.queryOwnedPurchases()
            .firstOrNull { it.productId == productId && it.state == BillingPurchaseState.PURCHASED }
            ?.let { acknowledgeAndPersist(it) }
    }

    private suspend fun acknowledgeAndPersist(purchase: BillingPurchase): Boolean {
        if (!purchase.isAcknowledged && !playBillingClient.acknowledgePurchase(purchase.purchaseToken)) {
            return false
        }
        userPreferencesRepository.setProUnlocked(true)
        return true
    }

    private suspend fun settleToReady() {
        _state.value = BillingState.Ready(playBillingClient.queryProduct(productId))
    }
}
