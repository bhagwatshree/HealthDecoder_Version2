package com.healthdecoder.app.ai

import com.healthdecoder.app.model.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device port of the backend's /api/dashboard and /api/health-summary aggregation:
 * builds the medication tracker (current vs previous dosage, status), recent clinical
 * inferences, parameter trends, and the medication timeline.
 */
object DashboardEngine {

    // 80/20: the vital-few tests that cover most health signals, in display order.
    // Must stay in sync with the "trendCategory" list in OcrEngine.buildPrompt() — the AI is
    // told to use these exact category strings when it recognizes one of these tests.
    val KEY_PARAMETER_ORDER = listOf(
        "Blood Sugar", "HbA1c", "TSH", "T3", "T4", "Hemoglobin", "WBC", "Platelets",
        "Total Cholesterol", "LDL", "HDL", "Triglycerides", "Creatinine",
        "Oxygen (SpO2)", "Ejection Fraction", "Vitamin D", "Vitamin B12"
    )

    fun isKeyParameter(name: String): Boolean = KEY_PARAMETER_ORDER.contains(name)

    /** Groups differently-worded lab names into one canonical trend name. */
    fun canonicalParamName(raw: String): String {
        val n = raw.lowercase()
        return when {
            n.contains("hba1c") || n.contains("glycated") || n.contains("glycosylated") -> "HbA1c"
            // Blood sugar readings all plot on ONE line regardless of test condition (fasting /
            // post-meal / random) — see bloodSugarContext() for the per-point condition label
            // shown alongside each value, instead of fragmenting the trend into separate lines.
            // Excluded: urine dipstick glucose (semi-quantitative +/++/+++, not a mg/dL reading —
            // also caught by the value-based numeric guard in buildHealthSummary, since a urine
            // panel's "Glucose" often carries no "urine" in its own name) and "Estimated Average
            // Glucose (eAG)", a value CALCULATED from HbA1c rather than measured directly.
            !n.contains("urine") && !n.contains("estimated average") && !n.contains("eag") && (
                n.contains("glucose") || n.contains("sugar") || n.contains("fbs") || n.contains("bsf") ||
                    n.contains("ppbs") || n.contains("rbs") || n.contains("grbs")
                ) -> "Blood Sugar"
            n.contains("tsh") || (n.contains("thyroid") && n.contains("stimulating")) -> "TSH"
            // Word-boundary match (not exact-equals) — real reports label this "T3 (Total), Serum",
            // "Free T3", "Serum T4", etc., not just the bare word "T3"/"T4".
            n.contains("triiodothyronine") || n.contains("free t3") || n.contains("ft3") ||
                Regex("\\bt3\\b").containsMatchIn(n) -> "T3"
            n.contains("thyroxine") || n.contains("free t4") || n.contains("ft4") ||
                Regex("\\bt4\\b").containsMatchIn(n) -> "T4"
            // Exclude MCH / MCHC ("mean corpuscular hemoglobin…") which are separate CBC indices.
            (n.contains("hemoglobin") || n.contains("haemoglobin") || n == "hb" || n == "hgb") &&
                !n.contains("corpuscular") && !n.contains("mch") -> "Hemoglobin"
            n.contains("wbc") || n.contains("white blood") || n.contains("leucocyte") || n.contains("leukocyte") -> "WBC"
            // Exclude MPV / platelet distribution width, which aren't the platelet count.
            n.contains("platelet") && !n.contains("volume") && !n.contains("mpv") && !n.contains("distribution") -> "Platelets"
            // VLDL first: "VLDL Cholesterol" must not fall through to Total Cholesterol.
            n.contains("vldl") -> "VLDL"
            n.contains("ldl") -> "LDL"
            n.contains("hdl") -> "HDL"
            n.contains("triglyceride") -> "Triglycerides"
            n.contains("cholesterol") -> "Total Cholesterol"
            // Exclude urinary/spot creatinine and the Albumin:Creatinine Ratio (ACR) — a kidney
            // screening test with a completely different normal range from serum creatinine.
            n.contains("creatinine") && !n.contains("urin") -> "Creatinine"
            n.contains("spo2") || n.contains("oxygen") || n.contains("saturation") -> "Oxygen (SpO2)"
            n.contains("ejection") || n == "ef" || n.contains("lvef") -> "Ejection Fraction"
            n.contains("vitamin d") || n.contains("25-oh") || n.contains("25 oh") -> "Vitamin D"
            n.contains("b12") || n.contains("cobalamin") -> "Vitamin B12"

            // ── Coagulation (PT/INR) — critical for anyone on a blood thinner ──────────
            n.contains("inr") || n.contains("international normali") -> "INR"
            n.contains("prothrombin") || Regex("\\bpt\\b").containsMatchIn(n) -> "Prothrombin Time"
            n.contains("aptt") || n.contains("activated partial") -> "APTT"
            n.contains("fibrinogen") -> "Fibrinogen"
            n.contains("d-dimer") || n.contains("d dimer") -> "D-Dimer"

            // ── Electrolytes & minerals ───────────────────────────────────────────────
            n.contains("sodium") || Regex("\\bna\\+?\\b").containsMatchIn(n) -> "Sodium"
            // Bare "K" means potassium — but never inside "Vitamin K".
            n.contains("potassium") || (!n.contains("vitamin") && Regex("\\bk\\+?\\b").containsMatchIn(n)) -> "Potassium"
            n.contains("chloride") -> "Chloride"
            n.contains("bicarbonate") || n.contains("hco3") -> "Bicarbonate"
            n.contains("calcium") && !n.contains("urin") -> "Calcium"
            n.contains("magnesium") -> "Magnesium"
            n.contains("phosphor") || n.contains("phosphate") -> "Phosphorus"

            // ── Kidney ────────────────────────────────────────────────────────────────
            n.contains("egfr") || n.contains("gfr") -> "eGFR"
            n.contains("uric acid") -> "Uric Acid"
            (n.contains("urea") || n.contains("bun")) && !n.contains("urin") -> "Urea"

            // ── Liver ─────────────────────────────────────────────────────────────────
            n.contains("bilirubin") && n.contains("direct") -> "Bilirubin (Direct)"
            n.contains("bilirubin") -> "Bilirubin (Total)"
            n.contains("sgot") || n.contains("aspartate") || Regex("\\bast\\b").containsMatchIn(n) -> "SGOT (AST)"
            n.contains("sgpt") || n.contains("alanine") || Regex("\\balt\\b").containsMatchIn(n) -> "SGPT (ALT)"
            n.contains("alkaline phosphat") || Regex("\\balp\\b").containsMatchIn(n) -> "Alkaline Phosphatase"
            n.contains("ggt") || n.contains("gamma gluta") || n.contains("gamma-gluta") -> "GGT"
            n.contains("globulin") && !n.contains("ratio") -> "Globulin"
            n.contains("albumin") && !n.contains("urin") && !n.contains("micro") && !n.contains("ratio") -> "Albumin"
            n.contains("protein") && !n.contains("urin") && !n.contains("c-reactive") -> "Total Protein"

            // ── Heart ─────────────────────────────────────────────────────────────────
            n.contains("troponin") -> "Troponin"
            n.contains("bnp") || n.contains("natriuretic") -> "BNP"
            n.contains("ck-mb") || n.contains("ckmb") || n.contains("creatine kinase") || n.contains("cpk") -> "CPK / CK-MB"

            // ── Blood count (CBC) extras ──────────────────────────────────────────────
            n.contains("rbc") || n.contains("red blood") || n.contains("erythrocyte count") -> "RBC Count"
            n.contains("hematocrit") || n.contains("haematocrit") || n.contains("pcv") -> "Hematocrit (PCV)"
            n.contains("mchc") -> "MCHC"
            n.contains("mcv") || n.contains("mean corpuscular volume") -> "MCV"
            n.contains("mch") || n.contains("mean corpuscular h") -> "MCH"
            n.contains("rdw") -> "RDW"
            n.contains("neutrophil") -> "Neutrophils"
            n.contains("lymphocyte") -> "Lymphocytes"
            n.contains("monocyte") -> "Monocytes"
            n.contains("eosinophil") -> "Eosinophils"
            n.contains("basophil") -> "Basophils"
            n.contains("esr") || n.contains("sedimentation") -> "ESR"

            // ── Vitamins, iron & inflammation ─────────────────────────────────────────
            n.contains("ferritin") -> "Ferritin"
            n.contains("tibc") || n.contains("iron binding") -> "TIBC"
            n.contains("iron") -> "Iron"
            n.contains("folate") || n.contains("folic") -> "Folate"
            n.contains("c-reactive") || n.contains("crp") -> "CRP"
            n.contains("vldl") -> "VLDL"
            else -> raw.trim()
        }
    }

