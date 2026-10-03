package com.papr.scanner.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.papr.scanner.PaprApplication
import com.papr.scanner.di.AppContainer

/**
 * Builds a ViewModel from AppContainer without a generic Hilt-style factory - see AppContainer's own "manual DI" rationale.
 * [create] runs with the CreationExtras as its receiver, so a ViewModel that needs to survive process death can
 * call `createSavedStateHandle()`.
 */
@Composable
inline fun <reified VM : ViewModel> paprViewModel(crossinline create: CreationExtras.(AppContainer) -> VM): VM {
    val container = (LocalContext.current.applicationContext as PaprApplication).container
    return viewModel(factory = viewModelFactory { initializer { create(container) } })
}
