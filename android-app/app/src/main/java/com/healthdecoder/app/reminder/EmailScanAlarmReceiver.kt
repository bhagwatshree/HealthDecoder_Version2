package com.healthdecoder.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.healthdecoder.app.local.AppSettings
import com.healthdecoder.app.local.EmailScanWorker

class EmailScanAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != EmailScanReminderManager.ACTION) return

        // Re-arm for tomorrow at the user's configured time before doing anything else —
        // exact alarms are one-shot, so the alarm itself doesn't repeat.
        EmailScanReminderManager.scheduleDaily(
            context,
            AppSettings.getEmailScanHour(context),
            AppSettings.getEmailScanMinute(context)
        )

        // The actual scan is network I/O, so hand it to WorkManager rather than doing it here —
        // BroadcastReceiver.onReceive must return quickly. The scheduled scan only needs to
        // cover the day that just ended; "Scan Now" in SettingsScreen covers 2 days instead.
        val request = OneTimeWorkRequestBuilder<EmailScanWorker>()
            .setInputData(Data.Builder().putInt(EmailScanWorker.KEY_LOOKBACK_DAYS, 1).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "DailyEmailScanWork",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
