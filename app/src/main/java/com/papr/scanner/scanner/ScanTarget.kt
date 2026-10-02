package com.papr.scanner.scanner

/** Which document/page a completed scan should be applied to. */
sealed interface ScanTarget {
    data object NewDocument : ScanTarget
    data class AppendPages(val documentId: Long) : ScanTarget
    data class ReplacePage(val documentId: Long, val pageId: Long) : ScanTarget
}
