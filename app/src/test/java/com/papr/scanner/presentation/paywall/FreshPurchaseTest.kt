package com.papr.scanner.presentation.paywall

import com.papr.scanner.billing.BillingState
import org.junit.Assert.assertEquals
import org.junit.Test

class FreshPurchaseTest {

    @Test
    fun `a purchase that just went through counts`() {
        assertEquals(true, isFreshPurchase(BillingState.Purchasing, BillingState.Purchased))
    }

    @Test
    fun `an earlier purchase seen again when the sheet reopens doesn't`() {
        assertEquals(false, isFreshPurchase(BillingState.Purchased, BillingState.Purchased))
        assertEquals(false, isFreshPurchase(BillingState.Connecting, BillingState.Purchased))
    }

    @Test
    fun `a purchase that ends pending or cancelled doesn't`() {
        assertEquals(false, isFreshPurchase(BillingState.Purchasing, BillingState.Pending))
        assertEquals(false, isFreshPurchase(BillingState.Purchasing, BillingState.Ready(product = null)))
    }
}
