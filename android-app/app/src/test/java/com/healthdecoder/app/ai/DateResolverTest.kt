package com.healthdecoder.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DateResolverTest {

    private fun section(
        reportName: String? = null,
        reportType: String? = null,
        reportDate: String? = null,
        dates: List<Pair<String, String>> = emptyList()
    ) = ScanExtraction(
        reportName = reportName,
        reportType = reportType,
        reportDate = reportDate,
        datesFound = dates.map { FoundDate(it.first, it.second) }
    )

    // ── Label priority ───────────────────────────────────────────────────────
    @Test
    fun `lab report prefers reported date over collected and printed`() {
        val s = section(
            reportName = "Complete Blood Count", reportType = "Lab Report",
            dates = listOf(
                "Printed on" to "2026-03-15",
                "Sample collected" to "2026-03-10",
                "Reported on" to "2026-03-12"
            )
        )
        assertEquals("2026-03-12", DateResolver.resolve(s, "blood_test"))
    }

    @Test
    fun `lab report falls back to collected date when no reported date`() {
        val s = section(
            reportType = "Lab Report",
            dates = listOf("Printed on" to "2026-03-15", "Collected on" to "2026-03-10")
        )
        assertEquals("2026-03-10", DateResolver.resolve(s, "blood_test"))
    }

    @Test
    fun `echo report prefers procedure date over report and printed dates`() {
        val s = section(
            reportName = "2D Echocardiography",
            dates = listOf(
                "Report date" to "2026-04-20",
                "Date of procedure" to "2026-04-18",
                "Printed" to "2026-04-25"
            )
        )
        assertEquals("2026-04-18", DateResolver.resolve(s, "2d_echo"))
    }

    @Test
    fun `prescription prefers visit date`() {
        val s = section(
            reportType = "Prescription",
            dates = listOf("Printed" to "2026-05-02", "Visit date" to "2026-05-01")
        )
        assertEquals("2026-05-01", DateResolver.resolve(s, "prescription"))
    }

    @Test
    fun `unlabeled date wins over printed date but loses to reported`() {
        val labeled = section(
            reportType = "Lab Report",
            dates = listOf("" to "2026-02-01", "Reported" to "2026-02-03")
        )
        assertEquals("2026-02-03", DateResolver.resolve(labeled, "blood_test"))

        val bare = section(
            reportType = "Lab Report",
            dates = listOf("" to "2026-02-01", "Printed on" to "2026-02-05")
        )
        assertEquals("2026-02-01", DateResolver.resolve(bare, "blood_test"))
    }

    @Test
    fun `falls back to model-chosen date when nothing labeled parses`() {
        val s = section(reportType = "Lab Report", reportDate = "2026-01-20")
        assertEquals("2026-01-20", DateResolver.resolve(s, "blood_test"))
    }

    // ── Format normalization ─────────────────────────────────────────────────
    @Test
    fun `parses indian day-first formats`() {
        assertEquals("2026-03-12", DateResolver.normalize("12/03/2026"))
        assertEquals("2026-03-12", DateResolver.normalize("12-03-2026"))
        assertEquals("2026-03-12", DateResolver.normalize("12.03.2026"))
        assertEquals("2026-03-12", DateResolver.normalize("12 Mar 2026"))
        assertEquals("2026-03-12", DateResolver.normalize("12-Mar-2026"))
        assertEquals("2026-03-12", DateResolver.normalize("12 March 2026"))
        assertEquals("2026-03-12", DateResolver.normalize("Mar 12, 2026"))
        assertEquals("2026-03-12", DateResolver.normalize("2026-03-12"))
    }

    @Test
    fun `strips time portions`() {
        assertEquals("2026-03-12", DateResolver.normalize("12/03/2026 10:45 AM"))
        assertEquals("2026-03-12", DateResolver.normalize("2026-03-12T09:30:00"))
    }

    @Test
    fun `rejects implausible and unparsable dates`() {
        assertNull(DateResolver.normalize("2099-01-01"))      // far future
        assertNull(DateResolver.normalize("1950-01-01"))      // >60 years old
        assertNull(DateResolver.normalize("not a date"))
        assertNull(DateResolver.normalize(null))
        assertNull(DateResolver.normalize(""))
    }

    @Test
    fun `skips invalid labeled date and falls down the ladder`() {
        val s = section(
            reportType = "Lab Report",
            dates = listOf("Reported" to "2099-12-31", "Collected" to "2026-03-10")
        )
        assertEquals("2026-03-10", DateResolver.resolve(s, "blood_test"))
    }

    // ── Medicine start/end dates (legitimately in the future, unlike report dates) ────────────
    // Real case: a handwritten addition on a follow-up prescription — "Ecosprin Gold 20 — 0-0-1
    // — from 20/10/26" — a NEW medicine that starts about two months after the visit date.
    @Test
    fun `normalizeMedicineDate accepts a future date that normalize rejects`() {
        val cal = Calendar.getInstance().apply { add(Calendar.MONTH, 2) }
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        val dayFirst = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(cal.time)
        assertEquals(iso, DateResolver.normalizeMedicineDate(dayFirst))
        // normalize() exists to sanity-check REPORT dates — it must keep rejecting future dates,
        // so the two functions weren't accidentally merged into one behavior.
        assertNull(DateResolver.normalize(dayFirst))
    }

    @Test
    fun `normalizeMedicineDate still rejects implausible OCR garbage`() {
        val farFuture = Calendar.getInstance().apply { add(Calendar.YEAR, 10) }
        assertNull(DateResolver.normalizeMedicineDate(SimpleDateFormat("dd/MM/yyyy", Locale.US).format(farFuture.time)))
        val farPast = Calendar.getInstance().apply { add(Calendar.YEAR, -10) }
        assertNull(DateResolver.normalizeMedicineDate(SimpleDateFormat("dd/MM/yyyy", Locale.US).format(farPast.time)))
    }
}
