package com.papr.scanner.data.local.db

/** PageEntity's embedded signature. Every column is nullable in the table because the embedding field itself is nullable. */
data class SignatureColumns(
    val imagePath: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val aspectRatio: Float,
)
