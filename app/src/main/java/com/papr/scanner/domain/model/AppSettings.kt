package com.papr.scanner.domain.model

enum class ExportFormat { PDF, IMAGES }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The choices on the Settings page. Defaults match how the app behaved before it had settings. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val deleteOriginalsAfterMerge: Boolean = false,
    val defaultExportFormat: ExportFormat = ExportFormat.PDF,
    val hapticsEnabled: Boolean = true,
)
