package com.papr.scanner.billing

import android.app.Activity
import android.util.Log
import com.papr.scanner.data.local.datastore.UserPreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface BillingState {
    data object Disconnected : BillingState
    data object Connecting : BillingState
    data class Ready(val product: BillingProduct?) : BillingState
    data object Purchasing : BillingState
    /** Bought with a slow payment method; Pro unlocks once Google Play confirms the payment. */
    data object Pending : BillingState
    data object Purchased : BillingState
}

/** What [BillingManager.connect] found out about this Google account's purchase of Pro. */
enum class ConnectResult {
    /** Google Play couldn't be reached, or didn't say what the account owns. Pro is left as it was. */
    UNAVAILABLE,
    /** Play answered; Pro is now on or off to match. */
    CHECKED,
    /** Pro was bought but the payment hasn't gone through yet. */
    PENDING,
}

/**
 * Disconnected -> Connecting -> Ready -> Purchasing -> Purchased or Pending.
 * Any purchase-flow error or cancellation returns to Ready rather than a
 * dedicated error state - there's nothing for the UI to recover from beyond
 * trying again. Connecting also reconciles what the account owns, silently,
 * as a side effect on isProUnlocked: it unlocks Pro after a reinstall or on a
 * new phone, acknowledges a purchase that was never acknowledged (Play refunds
 * those after three days), and removes Pro once Play confirms the purchase is
 * gone (refunded). The app connects once at startup, so this runs without the
 * user opening the paywall.
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

    // One connection attempt at a time: a second startConnection while the first is still
    // connecting fails, which read as "couldn't reach Google Play".
    private val connectMutex = Mutex()

    init {
        scope.launch {
            playBillingClient.purchaseUpdates.collect { result ->
                // One failed update mustn't end the collection: every later purchase would go unheard.
                try {
                    handlePurchaseUpdate(result)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("Papr", "Couldn't handle a purchase update", e)
                    _state.value = BillingState.Disconnected
                }
            }
        }
    }

    /**
     * Runs in this manager's own scope, so a screen that closes mid-connect can't leave the
     * shared state stuck in Connecting for the next one.
     */
    suspend fun connect(): ConnectResult = scope.async { connectMutex.withLock { connectAndReconcile() } }.await()

    fun purchase(activity: Activity) {
        if (_state.value !is BillingState.Ready) return
        _state.value = BillingState.Purchasing
        playBillingClient.launchPurchaseFlow(activity, productId)
    }

    private suspend fun connectAndReconcile(): ConnectResult {
        _state.value = BillingState.Connecting
        return try {
            if (!playBillingClient.connect()) {
                _state.value = BillingState.Disconnected
                return ConnectResult.UNAVAILABLE
            }
            val result = reconcileOwnedPurchases()
            if (result == ConnectResult.PENDING) _state.value = BillingState.Pending else settleToReady()
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Play services missing/outdated, offline, etc. - Disconnected lets
            // the UI offer a retry instead of spinning in Connecting forever.
            _state.value = BillingState.Disconnected
            ConnectResult.UNAVAILABLE
        }
    }

    private suspend fun handlePurchaseUpdate(result: PurchaseFlowResult) {
        when (result) {
            is PurchaseFlowResult.Success -> {
                val ours = result.purchases.filter { it.productId == productId }
                val purchased = ours.firstOrNull { it.state == BillingPurchaseState.PURCHASED }
                when {
                    purchased != null -> {
                        unlock(purchased)
                        _state.value = BillingState.Purchased
                    }
                    ours.isNotEmpty() -> _state.value = BillingState.Pending
                    else -> settleToReady()
                }
            }
            // Bought before (another phone, or a purchase this one never heard about): look it up.
            PurchaseFlowResult.AlreadyOwned -> connect()
            PurchaseFlowResult.UserCancelled, is PurchaseFlowResult.Error -> settleToReady()
        }
    }

    private suspend fun reconcileOwnedPurchases(): ConnectResult {
        // null = Play didn't answer; never take Pro away on that.
        val owned = playBillingClient.queryOwnedPurchases()?.filter { it.productId == productId }
            ?: return ConnectResult.UNAVAILABLE
        val purchased = owned.firstOrNull { it.state == BillingPurchaseState.PURCHASED }
        return when {
            purchased != null -> {
                unlock(purchased)
                ConnectResult.CHECKED
            }
            owned.isNotEmpty() -> ConnectResult.PENDING
            else -> {
                // Play answered and the purchase is gone (refunded or revoked).
                userPreferencesRepository.setProUnlocked(false)
                ConnectResult.CHECKED
            }
        }
    }

    /**
     * Pro unlocks as soon as Play says it's paid for. Acknowledging can fail (offline, Play
     * busy); the user still paid, so that only means trying again on the next connect.
     */
    private suspend fun unlock(purchase: BillingPurchase) {
        userPreferencesRepository.setProUnlocked(true)
        if (!purchase.isAcknowledged) playBillingClient.acknowledgePurchase(purchase.purchaseToken)
    }

    private suspend fun settleToReady() {
        _state.value = BillingState.Ready(playBillingClient.queryProduct(productId))
    }
}
