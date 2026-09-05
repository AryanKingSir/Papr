package com.papr.scanner.domain.model

data class Document(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int,
    val thumbnailUri: String,
    val pdfUri: String?,
    val folder: String?,
)
