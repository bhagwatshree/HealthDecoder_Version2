package com.healthdecoder.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the byte budget on a scan request. AWS Lambda hard-rejects any payload over
 * 6,291,456 bytes with a 413 raised before our handler runs, so a chunk that looks fine by page
 * count can still be undeliverable — which is exactly how a thick discharge summary used to fail
 * every time, with the size never surfacing in the error.
 */
class OcrEngineChunkTest {

    private fun page(sizeBytes: Int) = ByteArray(sizeBytes) to "image/jpeg"

    private fun bytesOf(chunk: List<Pair<ByteArray, String>>) =
        chunk.sumOf { it.first.size.toLong() }

    @Test
    fun `splits on the byte budget before the page count is reached`() {
        // 8 pages x 600KB = 4.8MB raw: under a 12-page cap, over a 3.5MB budget.
        val pages = List(8) { page(600_000) }

        val chunks = OcrEngine.chunkByBudget(pages, maxPages = 12, maxBytes = 3_500_000)

        assertTrue("expected more than one chunk, got ${chunks.size}", chunks.size > 1)
        chunks.forEach { assertTrue("chunk over budget: ${bytesOf(it)}", bytesOf(it) <= 3_500_000) }
        assertEquals("no page may be dropped", 8, chunks.sumOf { it.size })
    }

    @Test
    fun `still splits on the page count when pages are small`() {
        val pages = List(30) { page(10_000) }   // 300KB total — budget is irrelevant here

        val chunks = OcrEngine.chunkByBudget(pages, maxPages = 12, maxBytes = 3_500_000)

        assertEquals(listOf(12, 12, 6), chunks.map { it.size })
    }

    @Test
    fun `a single oversized page is sent alone rather than dropped`() {
        // One page over budget on its own, plus a normal one. Dropping it would silently lose
        // a page of the patient's report, which is worse than letting the backend reject it.
        val chunks = OcrEngine.chunkByBudget(
            listOf(page(4_000_000), page(100_000)),
            maxPages = 12,
            maxBytes = 3_500_000
        )

        assertEquals(2, chunks.size)
        assertEquals(1, chunks[0].size)
        assertEquals(2, chunks.sumOf { it.size })
    }

    @Test
    fun `empty input yields no chunks`() {
        assertEquals(emptyList<List<Pair<ByteArray, String>>>(),
            OcrEngine.chunkByBudget(emptyList(), maxPages = 12, maxBytes = 3_500_000))
    }
}
