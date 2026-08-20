package com.healthdecoder.app.reminder

import android.content.Context
import com.healthdecoder.app.model.MedName
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Locale

data class SlotConfig(
    val enabled: Boolean = false,
    val hour: Int = 8,
    val minute: Int = 0
)

data class MedicineSchedule(
    val medicineName: String,
    val patientName: String,
    val dosage: String,
    val frequency: String,
    val slots: Map<String, SlotConfig>,
    // Days of the week the medicine is taken (Calendar.DAY_OF_WEEK: 1=Sun .. 7=Sat).
    // null or empty means EVERY day — used for weekly scripts like "Wed & Sat only"
    // (e.g. Tolvaptan). Nullable so schedules saved before this field existed still load.
    val daysOfWeek: List<Int>? = null,
    // Structured reminder window resolved from the prescription (or set directly by the user in
    // the Add/Edit dialog). Null means "no constraint" — matches every schedule saved before these
    // fields existed, so nothing about existing reminders changes.
    val startDate: String? = null,
    val endDate: String? = null,
    // "Once every N days" dosing cadence (e.g. "once in 15 days" -> 15) for a medicine that
    // doesn't land on the same weekday every week, so [daysOfWeek] can't express it. Null for the
    // ordinary daily/weekly-named-day case — those already work via [runsOn]. This NARROWS the
    // "is it due today" check alongside daysOfWeek rather than replacing it (see [isDueToday]):
    // counting exact days from [startDate] is the only way to know if today is dose N, day N+1.
    val intervalDays: Int? = null
)

/** True if this schedule's medicine is due on [dayOfWeek] (Calendar.DAY_OF_WEEK). Every day when no
 *  specific days are set. */
fun MedicineSchedule.runsOn(dayOfWeek: Int): Boolean =
    daysOfWeek.isNullOrEmpty() || daysOfWeek!!.contains(dayOfWeek)

/** True if [todayIso] ("YYYY-MM-DD") falls within this schedule's start/end window. ISO strings
 *  compare lexicographically correctly, so no date parsing is needed at check time. */
fun MedicineSchedule.isCurrentlyActive(todayIso: String): Boolean =
    (startDate == null || startDate <= todayIso) && (endDate == null || endDate >= todayIso)

private fun daysBetweenIso(fromIso: String, toIso: String): Long? = try {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val from = fmt.parse(fromIso.trim())
    val to = fmt.parse(toIso.trim())
    if (from == null || to == null) null else (to.time - from.time) / 86_400_000L
} catch (e: Exception) { null }

/**
 * True if [todayIso] is one of this schedule's every-[intervalDays]-days dose days, counting from
 * [startDate] (day 0). Without an anchor date to count from, an interval can't be evaluated, so
 * this defaults to true (falls back to firing daily) rather than silently never firing.
 */
fun MedicineSchedule.isDueByInterval(todayIso: String): Boolean {
    val interval = intervalDays ?: return true
    if (interval <= 0) return true
    val anchor = startDate ?: return true
    val diff = daysBetweenIso(anchor, todayIso) ?: return true
    return diff >= 0 && diff % interval == 0L
}

/**
 * True if this schedule's medicine is due TODAY. Both constraints must hold: the day-of-week
 * script ([runsOn]) AND the every-N-days cadence ([isDueByInterval]).
 *
 * The interval used to REPLACE the day check, on the reasoning that a 15-day cycle isn't tied to
 * any weekday — true for that case, but it silently discarded the days whenever both were present.
 * An interval of 1 was the worst of it: "every 1 days" is trivially true every day, so a medicine
 * prescribed "5 days a week, Thursday & Sunday off" reminded on Thursday while its own reminder
 * screen correctly listed the five days it should run. The days were right, stored and displayed;
 * nothing ever asked them.
 *
 * An interval of 1 is exactly what "no interval" already means, so it is not treated as a
 * constraint at all. Where a genuine multi-day cycle and named days coexist, requiring both can
 * only withhold a dose the script didn't call for — never add one it didn't.
 */
fun MedicineSchedule.isDueToday(dayOfWeek: Int, todayIso: String): Boolean {
    val cadenceApplies = intervalDays != null && intervalDays > 1
    return runsOn(dayOfWeek) && (!cadenceApplies || isDueByInterval(todayIso))
}

