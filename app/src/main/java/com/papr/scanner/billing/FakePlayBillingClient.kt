package com.papr.scanner.billing

import android.app.Activity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Test-only: drives BillingManagerStateMachineTest without real Play Services. */
class FakePlayBillingClient : PlayBillingClient {

    var connectResult: Boolean = true
    var product: BillingProduct? = null
    var ownedPurchases: List<BillingPurchase> = emptyList()
    var acknowledgeResult: Boolean = true

    var launchPurchaseFlowCallCount = 0
        private set
    var lastLaunchedProductId: String? = null
        private set
    var queryProductCallCount = 0
        private set

    private val _purchaseUpdates = MutableSharedFlow<PurchaseFlowResult>(extraBufferCapacity = 1)
    override val purchaseUpdates: SharedFlow<PurchaseFlowResult> = _purchaseUpdates

    override suspend fun connect(): Boolean = connectResult

    override suspend fun queryProduct(productId: String): BillingProduct? {
        queryProductCallCount++
        return product
    }

    override suspend fun queryOwnedPurchases(): List<BillingPurchase> = ownedPurchases

    override suspend fun acknowledgePurchase(purchaseToken: String): Boolean = acknowledgeResult

    override fun launchPurchaseFlow(activity: Activity, productId: String) {
        launchPurchaseFlowCallCount++
        lastLaunchedProductId = productId
    }

    /** Test hook: simulate a PurchasesUpdatedListener callback arriving. */
    fun emitPurchaseUpdate(result: PurchaseFlowResult) {
        _purchaseUpdates.tryEmit(result)
    }
}
