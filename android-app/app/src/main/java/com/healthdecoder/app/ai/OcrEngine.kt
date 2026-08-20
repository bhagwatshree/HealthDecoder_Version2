package com.healthdecoder.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.healthdecoder.app.model.Medication
import com.healthdecoder.app.model.TestResults
import com.google.android.gms.tasks.Tasks
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Future test the doctor recommended, extracted from a scan. */
data class RecommendedTest(
    @SerializedName("testName") val testName: String,
    @SerializedName("dueDate") val dueDate: String? = null
)

/** A follow-up doctor visit read from a discharge summary's "FOLLOW UP" / "Review" section. */
data class FollowUp(
    @SerializedName("doctorName") val doctorName: String? = null,
    @SerializedName("specialty") val specialty: String? = null,
    // "after 7 days" -> 7; "after 2 weeks" -> 14; "after 1 month" -> 30. Null when an explicit date is given.
    @SerializedName("afterDays") val afterDays: Int? = null,
    @SerializedName("date") val date: String? = null,   // explicit YYYY-MM-DD if the summary printed one
    @SerializedName("place") val place: String? = null, // clinic / OPD / hospital
    @SerializedName("notes") val notes: String? = null  // e.g. "bring Lipid Profile", "Medicine OPD"
)

/** One date visible on a page together with its printed label ("Reported", "Collected"…). */
data class FoundDate(
    @SerializedName("label") val label: String? = null,
    @SerializedName("date") val date: String? = null
)

/** Structured result of scanning one report/prescription image. */
data class ScanExtraction(
    @SerializedName("patientName") val patientName: String? = null,
    @SerializedName("reportName") val reportName: String? = null,
    @SerializedName("reportDate") val reportDate: String? = null,
    @SerializedName("dateSource") val dateSource: String? = null,
    @SerializedName("datesFound") val datesFound: List<FoundDate> = emptyList(),
    @SerializedName("reportType") val reportType: String? = null,
    @SerializedName("comments") val comments: String? = null,
    @SerializedName("medications") val medications: List<Medication> = emptyList(),
    @SerializedName("recommendedTests") val recommendedTests: List<RecommendedTest> = emptyList(),
    @SerializedName("followUps") val followUps: List<FollowUp> = emptyList(),
    @SerializedName("testResults") val testResults: TestResults? = null,
    @SerializedName("rawText") val rawText: String? = null
)

/**
 * Full extraction of a scan, which may contain SEVERAL distinct reports (e.g. a bundle
 * of CBC + lipid profile + 2D Echo pages), each with its own name and dates.
 */
data class MultiScanExtraction(
    @SerializedName("patientName") val patientName: String? = null,
    @SerializedName("reports") val reports: List<ScanExtraction> = emptyList(),
    @SerializedName("rawText") val rawText: String? = null
) {
    /** Collapses all sections into one legacy-style extraction (used by Compare). */
    fun merged(): ScanExtraction {
        val first = reports.firstOrNull() ?: return ScanExtraction(patientName = patientName, rawText = rawText)
        return ScanExtraction(
            patientName = patientName,
            reportName = first.reportName,
            reportDate = first.reportDate,
            dateSource = first.dateSource,
            datesFound = reports.flatMap { it.datesFound },
            reportType = first.reportType,
            comments = reports.mapNotNull { it.comments?.takeIf { c -> c.isNotBlank() } }.joinToString("\n"),
            medications = reports.flatMap { it.medications },
            recommendedTests = reports.flatMap { it.recommendedTests },
            followUps = reports.flatMap { it.followUps },
            testResults = TestResults(
                parameters = reports.flatMap { it.testResults?.parameters ?: emptyList() },
                findings = reports.flatMap { it.testResults?.findings ?: emptyList() }
            ),
            rawText = rawText ?: first.rawText
        )
    }
}

/**
 * On-device replacement for the Node backend's scanMedicalReport(). Sends the actual image
 * to Gemini vision (so handwriting is read directly) with any device OCR text as a hint.
 * Falls back to a light local parse if Gemini is unavailable.
 */
object OcrEngine {