    /**
     * Trend panels shown in the Trends dropdown, in display order. A test lands in the FIRST
     * category listing its canonical name; anything unmatched falls into "Other tests", so no
     * scanned value can ever be hidden from the Trends screen.
     */
    const val CATEGORY_ALL = "All tests"
    const val CATEGORY_OTHER = "Other tests"

    val TREND_CATEGORIES: List<Pair<String, List<String>>> = listOf(
        "Blood Count" to listOf(
            "Hemoglobin", "RBC Count", "WBC", "Platelets", "Hematocrit (PCV)",
            "MCV", "MCH", "MCHC", "RDW",
            "Neutrophils", "Lymphocytes", "Monocytes", "Eosinophils", "Basophils", "ESR"
        ),
        "Diabetes" to listOf("Blood Sugar", "HbA1c"),
        "Heart & Cholesterol" to listOf(
            "Total Cholesterol", "LDL", "HDL", "VLDL", "Triglycerides",
            "Ejection Fraction", "Oxygen (SpO2)", "Troponin", "BNP", "CPK / CK-MB"
        ),
        "Coagulation (PT/INR)" to listOf(
            "Prothrombin Time", "INR", "APTT", "Fibrinogen", "D-Dimer"
        ),
        "Electrolytes & Minerals" to listOf(
            "Sodium", "Potassium", "Chloride", "Calcium", "Magnesium", "Phosphorus", "Bicarbonate"
        ),
        "Kidney & Liver" to listOf(
            "Creatinine", "Urea", "Uric Acid", "eGFR",
            "Bilirubin (Total)", "Bilirubin (Direct)", "SGOT (AST)", "SGPT (ALT)",
            "Alkaline Phosphatase", "GGT", "Total Protein", "Albumin", "Globulin"
        ),
        "Thyroid & Vitamins" to listOf(
            "TSH", "T3", "T4", "Vitamin D", "Vitamin B12", "Folate", "Iron", "Ferritin", "TIBC", "CRP"
        )
    )

