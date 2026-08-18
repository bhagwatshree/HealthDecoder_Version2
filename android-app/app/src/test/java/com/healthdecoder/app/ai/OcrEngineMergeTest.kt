package com.healthdecoder.app.ai

import com.healthdecoder.app.model.TestParameter
import com.healthdecoder.app.model.TestResults
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrEngineMergeTest {

    private fun section(
        name: String,
        date: String?,
        params: List<String> = emptyList(),
        rawText: String = ""
    ) = ScanExtraction(
        reportName = name,
        reportDate = date,
        testResults = TestResults(parameters = params.map { TestParameter(name = it, value = "1") }),
        rawText = rawText
    )

    @Test
    fun `report split across two chunks is recombined by name and date`() {
        val chunk1 = MultiScanExtraction(
            patientName = "Ramesh",
            reports = listOf(section("Complete Blood Count", "2026-06-26", params = listOf("Hemoglobin"), rawText = "page 1"))
        )
        val chunk2 = MultiScanExtraction(
            reports = listOf(section("Complete Blood Count", "2026-06-26", params = listOf("WBC"), rawText = "page 2"))
        )

        val merged = OcrEngine.mergeChunks(listOf(chunk1, chunk2))

        assertEquals(1, merged.reports.size)
        assertEquals(listOf("Hemoglobin", "WBC"), merged.reports[0].testResults!!.parameters.map { it.name })
        assertEquals("page 1\n\npage 2", merged.reports[0].rawText)
        assertEquals("Ramesh", merged.patientName)
    }

    @Test
    fun `distinct reports from different chunks stay separate`() {
        val chunk1 = MultiScanExtraction(reports = listOf(section("Complete Blood Count", "2026-06-26")))
        val chunk2 = MultiScanExtraction(reports = listOf(
            section("2D Echocardiography", "2026-06-27"),
            section("Lipid Profile", "2026-06-26")
        ))

        val merged = OcrEngine.mergeChunks(listOf(chunk1, chunk2))

        assertEquals(3, merged.reports.size)
    }

    @Test
    fun `same report name with different dates stays separate`() {
        val chunk1 = MultiScanExtraction(reports = listOf(section("Complete Blood Count", "2026-05-01")))
        val chunk2 = MultiScanExtraction(reports = listOf(section("Complete Blood Count", "2026-06-26")))

        val merged = OcrEngine.mergeChunks(listOf(chunk1, chunk2))

        assertEquals(2, merged.reports.size)
    }

    @Test
    fun `patient name comes from first chunk that found one`() {
        val chunk1 = MultiScanExtraction(patientName = "", reports = listOf(section("A", "2026-01-01")))
        val chunk2 = MultiScanExtraction(patientName = "Ramesh", reports = listOf(section("B", "2026-01-02")))

        assertEquals("Ramesh", OcrEngine.mergeChunks(listOf(chunk1, chunk2)).patientName)
    }
}