    /**
     * AWS Lambda refuses any invocation payload over 6,291,456 bytes — a hard platform limit,
     * not a setting, returned as a 413 before our code runs. Budget the RAW image bytes for one
     * request well under it: base64 costs 4 bytes per 3 (+33%), and the prompt, JSON envelope and
     * per-image metadata all share the same body. 3.5MB raw lands around 4.7MB on the wire,
     * leaving real headroom rather than sitting on the cliff edge.
     */
    private const val LAMBDA_MAX_REQUEST_BYTES = 6L * 1024 * 1024
    // x3/4 converts the wire budget back to raw bytes (undoing base64's +33%), then a further
    // x3/4 keeps a quarter of the limit free for the prompt and JSON envelope. ~3.5MB raw.
    private const val MAX_CHUNK_RAW_BYTES = LAMBDA_MAX_REQUEST_BYTES * 3 / 4 * 3 / 4

    /**
     * Splits pages into chunks bounded by page count AND total bytes, whichever is hit first.
     *
     * A single page larger than [maxBytes] is still sent on its own rather than dropped — it is
     * the caller's only chance at that page, and [ImageUtil] has already downscaled it, so in
     * practice one page is well under the budget. If such a page does exceed the platform limit
     * the backend's 413 now carries the real reason.
     */
    internal fun chunkByBudget(
        images: List<Pair<ByteArray, String>>,
        maxPages: Int,
        maxBytes: Long
    ): List<List<Pair<ByteArray, String>>> {
        val chunks = mutableListOf<List<Pair<ByteArray, String>>>()
        var current = mutableListOf<Pair<ByteArray, String>>()
        var currentBytes = 0L
        for (image in images) {
            val size = image.first.size.toLong()
            val wouldExceed = current.isNotEmpty() &&
                (current.size >= maxPages || currentBytes + size > maxBytes)
            if (wouldExceed) {
                chunks.add(current)
                current = mutableListOf()
                currentBytes = 0L
            }
            current.add(image)
            currentBytes += size
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
    }

    private val gson: Gson = GsonBuilder().setLenient().create()

    /** Marks a report saved via [localFallback] — the AI never actually read the document, so
     *  its reportDate/reportType/category weren't derived from real content and are suspect.
     *  [LocalRepository.reprocessReport] looks for this exact string to know it's safe (and
     *  necessary) to overwrite those fields once a real analysis succeeds. */
    const val DEGRADED_MARKER = "Parsed on-device from OCR text (AI unavailable)."

    /**
     * Scans one or more page images. The pages may contain several distinct reports;
     * each comes back as its own entry with its own name and correctly chosen date.
     *
     * Large batches are processed CHUNK BY CHUNK, then merged; a report whose pages span two
     * chunks is recombined by matching name + date. A failed chunk is skipped rather than
     * failing the whole scan.
     *
     * A chunk is bounded by BOTH a page count ([AppSettings.getScanChunkPages]) and a byte
     * budget ([MAX_CHUNK_RAW_BYTES]) — see [chunkByBudget]. The page count alone was not enough:
     * AWS Lambda hard-rejects any request body over 6,291,456 bytes with a 413 before our
     * handler ever runs, and 12 pages of a densely printed report comfortably exceeds that once
     * base64 inflates them by a third. Photograph a thick discharge summary and every scan
     * failed, with the size never mentioned anywhere in the error.
     *
     * Note this is about rendered PAGES, not source files: a PDF is rasterised to at most 15
     * page images before it gets here (see FileImportUtil.renderPdf) and the PDF's own file size
     * is never sent anywhere, so a 40MB PDF is not itself a problem.
     */
    fun scan(
        context: Context,
        images: List<Pair<ByteArray, String>>,
        localOcrText: String,
        scanType: String,
        reportCategory: String,
        operation: String = "scan"
    ): MultiScanExtraction {
        val chunkSize = com.healthdecoder.app.local.AppSettings.getScanChunkPages(context)
        val chunks = if (images.isEmpty()) listOf(emptyList())
            else chunkByBudget(images, chunkSize, MAX_CHUNK_RAW_BYTES)

        val results = mutableListOf<MultiScanExtraction>()
        val chunkTexts = mutableListOf<String>()
        var failedChunks = 0
        for ((index, chunk) in chunks.withIndex()) {
            // On-device OCR over THIS chunk's pages. Free (ML Kit, no network), and it now does
            // double duty: the accuracy hint below, AND the searchable transcription that the
            // model no longer burns output tokens re-typing (see buildPrompt). [localOcrText] is
            // only ever page 1's text from the scan screen, so it's a fallback, not the source.
            val prepared = localOcrPages(chunk)
            val chunkText = prepared.text.ifBlank { if (index == 0) localOcrText else "" }
            chunkTexts.add(chunkText)
            val result = scanChunk(context, prepared.images, chunkText, scanType, reportCategory, index + 1, chunks.size, operation)
            if (result != null) results.add(result) else failedChunks++
        }
        val fullText = chunkTexts.filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { localOcrText }
        if (results.isEmpty()) return localFallback(fullText, scanType)
        val merged = mergeChunks(results)
            .withLocalTranscription(fullText)
            .withLocalPatientName(context, fullText)
        // A partial failure used to vanish with no trace — some pages' data (e.g. the very
        // last page of a multi-page report) silently missing from the saved report with
        // nothing to indicate why. Now it's visible on the report itself instead of only in
        // logs, so it doesn't look like the document was fully, correctly read when it wasn't.
        if (failedChunks == 0) return merged
        val warning = "⚠ $failedChunks of ${chunks.size} page-batch${if (chunks.size > 1) "es" else ""} " +
            "failed to analyze — some pages of this document may be missing from the extracted data. Consider re-scanning."
        return merged.copy(
            reports = merged.reports.mapIndexed { i, section ->
                if (i == 0) section.copy(comments = listOfNotNull(warning, section.comments?.takeIf { it.isNotBlank() }).joinToString("\n"))
                else section
            }
        )
    }

    private fun scanChunk(
        context: Context,
        images: List<Pair<ByteArray, String>>,
        referenceText: String,
        scanType: String,
        reportCategory: String,
        part: Int,
        totalParts: Int,
        operation: String
    ): MultiScanExtraction? = try {
        val prompt = buildPrompt(referenceText, scanType, reportCategory, images.size, part, totalParts)
        // Scan calls go through our backend proxy (BackendAiClient), NOT GeminiClient directly —
        // the Gemini key never touches the device. Chat, medicine lookup/identify, and detailed
        // analysis are migrated too (see MedicalEngine). TTS (SpeechEngine) and translation
        // (LanguageUtil) still call Sarvam directly — the backend has no Sarvam proxy yet.
        val raw = BackendAiClient.generateFromImages(context, prompt, images, operation)
        parse(GeminiClient.stripJsonFences(raw))
    } catch (e: BackendAiClient.BackendAiException) {
        // A known, deterministic failure (daily quota exhausted, server down) — never worth
        // silently degrading to localFallback(), which can't read the document at all and
        // would file it under the wrong category with today's date instead of its real one.
        // Let the caller (BackgroundScanScheduler) surface the real reason to the user.
        throw e
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    /**
     * Merges per-chunk extractions into one result. Sections with the same report name
     * and date (a report whose pages landed in different chunks) are combined; distinct
     * reports stay separate. Visible for testing.
     */
    internal fun mergeChunks(chunks: List<MultiScanExtraction>): MultiScanExtraction {
        if (chunks.size == 1) return chunks.first()
        val merged = linkedMapOf<String, ScanExtraction>()
        for (chunk in chunks) {
            for (section in chunk.reports) {
                val key = (section.reportName ?: section.reportType ?: "report")
                    .trim().lowercase() + "|" + (section.reportDate ?: "")
                val prev = merged[key]
                merged[key] = if (prev == null) section else prev.copy(
                    comments = listOfNotNull(
                        prev.comments?.takeIf { it.isNotBlank() },
                        section.comments?.takeIf { it.isNotBlank() }
                    ).distinct().joinToString("\n"),
                    medications = prev.medications + section.medications,
                    recommendedTests = (prev.recommendedTests + section.recommendedTests)
                        .distinctBy { it.testName.trim().lowercase() },
                    followUps = (prev.followUps + section.followUps)
                        .distinctBy { "${it.doctorName?.trim()?.lowercase()}|${it.afterDays}|${it.date}" },
                    datesFound = (prev.datesFound + section.datesFound).distinct(),
                    testResults = TestResults(
                        parameters = (prev.testResults?.parameters ?: emptyList()) +
                            (section.testResults?.parameters ?: emptyList()),
                        findings = ((prev.testResults?.findings ?: emptyList()) +
                            (section.testResults?.findings ?: emptyList())).distinct()
                    ),
                    rawText = listOfNotNull(
                        prev.rawText?.takeIf { it.isNotBlank() },
                        section.rawText?.takeIf { it.isNotBlank() }
                    ).joinToString("\n\n")
                )
            }
        }
        return MultiScanExtraction(
            patientName = chunks.firstNotNullOfOrNull { it.patientName?.takeIf { n -> n.isNotBlank() } },
            reports = merged.values.toList(),
            rawText = chunks.mapNotNull { it.rawText?.takeIf { t -> t.isNotBlank() } }.joinToString("\n\n")
        )
    }

    /** Parses the new multi-report shape, falling back to the legacy single-report shape. */
    private fun parse(json: String): MultiScanExtraction? {
        val multi = try { gson.fromJson(json, MultiScanExtraction::class.java) } catch (e: Exception) { null }
        if (multi != null && multi.reports.isNotEmpty()) return multi
        val legacy = try { gson.fromJson(json, ScanExtraction::class.java) } catch (e: Exception) { null }
            ?: return null
        return MultiScanExtraction(
            patientName = legacy.patientName,
            reports = listOf(legacy),
            rawText = legacy.rawText
        )
    }

    private fun buildPrompt(
        referenceText: String,
        scanType: String,
        reportCategory: String,
        pageCount: Int,
        part: Int = 1,
        totalParts: Int = 1
    ): String {
        val pagesNote = buildString {
            if (pageCount > 1) append("The document is provided as $pageCount page images.")
            if (totalParts > 1) append(
                " NOTE: these pages are part $part of $totalParts of a larger scan batch processed in " +
                "chunks. Extract ONLY what is visible on these pages; other parts are processed " +
                "separately. A report may continue in another part — still extract everything visible here."
            )
        }
        val categoryText = if (scanType == "prescription")
            "The user is scanning this mainly to capture prescribed medicines, so identify EVERY medication, dosage, frequency, duration and instruction with great care. BUT do not assume the document is a plain prescription slip — classify \"reportType\" by what the document ACTUALLY is (a Discharge Summary, Consultation Note, Prescription, Lab Report or Diagnostic Scan), because discharge summaries and consultation notes also list medicines. Set \"reportName\" to the document's real printed title (e.g. \"Discharge Summary\")."
        else
            "This document is a Medical/Diagnostic Report of category \"$reportCategory\". Focus on dates, and extracting findings, observations, conclusions, and test parameters (values, units, reference ranges, abnormal flags). Still classify \"reportType\" by what the document actually is."

        val refBlock = if (referenceText.isNotBlank())
            "Here is auxiliary on-device OCR text to assist accuracy. It may be incomplete or miss handwriting, so ALWAYS prefer what you can read directly from the image:\n\"\"\"\n$referenceText\n\"\"\"\n"
        else ""

        return """
Analyze this medical report, lab result, or prescription image and extract the details as a JSON object.
$pagesNote
$refBlock
Context instructions:
$categoryText

IMPORTANT: This document may contain HANDWRITTEN text (a doctor's handwriting, margin notes, ticked boxes, or corrections). Read handwritten medicines, dosages, frequencies, and comments carefully and include them — do NOT ignore handwriting. If partly illegible, transcribe your best interpretation.

MULTIPLE REPORTS: The pages may contain SEVERAL distinct reports (for example a CBC, a lipid profile, and a 2D Echo bundled together), each with its own report name and its own dates. Return one entry in "reports" for EACH distinct report. Pages belonging to the same report must be merged into ONE entry. If everything is one single report, return a single entry.

DATES — read these rules very carefully:
A page often shows several dates with different labels: "Printed on", "Registered on", "Collected on" / "Sample collected", "Reported on" / "Reporting date" / "Report date", "Date of procedure" / "Study date" / "Date of examination", a visit date, or a bare date with no label. For EACH report:
1. List EVERY visible date with its label in "datesFound" (use label "" for an unlabeled date).
2. Choose "reportDate" by these priority rules:
   - Blood / urine / any sample-based lab report: use the REPORTED / REPORTING date. If missing, the sample COLLECTED date. NEVER the printed date.
   - Procedure or imaging report (2D Echo, Sonography/USG, X-Ray, ECG, CT, MRI, Doppler, Endoscopy...): use the PROCEDURE / STUDY / EXAMINATION date — the date it was performed.
   - Prescription: the visit / consultation date.
   - A bare unlabeled date: use it only when none of the above exist.
3. Set "dateSource" to the label of the date you chose (e.g. "Reported", "Procedure", "Visit", "Unlabeled").
4. Convert ALL dates to YYYY-MM-DD. Dates may be printed day-first in Indian formats (12/03/2026, 12-03-26, 12.Mar.2026, 12 March 2026). Do not guess; if no date is visible for a report, set reportDate to null.

PRIVACY: identity details (the patient's name, the referring doctor, the lab/hospital letterhead,
ID and contact numbers) are deliberately blacked out on these pages before they reach you. Do not
try to infer, reconstruct or report them, and do not treat a blacked-out box as missing data —
the device already holds those details. Extract only the clinical content.

Also ensure that:
1. "reportName" is the specific printed name of each report (e.g. "Complete Blood Count", "Lipid Profile", "2D Echocardiography").
2. Comments, instructions, remarks, or advice are extracted per report.
3. MEDICATIONS — extract EVERY medicine listed, including all rows of a "DISCHARGE MEDICATION",
   "Treatment", "Rx", or "Medicines on discharge" table in a discharge summary (these tables are
   often long — do not stop early or summarise; return one entry per medicine). For each medicine:
   - "name": drug name WITH its strength (e.g. "Concor 5mg", "Dolo 650mg", "Amifru 40mg"). Drop the
     form prefix (Tab./Cap./Syp./Inj.) from the name.
   - "frequency": the dosing schedule EXACTLY as written. Indian prescriptions use position codes —
     "1-0-1" = morning-afternoon-night, "0-0-1" = night only, "1-0-0" = morning only,
     "1/2-0-0" = half tablet in the morning, "1-1-1" = three times a day. For non-daily schedules
     write it plainly: "twice a week (Wed, Sat)", "once a week", "at 6 pm".
   - "duration": e.g. "till follow-up", "10 days", "1 month", "5 days".
   - "weeklySchedule": the days it is taken — ["Everyday"] for a daily medicine, or the specific days
     for a weekly one, e.g. ["Wednesday","Saturday"] or ["Sunday"] for once-weekly.
   - "isOptional": true only for SOS / PRN / "if required" medicines.
   - "notes": special instructions (empty stomach, before/after food, "no food 2 hrs"), AND any
     HANDWRITTEN substitution or brand the doctor wrote beside the printed drug.
   - "startDate": an EXPLICIT future start date printed or handwritten (e.g. "start from 20/10/26",
     a handwritten addition next to another drug), as YYYY-MM-DD. Leave null when the medicine
     starts on the visit/report date itself (the normal case).
   - "startAfterDays": if instead stated as an offset ("starts after 5 days"), the number of days
     after the report/visit date — same day-math convention as followUps.afterDays below
     ("after 1 week"→7, "after 2 weeks"→14, "after 1 month"→30). Leave null when startDate is set
     or the medicine starts immediately.
   - "endDate": an EXPLICIT end date printed or handwritten ANYWHERE on this medicine's row/entry
     — in an instruction sentence, a frequency line, a remarks column, or a dedicated date field —
     e.g. "TILL 20/10/26", "TILL 20 OCT 2026". Search the WHOLE row for this, not just an obvious
     "Duration"/"Instruction" column: a row can print BOTH a generic day-count ("Duration: 90
     Days") AND a separate, more specific calendar date in its instructions ("TILL 20 OCT 2026, 5
     DAYS A WEEK, THURSDAY & SUNDAY OFF..."). When both appear, the calendar date is the real,
     doctor-intended stop date and MUST be captured here — do not let a printed day-count column
     substitute for it or cause you to skip it. Do NOT set "endDate" for vague endings like "till
     follow-up" or "continue" — leave null; "duration" already captures that text as-is.
   - "durationDays": the course length in days, ONLY when it is a concrete count — "10 days"→10,
     "1 month"→30, "2 weeks"→14, "once a week, complete 4 tabs totally"→28. This is independent of
     "endDate" above — fill both when both are printed (e.g. a "Duration: 90 Days" column AND a
     "TILL 20 OCT 2026" instruction on the same row both get captured, into durationDays and
     endDate respectively). Leave null for "till follow-up"/"continue"/"ongoing" or anything
     without a determinable day count.
   - "intervalDays": ONLY for a dosing CADENCE that repeats every N days without lining up to the
     same weekday each week — "once in 15 days"→15, "every 3 days"→3, "alternate day"/"every other
     day"→2. Leave null for a daily medicine (use "frequency"'s position codes + weeklySchedule
     ["Everyday"]) or a medicine tied to specific weekday(s) (use "weeklySchedule" with the day
     names instead, e.g. "twice a week (Wed, Sat)") — those recurrence patterns are captured there,
     not here. Do not confuse with "durationDays" (the total course length, e.g. "90 Days"):
     "intervalDays" is how often each dose repeats, "durationDays" is how long the course runs.
   When the doctor has struck through a printed medicine and handwritten a replacement next to it,
   use the handwritten one as the name and note the original in "notes".
4. Future recommended tests go into that report's "recommendedTests".
   FOLLOW-UP VISITS: from a "FOLLOW UP", "Review", "Revisit", "Come after" or "Next appointment"
   section, extract EACH doctor visit into "followUps": the "doctorName", the "specialty" if named
   (Cardiology, Endocrinology, Medicine OPD...), WHEN as "afterDays" — a NUMBER of days from the
   discharge/report date (convert "after 1 week"→7, "after 2 weeks"→14, "after 15 days"→15,
   "after 1 month"→30, "after 3 months"→90) — or an explicit "date" (YYYY-MM-DD) if one is printed,
   the "place"/clinic/OPD, and "notes" (e.g. tests to carry like "bring Lipid Profile", or "check
   PT/INR after 3 days"). One entry per doctor/visit. Leave "followUps" empty if there is no such section.
5. Test results go into that report's "testResults": lab parameters into "parameters"; scan/diagnostic conclusions into "findings".
6. For each parameter, also classify it for trend-charting across multiple reports over time:
   - "trendCategory": if it matches one of these, use that EXACT text (case-sensitive) —
     Blood Sugar, HbA1c, TSH, T3, T4, Hemoglobin, WBC, Platelets, Total Cholesterol, LDL, HDL,
     Triglycerides, Creatinine, Oxygen (SpO2), Ejection Fraction, Vitamin D, Vitamin B12
     — otherwise a short clean name of your own for that specific test. Never merge two
     DIFFERENT tests into one category just because they share a word or organ — e.g. serum
     creatinine and urinary creatinine are different categories; blood glucose and urine
     glucose are different categories; an actual measured value and a value CALCULATED from
     a different test (e.g. HbA1c's "estimated average glucose") are different categories.
   - "trendCondition": the condition it was measured under, if the report states one — mainly
     relevant to blood sugar: "Fasting", "PP" (post-meal), or "Random". Empty string otherwise.
   - "excludeFromTrend": true only when the value is NOT a direct numeric measurement — e.g. a
     value calculated/derived from another test, or a semi-quantitative dipstick result like
     "+", "++", "+++", "Negative", "Trace". False for every normal numeric lab result.
   (This list must stay in sync with DashboardEngine.KEY_PARAMETER_ORDER in the Android app.)

The response MUST be a JSON object with this schema:
{
  "reports": [
    {
      "reportName": "Specific report name or null",
      "reportType": "Prescription | Discharge Summary | Consultation Note | Lab Report | Diagnostic Scan | Other",
      "reportDate": "YYYY-MM-DD or null",
      "dateSource": "Reported | Collected | Procedure | Visit | Unlabeled | null",
      "datesFound": [ { "label": "Reported", "date": "YYYY-MM-DD" } ],
      "comments": "Doctor's instructions/advice/notes for THIS report",
      "medications": [
        { "name": "", "dosage": "", "frequency": "", "duration": "", "isOptional": false, "weeklySchedule": ["Everyday"], "notes": "",
          "startDate": "YYYY-MM-DD or null", "startAfterDays": null, "endDate": "YYYY-MM-DD or null", "durationDays": null, "intervalDays": null }
      ],
      "recommendedTests": [ { "testName": "", "dueDate": "YYYY-MM-DD or null" } ],
      "followUps": [ { "doctorName": "", "specialty": "", "afterDays": 7, "date": "YYYY-MM-DD or null", "place": "", "notes": "" } ],
      "testResults": {
        "parameters": [
          {
            "name": "", "value": "", "unit": "", "referenceRange": "", "status": "High | Low | Normal",
            "trendCategory": "", "trendCondition": "", "excludeFromTrend": false
          }
        ],
        "findings": [ "" ]
      }
    }
  ]
}

Do NOT transcribe the document back to us — no "rawText", no full-text dump. The device
already has its own OCR transcription for search; re-typing the page here costs output
tokens (the expensive kind) for text we already hold. Return ONLY the structured fields.

Return ONLY raw JSON. No markdown code fences, no extra text.
""".trim()
    }

    /**
     * On-device text recognition (ML Kit) over a chunk's page images. Runs entirely on the
     * phone — no network, no API cost, no document content leaving the device for this step.
     * Blocking by design: [scan] is already called from Dispatchers.IO, and the result is
     * needed before the page images are sent on.
     *
     * Pages are decoded and recycled one at a time rather than all up front — a 15-page batch
     * of full-resolution bitmaps held simultaneously is an easy OutOfMemoryError on a mid-range
     * device. A page that fails to decode or recognise is skipped, never fatal: this text is an
     * accuracy hint plus search fodder, so partial text is strictly better than none.
     */
    private fun localOcrPages(images: List<Pair<ByteArray, String>>): PreparedPages {
        if (images.isEmpty()) return PreparedPages("", images)

        // Building the recognizer is itself failure-prone in release builds — ML Kit resolves
        // implementations reflectively, and a missing keep rule surfaces as a bare NPE from
        // inside obfuscated ML Kit code (see proguard-rules.pro). This step is an OPTIONAL
        // enhancement to a scan, never a precondition for one, so nothing it does may take the
        // scan down with it: on failure the pages go up exactly as they would have before this
        // existed. Note that also means no redaction happened, hence the loud log — a silent
        // fallback here would quietly send identity data the caller believes was covered.
        val recognizer = runCatching { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
            .getOrElse {
                Log.w("ScanDiag", "on-device OCR unavailable; pages go UN-REDACTED and unindexed", it)
                return PreparedPages("", images)
            }

        val texts = mutableListOf<String>()
        val prepared = mutableListOf<Pair<ByteArray, String>>()

        for ((bytes, mime) in images) {
            val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            if (bmp == null) { prepared.add(bytes to mime); continue }
            try {
                val recognised = runCatching { Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0))) }.getOrNull()
                if (recognised == null) { prepared.add(bytes to mime); continue }
                recognised.text.takeIf { it.isNotBlank() }?.let { texts.add(it) }

                // Cover identity regions before this page leaves the device. A page with nothing
                // to redact is forwarded byte-for-byte, so it never pays JPEG generation loss on
                // a document the model must read small printed values from.
                val redacted = PiiRedactor.redactedCopy(bmp, recognised)
                if (redacted == null) {
                    prepared.add(bytes to mime)
                } else {
                    val buffer = java.io.ByteArrayOutputStream()
                    redacted.compress(Bitmap.CompressFormat.JPEG, 92, buffer)
                    redacted.recycle()
                    prepared.add(buffer.toByteArray() to "image/jpeg")
                }
            } finally {
                bmp.recycle()
            }
        }
        return PreparedPages(texts.joinToString("\n\n"), prepared)
    }

