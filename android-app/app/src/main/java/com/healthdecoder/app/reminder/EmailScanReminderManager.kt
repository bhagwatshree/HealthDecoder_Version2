package com.healthdecoder.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Schedules the daily inbox scan (EmailScanWorker) for a fixed clock time — 7 PM by default —
 * rather than "every 24 hours from whenever the toggle was flipped on" (WorkManager's
 * PeriodicWorkRequest doesn't pin to a time of day). Mirrors MedicineReminderManager's
 * exact-alarm-that-reschedules-itself pattern.
 */
object EmailScanReminderManager {
    const val ACTION = "com.healthdecoder.app.EMAIL_SCAN"
    const val DEFAULT_HOUR = 19
    const val DEFAULT_MINUTE = 0
    private const val REQUEST_CODE = 9001

    fun scheduleDaily(context: Context, hour: Int = DEFAULT_HOUR, minute: Int = DEFAULT_MINUTE) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) ?: return

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
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

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        am.cancel(pi)
    }

    private fun buildPendingIntent(context: Context, flags: Int): PendingIntent? {
        val intent = Intent(context, EmailScanAlarmReceiver::class.java).apply { action = ACTION }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
