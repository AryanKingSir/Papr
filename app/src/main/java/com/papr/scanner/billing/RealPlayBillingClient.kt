package com.papr.scanner.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine

class RealPlayBillingClient(context: Context) : PlayBillingClient {

    private val productDetailsCache = mutableMapOf<String, ProductDetails>()

    private val _purchaseUpdates = MutableSharedFlow<PurchaseFlowResult>(extraBufferCapacity = 1)
    override val purchaseUpdates: SharedFlow<PurchaseFlowResult> = _purchaseUpdates

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        _purchaseUpdates.tryEmit(mapPurchasesUpdate(billingResult, purchases))
    }

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        // Re-establishes a dropped connection on the next call, instead of that call failing.
        .enableAutoServiceReconnection()
        .build()

    override suspend fun connect(): Boolean = suspendCancellableCoroutine { continuation ->
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (continuation.isActive) {
                    continuation.resume(billingResult.responseCode == BillingClient.BillingResponseCode.OK)
                }
            }

            override fun onBillingServiceDisconnected() {
                // Auto service reconnection restores the connection on the next call; nothing
                // to resume if this fires after setup already completed one way or the other.
            }
        })
    }

    override suspend fun queryProduct(productId: String): BillingProduct? {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        val result = client.queryProductDetails(params)
        val details = result.productDetailsList?.firstOrNull { it.productId == productId } ?: return null
        val price = details.oneTimePurchaseOfferDetails?.formattedPrice ?: return null
        productDetailsCache[productId] = details
        return BillingProduct(productId = productId, formattedPrice = price)
    }

    override suspend fun queryOwnedPurchases(): List<BillingPurchase>? {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        val purchases = suspendCancellableCoroutine<List<Purchase>?> { continuation ->
            client.queryPurchasesAsync(params) { billingResult, purchases ->
                continuation.resume(purchases.takeIf { billingResult.responseCode == BillingClient.BillingResponseCode.OK })
            }
        }
        return purchases?.flatMap { it.toBillingPurchases() }
    }

    override suspend fun acknowledgePurchase(purchaseToken: String): Boolean {
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()
        val result = client.acknowledgePurchase(params)
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    override fun launchPurchaseFlow(activity: Activity, productId: String) {
        val details = productDetailsCache[productId]
        if (details == null) {
            _purchaseUpdates.tryEmit(PurchaseFlowResult.Error("Product not loaded: $productId"))
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()
        client.launchBillingFlow(activity, params)
    }

    private fun mapPurchasesUpdate(billingResult: BillingResult, purchases: List<Purchase>?): PurchaseFlowResult =
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK ->
                PurchaseFlowResult.Success(purchases.orEmpty().flatMap { it.toBillingPurchases() })
            BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseFlowResult.UserCancelled
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseFlowResult.AlreadyOwned
            else -> PurchaseFlowResult.Error(billingResult.debugMessage)
        }

    private fun Purchase.toBillingPurchases(): List<BillingPurchase> = products.map { id ->
        BillingPurchase(
            productId = id,
            purchaseToken = purchaseToken,
            state = if (purchaseState == Purchase.PurchaseState.PURCHASED) {
                BillingPurchaseState.PURCHASED
            } else {
                BillingPurchaseState.PENDING
            },
            isAcknowledged = isAcknowledged,
        )
    }
}
