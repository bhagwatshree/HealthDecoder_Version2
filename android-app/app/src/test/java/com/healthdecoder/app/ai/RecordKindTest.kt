package com.healthdecoder.app.ai

import com.healthdecoder.app.model.MedicalReport
import com.healthdecoder.app.model.TestParameter
import com.healthdecoder.app.model.TestResults
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Records filter buckets. One scanned PDF frequently files several reports at once — a
 * haemogram, a biochemistry panel, a urine routine — so these names are what a patient is
 * actually scrolling past when looking for something else.
 */
class RecordKindTest {

    private fun report(
        type: String, category: String = "other", params: List<String> = emptyList()
    ) = MedicalReport(
        id = "r", patientName = "Jayant", reportDate = "2026-08-12", reportType = type,
        extractedText = "", comments = "", imagePath = "", createdAt = "2026-08-12T09:00:00Z",
        reportCategory = category,
        testResults = TestResults(parameters = params.map {
            TestParameter(name = it, value = "1", unit = "", referenceRange = "", status = "Normal")
        })
    )

    @Test fun `a prescription is a prescription`() {
        assertEquals(DashboardEngine.RecordKind.PRESCRIPTION,
            DashboardEngine.recordKindOf(report("Prescription", category = "prescription")))
        assertEquals(DashboardEngine.RecordKind.PRESCRIPTION,
            DashboardEngine.recordKindOf(report("Medicine Prescription", category = "prescription")))
    }

    /** Names seen on the user's own records screen, none of which match a fixed list of tests. */
    @Test fun `panels off one scanned PDF are all lab reports`() {
        for (t in listOf(
            "HAEMOGRAM, PT & INR, URINE ROUTINE, BIOCHEMISTRY, PROTEINS & ELECTROLYTES REPORT",
            "PROTEINS (SERUM)",
            "BIOCHEMISTRY REPORT",
            "Lipid Profile",
            "Complete Blood Count"
        )) {
            assertEquals(t, DashboardEngine.RecordKind.LAB, DashboardEngine.recordKindOf(report(t)))
        }
    }

    /** Carrying measured values is enough on its own, whatever the document calls itself. */
    @Test fun `a report with measured parameters is a lab report`() {
        val r = report("Some Unfamiliar Panel Name", params = listOf("Haemoglobin", "Platelets"))
        assertEquals(DashboardEngine.RecordKind.LAB, DashboardEngine.recordKindOf(r))
    }

    @Test fun `summaries, notes and scans are Other`() {
        assertEquals(DashboardEngine.RecordKind.OTHER, DashboardEngine.recordKindOf(report("Discharge Summary")))
        assertEquals(DashboardEngine.RecordKind.OTHER, DashboardEngine.recordKindOf(report("Consultation Note")))
        assertEquals(DashboardEngine.RecordKind.OTHER, DashboardEngine.recordKindOf(report("2D Echocardiography")))
        assertEquals(DashboardEngine.RecordKind.OTHER, DashboardEngine.recordKindOf(report("Other")))
    }

    /** A discharge summary lists medicines but is not a prescription slip. */
    @Test fun `a discharge summary is not filed as a prescription`() {
        assertEquals(DashboardEngine.RecordKind.OTHER,
            DashboardEngine.recordKindOf(report("Discharge Summary", category = "other")))
    }
}
