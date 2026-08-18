package com.healthdecoder.app.local.db

/**
 * Light projection of a report for list/search screens: everything needed to render a
 * row without loading the OCR text, test results, or analysis JSON.
 */
data class ReportSummary(
    val id: String,
    val patientName: String?,
    val reportDate: String?,
    val reportType: String?,
    val reportCategory: String?,
    val imagePath: String,
    val createdAt: String
)

/** id + stored page hashes of a report, for duplicate-scan detection. */
data class ReportHashRow(
    val id: String,
    val pageHashes: List<String>
)