    /** The panel a canonical test name belongs to — [CATEGORY_OTHER] when it matches none. */
    fun categoryOf(canonicalName: String): String =
        TREND_CATEGORIES.firstOrNull { (_, members) -> members.contains(canonicalName) }?.first
            ?: CATEGORY_OTHER

    /** Test condition for a blood-sugar reading (Fasting / PP / Random), or "" if unspecified —
     *  shown per-point alongside the value since a fasting and a post-meal reading aren't
     *  directly comparable even though they share one trend line. */
    fun bloodSugarContext(raw: String): String {
        val n = raw.lowercase()
        return when {
            n.contains("urine") -> ""
            n.contains("fbs") || n.contains("bsf") || n.contains("fasting") -> "Fasting"
            n.contains("ppbs") || n.contains("post prandial") || n.contains("postprandial") ||
                n.contains("post-meal") || n.contains("post meal") || n.contains("pp2") ||
                (n.contains("pp") && (n.contains("sugar") || n.contains("glucose"))) -> "PP"
            n.contains("rbs") || n.contains("grbs") || n.contains("random") -> "Random"
            else -> ""
        }
    }

    private data class MedPoint(
        val reportId: String, val dosage: String, val frequency: String, val duration: String,
        val isOptional: Boolean, val weeklySchedule: List<String>, val notes: String, val date: String,
        // Which prescription supersedes which. The printed report date when the document carries
        // one — a doctor's 13 Aug script overrides a 30 June discharge summary whichever order
        // they happened to be scanned in — falling back to the scan date for a document whose own
        // date could not be read. Compared as yyyy-MM-dd, so both forms sort together.
        val currency: String,
        val startDate: String?, val endDate: String?, val intervalDays: Int?
    )

