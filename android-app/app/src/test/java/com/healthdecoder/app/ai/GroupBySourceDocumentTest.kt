package com.healthdecoder.app.ai

import com.healthdecoder.app.model.MedicalReport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Records groups reports by the document they came out of. The grouping is presentation only —
 * nothing about how reports are stored or aggregated depends on it — but getting the key wrong
 * merges unrelated records, which is worse than not grouping at all.
 */
class GroupBySourceDocumentTest {

    private fun report(
        id: String, patient: String = "Jayant", pages: List<String> = emptyList()
    ) = MedicalReport(
        id = id, patientName = patient, reportDate = "2026-08-12", reportType = "Lab Report",
        extractedText = "", comments = "", imagePath = pages.firstOrNull() ?: "",
        imagePaths = pages, createdAt = "2026-08-12T09:00:00Z"
    )

    /** The reported case: one PDF, several panels. */
    @Test fun `panels sharing page files form one group`() {
        val pages = listOf("/f/p1.jpg", "/f/p2.jpg")
        val groups = DashboardEngine.groupBySourceDocument(
            listOf(report("haemogram", pages = pages), report("proteins", pages = pages), report("biochem", pages = pages))
        )
        assertEquals(1, groups.size)
        assertEquals(3, groups.first().size)
    }

    @Test fun `different documents stay separate`() {
        val groups = DashboardEngine.groupBySourceDocument(
            listOf(report("a", pages = listOf("/f/a.jpg")), report("b", pages = listOf("/f/b.jpg")))
        )
        assertEquals(2, groups.size)
    }

    /**
     * Reports with no stored pages — imported, restored, or hand-entered — must NOT all collapse
     * into a single group keyed on the empty path list.
     */
    @Test fun `reports without page files each stand alone`() {
        val groups = DashboardEngine.groupBySourceDocument(
            listOf(report("x"), report("y"), report("z"))
        )
        assertEquals(3, groups.size)
    }

    @Test fun `one document covering two patients does not merge them`() {
        val pages = listOf("/f/p1.jpg")
        val groups = DashboardEngine.groupBySourceDocument(
            listOf(report("a", patient = "Jayant", pages = pages), report("b", patient = "Sunita", pages = pages))
        )
        assertEquals(2, groups.size)
    }

    @Test fun `incoming order is preserved`() {
        val pages = listOf("/f/p1.jpg")
        val groups = DashboardEngine.groupBySourceDocument(
            listOf(report("newest", pages = pages), report("older", pages = listOf("/f/q.jpg")))
        )
        assertEquals("newest", groups[0].first().id)
        assertEquals("older", groups[1].first().id)
    }

    @Test fun `every report survives grouping`() {
        val input = listOf(
            report("a", pages = listOf("/f/1.jpg")), report("b", pages = listOf("/f/1.jpg")),
            report("c"), report("d", pages = listOf("/f/2.jpg"))
        )
        assertEquals(input.size, DashboardEngine.groupBySourceDocument(input).sumOf { it.size })
    }
}
