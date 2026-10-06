package com.papr.scanner.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.papr.scanner.PaprApplication
import com.papr.scanner.presentation.detail.DocumentDetailScreen
import com.papr.scanner.presentation.home.HomeScreen
import com.papr.scanner.presentation.merge.MergeConfirmScreen
import com.papr.scanner.presentation.onboarding.WelcomeScreen
import com.papr.scanner.presentation.paywall.ProUpgradeSheet
import com.papr.scanner.presentation.settings.SettingsScreen
import kotlinx.coroutines.launch

@Composable
fun PaprNavHost(
    navController: NavHostController,
    startDestination: String,
    onScanClick: () -> Unit,
    onAddPagesClick: (documentId: Long) -> Unit,
    onReplacePageClick: (documentId: Long, pageId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = (LocalContext.current.applicationContext as PaprApplication).container
    NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {
        composable(Screen.Welcome.route) { entry ->
            WelcomeScreen(
                onFinish = {
                    navController.fromTop(entry) {
                        // On the app scope: the slides are gone before the write lands.
                        container.applicationScope.launch { container.onboardingRepository.markWelcomeSeen() }
                        if (previousBackStackEntry != null) {
                            // Opened again from Settings: back to where they came from.
                            popBackStack()
                        } else {
                            navigate(Screen.Home.route) { popUpTo(Screen.Welcome.route) { inclusive = true } }
                        }
                    }
                },
            )
        }
        composable(Screen.Home.route) { entry ->
            // The sheet's open state and the reason it opened (null when the Pro pill opened it) survive rotation.
            var paywallOpen by rememberSaveable { mutableStateOf(false) }
            var paywallReason by rememberSaveable { mutableStateOf<String?>(null) }

            HomeScreen(
                onDocumentClick = { id -> navController.fromTop(entry) { navigate(Screen.DocumentDetail.createRoute(id)) } },
                onScanClick = onScanClick,
                onUpgradeClick = { reason ->
                    paywallReason = reason
                    paywallOpen = true
                },
                onMergeClick = { ids -> navController.fromTop(entry) { navigate(Screen.MergeConfirm.createRoute(ids)) } },
                onSettingsClick = { navController.fromTop(entry) { navigate(Screen.Settings.route) } },
            )

            if (paywallOpen) {
                ProUpgradeSheet(onDismiss = { paywallOpen = false }, reason = paywallReason)
            }
        }
        composable(Screen.Settings.route) { entry ->
            SettingsScreen(
                onBack = { navController.fromTop(entry) { popBackStack() } },
                onShowTour = { navController.fromTop(entry) { navigate(Screen.Welcome.route) } },
            )
        }
        composable(
            route = Screen.DocumentDetail.route,
            arguments = listOf(navArgument(Screen.DocumentDetail.ARG_DOCUMENT_ID) { type = NavType.LongType }),
        ) { backStackEntry ->
            val documentId = backStackEntry.arguments?.getLong(Screen.DocumentDetail.ARG_DOCUMENT_ID)
            if (documentId == null) {
                // Missing arg shouldn't be reachable via createRoute(), but if it ever
                // happens, bounce back to Home instead of rendering a blank screen.
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                DocumentDetailScreen(
                    documentId = documentId,
                    onBack = { navController.fromTop(backStackEntry) { popBackStack() } },
                    onAddPagesClick = { onAddPagesClick(documentId) },
                    onReplacePageClick = { pageId -> onReplacePageClick(documentId, pageId) },
                )
            }
        }
        composable(
            route = Screen.MergeConfirm.route,
            arguments = listOf(navArgument(Screen.MergeConfirm.ARG_DOCUMENT_IDS) { type = NavType.StringType }),
        ) { backStackEntry ->
            val idsArg = backStackEntry.arguments?.getString(Screen.MergeConfirm.ARG_DOCUMENT_IDS)
            val documentIds = idsArg?.split(",")?.mapNotNull { it.toLongOrNull() }.orEmpty()
            if (documentIds.size < 2) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                MergeConfirmScreen(
                    documentIds = documentIds,
                    onBack = { navController.fromTop(backStackEntry) { popBackStack() } },
                    onMergeComplete = { newDocumentId ->
                        navController.navigate(Screen.DocumentDetail.createRoute(newDocumentId)) {
                            popUpTo(Screen.MergeConfirm.route) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Runs [action] only while [entry] is still the screen on top. A second tap that lands during a
 * screen transition would otherwise pop or push again - two Backs in a row popped Home itself
 * and left a blank window.
 */
private fun NavHostController.fromTop(entry: NavBackStackEntry, action: NavHostController.() -> Unit) {
    if (currentBackStackEntry?.id == entry.id) action()
}