    fun buildDashboard(reports: List<MedicalReport>, pendingTests: List<PendingTest>): DashboardData {
        val medicationHistory = buildMedicationHistory(reports)

        // Most recent first — the printed reportDate is what the card SHOWS, so it must also be
        // what the card is SORTED by, or a card can display one date while another (older,
        // sorted-in wrong) shows above it, which reads as the wrong report entirely.
        val testInferences = reports
            .filter { it.comparisonResult?.hasComparison == true }
            .sortedByDescending { it.reportDate ?: it.createdAt }
            .map {
                TestInference(
                    reportId = it.id,
                    patientName = it.patientName ?: "Unknown Patient",
                    reportDate = it.reportDate ?: "",
                    reportCategory = it.reportCategory ?: "",
                    reportType = it.reportType ?: "",
                    hasMedications = it.medications.any { m -> !m.name.isNullOrBlank() },
                    summary = it.comparisonResult?.comparisonSummary ?: "",
                    status = it.comparisonResult?.status ?: ""
                )
            }
            .take(5)

        return DashboardData(
            reports = reports,
            pendingTests = pendingTests,
            medicationHistory = medicationHistory,
            testInferences = testInferences
        )
    }

    /**
     * The three buckets a patient actually sorts their own paperwork into. Narrower than the
     * stored [MedicalReport.reportCategory], which only ever says "prescription" or "other" and so
     * cannot separate a blood test from a discharge summary.
     */
    enum class RecordKind { PRESCRIPTION, LAB, OTHER }

    private val LAB_TYPE_SIGNALS = listOf(
        "lab", "haemogram", "hemogram", "cbc", "blood count", "profile", "panel", "urine",
        "biochem", "pathology", "haematolog", "hematolog", "electrolyte", "protein", "lipid",
        "renal", "liver", "kidney", "thyroid", "hba1c", "glycated", "prothrombin", "fibrinogen",
        "serum", "culture", "biopsy", "assay"
    )

    /**
     * Which bucket a report belongs to. A prescription is whatever was filed as one; a lab report
     * is recognised by CARRYING measured parameters first and by its printed type second, since
     * "PROTEINS (SERUM)" and "BIOCHEMISTRY REPORT" are unmistakably lab work while matching no
     * fixed list of names. Everything else — discharge summaries, consultation notes, echoes and
     * scans — is OTHER.
     */
    fun recordKindOf(report: MedicalReport): RecordKind {
        val type = (report.reportType ?: "").trim().lowercase()
        if (report.reportCategory == "prescription" || type.contains("prescription")) {
            return RecordKind.PRESCRIPTION
        }
        val hasMeasuredValues = (report.testResults?.parameters?.size ?: 0) > 0
        if (hasMeasuredValues || LAB_TYPE_SIGNALS.any { type.contains(it) }) return RecordKind.LAB
        return RecordKind.OTHER
    }

    /**
     * Groups reports by the document they were extracted from, preserving the order they arrive in.
     *
     * One scanned PDF routinely yields several reports — a haemogram, a biochemistry panel, a
     * urine routine — and on a flat list they look like unrelated records that happen to share a
     * date, with nothing to say they came from the same page set until you open one. Reports from
     * one scan share their stored page files, which is what identifies the document here.
     *
     * A report with no stored pages (imported, restored, or manually entered) is keyed by its own
     * id so it stands alone: keyed on empty paths, every such report would collapse into one
     * meaningless group. The patient is part of the key too, so the theoretical case of one
     * document covering two people can't merge them.
     */
    fun groupBySourceDocument(reports: List<MedicalReport>): List<List<MedicalReport>> {
        val groups = LinkedHashMap<String, MutableList<MedicalReport>>()
        for (report in reports) {
            val pages = report.imagePaths.filter { it.isNotBlank() }
            val key = if (pages.isEmpty()) "report:${report.id}"
                else "doc:${report.patientName.orEmpty().trim().lowercase()}|${pages.joinToString("|")}"
            groups.getOrPut(key) { mutableListOf() }.add(report)
        }
        return groups.values.toList()
    }

