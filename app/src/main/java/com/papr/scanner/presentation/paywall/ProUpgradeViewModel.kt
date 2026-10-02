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

    init {
        retry()
    }

    fun retry() {
        viewModelScope.launch { billingManager.connect() }
    }

    fun purchase(activity: Activity) {
        billingManager.purchase(activity)
    }
}
