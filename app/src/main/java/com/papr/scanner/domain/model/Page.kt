package com.papr.scanner.domain.model

data class Page(
    val id: Long,
    val documentId: Long,
    val pageIndex: Int,
    val imageUri: String,
    val extractedText: String?,
    val confidentialMarks: List<ConfidentialMarkStroke> = emptyList(),
    val signature: PageSignature? = null,
    val annotations: List<PageAnnotation> = emptyList(),
)