    /**
     * The yyyy-MM-dd a report speaks for: its printed date, or the day it was scanned when the
     * document carried no readable date. Used to decide which of two prescriptions supersedes the
     * other, so a later script wins on clinical recency rather than on the order the user happened
     * to catalogue their paperwork in.
     */
    internal fun currencyOf(report: MedicalReport): String =
        report.reportDate?.takeIf { it.isNotBlank() } ?: report.createdAt.take(10)

    private fun buildMedicationHistory(reports: List<MedicalReport>): List<MedicationHistory> {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        // Ordered oldest-first by the PRINTED report date, so dosage changes are read in the order
        // the doctor actually prescribed them. Scan time only breaks ties and stands in for a
        // document whose own date could not be read.
        //
        // This used to order by scan time alone, on the grounds that printed dates were often
        // mis-read. The cost of that was worse than the problem it avoided: scanning an OLD
        // document late — a June discharge summary catalogued after August's prescription — made
        // the stale script current and drove the reminders from it, and no amount of reprocessing
        // could correct it, because reprocessing preserves createdAt. A later script superseding
        // an earlier one is the actual clinical rule, and date extraction is now specified in far
        // more detail (see OcrEngine.buildPrompt's DATES section) than when that trade was made.
        val chrono = reports.sortedWith(compareBy({ currencyOf(it) }, { it.createdAt }))
        // Keyed by MedName.canonicalKey so the SAME drug written differently across scans
        // ("Tab. Concor" vs "Concor 5mg") is ONE medicine, not two duplicated reminders.
        val patientMed = mutableMapOf<String, MutableMap<String, MutableList<MedPoint>>>()
        // Best display label seen for each "patient|canonicalKey" (form prefix dropped, strength kept).
        val displayName = mutableMapOf<String, String>()
        val latestCurrency = mutableMapOf<String, String>()

        for (r in chrono) {
            // Only reports that carry medicines (prescriptions) can change medication
            // status. Lab/scan reports say nothing about medicines — without this guard,
            // scanning a newer blood report marked every medicine "Discontinued".
            // Gson (portable import) and Room both bypass Kotlin null-safety, so a Medication's
            // "non-null" String fields can actually be null at runtime — guard every access.
            if (r.medications.none { !it.name.isNullOrBlank() }) continue
            val patient = r.patientName ?: "Unknown Patient"
            val date = r.reportDate ?: r.createdAt          // shown to the user
            val currency = currencyOf(r)                     // decides which prescription is current
            if ((latestCurrency[patient] ?: "") <= currency) latestCurrency[patient] = currency
            val medMap = patientMed.getOrPut(patient) { mutableMapOf() }
            for (m in r.medications) {
                if (m.name.isNullOrBlank()) continue
                val key = MedName.canonicalKey(m.name)
                val clean = MedName.cleanDisplay(m.name.trim())
                // Prefer the richest label for display: one that carries a strength (a digit) over
                // one that doesn't. When BOTH carry a digit, the latest-scanned one wins (this
                // loop runs oldest->newest) rather than the longer string — an older discharge
                // summary's per-DOSE amount ("Acitrom 0.5mg", i.e. half of a 1mg tablet) can read
                // longer than a newer prescription's actual tablet strength ("Acitrom 1mg"), and
                // the newer, more authoritative script should win regardless of string length.
                val dnKey = "$patient|$key"
                val prevName = displayName[dnKey]
                if (prevName == null ||
                    (clean.any { it.isDigit() } && !prevName.any { it.isDigit() }) ||
                    clean.any { it.isDigit() }
                ) displayName[dnKey] = clean
                medMap.getOrPut(key) { mutableListOf() }.add(
                    MedPoint(r.id, m.dosage.orEmpty().ifEmpty { "1 tablet" }, m.frequency.orEmpty(), m.duration ?: "",
                        m.isOptional, m.weeklySchedule ?: emptyList(), m.notes ?: "", date, currency,
                        m.startDate, m.endDate, m.intervalDays)
                )
            }
        }

        val out = mutableListOf<MedicationHistory>()
        for ((patient, medMap) in patientMed) {
            val latest = latestCurrency[patient] ?: ""
            for ((medKey, list) in medMap) {
                val medName = displayName["$patient|$medKey"] ?: medKey
                if (list.isEmpty()) continue
                val current = list.last()
                var previous: MedPoint? = null
                if (list.size > 1) {
                    for (k in list.size - 2 downTo 0) {
                        if (list[k].dosage != current.dosage || list[k].frequency != current.frequency) { previous = list[k]; break }
                    }
                    if (previous == null) previous = list[list.size - 2]
                }
                // A medicine is "Discontinued" only when a MORE RECENTLY SCANNED prescription omitted
                // it — i.e. the doctor's newer script dropped it. Not when an out-of-order printed date
                // makes it look old. EXCEPTION: a medicine still inside its own resolved endDate isn't
                // discontinued just because a later, narrower-scope prescription didn't repeat it (e.g.
                // a weekly 4-dose course from a discharge summary, mid-course when a chronic-meds-only
                // follow-up script is scanned) — it stays Active/Scheduled until that window closes.
                val stillInOwnWindow = current.endDate != null && current.endDate >= today
                val isOmitted = current.currency < latest && !stillInOwnWindow
                val notYetStarted = current.startDate != null && current.startDate > today
                val status = when {
                    isOmitted -> "Discontinued"
                    notYetStarted -> "Scheduled"
                    previous != null && (previous.dosage != current.dosage || previous.frequency != current.frequency) -> "Changed"
                    else -> "Active"
                }
                out.add(
                    MedicationHistory(
                        patientName = patient,
                        medicineName = medName,
                        currentDosage = current.dosage,
                        currentFrequency = current.frequency,
                        currentDuration = current.duration,
                        previousDosage = previous?.dosage ?: "",
                        previousFrequency = previous?.frequency ?: "",
                        status = status,
                        lastUpdated = current.date,
                        reportId = current.reportId,
                        isOptional = current.isOptional,
                        weeklySchedule = current.weeklySchedule,
                        notes = current.notes,
                        currentStartDate = current.startDate,
                        currentEndDate = current.endDate,
                        currentIntervalDays = current.intervalDays
                    )
                )
            }
        }
        return out
    }