    /** On-device OCR text for a chunk, plus that chunk's pages with identity regions painted out. */
    private data class PreparedPages(val text: String, val images: List<Pair<ByteArray, String>>)

    /**
     * Attaches the on-device transcription to the extraction, since the model is no longer asked
     * to return one. Every section of a multi-report document gets the same full-document text:
     * this text exists for full-text SEARCH (and the degraded-scan fallback), where matching the
     * right document matters and per-panel precision does not — a search for "cholesterol" now
     * matches every report scanned from that page rather than only the lipid panel, which is a
     * cheap price for not paying output-token rates to have the page typed back to us.
     */
    private fun MultiScanExtraction.withLocalTranscription(text: String): MultiScanExtraction =
        if (text.isBlank()) this
        else copy(
            rawText = rawText?.takeIf { it.isNotBlank() } ?: text,
            reports = reports.map { it.copy(rawText = it.rawText?.takeIf { t -> t.isNotBlank() } ?: text) }
        )

    /**
     * Resolves whose report this is WITHOUT the model's help — the name is blacked out before
     * upload (see [PiiRedactor]), so it can only come from the device. Preference order:
     * the name printed on the page as read by on-device OCR, then whichever family member the
     * user currently has selected, then the existing "Unknown Patient" placeholder that the
     * rest of the app already handles.
     */
    private fun MultiScanExtraction.withLocalPatientName(context: Context, text: String): MultiScanExtraction {
        if (!patientName.isNullOrBlank()) return this
        val fromPage = extractPatientName(text).takeIf { it != "Unknown Patient" }
        val resolved = fromPage
            ?: com.healthdecoder.app.local.AppSettings.getActivePatient(context)?.takeIf { it.isNotBlank() }
            ?: return this
        return copy(patientName = resolved)
    }

