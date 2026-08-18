package com.healthdecoder.app.reminder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.healthdecoder.app.MainActivity

class AppointmentReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppointmentReminderManager.ACTION) return
        val id = intent.getStringExtra(AppointmentReminderManager.EXTRA_ID) ?: return

        val appt = AppointmentStore.loadAll(context).firstOrNull { it.id == id } ?: return

        // Reschedule recurring alarms (so it rings again tomorrow/next week if daily/weekly)
        AppointmentReminderManager.scheduleAll(context)

        val title = "Appointment Reminder: Dr. ${appt.doctorName}"
        val body = "Time: ${appt.time} | Place: ${appt.place}"

        val tapPi = PendingIntent.getActivity(
            context,
            id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, MedicineReminderManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(tapPi)
            .setVibrate(longArrayOf(0, 300, 150, 300))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(id.hashCode(), builder.build())
    }
}
