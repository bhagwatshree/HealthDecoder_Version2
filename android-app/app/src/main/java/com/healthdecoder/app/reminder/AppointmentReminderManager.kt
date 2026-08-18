package com.healthdecoder.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AppointmentReminderManager {
    const val ACTION = "com.healthdecoder.app.APPOINTMENT_REMINDER"
    const val EXTRA_ID = "appointment_id"

    fun scheduleAll(context: Context) {
        val appointments = AppointmentStore.loadAll(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        appointments.forEach { appt ->
            val intent = Intent(context, AppointmentReminderReceiver::class.java).apply {
                action = ACTION
                putExtra(EXTRA_ID, appt.id)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                appt.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, appt.hour)
                set(Calendar.MINUTE, appt.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)

                if (appt.date.isNotBlank()) {
                    // Format: YYYY-MM-DD
                    val parts = appt.date.split("-").mapNotNull { it.toIntOrNull() }
                    if (parts.size == 3) {
                        set(Calendar.YEAR, parts[0])
                        set(Calendar.MONTH, parts[1] - 1)
                        set(Calendar.DAY_OF_MONTH, parts[2])
                    }
                }

                val now = System.currentTimeMillis()
                if (timeInMillis <= now) {
                    val rec = appt.recurrence.takeIf { it.isNotBlank() } ?: if (appt.isRecurring) "Daily" else "None"
                    if (rec == "None") {
                        // Stale one-off, don't schedule
                        return@forEach
                    }
                    while (timeInMillis <= now) {
                        when (rec) {
                            "Daily" -> add(Calendar.DAY_OF_YEAR, 1)
                            "Weekly" -> add(Calendar.WEEK_OF_YEAR, 1)
                            "Monthly" -> add(Calendar.MONTH, 1)
                            "3 Months" -> add(Calendar.MONTH, 3)
                            "6 Months" -> add(Calendar.MONTH, 6)
                            "1 Year" -> add(Calendar.YEAR, 1)
                            else -> return@forEach
                        }
                    }
                }
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                else
                    am.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            } catch (e: SecurityException) {
                am.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
        }
    }

    fun cancel(context: Context, id: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AppointmentReminderReceiver::class.java).apply {
            action = ACTION
        }
        val pi = PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            am.cancel(pi)
        }
    }
}