    /** Minimal offline fallback: keep the OCR text and a best-effort patient name. */
    private fun localFallback(localOcrText: String, scanType: String): MultiScanExtraction {
        val name = extractPatientName(localOcrText)
        val type = when (scanType) {
            "prescription" -> "Prescription"
            "report" -> "Lab Report"
            else -> "Other"
        }
        val section = ScanExtraction(
            patientName = name,
            reportDate = null,
            reportType = type,
            comments = DEGRADED_MARKER,
            medications = emptyList(),
            recommendedTests = emptyList(),
            testResults = TestResults(),
            rawText = localOcrText
        )
        return MultiScanExtraction(patientName = name, reports = listOf(section), rawText = localOcrText)
    }

    private fun extractPatientName(text: String): String {
        if (text.isBlank()) return "Unknown Patient"
        val regex = Regex("(?:Name|Patient|Patient\\s*Name)\\s*[:\\-]?\\s*(?:Mr\\.|Mrs\\.|Ms\\.)?\\s*([A-Za-z ]{3,})", RegexOption.IGNORE_CASE)
        val match = regex.find(text)
        val candidate = match?.groupValues?.getOrNull(1)?.trim()?.replace(Regex("\\s+"), " ")
        return if (!candidate.isNullOrBlank() && candidate.length > 3) candidate else "Unknown Patient"
    }