object MedicineScheduleStore {
    private const val PREFS_NAME = "medicine_schedules"
    private const val KEY_SCHEDULES = "schedules_v1"
    // Medicines the user explicitly deleted a reminder for. TodaysMedicinesTab re-seeds a reminder
    // for every active medicine on each load; without this, a deleted reminder would keep coming
    // back because the medicine still exists in a report. Deleting marks it here; explicitly
    // (re)adding via upsert clears it.
    private const val KEY_DISMISSED = "dismissed_meds_v1"
    private val gson = GsonBuilder().create()

    private fun dismissKey(medicineName: String, patientName: String) =
        "${MedName.canonicalKey(medicineName)}|${patientName.trim().lowercase()}"

    private fun loadDismissed(context: Context): MutableSet<String> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DISMISSED, null)
            ?: return mutableSetOf()
        return try {
            gson.fromJson<Set<String>>(json, object : TypeToken<Set<String>>() {}.type)?.toMutableSet() ?: mutableSetOf()
        } catch (e: Exception) { mutableSetOf() }
    }

    private fun saveDismissed(context: Context, set: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_DISMISSED, gson.toJson(set)).apply()
    }

    /** True if the user deleted this medicine's reminder and hasn't re-added it — don't auto-seed it. */
    fun isDismissed(context: Context, medicineName: String, patientName: String): Boolean =
        loadDismissed(context).contains(dismissKey(medicineName, patientName))

    /** Un-dismiss a medicine so it can be auto-seeded again — called when a NEW scan brings a fresh
     *  prescription of it, so a previously-deleted reminder legitimately comes back for the new script. */
    fun clearDismissed(context: Context, medicineName: String, patientName: String) {
        val set = loadDismissed(context)
        if (set.remove(dismissKey(medicineName, patientName))) saveDismissed(context, set)
    }

    val defaultSlotTimes = mapOf(
        "Morning"   to Pair(8,  0),
        "Afternoon" to Pair(13, 0),
        "Evening"   to Pair(18, 0),
        "Night"     to Pair(22, 0)
    )

    fun loadAll(context: Context): List<MedicineSchedule> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SCHEDULES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<MedicineSchedule>>() {}.type
            gson.fromJson<List<MedicineSchedule>>(json, type).orEmpty().mapNotNull { it.sanitized() }
        } catch (e: Exception) { emptyList() }
    }


    fun saveAll(context: Context, schedules: List<MedicineSchedule>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SCHEDULES, gson.toJson(schedules)).apply()
    }

    /** Removes a medicine's reminder schedule and remembers it as dismissed so it isn't auto-re-seeded. */
    fun delete(context: Context, medicineName: String, patientName: String) {
        saveAll(context, loadAll(context).filterNot { it.matches(medicineName, patientName) })
        saveDismissed(context, loadDismissed(context).apply { add(dismissKey(medicineName, patientName)) })
    }

    /** Deletes ALL saved reminder schedules (and the dismissed list). */
    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_SCHEDULES).remove(KEY_DISMISSED).apply()
    }

    fun upsert(context: Context, schedule: MedicineSchedule) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.matches(schedule.medicineName, schedule.patientName) }
        if (idx >= 0) list[idx] = schedule else list.add(schedule)
        saveAll(context, list)
        // Explicitly adding/editing a reminder un-dismisses it.
        saveDismissed(context, loadDismissed(context).apply { remove(dismissKey(schedule.medicineName, schedule.patientName)) })
    }

    /**
     * Renames a patient's medicine reminder schedule (keeping its slot times/dosage) when the
     * medicine is renamed, so the reminder keeps firing under the corrected name. Optionally
     * refreshes the shown [dosage]/[frequency]. If a schedule already exists under [newName],
     * the old one is dropped in its favour (the corrected name wins, no duplicate reminders).
     * No-op when the medicine has no schedule. Caller must run MedicineReminderManager.scheduleAll.
     */
    fun rename(
        context: Context,
        patientName: String,
        oldName: String,
        newName: String,
        dosage: String? = null,
        frequency: String? = null
    ) {
        if (newName.isBlank() || oldName.equals(newName, ignoreCase = true)) {
            // Same name — just refresh dosage/frequency if given.
            if (dosage != null || frequency != null) {
                val list = loadAll(context).toMutableList()
                val idx = list.indexOfFirst { it.matches(oldName, patientName) }
                if (idx >= 0) list[idx] = list[idx].copy(
                    dosage = dosage ?: list[idx].dosage,
                    frequency = frequency ?: list[idx].frequency
                )
                saveAll(context, list)
            }
            return
        }
        val list = loadAll(context).toMutableList()
        val oldIdx = list.indexOfFirst { it.matches(oldName, patientName) }
        if (oldIdx < 0) return
        val renamed = list[oldIdx].copy(
            medicineName = newName,
            dosage = dosage ?: list[oldIdx].dosage,
            frequency = frequency ?: list[oldIdx].frequency
        )
        // Drop any pre-existing schedule under the new name so we don't end up with two.
        list.removeAll { it.matches(newName, patientName) }
        val insertAt = list.indexOfFirst { it.matches(oldName, patientName) }
        if (insertAt >= 0) list[insertAt] = renamed else list.add(renamed)
        saveAll(context, list)
    }

    /**
     * Re-keys a patient's reminder schedules when two mis-scanned name variants are merged. If the
     * same medicine already has a schedule under [newName], the old one is dropped in its favour so
     * the merge can't leave two reminders for one medicine. Caller runs MedicineReminderManager.scheduleAll.
     */
    fun renamePatient(context: Context, oldName: String, newName: String) {
        if (oldName.equals(newName, ignoreCase = true)) return
        val list = loadAll(context).toMutableList()
        val result = mutableListOf<MedicineSchedule>()
        for (s in list) {
            if (!s.patientName.equals(oldName, ignoreCase = true)) { result.add(s); continue }
            val moved = s.copy(patientName = newName)
            // Drop any existing schedule for the same medicine already under the new patient name.
            result.removeAll { it.matches(moved.medicineName, newName) }
            if (result.none { it.matches(moved.medicineName, newName) }) result.add(moved)
        }
        saveAll(context, result)
    }

    /**
     * Refreshes an EXISTING reminder's clinical facts — display name, dosage, frequency,
     * WHICH DAYS it's taken, and its start/end date — from the latest scanned prescription, so a
     * corrected re-scan (e.g. a follow-up prescription fixing a discharge summary's misread
     * dosage, adding a real end date, or narrowing daily to "5 days a week, Thu & Sun off")
     * actually reaches the reminder instead of leaving it stuck with whatever the first scan
     * produced. [daysOfWeek] IS a clinical fact (which days the doctor prescribed it for), not a
     * user preference — only [SlotConfig] (which of those days' time-slots are toggled on, and at
     * what time) is the user's own reminder-timing choice and stays untouched here. No-op when no
     * schedule exists yet (that's [autoSeedIfAbsent]'s job) or nothing actually changed.
     */
    fun syncFromLatest(
        context: Context,
        medicineName: String,
        patientName: String,
        dosage: String,
        frequency: String,
        daysOfWeek: List<Int> = emptyList(),
        startDate: String? = null,
        endDate: String? = null,
        intervalDays: Int? = null
    ) {
        val canon = MedName.canonicalKey(medicineName)
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst {
            it.patientName.equals(patientName, ignoreCase = true) && MedName.canonicalKey(it.medicineName) == canon
        }
        if (idx < 0) return
        val existing = list[idx]
        // The latest scan's name wins when it's at least as rich (carries a strength digit the
        // existing label lacks, or the existing label has none either) — same tie-break DashboardEngine
        // uses so the reminder shows the same authoritative name as the tracker, not a stale one.
        val existingHasDigit = existing.medicineName.any { it.isDigit() }
        val newHasDigit = medicineName.any { it.isDigit() }
        val newName = if (newHasDigit || !existingHasDigit) medicineName else existing.medicineName
        val updated = existing.copy(
            medicineName = newName,
            dosage = dosage.ifBlank { existing.dosage },
            frequency = frequency.ifBlank { existing.frequency },
            daysOfWeek = daysOfWeek.ifEmpty { null },
            startDate = startDate,
            endDate = endDate,
            intervalDays = intervalDays
        )
        if (updated != existing) {
            list[idx] = updated
            saveAll(context, list)
        }
    }

    fun autoSeedIfAbsent(
        context: Context,
        medicineName: String,
        patientName: String,
        dosage: String,
        frequency: String,
        activeSlots: List<String>,
        daysOfWeek: List<Int> = emptyList(),
        startDate: String? = null,
        endDate: String? = null,
        intervalDays: Int? = null
    ) {
        // Canonical match so "Tab. Concor" and "Concor 5mg" (same drug, different scans) don't both seed.
        val canon = MedName.canonicalKey(medicineName)
        if (loadAll(context).any {
                it.patientName.equals(patientName, ignoreCase = true) &&
                    MedName.canonicalKey(it.medicineName) == canon
            }) return
        if (isDismissed(context, medicineName, patientName)) return // user deleted it — don't bring it back
        val slots = defaultSlotTimes.mapValues { (slot, hm) ->
            SlotConfig(enabled = activeSlots.contains(slot), hour = hm.first, minute = hm.second)
        }
        // Seed directly (not via upsert, which would clear the dismissed flag — irrelevant here since
        // we've already confirmed it isn't dismissed, but keeps the auto-seed path self-contained).
        val list = loadAll(context).toMutableList()
        list.add(MedicineSchedule(medicineName, patientName, dosage, frequency, slots, daysOfWeek.ifEmpty { null }, startDate, endDate, intervalDays))
        saveAll(context, list)
    }

    private fun MedicineSchedule.matches(name: String, patient: String) =
        MedName.canonicalKey(medicineName) == MedName.canonicalKey(name) &&
        patientName.equals(patient, ignoreCase = true)

    /**
     * Merges reminder schedules that are the SAME drug written differently ("Tab. Concor" +
     * "Concor 5mg") into one, keeping the richer name and the union of enabled slots. Idempotent —
     * writes nothing when there are no duplicates. Call before seeding/rendering the reminders list.
     */
    fun dedupeCanonical(context: Context) {
        val list = loadAll(context)
        if (list.isEmpty()) return
        val merged = LinkedHashMap<String, MedicineSchedule>()
        for (s in list) {
            val key = "${s.patientName.trim().lowercase()}|${MedName.canonicalKey(s.medicineName)}"
            val existing = merged[key]
            // Even singletons get a tidied display name ("Tab. Pan D" -> "Pan D").
            merged[key] = if (existing == null) s.copy(medicineName = MedName.cleanDisplay(s.medicineName))
                          else mergeSchedules(existing, s)
        }
        val result = merged.values.toList()
        if (result != list) saveAll(context, result)
    }

    private fun mergeSchedules(a: MedicineSchedule, b: MedicineSchedule): MedicineSchedule {
        // The richer-labelled entry (carries a strength/digit, else longer) is the primary; keep ITS
        // slots so a user's edit on it isn't overwritten. We do NOT union slots — unioning used to
        // resurrect an old auto-seeded slot (e.g. Night) that the user had switched to Evening.
        fun score(n: String) = (if (n.any(Char::isDigit)) 1000 else 0) + MedName.cleanDisplay(n).length
        val primary = if (score(b.medicineName) > score(a.medicineName)) b else a
        val secondary = if (primary === a) b else a
        val slots = if (primary.slots.values.any { it.enabled }) primary.slots else secondary.slots
        return primary.copy(
            medicineName = MedName.cleanDisplay(primary.medicineName),
            dosage = primary.dosage.ifBlank { secondary.dosage },
            frequency = primary.frequency.ifBlank { secondary.frequency },
            slots = slots,
            daysOfWeek = primary.daysOfWeek ?: secondary.daysOfWeek,
            startDate = primary.startDate ?: secondary.startDate,
            endDate = primary.endDate ?: secondary.endDate,
            intervalDays = primary.intervalDays ?: secondary.intervalDays
        )
    }
}

