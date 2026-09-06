package com.papr.scanner.billing

import android.app.Activity
import kotlinx.coroutines.flow.SharedFlow

data class BillingProduct(val productId: String, val formattedPrice: String)

enum class BillingPurchaseState { PURCHASED, PENDING }

data class BillingPurchase(
    val productId: String,
    val purchaseToken: String,
    val state: BillingPurchaseState,
    val isAcknowledged: Boolean,
)

sealed interface PurchaseFlowResult {
    data class Success(val purchases: List<BillingPurchase>) : PurchaseFlowResult
    data object UserCancelled : PurchaseFlowResult
    data class Error(val message: String) : PurchaseFlowResult
}

/**
 * A thin interface over BillingClient v9, expressed entirely in this app's
 * own simple domain types rather than com.android.billingclient.api.* model
 * classes - those aren't meaningfully constructible outside real Play
 * Services, which would make FakePlayBillingClient (and so
 * BillingManagerStateMachineTest) impossible without Robolectric or a real
 * device, contrary to the project's lightweight-testing policy.
 */
interface PlayBillingClient {
    suspend fun connect(): Boolean

    suspend fun queryProduct(productId: String): BillingProduct?

    suspend fun queryOwnedPurchases(): List<BillingPurchase>

    suspend fun acknowledgePurchase(purchaseToken: String): Boolean

    /** Fire-and-forget: the result arrives via [purchaseUpdates]. Requires a prior [queryProduct] for this id. */
    fun launchPurchaseFlow(activity: Activity, productId: String)

    val purchaseUpdates: SharedFlow<PurchaseFlowResult>
}
