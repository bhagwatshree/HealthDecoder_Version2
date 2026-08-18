package com.healthdecoder.app.ai

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Verifies and resolves the clinical date of a scanned report. The AI is asked to pick
 * the right date, but this class re-checks its work from the full list of labeled dates
 * it found on the page, so a "Printed on" date never becomes a lab report's date:
 *
 *  - sample-based lab reports (blood/urine)  → Reported date, else Collected date
 *  - procedure reports (2D Echo, USG, X-ray) → Procedure/Study/Examination date
 *  - prescriptions                           → Visit/consultation date
 *  - a bare unlabeled date only as fallback; a Printed date only as the very last resort
 *
 * All dates are normalized from common Indian day-first formats to YYYY-MM-DD and sanity
 * checked (not in the future, not implausibly old).
 */
object DateResolver {

    /** Kinds of dates that appear on medical documents, from their printed labels. */
    private enum class DateKind { REPORTED, COLLECTED, PROCEDURE, VISIT, UNLABELED, PRINTED }

    private enum class DocKind { LAB, PROCEDURE, PRESCRIPTION, OTHER }

    /** Priority ladder per document kind (earlier = preferred). */
    private val priorities = mapOf(
        DocKind.LAB to listOf(DateKind.REPORTED, DateKind.COLLECTED, DateKind.UNLABELED, DateKind.VISIT, DateKind.PROCEDURE, DateKind.PRINTED),
        DocKind.PROCEDURE to listOf(DateKind.PROCEDURE, DateKind.REPORTED, DateKind.UNLABELED, DateKind.COLLECTED, DateKind.VISIT, DateKind.PRINTED),
        DocKind.PRESCRIPTION to listOf(DateKind.VISIT, DateKind.UNLABELED, DateKind.REPORTED, DateKind.PROCEDURE, DateKind.COLLECTED, DateKind.PRINTED),
        DocKind.OTHER to listOf(DateKind.REPORTED, DateKind.PROCEDURE, DateKind.UNLABELED, DateKind.COLLECTED, DateKind.VISIT, DateKind.PRINTED)
    )

    /**
     * Picks the correct date for one extracted report section. Returns an ISO date, or
     * null when no plausible date exists anywhere on the page.
     */
    fun resolve(section: ScanExtraction, category: String): String? {
        val docKind = classify(section, category)
        val ladder = priorities.getValue(docKind)

        // Labeled candidates the model saw on the page, deduped, invalid dates dropped.
        val candidates = section.datesFound.mapNotNull { fd ->
            val iso = normalize(fd.date) ?: return@mapNotNull null
            labelKind(fd.label) to iso
        }

        for (kind in ladder) {
            candidates.firstOrNull { it.first == kind }?.let { return it.second }
        }
        // Nothing labeled/parsable — fall back to the date the model chose itself.
        return normalize(section.reportDate)
    }

    private fun classify(section: ScanExtraction, category: String): DocKind {
        val hints = listOf(category, section.reportType ?: "", section.reportName ?: "")
            .joinToString(" ").lowercase()
        return when {
            Regex("prescription").containsMatchIn(hints) -> DocKind.PRESCRIPTION
            Regex("echo|sonograph|ultrasound|usg|x[- ]?ray|xray|ecg|ekg|ct\\b|mri|doppler|endoscop|colonoscop|mammograph|angiograph|scan").containsMatchIn(hints) -> DocKind.PROCEDURE
            Regex("blood|urine|stool|serum|lab|patholog|hba1c|lipid|cbc|thyroid|culture").containsMatchIn(hints) -> DocKind.LAB
            else -> DocKind.OTHER
        }
    }

    private fun labelKind(label: String?): DateKind {
        val l = (label ?: "").lowercase()
        return when {
            l.isBlank() -> DateKind.UNLABELED
            Regex("print").containsMatchIn(l) -> DateKind.PRINTED
            Regex("report").containsMatchIn(l) -> DateKind.REPORTED
            Regex("collect|sample|drawn|received|regist").containsMatchIn(l) -> DateKind.COLLECTED
            Regex("procedure|study|exam|perform|scan|investigat").containsMatchIn(l) -> DateKind.PROCEDURE
            Regex("visit|consult|prescri|opd|admission").containsMatchIn(l) -> DateKind.VISIT
            else -> DateKind.UNLABELED
        }
    }

    // ── Parsing & sanity checks ─────────────────────────────────────────────
    // Day-first formats first: Indian reports print 12/03/2026 meaning 12 March.
    private val patterns = listOf(
        "yyyy-MM-dd", "yyyy/MM/dd",
        "dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy",
        "dd/MM/yy", "dd-MM-yy", "dd.MM.yy",
        "dd MMM yyyy", "dd-MMM-yyyy", "dd.MMM.yyyy", "dd MMMM yyyy",
        "MMM dd, yyyy", "MMMM dd, yyyy"
    )

    /**
     * Normalizes a date string in any common medical-report format to YYYY-MM-DD.
     * Returns null when unparsable or outside the plausible range (future dates and
     * dates more than 60 years old are rejected — usually OCR misreads).
     */
    fun normalize(raw: String?): String? = parseFirstPlausible(raw, ::isPlausible)

    /**
     * Same parsing as [normalize] but for a medicine's start/end date, which is legitimately in
     * the future (a course starting next month, an end date months out) — [normalize]'s "not in
     * the future" sanity check exists for REPORT dates and must stay that way for [resolve].
     * Still rejects implausible OCR garbage (more than ~5 years out either direction).
     */
    fun normalizeMedicineDate(raw: String?): String? = parseFirstPlausible(raw, ::isPlausibleMedicineDate)

    /**
     * Tries every known date format against [raw] in order, same as the original single-pass
     * parser: a pattern that parses but yields an implausible date (e.g. a 2-digit year matched
     * by a 4-digit-year pattern, reading "26" as year 26 AD) is skipped in favor of the NEXT
     * pattern, rather than failing outright — some formats are ambiguous and only a later
     * pattern in the list correctly disambiguates them. Returns the first parse whose date passes
     * [isPlausible].
     */
    private fun parseFirstPlausible(raw: String?, isPlausible: (Calendar) -> Boolean): String? {
        val text = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        // Strip a time portion ("12/03/2026 10:45 AM" → "12/03/2026").
        val dateOnly = text.replace(Regex("[T ]\\d{1,2}:\\d{2}.*$"), "").trim()

        for (pattern in patterns) {
            val fmt = SimpleDateFormat(pattern, Locale.ENGLISH).apply { isLenient = false }
            val parsed = try { fmt.parse(dateOnly) } catch (e: Exception) { null } ?: continue
            val cal = Calendar.getInstance().apply { time = parsed }
            if (isPlausible(cal)) return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(parsed)
        }
        return null
    }

    private fun isPlausible(cal: Calendar): Boolean {
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val oldest = Calendar.getInstance().apply { add(Calendar.YEAR, -60) }
        return cal.before(tomorrow) && cal.after(oldest)
    }

    private fun isPlausibleMedicineDate(cal: Calendar): Boolean {
        val earliest = Calendar.getInstance().apply { add(Calendar.YEAR, -5) }
        val latest = Calendar.getInstance().apply { add(Calendar.YEAR, 5) }
        return cal.after(earliest) && cal.before(latest)
    }
}
