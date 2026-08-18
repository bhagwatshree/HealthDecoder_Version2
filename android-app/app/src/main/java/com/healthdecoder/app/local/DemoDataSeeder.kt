package com.healthdecoder.app.local

import android.content.Context
import com.healthdecoder.app.model.MedicalReport
import com.healthdecoder.app.model.Medication
import com.healthdecoder.app.model.PendingTest
import com.healthdecoder.app.model.TestParameter
import com.healthdecoder.app.model.TestResults
import com.healthdecoder.app.reminder.AppointmentReminderManager
import com.healthdecoder.app.reminder.AppointmentSchedule
import com.healthdecoder.app.reminder.AppointmentStore
import com.healthdecoder.app.reminder.MedicineReminderManager
import com.healthdecoder.app.reminder.MedicineScheduleStore
import com.healthdecoder.app.ui.parseRoutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Seeds (and cleans up) a fake "Aisha (Demo)" patient with a couple of lab reports, a pending
 * test, a medicine reminder and a doctor appointment — so a brand-new install (a Play Store
 * tester, most likely) can see what a populated Records/Trends/Reminders/Doctor Brief actually
 * looks like without scanning a real document. See OnboardingScreen ("Try Demo") and
 * SettingsScreen ("Try Demo Data" card) for the two entry points.
 *
 * Everything here rides through the exact same write paths a real scan uses (LocalRepository /
 * LocalStore / MedicineScheduleStore / AppointmentStore) so it stays consistent with encryption,
 * indexing and reminder scheduling — nothing is hand-rolled.
 */
object DemoDataSeeder {

    /** Unmistakably fake, and can't collide with a real scanned patient's name. */
    const val DEMO_PATIENT_NAME = "Aisha (Demo)"
    private const val DEMO_RELATION = "Demo"
    private const val DEMO_EMOJI = "🧪"

