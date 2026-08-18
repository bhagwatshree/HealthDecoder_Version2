package com.healthdecoder.app.reminder

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class AppointmentSchedule(
    val id: String = UUID.randomUUID().toString(),
    val doctorName: String,
    val date: String, // format: "YYYY-MM-DD"
    val time: String, // format: "HH:MM"
    val place: String,
    val isRecurring: Boolean = false, // legacy flag
    val recurrence: String = "None", // "None", "Daily", "Weekly", "Monthly", "3 Months", "6 Months", "1 Year"
    val hour: Int,
    val minute: Int,
    // Blank means "no patient recorded" (appointments saved before this field existed) — treated as
    // visible to every family member rather than hidden, same as an untagged family profile.
    val patientName: String = ""
)

/**
 * "Dr. Karne" whether [AppointmentSchedule.doctorName] was stored as "Karne" or already
 * "Dr. Karne" — a discharge summary's "Consultant: Dr. X" line is often OCR-extracted WITH the
 * honorific already attached, so unconditionally prefixing "Dr. " produced "Dr. Dr. Karne".
 */
fun AppointmentSchedule.doctorLabel(): String {
    val n = doctorName.trim()
    return if (Regex("(?i)^dr\\.?\\s").containsMatchIn(n)) n else "Dr. $n"
}

object AppointmentStore {
    private const val PREFS_NAME = "appointment_schedules"
    private const val KEY_APPOINTMENTS = "appointments_v1"
    private val gson = GsonBuilder().create()

    fun loadAll(context: Context): List<AppointmentSchedule> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APPOINTMENTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AppointmentSchedule>>() {}.type
            gson.fromJson<List<AppointmentSchedule>>(json, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    fun saveAll(context: Context, list: List<AppointmentSchedule>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_APPOINTMENTS, gson.toJson(list)).apply()
    }

    fun upsert(context: Context, appointment: AppointmentSchedule) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.id == appointment.id }
        if (idx >= 0) list[idx] = appointment else list.add(appointment)
        saveAll(context, list)
    }

    /** Adds [appointment] unless one for the same doctor, date AND patient already exists (so a
     *  re-scan of the same discharge summary doesn't pile up duplicate follow-ups — while still
     *  allowing two different family members to see the same doctor on the same date). Returns
     *  true if added. */
    fun addIfAbsent(context: Context, appointment: AppointmentSchedule): Boolean {
        val list = loadAll(context)
        val exists = list.any {
            it.doctorName.trim().equals(appointment.doctorName.trim(), ignoreCase = true) &&
                it.date == appointment.date &&
                // .orEmpty(): a pre-existing appointment saved before patientName existed
                // deserializes to a raw null here, not the Kotlin default — see TodaysMedicinesTab.
                it.patientName.orEmpty().trim().equals(appointment.patientName.orEmpty().trim(), ignoreCase = true)
        }
        if (exists) return false
        saveAll(context, list + appointment)
        return true
    }

    fun delete(context: Context, id: String) {
        saveAll(context, loadAll(context).filterNot { it.id == id })
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_APPOINTMENTS).apply()
    }
}
