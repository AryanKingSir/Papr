package com.papr.scanner.presentation.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")

    data object DocumentDetail : Screen("document/{documentId}") {
        const val ARG_DOCUMENT_ID = "documentId"
        fun createRoute(documentId: Long) = "document/$documentId"
    }

    data object MergeConfirm : Screen("merge/{documentIds}") {
        const val ARG_DOCUMENT_IDS = "documentIds"
        fun createRoute(documentIds: List<Long>) = "merge/${documentIds.joinToString(",")}"
    }
}