    /**
     * The canonical trend category for a parameter, or null if it should NOT be trended
     * (excluded by the AI, or a non-numeric reading masquerading under a numeric test's name).
     * Shared so [resolveStandardUnits] and [buildHealthSummary] categorise identically.
     */
    private fun trendCategoryOf(p: TestParameter): String? {
        if (p.name.isBlank()) return null
        // AI classified this at scan time (sees full report/panel context) — trust it when
        // present. Reports scanned before that existed fall back to the keyword heuristics.
        if (p.excludeFromTrend == true) return null
        // Normalize BOTH paths through canonicalParamName: the AI's own trendCategory is free text
        // ("Serum Sodium", "S. Creatinine"), so without this the same test fragments into separate
        // lines depending on which report it came from.
        var canon = canonicalParamName(p.trendCategory?.takeIf { it.isNotBlank() } ?: p.name)
        // A urine dipstick's semi-quantitative "++" (or "Negative"/"Trace") can share a bare
        // name like "Glucose" with no "urine" qualifier — never let a non-numeric reading merge
        // onto a numeric mg/dL line regardless of spelling (defense-in-depth vs excludeFromTrend).
        if (canon == "Blood Sugar" && p.value.toFloatOrNull() == null) canon = p.name.trim()
        return canon
    }

    /**
     * The unit each trendable test should be standardized to: the FIRST non-blank unit seen for
     * it in chronological order. Callers persist this (see AppSettings) so it's locked once and
     * later readings in a different unit get converted to it. Derived from the full report set.
     */
    fun resolveStandardUnits(reports: List<MedicalReport>): Map<String, String> {
        val chrono = reports.sortedBy { it.reportDate ?: it.createdAt }
        val out = linkedMapOf<String, String>()
        for (r in chrono) {
            for (p in r.testResults?.parameters ?: emptyList()) {
                val canon = trendCategoryOf(p) ?: continue
                val unit = p.unit.trim()
                if (unit.isNotEmpty() && !out.containsKey(canon)) out[canon] = unit
            }
        }
        return out
    }

