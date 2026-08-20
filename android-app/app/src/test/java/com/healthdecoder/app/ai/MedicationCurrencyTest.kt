package com.healthdecoder.app.ai

import com.healthdecoder.app.model.MedicalReport
import com.healthdecoder.app.model.Medication
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which prescription drives the medication tracker (and therefore the reminders) when a patient
 * has more than one. The rule is clinical recency — a later script supersedes an earlier one —
 * NOT the order the user got round to scanning their paperwork in.
 */
class MedicationCurrencyTest {

    private fun report(
        id: String, reportDate: String, createdAt: String, vararg meds: Pair<String, String>
    ) = MedicalReport(
        id = id,
        patientName = "Jayant",
        reportDate = reportDate,
        reportType = "Prescription",
        extractedText = "",
        comments = "",
        imagePath = "",
        createdAt = createdAt,
        medications = meds.map { (name, freq) ->
            Medication(name = name, dosage = "1 tablet", frequency = freq, duration = "")
        }
    )

    private fun historyFor(reports: List<MedicalReport>) =
        DashboardEngine.buildDashboard(reports, emptyList()).medicationHistory

    /**
     * The reported case: a 30 June discharge summary catalogued AFTER the 13 Aug prescription it
     * was superseded by. Ordering by scan time made the June script current and drove the
     * reminders from it; reprocessing could never fix that, since reprocess preserves createdAt.
     */
    @Test fun `a later-dated prescription wins even when scanned afterwards`() {
        val august = report("aug", "2026-08-13", "2026-08-13T09:00:00Z", "Acitrom 1mg" to "0-0-1")
        val june = report("jun", "2026-06-30", "2026-08-20T09:00:00Z", "Acitrom 0.5mg" to "1-0-1")

        val acitrom = historyFor(listOf(august, june)).single { it.medicineName.contains("Acitrom") }

        assertEquals("0-0-1", acitrom.currentFrequency)
        assertEquals("aug", acitrom.reportId)
    }

    @Test fun `scan order still decides when neither report carries a printed date`() {
        val first = report("first", "", "2026-08-01T09:00:00Z", "Concor 5mg" to "1-0-0")
        val second = report("second", "", "2026-08-15T09:00:00Z", "Concor 5mg" to "1-0-1")

        val concor = historyFor(listOf(second, first)).single { it.medicineName.contains("Concor") }

        assertEquals("1-0-1", concor.currentFrequency)
        assertEquals("second", concor.reportId)
    }

    @Test fun `input order does not matter, only the dates do`() {
        val older = report("older", "2026-06-30", "2026-06-30T09:00:00Z", "Dolo 650mg" to "1-0-1")
        val newer = report("newer", "2026-08-13", "2026-08-13T09:00:00Z", "Dolo 650mg" to "0-0-1")

        for (ordering in listOf(listOf(older, newer), listOf(newer, older))) {
            val dolo = historyFor(ordering).single { it.medicineName.contains("Dolo") }
            assertEquals("0-0-1", dolo.currentFrequency)
        }
    }
}
