package com.healthdecoder.app.ai

import com.healthdecoder.app.model.MedicalReport
import com.healthdecoder.app.model.Medication
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DashboardEngineTest {

    private fun report(
        id: String,
        date: String,
        meds: List<Medication> = emptyList(),
        category: String = if (meds.isEmpty()) "blood_test" else "prescription"
    ) = MedicalReport(
        id = id,
        patientName = "Ramesh",
        reportDate = date,
        reportType = if (meds.isEmpty()) "Lab Report" else "Prescription",
        extractedText = "",
        comments = "",
        medications = meds,
        imagePath = "",
        createdAt = "${date}T10:00:00.000Z",
        reportCategory = category
    )

    private fun statusOf(reports: List<MedicalReport>, medicine: String): String =
        DashboardEngine.buildDashboard(reports, emptyList())
            .medicationHistory.first { it.medicineName == medicine }.status

    // Status gating compares a medication's startDate/endDate against the REAL system clock, not
    // the reports' fictional dates above — so these tests anchor to "now" at run time rather than
    // hardcoding a date that could drift into the past/future depending on when tests execute.
    private fun daysFromNow(days: Int): String {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, days) }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    @Test
    fun `newer lab report does not discontinue medicines`() {
        val reports = listOf(
            report("rx1", "2026-05-01", meds = listOf(Medication("Metformin", "500mg", "Twice daily"))),
            report("lab1", "2026-06-01") // blood report, no medicines
        )
        assertEquals("Active", statusOf(reports, "Metformin"))
    }

    @Test
    fun `medicine omitted from a newer prescription is discontinued`() {
        val reports = listOf(
            report("rx1", "2026-05-01", meds = listOf(
                Medication("Metformin", "500mg", "Twice daily"),
                Medication("Atorvastatin", "10mg", "At night")
            )),
            report("rx2", "2026-06-01", meds = listOf(Medication("Metformin", "500mg", "Twice daily")))
        )
        assertEquals("Discontinued", statusOf(reports, "Atorvastatin"))
        assertEquals("Active", statusOf(reports, "Metformin"))
    }

    @Test
    fun `dosage change in a newer prescription shows changed`() {
        val reports = listOf(
            report("rx1", "2026-05-01", meds = listOf(Medication("Metformin", "500mg", "Twice daily"))),
            report("rx2", "2026-06-01", meds = listOf(Medication("Metformin", "1000mg", "Twice daily")))
        )
        assertEquals("Changed", statusOf(reports, "Metformin"))
    }

    @Test
    fun `lab report between prescriptions does not mark medicines removed in timeline`() {
        val reports = listOf(
            report("rx1", "2026-05-01", meds = listOf(Medication("Metformin", "500mg", "Twice daily"))),
            report("lab1", "2026-05-15"),
            report("rx2", "2026-06-01", meds = listOf(Medication("Metformin", "500mg", "Twice daily")))
        )
        val summary = DashboardEngine.buildHealthSummary("Ramesh", reports)
        val allRemoved = summary.medicationTimeline.flatMap { it.removed }
        assertEquals(emptyList<String>(), allRemoved)
        assertEquals(listOf("Metformin"), summary.medicationTimeline.last().activeMedicines.map { it.name })
    }

    // Real case: a discharge summary (30 Jul) prints "Concor 5 mg" plainly; the follow-up
    // prescription (13 Aug) prints the same drug with its generic/salt name bracketed
    // ("CONCOR TAB 5MG (Bisoprolol 5mg)"). Before the MedName fix these forked into two rows the
    // user had to de-duplicate by hand.
    @Test
    fun `bracketed generic-salt variant on a later scan updates the same medicine, not a duplicate`() {
        val reports = listOf(
            report("discharge", "2026-07-30", meds = listOf(Medication("Concor 5 mg", "1 tablet", "1-0-0"))),
            report("followup", "2026-08-13", meds = listOf(Medication("CONCOR TAB 5MG (Bisoprolol 5mg)", "1 tablet", "1-0-0")))
        )
        val history = DashboardEngine.buildDashboard(reports, emptyList()).medicationHistory
        assertEquals(1, history.count { it.medicineName.contains("Concor", ignoreCase = true) })
        assertEquals("Active", statusOf(reports, history.first { it.medicineName.contains("Concor", ignoreCase = true) }.medicineName))
    }

    // Real case: the discharge summary's "0.5 mg" is the per-DOSE amount (half of a 1mg tablet),
    // not the tablet's actual strength — the newer prescription's "1mg" is correct and, being the
    // more recent scan, must win the display-name tie-break even though "0.5mg" is a longer string.
    @Test
    fun `later scan wins the display name when both carry a strength digit`() {
        val reports = listOf(
            report("discharge", "2026-07-30", meds = listOf(Medication("Acitrom 0.5mg", "0.5mg", "at 6pm"))),
            report("followup", "2026-08-13", meds = listOf(Medication("Acitrom 1mg", "half tablet", "0-1/2-0")))
        )
        val history = DashboardEngine.buildDashboard(reports, emptyList()).medicationHistory
        assertEquals(1, history.size)
        assertEquals("Acitrom 1mg", history.first().medicineName)
    }

    // Real case: a handwritten addition on the 13 Aug follow-up prescription — "Ecosprin Gold 20
    // — 0-0-1 — from 20/10/26" — a brand new medicine that doesn't start until two months later.
    @Test
    fun `medicine with a future start date is Scheduled, not Active`() {
        val reports = listOf(
            report("followup", "2026-08-13", meds = listOf(
                Medication("Ecosprin Gold 20", "1 tablet", "0-0-1", startDate = daysFromNow(60))
            ))
        )
        assertEquals("Scheduled", statusOf(reports, "Ecosprin Gold 20"))
    }

    // Real case: the 30 Jul discharge summary's Tayo 60K is a weekly, 4-dose (~28 day) course.
    // The 13 Aug follow-up prescription is a chronic-meds-only script that doesn't repeat it —
    // Tayo 60K must stay Active while its own resolved window hasn't closed yet, not flip to
    // Discontinued just because a later, narrower-scope prescription omitted it.
    @Test
    fun `medicine within its own duration window survives a narrower later prescription`() {
        val reports = listOf(
            report("discharge", "2026-07-30", meds = listOf(
                Medication("Tayo 60K", "1 tablet", "once a week", endDate = daysFromNow(14))
            )),
            report("followup", "2026-08-13", meds = listOf(Medication("Concor 5mg", "1 tablet", "1-0-0")))
        )
        assertEquals("Active", statusOf(reports, "Tayo 60K"))
    }

    @Test
    fun `medicine flips to discontinued once its own end date has passed`() {
        val reports = listOf(
            report("discharge", "2026-07-30", meds = listOf(
                Medication("Tayo 60K", "1 tablet", "once a week", endDate = daysFromNow(-14))
            )),
            report("followup", "2026-08-13", meds = listOf(Medication("Concor 5mg", "1 tablet", "1-0-0")))
        )
        assertEquals("Discontinued", statusOf(reports, "Tayo 60K"))
    }
}
