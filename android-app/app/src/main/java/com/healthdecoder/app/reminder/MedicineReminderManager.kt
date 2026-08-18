package com.healthdecoder.app.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Schedules medicine reminders. Alarms are keyed by TIME OF DAY, not by medicine:
 * every medicine whose slot fires at e.g. 08:00 shares one alarm, so the user gets a
 * single reminder listing all medicines due together instead of a burst of separate ones.
 *
 * The set of currently scheduled times is remembered in prefs so stale alarms can be
 * cancelled whenever schedules change — always update MedicineScheduleStore first, then
 * call [scheduleAll].
 */
object MedicineReminderManager {
    const val CHANNEL_ID    = "medicine_reminders"
    const val CHANNEL_NAME  = "Medicine Reminders"
    const val ACTION        = "com.healthdecoder.app.MEDICINE_REMINDER"
    const val EXTRA_HOUR    = "hour"
    const val EXTRA_MINUTE  = "minute"

    private const val PREFS_NAME = "reminder_alarms"
    private const val KEY_TIMES  = "scheduled_times" // comma-separated hour*60+minute codes

    /** One medicine dose that is due at a given reminder time. */
    data class DueMedicine(
        val medicineName: String,
        val patientName: String,
        val dosage: String,
        val slot: String
    )

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Daily medicine intake reminders sent to phone and paired watch"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    /** All medicines whose enabled slots fire at exactly hour:minute AND are due today (weekly
     *  scripts like "Wed & Sat only", or an every-N-days cadence like "once in 15 days", are
     *  skipped on off days, and a medicine outside its own start/end window — not yet started, or
     *  its course has ended — is skipped entirely). */
    fun dueMedicines(context: Context, hour: Int, minute: Int): List<DueMedicine> {
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
        return MedicineScheduleStore.loadAll(context)
            .filter { it.isDueToday(today, todayIso) && it.isCurrentlyActive(todayIso) }
            .flatMap { schedule ->
            schedule.slots.mapNotNull { (slot, cfg) ->
                if (cfg.enabled && cfg.hour == hour && cfg.minute == minute)
                    DueMedicine(schedule.medicineName, schedule.patientName, schedule.dosage, slot)
                else null
            }
        }
    }

    /**
     * Recomputes and (re)sets one alarm per distinct enabled reminder time, cancelling
     * alarms for times no longer used. The single entry point after any schedule change.
     */
    @Synchronized
    fun scheduleAll(context: Context) {
        val wantedCodes = MedicineScheduleStore.loadAll(context)
            .flatMap { it.slots.values }
            .filter { it.enabled }
            .map { it.hour * 60 + it.minute }
            .toSortedSet()

        // Cancel alarms for times that no schedule uses anymore.
        (loadScheduledCodes(context) - wantedCodes).forEach { cancelAlarmAt(context, it) }

        wantedCodes.forEach { code -> setAlarmAt(context, code / 60, code % 60) }
        saveScheduledCodes(context, wantedCodes)
    }

    /** Kept for call-site compatibility: schedules are stored first, then this re-syncs alarms. */
    fun scheduleForMedicine(context: Context, @Suppress("UNUSED_PARAMETER") schedule: MedicineSchedule) =
        scheduleAll(context)

    /** Cancels all scheduled medicine alarms (used when wiping data). */
    @Synchronized
    fun cancelAll(context: Context) {
        loadScheduledCodes(context).forEach { cancelAlarmAt(context, it) }
        saveScheduledCodes(context, emptySet())
    }

    /** Stable notification id for the reminder that fires at hour:minute. */
    fun notificationId(hour: Int, minute: Int): Int = hour * 60 + minute

    /** One-off repeat of this reminder after [minutes] ("Remind me later" on the alarm page). */
    fun snooze(context: Context, hour: Int, minute: Int, minutes: Int = 10) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, MedicineReminderReceiver::class.java).apply {
            action = ACTION
            putExtra(EXTRA_HOUR, hour)
            putExtra(EXTRA_MINUTE, minute)
        }
        // Separate request-code space so the snooze never replaces the daily alarm.
        val pi = PendingIntent.getBroadcast(
            context, SNOOZE_CODE_BASE + hour * 60 + minute, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = System.currentTimeMillis() + minutes * 60_000L
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private const val SNOOZE_CODE_BASE = 100_000

    // ── internals ────────────────────────────────────────────────────────────
    private fun setAlarmAt(context: Context, hour: Int, minute: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context, hour, minute, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            ?: return

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

    private fun cancelAlarmAt(context: Context, code: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context, code / 60, code % 60, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            ?: return
        am.cancel(pi)
    }

    private fun buildPendingIntent(context: Context, hour: Int, minute: Int, flags: Int): PendingIntent? {
        val intent = Intent(context, MedicineReminderReceiver::class.java).apply {
            action = ACTION
            putExtra(EXTRA_HOUR, hour)
            putExtra(EXTRA_MINUTE, minute)
        }
        // Request code == time code, so one PendingIntent exists per reminder time.
        return PendingIntent.getBroadcast(context, hour * 60 + minute, intent, flags)
    }

    private fun loadScheduledCodes(context: Context): Set<Int> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TIMES, "")!!
            .split(',')
            .mapNotNull { it.toIntOrNull() }
            .toSet()

    private fun saveScheduledCodes(context: Context, codes: Set<Int>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TIMES, codes.joinToString(",")).apply()
    }
}
