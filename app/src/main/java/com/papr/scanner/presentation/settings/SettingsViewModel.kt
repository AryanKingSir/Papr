package com.papr.scanner.presentation.settings

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.papr.scanner.billing.BillingManager
import com.papr.scanner.billing.ConnectResult
import com.papr.scanner.data.local.SavedSignature
import com.papr.scanner.data.local.SavedSignatureStore
import com.papr.scanner.data.local.datastore.OnboardingRepository
import com.papr.scanner.data.local.datastore.UserPreferencesRepository
import com.papr.scanner.domain.model.AppSettings
import com.papr.scanner.domain.model.ExportFormat
import com.papr.scanner.domain.model.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val preferences: UserPreferencesRepository,
    private val onboarding: OnboardingRepository,
    private val savedSignatureStore: SavedSignatureStore,
    private val billingManager: BillingManager,
    isProFlow: Flow<Boolean>,
    // The app already holds the stored settings, so the first frame shows them rather than the defaults.
    initialSettings: AppSettings?,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        preferences.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialSettings ?: AppSettings())

    val savedSignature: StateFlow<SavedSignature?> = savedSignatureStore.state

    /** Null until it's known, so a Pro owner never sees "Free plan" flash up first. */
    val isPro: StateFlow<Boolean?> = isProFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun setThemeMode(mode: ThemeMode) = save { preferences.setThemeMode(mode) }

    fun setDynamicColor(enabled: Boolean) = save { preferences.setDynamicColor(enabled) }

    fun setDeleteOriginalsAfterMerge(enabled: Boolean) = save { preferences.setDeleteOriginalsAfterMerge(enabled) }

    fun setDefaultExportFormat(format: ExportFormat) = save { preferences.setDefaultExportFormat(format) }

    fun setHapticsEnabled(enabled: Boolean) = save { preferences.setHapticsEnabled(enabled) }

    /** Brings back the welcome slides and every one-time tip. */
    fun resetTour() = save { onboarding.resetTour() }

    /** Replaces the saved signature with a freshly drawn one. */
    fun saveSignature(signature: Bitmap) = save {
        try {
            savedSignatureStore.save(signature)
        } finally {
            signature.recycle()
        }
    }

    fun removeSignature() = save { savedSignatureStore.delete() }

    /** Runs a write, turning a storage failure (a full disk, say) into a message instead of a crash. */
    private fun save(write: suspend () -> Unit) = viewModelScope.launch {
        try {
            write()
        } catch (e: IOException) {
            Log.e("Papr", "Couldn't save a setting", e)
            _messages.emit("Couldn't save that. Free up some storage and try again.")
        }
    }

    /**
     * Connecting re-checks the Google account's purchases and unlocks Pro if it
     * owns it - the same check a reinstall relies on - so restoring is just that,
     * plus saying what it found.
     */
    fun restorePurchase() {
        if (_isRestoring.value) return
        viewModelScope.launch {
            _isRestoring.value = true
            val wasPro = preferences.isProUnlocked.first()
            try {
                val message = when (billingManager.connect()) {
                    ConnectResult.UNAVAILABLE -> "Couldn't reach Google Play. Try again."
                    ConnectResult.PENDING -> "Your Papr Pro payment is still pending. Pro unlocks once Google Play confirms it."
                    // Read from storage: connect() has just written it, and a derived flow may not have caught up.
                    ConnectResult.CHECKED -> when {
                        !preferences.isProUnlocked.first() -> "No Papr Pro purchase found on this Google account."
                        wasPro -> "You already own Papr Pro."
                        else -> "Papr Pro restored."
                    }
                }
                _messages.emit(message)
            } finally {
                _isRestoring.value = false
            }
        }
    }
}
