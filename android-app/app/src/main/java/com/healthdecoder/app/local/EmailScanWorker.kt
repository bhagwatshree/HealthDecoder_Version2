package com.healthdecoder.app.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.healthdecoder.app.MainActivity
import com.healthdecoder.app.ai.OcrEngine
import com.healthdecoder.app.local.db.ProcessedEmailDao
import com.healthdecoder.app.model.ProcessedEmail
import com.healthdecoder.app.network.GmailApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.Properties
import javax.mail.Folder
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.MimeMessage
import javax.mail.search.AndTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.OrTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SubjectTerm

private val REPORT_KEYWORDS =
    listOf("report", "lab", "diagnostic", "billing", "test", "health", "prescription", "invoice", "medical")

class EmailScanWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val CHANNEL_ID = "email_scan_notifications"
        private const val NOTIFICATION_ID = 8821

        /** How many days back to search. The 7 PM scheduled scan only needs to cover the day
         *  that just ended (1); a manually-triggered scan covers 2 to also catch anything the
         *  previous day's run might have missed. */
        const val KEY_LOOKBACK_DAYS = "lookback_days"
        /** Output data key: how many new reports this run found (readable via WorkInfo). */
        const val KEY_FOUND_COUNT = "found_count"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Having a linked email at all already required a deliberate action (OAuth link or
        // entering IMAP credentials) — that's the real consent gate. isEmailConsentGranted only
        // controls whether the *recurring* daily alarm is armed (see EmailScanReminderManager);
        // a manually-triggered scan (Scan Now / ScanScreen) should still run without it.
        val email = AppSettings.getLinkedEmail(context) ?: return@withContext Result.success()
        val type = AppSettings.getLinkedEmailType(context) ?: return@withContext Result.success()
        val lookbackDays = inputData.getInt(KEY_LOOKBACK_DAYS, 1)

        val db = LocalStore.getDatabase(context)
        val dao = db.processedEmailDao()

        try {
            val newReportsCount = if (type == "gmail") scanGmailViaApi(dao, lookbackDays) else scanImap(email, dao, lookbackDays)
            if (newReportsCount > 0) {
                showNotification(newReportsCount)
            }
            Result.success(Data.Builder().putInt(KEY_FOUND_COUNT, newReportsCount).build())
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }

    /** Gmail path: uses the Gmail REST API (gmail.readonly scope) rather than IMAP, since the
     *  full mailbox IMAP scope (https://mail.google.com/) requires a paid Google security
     *  assessment to verify for production — see server.js's /api/auth/google route. */
    private suspend fun scanGmailViaApi(dao: ProcessedEmailDao, lookbackDays: Int): Int {
        val accessToken = try {
            val token = com.healthdecoder.app.network.NetworkModule.getApi(context).getGoogleAccessToken().access_token
            SecureKeyManager.setEmailToken(context, token)
            token
        } catch (e: Exception) {
            SecureKeyManager.getEmailToken(context)
        } ?: return 0

        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, -lookbackDays)
        val afterClause = "after:${SimpleDateFormat("yyyy/MM/dd", Locale.US).format(cal.time)}"
        val subjectClause = "(" + REPORT_KEYWORDS.joinToString(" OR ") { "subject:$it" } + ")"
        val customSearch = AppSettings.getEmailSearchPrompt(context)
        val customClause = if (customSearch.isNotBlank()) " subject:$customSearch" else ""
        val query = "$afterClause $subjectClause has:attachment filename:pdf$customClause"

        var newReportsCount = 0
        for (messageId in GmailApiClient.searchMessageIds(accessToken, query)) {
            if (dao.exists(messageId)) continue
            val attachment = GmailApiClient.findPdfAttachment(accessToken, messageId) ?: continue
            val bytes = GmailApiClient.downloadAttachment(accessToken, messageId, attachment.attachmentId)

            val emailImportsDir = File(context.cacheDir, "email_imports").apply { mkdirs() }
            val tempFile = File(emailImportsDir, "imported_${System.currentTimeMillis()}_${attachment.fileName}")
            tempFile.writeBytes(bytes)

            dao.insert(
                ProcessedEmail(
                    messageId = messageId,
                    attachmentName = attachment.fileName,
                    processedAt = System.currentTimeMillis(),
                    pendingLocalPath = tempFile.absolutePath
                )
            )
            newReportsCount++
        }
        return newReportsCount
    }

    /** "Other (IMAP)" path: a plain IMAP mailbox with a host/port/app-password the user entered
     *  directly (see SettingsScreen) — not Gmail, so none of the OAuth scope concerns apply. */
    private fun scanImap(email: String, dao: ProcessedEmailDao, lookbackDays: Int): Int {
        val password = SecureKeyManager.getImapPassword(context) ?: return 0
        val host = AppSettings.getImapHost(context)
        val port = AppSettings.getImapPort(context)

        val props = Properties()
        props.setProperty("mail.store.protocol", "imaps")
        props.setProperty("mail.imaps.host", host)
        props.setProperty("mail.imaps.port", port.toString())
        props.setProperty("mail.imaps.ssl.enable", "true")
        props.setProperty("mail.imaps.timeout", "10000")
        props.setProperty("mail.imaps.connectiontimeout", "10000")

        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        store.connect(host, port, email, password)

        val inbox = store.getFolder("INBOX")
        inbox.open(Folder.READ_ONLY)

        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, -lookbackDays)
        val dateTerm = ReceivedDateTerm(ComparisonTerm.GE, cal.time)

        // Generic keyword subject filtering
        val subjectOrTerm = OrTerm(REPORT_KEYWORDS.map { SubjectTerm(it) }.toTypedArray())

        val customSearch = AppSettings.getEmailSearchPrompt(context)
        val finalTerm = if (customSearch.isNotBlank()) {
            val customTerm = OrTerm(SubjectTerm(customSearch), SubjectTerm(customSearch.lowercase()))
            AndTerm(dateTerm, AndTerm(subjectOrTerm, customTerm))
        } else {
            AndTerm(dateTerm, subjectOrTerm)
        }

        val messages = inbox.search(finalTerm)
        var newReportsCount = 0

        for (msg in messages) {
            if (msg !is MimeMessage) continue
            val messageId = msg.messageID ?: "${msg.subject}_${msg.sentDate?.time}"

            // Deduplicate: check if this message was already scanned
            if (dao.exists(messageId)) continue

            // Check for attachments in multipart message
            val content = msg.content
            if (content is Multipart) {
                for (i in 0 until content.count) {
                    val part = content.getBodyPart(i)
                    if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) || !part.fileName.isNullOrBlank()) {
                        val fileName = part.fileName ?: "report.pdf"
                        if (fileName.lowercase().endsWith(".pdf")) {
                            // Found a PDF report attachment!
                            val emailImportsDir = File(context.cacheDir, "email_imports").apply { mkdirs() }
                            val tempFile = File(emailImportsDir, "imported_${System.currentTimeMillis()}_$fileName")

                            part.inputStream.use { input ->
                                tempFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }

                            // Save record to database with local file path, waiting for confirmation
                            val record = ProcessedEmail(
                                messageId = messageId,
                                attachmentName = fileName,
                                processedAt = System.currentTimeMillis(),
                                pendingLocalPath = tempFile.absolutePath
                            )
                            dao.insert(record)
                            newReportsCount++
                            break // Process one attachment per email
                        }
                    }
                }
            }
        }

        inbox.close(false)
        store.close()
        return newReportsCount
    }

    private fun showNotification(count: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Email Medical Scans",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when new medical reports are found in linked emails"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Tap notification to open app
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("New Lab Report Found")
            .setContentText("Found $count new medical report(s) in your email. Tap to import.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
