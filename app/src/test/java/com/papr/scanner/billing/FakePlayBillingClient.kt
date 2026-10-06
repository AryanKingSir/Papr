package com.papr.scanner.billing

import android.app.Activity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Test-only: drives BillingManagerStateMachineTest without real Play Services. */
class FakePlayBillingClient : PlayBillingClient {

    var connectResult: Boolean = true
    var product: BillingProduct? = null
    /** null = Play didn't answer the query (offline, error). */
    var ownedPurchases: List<BillingPurchase>? = emptyList()
    var acknowledgeResult: Boolean = true
    var queryProductError: Exception? = null
    /** When set, connect() waits for it - to test a caller giving up mid-connect. */
    var connectGate: CompletableDeferred<Unit>? = null

    val acknowledgedTokens = mutableListOf<String>()

    var launchPurchaseFlowCallCount = 0
        private set
    var lastLaunchedProductId: String? = null
        private set
    var queryProductCallCount = 0
        private set

    private val _purchaseUpdates = MutableSharedFlow<PurchaseFlowResult>(extraBufferCapacity = 1)
    override val purchaseUpdates: SharedFlow<PurchaseFlowResult> = _purchaseUpdates

    override suspend fun connect(): Boolean {
        connectGate?.await()
        return connectResult
    }

    override suspend fun queryProduct(productId: String): BillingProduct? {
        queryProductCallCount++
        queryProductError?.let { throw it }
        return product
    }

    override suspend fun queryOwnedPurchases(): List<BillingPurchase>? = ownedPurchases

    override suspend fun acknowledgePurchase(purchaseToken: String): Boolean {
        acknowledgedTokens += purchaseToken
        return acknowledgeResult
    }

    override fun launchPurchaseFlow(activity: Activity, productId: String) {
        launchPurchaseFlowCallCount++
        lastLaunchedProductId = productId
    }

    /** Test hook: simulate a PurchasesUpdatedListener callback arriving. */
    fun emitPurchaseUpdate(result: PurchaseFlowResult) {
        _purchaseUpdates.tryEmit(result)
    }
}
