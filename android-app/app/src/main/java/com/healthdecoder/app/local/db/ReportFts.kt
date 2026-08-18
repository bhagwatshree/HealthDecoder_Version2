package com.healthdecoder.app.local.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import com.healthdecoder.app.model.MedicalReport

/**
 * External-content FTS index over the reports table. Room generates triggers that keep
 * it in sync with every insert/update/delete on `reports`, so full-text search over the
 * OCR text ("which report mentioned creatinine?") is a fast index lookup instead of a
 * scan through every report's JSON.
 */
@Fts4(contentEntity = MedicalReport::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "reports_fts")
data class ReportFts(
    val patientName: String?,
    val reportType: String?,
    val comments: String?,
    val extractedText: String?
)