    /**
     * Parses a printed reference range into numeric (low, high) bounds, or nulls where a bound is
     * open. Handles the common lab spellings: "70-100", "70 – 100 mg/dL", "<200", "> 40",
     * "Up to 5.5", "Less than 200". Returns (null, null) for non-numeric ranges (e.g. "Negative").
     */
    private fun parseRefRange(raw: String?): Pair<Float?, Float?> {
        val s = raw?.trim()?.lowercase() ?: return null to null
        if (s.isEmpty()) return null to null
        // First number(s) found, ignoring any trailing unit text.
        val nums = Regex("[-+]?\\d*\\.?\\d+").findAll(s).map { it.value.toFloatOrNull() }.filterNotNull().toList()
        return when {
            s.startsWith("<") || s.startsWith("less than") || s.startsWith("up to") || s.startsWith("upto") || s.startsWith("below") ->
                null to nums.firstOrNull()
            s.startsWith(">") || s.startsWith("greater than") || s.startsWith("more than") || s.startsWith("above") || s.startsWith("at least") ->
                nums.firstOrNull() to null
            // A hyphen/dash/"to" between two numbers is a closed range.
            nums.size >= 2 && Regex("\\d\\s*(?:-|–|—|to)\\s*\\d").containsMatchIn(s) ->
                nums[0] to nums[1]
            else -> null to null // single bare number with no direction — can't place a band safely
        }
    }

    /** Formats a converted value without noisy trailing zeros (e.g. 8.0 -> "8", 0.4400 -> "0.44"). */
    private fun fmtNum(v: Float): String =
        if (v == v.toLong().toFloat()) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    fun buildHealthSummary(
        patientName: String,
        reports: List<MedicalReport>,
        standardUnits: Map<String, String> = emptyMap()
    ): HealthSummary {
        if (reports.isEmpty()) {
            return HealthSummary("No reports found for this patient in the selected period.", emptyList(), emptyList(), emptyList())
        }
        val chrono = reports.sortedBy { it.reportDate ?: it.createdAt }

        // Parameter trends (canonicalised so the same test groups across reports).
        // Guard: at most ONE value per test per report, so a single report can't produce
        // multiple points on the same line (e.g. Hemoglobin vs MCH collapsing together).
        val paramMap = linkedMapOf<String, MutableList<TrendDataPoint>>()
        val seenPerReport = HashSet<String>()
        for (r in chrono) {
            val date = (r.reportDate ?: r.createdAt).split("T")[0]
            for (p in r.testResults?.parameters ?: emptyList()) {
                val canon = trendCategoryOf(p) ?: continue
                if (!seenPerReport.add("$canon|${r.id}")) continue // already have this test for this report
                val context = p.trendCondition?.takeIf { it.isNotBlank() }
                    ?: (if (canon == "Blood Sugar") bloodSugarContext(p.name) else "")

                // Standardize this reading's unit to the test's locked standard so the line is
                // comparable across labs. Same unit (ignoring notation) → just adopt the standard
                // spelling. Different unit with a verified factor → convert. Different unit with
                // no factor → keep as printed and let the chart flag it (never guess).
                val rawUnit = p.unit.trim()
                val std = standardUnits[canon]
                var value = p.value; var unit = rawUnit
                var origValue = ""; var origUnit = ""; var converted = false
                if (std != null && rawUnit.isNotEmpty()) {
                    if (UnitConverter.canonicalizeUnitString(rawUnit) == UnitConverter.canonicalizeUnitString(std)) {
                        unit = std // identical unit, normalize spelling (e.g. "mg %" == "mg/dL")
                    } else {
                        val num = p.value.toFloatOrNull()
                        val conv = if (num != null) UnitConverter.convert(canon, num, rawUnit, std) else null
                        if (conv != null) {
                            value = fmtNum(conv); unit = std
                            origValue = p.value; origUnit = rawUnit; converted = true
                        }
                        // else: no verified factor — leave value/unit as printed, converted=false.
                    }
                }
                // Normal-range band: parse the report's reference range, then express it in the
                // same unit as the plotted value (converting the bounds too when the value was).
                var (refLow, refHigh) = parseRefRange(p.referenceRange)
                if (converted && (refLow != null || refHigh != null)) {
                    refLow = refLow?.let { UnitConverter.convert(canon, it, rawUnit, unit) }
                    refHigh = refHigh?.let { UnitConverter.convert(canon, it, rawUnit, unit) }
                }
                paramMap.getOrPut(canon) { mutableListOf() }.add(
                    TrendDataPoint(date, value, unit, p.status ?: "", r.id, context, origValue, origUnit, converted, refLow, refHigh)
                )
            }
        }
        val trends = paramMap.map { (name, points) ->
            var trend = "stable"
            val numeric = points.filter { it.value.toFloatOrNull() != null }
            if (numeric.size >= 2) {
                val first = numeric.first().value.toFloat(); val last = numeric.last().value.toFloat()
                val ls = numeric.last().status.lowercase(); val ps = numeric.first().status.lowercase()
                trend = when {
                    ls == "normal" && ps != "normal" -> "improving"
                    ls != "normal" && ps == "normal" -> "worsening"
                    Math.abs(last - first) / (Math.abs(first).takeIf { it != 0f } ?: 1f) < 0.05f -> "stable"
                    last < first -> "decreasing"
                    else -> "increasing"
                }
            }
            ParameterTrend(name, points, trend)
        }.sortedWith(compareBy(
            { if (isKeyParameter(it.name)) 0 else 1 },               // key tests first
            { KEY_PARAMETER_ORDER.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } },
            { it.name }
        ))

