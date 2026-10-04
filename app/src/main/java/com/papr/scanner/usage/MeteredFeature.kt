package com.papr.scanner.usage

/** How many times a month the free plan can use each [MeteredFeature]. Pro has no limit. */
const val FREE_MONTHLY_LIMIT = 5

/** A feature the free plan can use [FREE_MONTHLY_LIMIT] times a month. */
enum class MeteredFeature(private val usesName: String) {
    OCR_EXPORT("searchable-text exports"),
    PASSWORD_EXPORT("password-protected exports"),
    COMPRESSED_EXPORT("compressed exports"),
    MERGE("merges");

    /** Why the Pro sheet opened when this feature's limit is used up. */
    val limitReachedMessage: String get() = "You've used this month's $FREE_MONTHLY_LIMIT free $usesName."
}

/** What's left of a feature this month; [remaining] is null when it's unlimited (Pro). */
data class Allowance(val remaining: Int?, val limit: Int)