/**
 * Gson fills fields by reflection and knows nothing about Kotlin nullability: a stored record
 * missing "patientName" (or carrying an explicit null) produces a MedicineSchedule whose
 * non-null String field IS null. Nothing complains until the first use, which crashed the whole
 * app — dedupeCanonical() calling patientName.trim() took out the Reminders and Doctor
 * Appointments screens on open, as a bare NPE with no hint that JSON was involved.
 *
 * So every schedule is repaired here, at the one place untrusted JSON becomes objects, rather
 * than defending at each of the dozens of use sites. A record with no usable medicine name is
 * dropped: it can't be displayed, matched or scheduled, and keeping it only moves the crash.
 */
internal fun MedicineSchedule?.sanitized(): MedicineSchedule? {
    val s = this ?: return null
    val name = str(s.medicineName).trim()
    if (name.isEmpty()) return null
    return s.copy(
        medicineName = name,
        patientName = str(s.patientName),
        dosage = str(s.dosage),
        frequency = str(s.frequency),
        // A null slot value is as fatal as a null field once something reads .enabled.
        slots = s.slots.orEmpty().filterValues { it != null }
    )
}

/** Non-null String is a subtype of String?, so this accepts the field and catches Gson's null. */
private fun str(value: String?): String = value ?: ""