    private val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun daysAgo(n: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -n)
        return isoDate.format(cal.time)
    }

    private fun daysFromNow(n: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, n)
        return isoDate.format(cal.time)
    }

    /** "created" a report at 9am on [dateIso] — only the relative ORDER matters (older report's
     *  createdAt must sort before the newer one's) so DashboardEngine treats the newer report's
     *  medicines as the current prescription. */
    private fun createdAtFor(dateIso: String): String = "${dateIso}T09:00:00.000Z"

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

    /** Quick check for whether the demo patient currently exists (drives the Account screen's
     *  "Add Demo Data" / "Remove Demo Data" button state). */
    suspend fun isDemoDataPresent(context: Context): Boolean = withContext(Dispatchers.IO) {
        AppSettings.getFamilyProfilesRaw(context)
            .any { it.name.trim().equals(DEMO_PATIENT_NAME, ignoreCase = true) }
    }

    suspend fun seedDemoData(context: Context) = withContext(Dispatchers.IO) {
        // Family member — via the same path real "add a family member" uses, so name-uniqueness
        // etc. stays correct. No-ops harmlessly if the demo patient already exists.
        LocalRepository.addFamilyMember(
            context, DEMO_PATIENT_NAME, DEMO_RELATION, /* sex = */ "Female", /* dob = */ "", DEMO_EMOJI
        )

        val olderDate = daysAgo(60)
        val newerDate = daysAgo(5)

        // Report 1 (older): out-of-range Hemoglobin/Cholesterol, no medicines yet.
        val olderReport = MedicalReport(
            id = LocalStore.newId(),
            patientName = DEMO_PATIENT_NAME,
            reportDate = olderDate,
            reportType = "Lab Report",
            extractedText = "Sample demo report (Complete Blood Count + Lipid Profile + Fasting Blood Sugar) — for exploring the app before scanning a real document.",
            comments = "Annual health checkup — routine bloodwork.",
            medications = emptyList(),
            imagePath = "",
            imagePaths = emptyList(),
            sourceFiles = emptyList(),
            createdAt = createdAtFor(olderDate),
            testResults = TestResults(
                parameters = listOf(
                    TestParameter("Hemoglobin", "11.2", "g/dL", "13.0-17.0", "Low", "Hemoglobin", ""),
                    TestParameter("Total Cholesterol", "210", "mg/dL", "<200", "High", "Total Cholesterol", ""),
                    TestParameter("Fasting Blood Sugar", "98", "mg/dL", "70-100", "Normal", "Blood Sugar", "Fasting")
                ),
                findings = listOf(
                    "Mild anemia noted — hemoglobin below the reference range.",
                    "Cholesterol slightly elevated — dietary review suggested."
                )
            ),
            reportCategory = "blood_test",
            analyzed = true
        )

        // Report 2 (newer): same three parameters, all improved — plus the two medicines that
        // explain the improvement, telling a "getting better" demo story.
        val newerReport = MedicalReport(
            id = LocalStore.newId(),
            patientName = DEMO_PATIENT_NAME,
            reportDate = newerDate,
            reportType = "Lab Report",
            extractedText = "Sample demo report (Complete Blood Count + Lipid Profile + Fasting Blood Sugar) — for exploring the app before scanning a real document.",
            comments = "Follow-up bloodwork after two months on treatment.",
            medications = listOf(
                Medication(name = "Metformin", dosage = "500mg", frequency = "Twice daily"),
                Medication(name = "Atorvastatin", dosage = "10mg", frequency = "Once at night")
            ),
            imagePath = "",
            imagePaths = emptyList(),
            sourceFiles = emptyList(),
            createdAt = createdAtFor(newerDate),
            testResults = TestResults(
                parameters = listOf(
                    TestParameter("Hemoglobin", "12.8", "g/dL", "13.0-17.0", "Normal", "Hemoglobin", ""),
                    TestParameter("Total Cholesterol", "185", "mg/dL", "<200", "Normal", "Total Cholesterol", ""),
                    TestParameter("Fasting Blood Sugar", "94", "mg/dL", "70-100", "Normal", "Blood Sugar", "Fasting")
                ),
                findings = listOf(
                    "Hemoglobin back within the normal range.",
                    "Cholesterol improved with diet and medication."
                )
            ),
            reportCategory = "blood_test",
            analyzed = true
        )

        LocalStore.upsertReport(context, olderReport)
        LocalStore.upsertReport(context, newerReport)

        // Pending test
        LocalStore.upsertPendingTest(
            context,
            PendingTest(
                id = LocalStore.newId(),
                patientName = DEMO_PATIENT_NAME,
                testName = "HbA1c",
                dueDate = daysFromNow(21),
                status = "Pending",
                resolvedReportId = null,
                createdAt = nowIso()
            )
        )

        // Medicine reminders — mirrors TodaysMedicinesTab's auto-seed-on-scan behavior (frequency
        // text -> active time slots) so the reminder exists immediately, without needing the user
        // to open the Reminders tab first for the auto-seed LaunchedEffect to run.
        for (m in newerReport.medications) {
            val activeSlots = parseRoutine(m.frequency, m.dosage).filter { it.second }.map { it.first }
            MedicineScheduleStore.autoSeedIfAbsent(
                context, m.name, DEMO_PATIENT_NAME, m.dosage, m.frequency, activeSlots, emptyList()
            )
        }
        MedicineReminderManager.scheduleAll(context)

        // Doctor appointment
        AppointmentStore.upsert(
            context,
            AppointmentSchedule(
                doctorName = "Dr. Sample",
                date = daysFromNow(10),
                time = "10:30",
                place = "General Medicine OPD",
                isRecurring = false,
                recurrence = "None",
                hour = 10,
                minute = 30,
                patientName = DEMO_PATIENT_NAME
            )
        )
        AppointmentReminderManager.scheduleAll(context)

        // Focus Home on the demo patient right away — the whole point of "Try Demo" is seeing a
        // populated screen immediately, not making the user find the new profile in the picker.
        AppSettings.setActivePatient(context, DEMO_PATIENT_NAME)
    }

    /** Removes everything scoped to the demo patient, and ONLY the demo patient — reports (via
     *  the same per-report delete real deletion uses, so files clean up too), the pending test,
     *  the medicine reminder (alarms cancelled first), the appointment (alarm cancelled first),
     *  and the family profile entry. Never touches any other patient's data. */
    suspend fun removeDemoData(context: Context) = withContext(Dispatchers.IO) {
        val reports = LocalStore.getReports(context)
            .filter { it.patientName.equals(DEMO_PATIENT_NAME, ignoreCase = true) }
        for (r in reports) LocalRepository.deleteReport(context, r.id)

        val pending = LocalStore.getPendingTests(context)
            .filter { it.patientName.equals(DEMO_PATIENT_NAME, ignoreCase = true) }
        for (p in pending) LocalRepository.deletePendingTest(context, p.id)

        val schedules = MedicineScheduleStore.loadAll(context)
            .filter { it.patientName.equals(DEMO_PATIENT_NAME, ignoreCase = true) }
        for (s in schedules) MedicineScheduleStore.delete(context, s.medicineName, s.patientName)
        MedicineReminderManager.scheduleAll(context) // re-sync grouped alarms now that these are gone

        val appointments = AppointmentStore.loadAll(context)
            .filter { it.patientName.orEmpty().equals(DEMO_PATIENT_NAME, ignoreCase = true) }
        for (a in appointments) {
            AppointmentReminderManager.cancel(context, a.id)
            AppointmentStore.delete(context, a.id)
        }

        val profile = AppSettings.getFamilyProfilesRaw(context)
            .firstOrNull { it.name.trim().equals(DEMO_PATIENT_NAME, ignoreCase = true) }
        if (profile != null) LocalRepository.removeFamilyMember(context, profile.id)

        if (AppSettings.getActivePatient(context).equals(DEMO_PATIENT_NAME, ignoreCase = true)) {
            AppSettings.setActivePatient(context, null)
        }
    }
}