    suspend fun translateSearchPromptToFilter(context: Context, userPrompt: String): String {
        if (userPrompt.isBlank()) {
            return "subject:(report OR lab OR diagnostic OR billing OR test OR health OR prescription) has:attachment filename:pdf"
        }
        val prompt = """
            You are a medical email search assistant. Translate the user's request for medical reports or hospital emails into a standard Gmail search query syntax.
            Also, automatically include generic keywords for medical reports (like report, lab, test, diagnostic, prescription) so that the search captures reports even if they don't exactly match the user's specific request.
            The default generic query is: "subject:(report OR lab OR diagnostic OR billing OR test OR health OR prescription) has:attachment filename:pdf"
            
            Examples:
            Input: "search SRL Labs"
            Output: "SRL subject:(report OR lab OR diagnostic OR billing OR test OR health OR prescription) has:attachment filename:pdf"
            
            Input: "find blood test from Metropolis"
            Output: "Metropolis subject:(report OR lab OR diagnostic OR billing OR test OR health OR prescription OR blood) has:attachment filename:pdf"
            
            Input: "Max Hospital"
            Output: "Max subject:(report OR lab OR diagnostic OR billing OR test OR health OR prescription) has:attachment filename:pdf"
            
            Input: "$userPrompt"
            Output: 
        """.trimIndent()
        return try {
            val response = BackendAiClient.generateText(context, prompt, operation = "email-search-filter")
            GeminiClient.stripJsonFences(response).trim().removeSurrounding("\"").trim()
        } catch (e: Exception) {
            e.printStackTrace()
            val words = userPrompt.split(" ").filter { it.length > 2 }.joinToString(" OR ")
            if (words.isNotBlank()) {
                "($words) subject:(report OR lab OR diagnostic OR billing OR test OR health OR prescription) has:attachment filename:pdf"
            } else {
                "subject:(report OR lab OR diagnostic OR billing OR test OR health OR prescription) has:attachment filename:pdf"
            }
        }
    }
}
