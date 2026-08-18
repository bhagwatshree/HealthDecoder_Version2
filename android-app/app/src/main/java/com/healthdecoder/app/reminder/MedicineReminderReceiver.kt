package com.healthdecoder.app.reminder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.healthdecoder.app.MainActivity
import com.healthdecoder.app.local.AppSettings

class MedicineReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MedicineReminderManager.ACTION) return

        val hour = intent.getIntExtra(MedicineReminderManager.EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(MedicineReminderManager.EXTRA_MINUTE, -1)
        if (hour < 0 || minute < 0) return

        // Re-sync all alarms (sets this time again for tomorrow, drops stale times).
        MedicineReminderManager.scheduleAll(context)

        // Everything due right now fires as ONE reminder listing all medicines.
        val due = MedicineReminderManager.dueMedicines(context, hour, minute)
        if (due.isEmpty()) return // schedule changed since the alarm was set

        MedicineReminderManager.createChannel(context)

        val slotLabel = due.first().slot
        val multiPatient = due.map { it.patientName.lowercase() }.distinct().size > 1
        val title = if (due.size == 1) "Time to take ${due.first().medicineName}"
                    else "$slotLabel medicines — ${due.size} to take"
        val body = due.joinToString("\n") { med ->
            buildString {
                append("• ").append(med.medicineName)
                if (med.dosage.isNotBlank()) append(" — ").append(med.dosage)
                if (multiPatient && med.patientName.isNotBlank()) append(" (").append(med.patientName).append(")")
            }
        }

        val fullscreen = AppSettings.getReminderStyle(context) == AppSettings.REMINDER_STYLE_FULLSCREEN
        val alarmScreenIntent = Intent(context, ReminderAlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MedicineReminderManager.EXTRA_HOUR, hour)
            putExtra(MedicineReminderManager.EXTRA_MINUTE, minute)
        }
        val notifId = MedicineReminderManager.notificationId(hour, minute)

        // Tapping the notification opens the big-text alarm page in full-screen mode,
        // or the app itself in normal mode.
        val tapPi = PendingIntent.getActivity(
            context, notifId,
            if (fullscreen) alarmScreenIntent
            else Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, MedicineReminderManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (fullscreen) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(tapPi)
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (fullscreen && canUseFullScreen(context)) {
            // Wakes the screen and shows the large-text alarm page over the lock screen;
            // if the phone is unlocked and in use, Android shows a heads-up banner instead.
            builder.setFullScreenIntent(
                PendingIntent.getActivity(
                    context, notifId + 1, alarmScreenIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ),
                true
            )
        }

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notifId, builder.build())
    }

    private fun canUseFullScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.canUseFullScreenIntent()
    }
}
