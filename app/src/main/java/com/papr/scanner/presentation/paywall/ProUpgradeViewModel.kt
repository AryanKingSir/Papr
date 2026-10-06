package com.papr.scanner.presentation.paywall

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papr.scanner.billing.BillingManager
import com.papr.scanner.billing.BillingState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ProUpgradeViewModel(private val billingManager: BillingManager) : ViewModel() {

    val state: StateFlow<BillingState> = billingManager.state
    val isProUnlocked: StateFlow<Boolean> = billingManager.isProUnlocked

    /** Called each time the sheet opens, not once per ViewModel: the sheet's ViewModel outlives a closed sheet. */
    fun retry() {
        val current = state.value
        if (current is BillingState.Ready && current.product != null) return
        viewModelScope.launch { billingManager.connect() }
    }

    fun purchase(activity: Activity) {
        billingManager.purchase(activity)
    }
}