        // Medication timeline — built only from reports that carry medicines, so a lab
        // report between two prescriptions doesn't show every medicine as "removed".
        val timeline = mutableListOf<MedicationTimelineEntry>()
        var prev = mapOf<String, Medication>()
        for (r in chrono.filter { rep -> rep.medications.any { it.name.isNotBlank() } }) {
            val date = (r.reportDate ?: r.createdAt).split("T")[0]
            val cur = r.medications.associateBy { it.name }
            val added = r.medications.filter { !prev.containsKey(it.name) }.map { it.name }
            val removed = prev.keys.filter { !cur.containsKey(it) }
            val changed = r.medications.filter { prev[it.name]?.let { p -> p.dosage != it.dosage || p.frequency != it.frequency } == true }
                .map { "${it.name} (${prev[it.name]?.dosage} → ${it.dosage})" }
            if (added.isNotEmpty() || removed.isNotEmpty() || changed.isNotEmpty() || timeline.isEmpty()) {
                timeline.add(MedicationTimelineEntry(date, r.id, r.reportCategory, added, removed, changed,
                    r.medications.map { Medication(it.name, it.dosage, it.frequency) }))
            }
            prev = cur
        }

        // Active flags
        val flags = mutableListOf<String>()
        for (r in reports.reversed()) {
            r.healthInsights?.prescriptionAlignment?.flags?.forEach { if (it !in flags) flags.add(it) }
            if (flags.size >= 5) break
        }

        val worsening = trends.filter { it.trend == "worsening" }
        val improving = trends.filter { it.trend == "improving" }
        val activeMeds = timeline.lastOrNull()?.activeMedicines?.joinToString { it.name } ?: ""
        val narrative = buildString {
            append("${reports.size} report(s) recorded for $patientName. ")
            if (improving.isNotEmpty()) append("${improving.joinToString { it.name }} improving. ")
            if (worsening.isNotEmpty()) append("${worsening.joinToString { it.name }} worsening — consult your doctor. ")
            if (improving.isEmpty() && worsening.isEmpty() && trends.isNotEmpty()) append("Parameters remain stable. ")
            if (activeMeds.isNotEmpty()) append("Active medicines: $activeMeds.")
        }

        return HealthSummary(narrative, trends, timeline, flags)
    }
}
