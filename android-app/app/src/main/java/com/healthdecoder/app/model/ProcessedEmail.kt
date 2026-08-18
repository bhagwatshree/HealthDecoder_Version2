package com.healthdecoder.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "processed_emails")
data class ProcessedEmail(
    @PrimaryKey val messageId: String,
    val attachmentName: String,
    val processedAt: Long,
    val pendingLocalPath: String? = null
)
