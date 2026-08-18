package com.healthdecoder.app.local

import android.content.Context
import android.util.Log
import com.healthdecoder.app.ai.DashboardEngine
import com.healthdecoder.app.ai.DateResolver
import com.healthdecoder.app.ai.MedicalEngine
import com.healthdecoder.app.ai.OcrEngine
import com.healthdecoder.app.ai.ScanExtraction
import com.healthdecoder.app.ai.UnitConverter
import com.healthdecoder.app.backup.BackupManager
import com.healthdecoder.app.backup.BackupSync
import com.healthdecoder.app.backup.ExportManager
import com.healthdecoder.app.reminder.MedicineReminderManager
import com.healthdecoder.app.reminder.MedicineScheduleStore
import com.healthdecoder.app.reminder.AppointmentStore
import com.healthdecoder.app.reminder.AppointmentSchedule
import com.healthdecoder.app.reminder.AppointmentReminderManager
import com.healthdecoder.app.model.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thrown when a scan is recognized as a report that is already saved, so it is not
 * added again. [existing] is the report it duplicates.
 */
class DuplicateReportException(val existing: MedicalReport) : Exception(
    "Duplicate of report ${existing.id} (${existing.patientName}, ${existing.reportDate})"
)

/**
 * The single on-device data source the UI talks to. Replaces the Retrofit/PC-server API:
 * it stores records locally, runs the AI/aggregation engines on-device, and makes a local
 * backup after every change (which BackupSync later pushes to the cloud when online).
 * All methods are suspend + run on Dispatchers.IO.
 */
object LocalRepository {

    private val gson = Gson()
    private val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun today() = isoDate.format(Date())
    private fun nowIso() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

    private fun afterWrite(context: Context) {
        // Uses the persisted auto-backup password (opt-in, see SecureKeyManager) if the user
        // has set one — otherwise unprotected, exactly as before. These backups are the ones
        // that actually leave the device via BackupSync's cloud upload, so a password set only
        // on the manual Export button wouldn't otherwise cover them.
        runCatching { BackupManager.createLocalBackup(context, SecureKeyManager.getBackupPassword(context)) }
        runCatching { BackupSync.syncPending(context) }
    }

    /** Wipes ALL on-device data (records, images, pending tests, logs, cached analysis). */
    suspend fun clearAllData(context: Context) = withContext(Dispatchers.IO) {
        LocalStore.closeDatabase() // release the SQLite file before deleting it
        val dir = LocalStore.recordsDir(context)
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * Wipes EVERYTHING this app stores about the user on this device — used by "Delete Account"
     * (ProfileScreen), which pairs this with deleting the server account. Broader than
     * [clearAllData]: that only covers `recordsDir` (the encrypted SQLite DB — reports, pending
     * tests, medication logs — plus images/sources/detailed-analysis files); family profiles and
     * medicine/appointment reminders live in separate SharedPreferences and are cleared here too,
     * along with cancelling their scheduled alarms so nothing fires for data that no longer exists.
     *
     * The delete-account dialog promises the user TOTAL removal, so this also clears everything
     * that merely *identifies* them rather than just their clinical data — see
     * [AppSettings.clearAllPersonalData] for that list (linked mailbox, biometric session,
     * patient-keyed trend units, ...) — plus the two secrets kept outside AppSettings: the Gmail
     * OAuth token and the IMAP password in SecureKeyManager. Leaving any of those behind would
     * make the promise false.
     */
    suspend fun clearAllLocalData(context: Context) = withContext(Dispatchers.IO) {
        clearAllData(context) // DB (reports, pending tests, med logs) + images/sources/detailed_analysis files

        MedicineReminderManager.cancelAll(context)
        AppointmentStore.loadAll(context).forEach { AppointmentReminderManager.cancel(context, it.id) }
        // The mailbox link is about to be wiped, so stop the daily scan that depends on it.
        com.healthdecoder.app.reminder.EmailScanReminderManager.cancel(context)

        MedicineScheduleStore.clearAll(context)
        AppointmentStore.clearAll(context)

        SecureKeyManager.setEmailToken(context, null)
        SecureKeyManager.setImapPassword(context, null)
        // Covers family profiles, active patient, session + biometric tokens, linked mailbox
        // config, trend units and the export marker in one pass.
        AppSettings.clearAllPersonalData(context)
    }

    // ── Reports ───────────────────────────────────────────────────────────────
    suspend fun getReports(context: Context): List<MedicalReport> = withContext(Dispatchers.IO) {
        LocalStore.getReports(context)
    }

    suspend fun getReport(context: Context, id: String): MedicalReport? = withContext(Dispatchers.IO) {
        LocalStore.getReport(context, id)
    }

    /** IDs of reports matching the query via full-text search (patient, type, comments, OCR text). */
    suspend fun searchReportIds(context: Context, query: String): Set<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) emptySet()
        else LocalStore.searchReports(context, query).map { it.id }.toSet()
    }

    suspend fun deleteReport(context: Context, id: String) = withContext(Dispatchers.IO) {
        LocalStore.deleteReport(context, id)
        detailedCacheFile(context, id).delete()
        afterWrite(context)
    }

    /** Already-stored duplicate reports (newer copies of an earlier report). */
    suspend fun findDuplicateReports(context: Context): List<MedicalReport> = withContext(Dispatchers.IO) {
        LocalStore.findStoredDuplicates(context)
    }

    /** Deletes all stored duplicate reports, keeping the original of each group. Returns how many were removed. */
    suspend fun deleteDuplicateReports(context: Context): Int = withContext(Dispatchers.IO) {
        val duplicates = LocalStore.findStoredDuplicates(context)
        for (dup in duplicates) {
            LocalStore.deleteReport(context, dup.id)
            detailedCacheFile(context, dup.id).delete()
        }
        if (duplicates.isNotEmpty()) afterWrite(context)
        duplicates.size
    }

    /**
     * Scans one or more page images and saves them on-device. The pages may bundle
     * SEVERAL distinct reports (e.g. CBC + lipid profile + 2D Echo): each becomes its
     * own record with its own name and its own date, resolved by [DateResolver] from the
     * labeled dates on the page (Reported date for sample labs, Procedure date for
     * scans/echo, visit date for prescriptions — never the printed date).
     *
     * Duplicate protection: an exact re-import of the same file/photo is rejected before
     * the AI extraction runs; a re-scan of the same paper (same patient, date, category
     * with near-identical text) is skipped per section. If EVERYTHING was already saved,
     * [DuplicateReportException] is thrown and nothing is stored.
     *
     * @param sources original imported files (bytes, name, mime) preserved so the user can download them.
     * @return the saved reports, one per distinct report found in the scan.
     */
    suspend fun saveScan(
        context: Context,
        pages: List<Pair<ByteArray, String>>,
        sources: List<Triple<ByteArray, String, String>>,
        localOcrText: String,
        scanType: String,
        reportCategory: String,
        patientNameOverride: String = ""
    ): List<MedicalReport> = withContext(Dispatchers.IO) {
        // Stage 1: exact duplicate — the same photo/file bytes were saved before.
        val incomingHashes = (pages.map { it.first } + sources.map { it.first })
            .map { LocalStore.sha256(it) }
            .distinct()
        LocalStore.findReportByAnyHash(context, incomingHashes)?.let { throw DuplicateReportException(it) }

        val extraction = OcrEngine.scan(context, pages, localOcrText, scanType, reportCategory)
        val sections = extraction.reports.ifEmpty { listOf(extraction.merged()) }
        Log.i("ScanDiag", "extracted patient=${extraction.patientName} sections=${sections.size} " +
            sections.joinToString(" | ") { "[type=${it.reportType} name=${it.reportName} meds=${it.medications.size}]" })
        val detectedName = patientNameOverride.trim()
            .ifBlank { extraction.patientName ?: sections.firstOrNull()?.patientName ?: "Unknown Patient" }
        // Match the detected name to an existing patient/family member by name tokens so a longer
        // printed name (e.g. "Bhagwat Jayant Shriram") files under the existing short one ("Jayant")
        // instead of fragmenting into a new patient. A user-typed override is trusted as-is.
        val patientName = if (patientNameOverride.isNotBlank()) detectedName
            else matchToExistingPatient(context, detectedName)

        // The scanned page images and original files are stored ONCE and shared by every
        // report in the bundle (deletion is reference-aware, see LocalStore.deleteReport).
        val bundleId = LocalStore.newId()
        val imagePaths = pages.mapIndexed { index, (bytes, _) ->
            LocalStore.saveImage(context, if (index == 0) bundleId else "${bundleId}_$index", bytes)
        }
        val imagePath = imagePaths.firstOrNull() ?: ""
        val sourceFiles = sources.mapIndexed { index, (bytes, name, mime) ->
            SourceFile(
                path = LocalStore.saveSourceFile(context, bundleId, index, name, bytes),
                name = name,
                mimeType = mime
            )
        }

        val saved = mutableListOf<MedicalReport>()
        var firstDuplicate: MedicalReport? = null
        // Bulk imports: run comparison/insights locally instead of per-report AI calls,
        // otherwise a many-report scan bursts past the free tier's requests-per-minute cap.
        val allowPerReportAi = sections.size <= 2

        for ((index, section) in sections.withIndex()) {
            // The date on THIS report's page, chosen by label priority and sanity checked.
            val reportDate = DateResolver.resolve(section, reportCategory) ?: today()
            val sectionType = section.reportName?.takeIf { it.isNotBlank() }
                ?: section.reportType
                ?: if (scanType == "prescription") "Prescription" else "Other"
            // Let the DOCUMENT decide its category, not the scan entry-point. A discharge summary or
            // lab report scanned via "Medicine Scan" must still file as a report (so it appears under
            // Records and its insights aren't mislabelled as a prescription); only a document the AI
            // actually recognises as a prescription is filed as one. Falls back to the caller's
            // category when the type is unknown.
            val category = classifyCategory(sectionType, section.reportType, reportCategory, scanType)
            val sectionText = section.rawText?.takeIf { it.isNotBlank() } ?: extraction.rawText ?: ""

            // Stage 2: this individual report was already saved from an earlier scan.
            val dup = LocalStore.findContentDuplicate(context, patientName, reportDate, category, sectionText)
            if (dup != null) {
                Log.i("ScanDiag", "section '$sectionType' skipped as duplicate of ${dup.id} " +
                    "(patient=$patientName date=$reportDate category=$category)")
                if (firstDuplicate == null) firstDuplicate = dup
                continue
            }

            var report = MedicalReport(
                id = if (index == 0) bundleId else LocalStore.newId(),
                patientName = patientName,
                reportDate = reportDate,
                reportType = sectionType,
                extractedText = sectionText,
                comments = section.comments ?: "",
                medications = dedupeMedications(resolveMedicationDates(section.medications, reportDate)),
                imagePath = imagePath,
                imagePaths = imagePaths,
                sourceFiles = sourceFiles,
                createdAt = nowIso(),
                testResults = section.testResults ?: TestResults(),
                comparisonResult = null,
                reportCategory = category,
                healthInsights = null,
                pageHashes = incomingHashes
            )

            val previous = findPrevious(context, patientName, category, reportDate, excludeId = report.id)
            val comparison = MedicalEngine.compareReports(context, report, previous, allowAi = allowPerReportAi)
            val insights = MedicalEngine.healthInsights(context, report, allowAi = allowPerReportAi)
            report = report.copy(comparisonResult = comparison, healthInsights = insights)

            LocalStore.upsertReport(context, report)

            // Auto-add recommended tests, then auto-resolve matching pending tests.
            for (t in section.recommendedTests) {
                if (t.testName.isNotBlank()) {
                    LocalStore.upsertPendingTest(context, PendingTest(
                        id = LocalStore.newId(), patientName = patientName,
                        testName = t.testName, dueDate = t.dueDate, status = "Pending",
                        resolvedReportId = null, createdAt = nowIso()
                    ))
                }
            }
            // Auto-add follow-up doctor visits from a discharge summary's "FOLLOW UP" section.
            addFollowUpAppointments(context, section.followUps, reportDate, patientName)

            autoResolvePending(context, report)
            saved.add(report)
            Log.i("ScanDiag", "SAVED report id=${report.id} type=$sectionType category=$category " +
                "patient=$patientName date=$reportDate meds=${report.medications.size} followUps=${section.followUps.size}")
        }

        if (saved.isEmpty()) {
            // Every report in this scan already exists — clean up the files we stored.
            (imagePaths + sourceFiles.map { it.path }).forEach { runCatching { File(it).delete() } }
            throw DuplicateReportException(firstDuplicate ?: LocalStore.getReports(context).first())
        }

        // A freshly scanned prescription should revive its reminder even if the user had previously
        // deleted it (a new script = the medicine is wanted again) — un-dismiss each medicine so the
        // reminders screen re-seeds it.
        for (r in saved) for (m in r.medications) if (m.name.isNotBlank())
            MedicineScheduleStore.clearDismissed(context, m.name, r.patientName ?: patientName)

        afterWrite(context)
        saved
    }

    /**
     * Decides whether a scanned document is a "prescription" or a general report ("other"), based on
     * what the document actually IS (the AI-detected type) rather than which scan tab the user opened.
     * A discharge summary, consultation note or lab report contains medicines too, but it belongs under
     * Records — only a document the AI recognises as an actual prescription/medicine slip is filed as
     * one. Falls back to the caller's category, then the scan type, when the type is unknown.
     */
    private fun classifyCategory(
        displayType: String,
        aiReportType: String?,
        callerCategory: String,
        scanType: String
    ): String {
        val t = (aiReportType ?: displayType).trim().lowercase()
        // A clinical/diagnostic document belongs under Records even though it also lists medicines —
        // e.g. a "Discharge Summary & Prescription" is a discharge summary, not a prescription slip.
        val reportSignals = listOf(
            "discharge", "summary", "consult", "report", "lab", "profile", "scan", "echo",
            "sonograph", "ultrasound", "x-ray", "xray", "ecg", "ekg", "mri", "ct ", "doppler",
            "pathology", "haemogram", "hemogram", "cbc", "panel", "urine", "biochem", "endocrin",
            "electrocardiogram", "lipid", "renal", "liver", "kidney", "thyroid", "glycated",
            "hba1c", "fibrinogen", "prothrombin", "electrolyte", "haematolog", "hematolog",
            "blood count", "biopsy", "card"
        )
        val looksLikeReport = reportSignals.any { t.contains(it) }
        // Only a document that IS a plain prescription/medicine slip (not one that merely mentions
        // the word) is filed as a prescription.
        val isPrescription = !looksLikeReport &&
            (t == "prescription" || t == "rx" || t == "medicine" || t == "medication order" ||
             t == "prescription slip" || t == "medicine prescription")
        val knownReport = looksLikeReport ||
            (t.isNotBlank() && t != "other" && t != "unknown" && !isPrescription)
        return when {
            isPrescription -> "prescription"
            knownReport    -> "other"
            callerCategory.isNotBlank() -> callerCategory
            scanType == "prescription" -> "prescription"
            else -> "other"
        }
    }

    /**
     * Self-healing migration: older reports were filed by which scan tab was open, not by what the
     * document actually is, so lab reports and discharge summaries could be stored as "prescription".
     * Re-derives each report's category from its detected type and rewrites the ones that disagree.
     * Idempotent — once everything matches it writes nothing. Returns how many were corrected.
     */
    private fun reclassifyMiscategorized(context: Context, reports: List<MedicalReport>): Int {
        var fixed = 0
        for (r in reports) {
            val current = r.reportCategory ?: ""
            val correct = classifyCategory(r.reportType ?: "", r.reportType, current, "")
            if (!correct.equals(current, ignoreCase = true)) {
                LocalStore.upsertReport(context, r.copy(reportCategory = correct))
                fixed++
            }
        }
        return fixed
    }

    /**
     * Turns a discharge summary's "FOLLOW UP" entries into doctor appointments. Each visit's date is
     * the explicit printed date if given, otherwise the report date plus its "after N days" offset.
     * De-duplicated by doctor+date so re-scanning the same summary doesn't pile up copies.
     */
    private fun addFollowUpAppointments(
        context: Context,
        followUps: List<com.healthdecoder.app.ai.FollowUp>,
        reportDate: String,
        patientName: String?
    ) {
        if (followUps.isEmpty()) return
        var addedAny = false
        for (f in followUps) {
            val doctor = f.doctorName?.trim().orEmpty()
            val specialty = f.specialty?.trim().orEmpty()
            val notes = f.notes?.trim().orEmpty()
            // Where it can be placed on the calendar: an explicit date, else reportDate + afterDays.
            val date = f.date?.takeIf { isValidIsoDate(it) }
                ?: f.afterDays?.let { addDaysIso(reportDate, it) }
                ?: continue
            val label = when {
                doctor.isNotBlank() -> doctor
                specialty.isNotBlank() -> specialty
                else -> "Follow-up visit"
            }
            val place = listOf(f.place?.trim().orEmpty(), specialty, notes)
                .firstOrNull { it.isNotBlank() }.orEmpty()
            val added = AppointmentStore.addIfAbsent(
                context,
                AppointmentSchedule(
                    doctorName = label,
                    date = date,
                    time = "10:00",
                    place = place,
                    isRecurring = false,
                    recurrence = "None",
                    hour = 10,
                    minute = 0,
                    patientName = patientName.orEmpty()
                )
            )
            if (added) addedAny = true
        }
        if (addedAny) AppointmentReminderManager.scheduleAll(context)
    }

    private fun isValidIsoDate(s: String): Boolean =
        Regex("""\d{4}-\d{2}-\d{2}""").matches(s.trim())

    /** [baseIso] ("YYYY-MM-DD") advanced by [days]; null if the base date can't be parsed. */
    private fun addDaysIso(baseIso: String, days: Int): String? = try {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = java.util.Calendar.getInstance()
        cal.time = fmt.parse(baseIso.trim()) ?: throw IllegalArgumentException()
        cal.add(java.util.Calendar.DAY_OF_YEAR, days)
        fmt.format(cal.time)
    } catch (e: Exception) { null }

    /**
     * Resolves each medication's startDate/endDate to absolute ISO strings, the same way
     * [addFollowUpAppointments] resolves a follow-up's afterDays: an explicit date the AI already
     * read off the page wins; otherwise startAfterDays/durationDays are added to [reportDate].
     * When [Medication.intervalDays] is set ("once every 15 days") but no start date could be
     * resolved, [reportDate] itself becomes the anchor — an every-N-days cadence is meaningless
     * without a day to count from, and the visit/scan date is the only date guaranteed to exist.
     * Idempotent — a medication whose dates are already resolved ISO strings passes through
     * unchanged, so it's safe to call again on a previously-resolved list.
     */
    private fun resolveMedicationDates(medications: List<Medication>, reportDate: String): List<Medication> =
        medications.map { m ->
            var start = m.startDate?.takeIf { isValidIsoDate(it) } ?: m.startAfterDays?.let { addDaysIso(reportDate, it) }
            val end = m.endDate?.takeIf { isValidIsoDate(it) } ?: m.durationDays?.let { addDaysIso(reportDate, it) }
            if (start == null && m.intervalDays != null && m.intervalDays > 0) start = reportDate
            if (start == m.startDate && end == m.endDate) m else m.copy(startDate = start, endDate = end)
        }

    /** Resolves a scanned patient name to an existing patient/family member when the names clearly
     *  refer to the same person (shared name tokens), so a longer printed name doesn't fragment into
     *  a new patient. Returns the detected name unchanged when there's no confident match. */
    private fun matchToExistingPatient(context: Context, detected: String): String {
        val d = detected.trim()
        if (d.isEmpty() || d.equals("Unknown Patient", ignoreCase = true)) return d
        val existing = (LocalStore.getReports(context).mapNotNull { it.patientName?.takeIf { n -> n.isNotBlank() } } +
            AppSettings.getFamilyProfilesRaw(context).map { it.name }).filter { it.isNotBlank() }.distinct()
        existing.firstOrNull { it.equals(d, ignoreCase = true) }?.let { return it }
        fun tokens(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.toSet()
        val dt = tokens(d)
        if (dt.isEmpty()) return d
        // Existing name whose tokens are fully inside the detected name (or vice versa): "jayant" ⊆
        // "bhagwat jayant shriram". Prefer the one sharing the most tokens.
        return existing.filter {
            val et = tokens(it)
            et.isNotEmpty() && (dt.containsAll(et) || et.containsAll(dt))
        }.maxByOrNull { tokens(it).intersect(dt).size } ?: d
    }

    suspend fun updateReport(context: Context, id: String, req: ReportUpdateRequest): MedicalReport? = withContext(Dispatchers.IO) {
        val existing = LocalStore.getReport(context, id) ?: return@withContext null
        val category = req.reportCategory ?: if (req.reportType == "Prescription") "prescription" else (existing.reportCategory ?: "other")
        var updated = existing.copy(
            patientName = req.patientName,
            reportDate = req.reportDate,
            reportType = req.reportType,
            comments = req.comments,
            medications = dedupeMedications(req.medications),
            extractedText = req.extractedText,
            testResults = req.testResults ?: existing.testResults,
            reportCategory = category
        )
        val previous = findPrevious(context, updated.patientName, category, updated.reportDate ?: today(), excludeId = id)
        val comparison = MedicalEngine.compareReports(context, updated, previous)
        val insights = MedicalEngine.healthInsights(context, updated)
        updated = updated.copy(comparisonResult = comparison, healthInsights = insights)
        LocalStore.upsertReport(context, updated)
        detailedCacheFile(context, id).delete() // invalidate cached detailed analysis
        afterWrite(context)
        updated
    }

    /**
     * Stores a report's file(s) WITHOUT running any AI analysis — the "upload only" path, for
     * archiving old records without spending API calls. Creates one report per upload, flagged
     * [MedicalReport.analyzed] = false so the detail screen can offer to analyze it later (via
     * [reprocessReport], which reads these same stored images). Exact-duplicate uploads (same
     * bytes) are rejected. Patient name / date / category come from the user since nothing is
     * extracted; a blank name falls back to "Unknown Patient".
     */
    suspend fun saveUploadOnly(
        context: Context,
        pages: List<Pair<ByteArray, String>>,
        sources: List<Triple<ByteArray, String, String>>,
        reportCategory: String,
        patientNameOverride: String = "",
        reportDate: String? = null
    ): MedicalReport = withContext(Dispatchers.IO) {
        val incomingHashes = (pages.map { it.first } + sources.map { it.first })
            .map { LocalStore.sha256(it) }.distinct()
        LocalStore.findReportByAnyHash(context, incomingHashes)?.let { throw DuplicateReportException(it) }

        val bundleId = LocalStore.newId()
        val imagePaths = pages.mapIndexed { index, (bytes, _) ->
            LocalStore.saveImage(context, if (index == 0) bundleId else "${bundleId}_$index", bytes)
        }
        val sourceFiles = sources.mapIndexed { index, (bytes, name, mime) ->
            SourceFile(path = LocalStore.saveSourceFile(context, bundleId, index, name, bytes), name = name, mimeType = mime)
        }
        val category = reportCategory.ifBlank { "other" }
        val report = MedicalReport(
            id = bundleId,
            patientName = patientNameOverride.trim().ifBlank { "Unknown Patient" },
            reportDate = reportDate?.takeIf { it.isNotBlank() } ?: today(),
            reportType = if (category == "prescription") "Prescription" else "Uploaded",
            extractedText = "",
            comments = "",
            medications = emptyList(),
            imagePath = imagePaths.firstOrNull() ?: "",
            imagePaths = imagePaths,
            sourceFiles = sourceFiles,
            createdAt = nowIso(),
            testResults = TestResults(),
            comparisonResult = null,
            reportCategory = category,
            healthInsights = null,
            pageHashes = incomingHashes,
            analyzed = false
        )
        LocalStore.upsertReport(context, report)
        afterWrite(context)
        report
    }

    /**
     * Re-runs OCR/AI extraction on a report's originally scanned image(s) and refreshes its
     * test parameters, medications and insights. Serves two cases: a first scan that came back
     * incomplete (e.g. the AI API was briefly unavailable), and analyzing an "upload only" report
     * on demand — either way it marks the report [MedicalReport.analyzed] = true.
     */
    suspend fun reprocessReport(context: Context, id: String, allowAi: Boolean = true): MedicalReport? = withContext(Dispatchers.IO) {
        val existing = LocalStore.getReport(context, id) ?: return@withContext null
        val pages = existing.imagePaths.mapNotNull { path ->
            val file = File(path)
            if (!file.exists()) return@mapNotNull null
            file.readBytes() to mimeForPath(path)
        }
        if (pages.isEmpty()) return@withContext existing

        val category = existing.reportCategory ?: "other"
        val scanType = if (category == "prescription" || existing.reportType == "Prescription") "prescription" else "report"
        val extraction = OcrEngine.scan(context, pages, "", scanType, category)
        val sections = extraction.reports.ifEmpty { listOf(extraction.merged()) }
        val section = sections.firstOrNull { DateResolver.resolve(it, category) == existing.reportDate } ?: sections.first()

        // A report saved by OcrEngine's localFallback() never had its document actually read —
        // its date/type/category were guessed from which scan tab was open, not the content, and
        // its comments are just that placeholder. Now that a real analysis succeeded, all of
        // those are safe (and necessary) to overwrite instead of preserving the guesses.
        val wasDegraded = existing.comments?.trim() == OcrEngine.DEGRADED_MARKER
        val sectionType = section.reportName?.takeIf { it.isNotBlank() }
            ?: section.reportType
            ?: existing.reportType
            ?: "Other"
        val correctedCategory = if (wasDegraded) classifyCategory(sectionType, section.reportType, category, scanType) else category
        val correctedDate = if (wasDegraded) (DateResolver.resolve(section, correctedCategory) ?: existing.reportDate) else existing.reportDate

        // Parameters/medications aren't user-editable today, so overwriting them is safe;
        // comments/raw text ARE user-editable, so only fill those in if still blank (a degraded
        // report's placeholder comment doesn't count as real user content, so it's replaced too).
        var updated = existing.copy(
            testResults = section.testResults ?: existing.testResults,
            medications = dedupeMedications(resolveMedicationDates(
                section.medications.ifEmpty { existing.medications }, correctedDate ?: today()
            )),
            comments = if (wasDegraded) section.comments else (existing.comments?.takeIf { it.isNotBlank() } ?: section.comments),
            extractedText = existing.extractedText?.takeIf { it.isNotBlank() } ?: section.rawText,
            // An upload-only report becomes a full, analyzed report once this succeeds. For a
            // report the AI detected a type/date for, adopt those too if the upload had placeholders.
            analyzed = true,
            reportType = if (wasDegraded || existing.reportType == "Uploaded") sectionType else existing.reportType,
            reportCategory = correctedCategory,
            reportDate = correctedDate
        )
        // Comparison/insights are enrichment, not correctness — each is a separate AI call, so a
        // bulk fix-up (see fixDegradedReports) skips them to spend the day's quota on actually
        // re-reading documents instead of burning 2/3 of it on summaries. They fill in later, the
        // next time this report is normally opened/edited.
        val previous = findPrevious(context, updated.patientName, correctedCategory, updated.reportDate ?: today(), excludeId = id)
        val comparison = MedicalEngine.compareReports(context, updated, previous, allowAi = allowAi)
        val insights = MedicalEngine.healthInsights(context, updated, allowAi = allowAi)
        updated = updated.copy(comparisonResult = comparison, healthInsights = insights)
        LocalStore.upsertReport(context, updated)
        // Analyzing a (possibly upload-only) discharge summary should also add its follow-up visits
        // and revive reminders for its medicines, just like a fresh scan does.
        addFollowUpAppointments(context, section.followUps, updated.reportDate ?: today(), updated.patientName)
        for (m in updated.medications) if (m.name.isNotBlank())
            MedicineScheduleStore.clearDismissed(context, m.name, updated.patientName ?: "")
        Log.i("ScanDiag", "REPROCESSED id=$id meds=${updated.medications.size} followUps=${section.followUps.size}")
        detailedCacheFile(context, id).delete() // invalidate cached detailed analysis
        afterWrite(context)
        updated
    }

    /** Reports saved by OcrEngine's localFallback() — never actually analyzed, so their date/type/
     *  category are guesses rather than read from the document. Surfaced so the user can fix all of
     *  them in one action instead of finding and re-opening each one individually. */
    suspend fun findDegradedReports(context: Context): List<MedicalReport> = withContext(Dispatchers.IO) {
        LocalStore.getReports(context).filter { it.comments?.trim() == OcrEngine.DEGRADED_MARKER }
    }

    /** Result of [fixDegradedReports]: how many were corrected, and how many are still stuck
     *  because the AI backend rejected the retry too (e.g. the daily quota is still exhausted) —
     *  those keep their placeholder comment so a later retry finds them again. */
    data class FixDegradedResult(val fixed: Int, val remaining: Int, val stoppedReason: String? = null)

    /**
     * Re-analyzes every report still carrying the localFallback placeholder, using each report's
     * already-stored images — no need to re-pick files from where they were originally scanned.
     * Stops the moment the backend rejects a retry (rather than burning through the rest with
     * guaranteed failures), since that almost always means the same quota/outage is still in
     * effect for all the others too; whatever's left over stays flagged for the next attempt.
     */
    suspend fun fixDegradedReports(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): FixDegradedResult = withContext(Dispatchers.IO) {
        val targets = findDegradedReports(context)
        var fixed = 0
        var stoppedReason: String? = null
        for ((index, report) in targets.withIndex()) {
            onProgress(index, targets.size)
            try {
                reprocessReport(context, report.id, allowAi = false)
                fixed++
            } catch (e: com.healthdecoder.app.ai.BackendAiClient.BackendAiException) {
                stoppedReason = e.message
                break
            }
        }
        onProgress(targets.size, targets.size)
        FixDegradedResult(fixed = fixed, remaining = targets.size - fixed, stoppedReason = stoppedReason)
    }

    // A multi-panel document (e.g. one 7-page lab report bundling CBC + PT/INR + electrolytes)
    // used to be split into AI requests of this many pages before today's fix raised the chunk
    // size — a bundle scanned with MORE pages than this was liable to have its tail chunk
    // (e.g. just the last page) silently fail to parse and vanish with no trace, dropping that
    // panel's data entirely. Bundles at or under this size were always sent in one request and
    // are not at risk from this specific bug.
    private const val OLD_CHUNK_BOUNDARY = 6

    /** Every report saved from the same original scan shares the same stored page images —
     *  that's the only reliable way left to tell which saved reports came from the same
     *  document, since the split panels don't otherwise reference each other. */
    private fun bundleKeyOf(r: MedicalReport): String = r.imagePaths.joinToString("|")

    /**
     * Finds scan bundles whose original page count exceeded the old chunk boundary — each such
     * bundle is a candidate for having silently lost a panel (see [OLD_CHUNK_BOUNDARY]). Grouped
     * by shared stored files so [recoverMissingPanels] can re-derive panels for the whole bundle
     * at once rather than per already-saved report.
     */
    suspend fun findAtRiskBundles(context: Context): List<List<MedicalReport>> = withContext(Dispatchers.IO) {
        val checked = AppSettings.getCheckedRecoveryBundles(context)
        LocalStore.getReports(context)
            .filter { it.imagePaths.size > OLD_CHUNK_BOUNDARY && it.imagePaths.all { p -> File(p).exists() } }
            .groupBy { bundleKeyOf(it) }
            .filterKeys { it !in checked }
            .values.toList()
    }

    /** Result of [recoverMissingPanels]: how many bundles were checked, how many previously-
     *  missing panels were found and saved, and why it stopped early if it did. */
    data class RecoveryResult(val bundlesChecked: Int, val panelsRecovered: Int, val stoppedReason: String? = null)

    /**
     * Re-reads each at-risk bundle's ORIGINAL stored pages (now processed with the larger,
     * fixed chunk size — see [AppSettings.getScanChunkPages]) and saves any panel that isn't
     * already present as its own report, using the same content-based duplicate check
     * [saveScan] uses — so panels that already succeeded the first time are correctly
     * recognized and skipped, and only genuinely-missing ones (e.g. a dropped electrolytes
     * panel) get added. Does NOT use [saveScan] itself: its Stage-1 exact-file-hash duplicate
     * check would reject the whole resubmission on the very first hash match, before the AI
     * ever got a chance to re-derive the missing panel.
     */
    suspend fun recoverMissingPanels(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): RecoveryResult = withContext(Dispatchers.IO) {
        val bundles = findAtRiskBundles(context)
        var recovered = 0
        var stoppedReason: String? = null
        for ((index, bundle) in bundles.withIndex()) {
            onProgress(index, bundles.size)
            val rep = bundle.first()
            val bundleKey = bundleKeyOf(rep)
            // What this bundle ALREADY has, so a fresh (non-deterministic) re-extraction of an
            // already-successful panel isn't mistaken for a new one — comparing against the
            // AI's own wording of the SAME panel (fuzzy text similarity) is exactly what broke
            // here: two independent extractions of the same CBC panel don't come back byte-for-
            // byte identical, so a whole-document Jaccard-similarity duplicate check can miss
            // the match and save it again. Comparing against what TYPE of panel this bundle
            // already contains is a much more reliable signal for this specific case.
            val existingTypes = bundle.map { (it.reportType ?: "").trim().lowercase() }.filter { it.isNotBlank() }
            val patientName = rep.patientName ?: "Unknown Patient"
            val pages = rep.imagePaths.mapNotNull { path ->
                val file = File(path)
                if (!file.exists()) return@mapNotNull null
                file.readBytes() to mimeForPath(path)
            }
            if (pages.isEmpty()) continue
            val category = rep.reportCategory ?: "other"
            val scanType = if (category == "prescription") "prescription" else "report"
            try {
                val extraction = OcrEngine.scan(context, pages, "", scanType, category)
                for (section in extraction.reports.ifEmpty { listOf(extraction.merged()) }) {
                    val reportDate = DateResolver.resolve(section, category) ?: continue
                    val sectionType = section.reportName?.takeIf { it.isNotBlank() } ?: section.reportType ?: continue
                    val sectionCategory = classifyCategory(sectionType, section.reportType, category, scanType)
                    val sectionText = section.rawText?.takeIf { it.isNotBlank() } ?: extraction.rawText ?: ""
                    val newType = sectionType.trim().lowercase()
                    val alreadyHave = existingTypes.any { it == newType || it.contains(newType) || newType.contains(it) }
                    if (alreadyHave) continue

                    var recoveredReport = MedicalReport(
                        id = LocalStore.newId(),
                        patientName = patientName,
                        reportDate = reportDate,
                        reportType = sectionType,
                        extractedText = sectionText,
                        comments = section.comments ?: "",
                        medications = dedupeMedications(resolveMedicationDates(section.medications, reportDate)),
                        imagePath = rep.imagePath,
                        imagePaths = rep.imagePaths,
                        sourceFiles = rep.sourceFiles,
                        createdAt = nowIso(),
                        testResults = section.testResults ?: TestResults(),
                        reportCategory = sectionCategory,
                        pageHashes = rep.pageHashes
                    )
                    val previous = findPrevious(context, recoveredReport.patientName, sectionCategory, reportDate, excludeId = recoveredReport.id)
                    // allowAi = false: this is a bulk background recovery pass, possibly across
                    // many bundles — spend the AI budget on re-deriving the missing DATA, not on
                    // fresh comparison/insight summaries the user hasn't asked to see yet.
                    val comparison = MedicalEngine.compareReports(context, recoveredReport, previous, allowAi = false)
                    val insights = MedicalEngine.healthInsights(context, recoveredReport, allowAi = false)
                    recoveredReport = recoveredReport.copy(comparisonResult = comparison, healthInsights = insights)
                    LocalStore.upsertReport(context, recoveredReport)
                    addFollowUpAppointments(context, section.followUps, reportDate, recoveredReport.patientName)
                    recovered++
                    Log.i("ScanDiag", "RECOVERED panel type=$sectionType category=$sectionCategory date=$reportDate " +
                        "patient=${recoveredReport.patientName} bundleOf=${rep.id}")
                }
                // Fully processed (even if it added nothing) — never re-flag or re-spend AI
                // calls on this exact bundle again.
                AppSettings.markRecoveryBundleChecked(context, bundleKey)
            } catch (e: com.healthdecoder.app.ai.BackendAiClient.BackendAiException) {
                // Left UNCHECKED on purpose: a quota/outage failure isn't a verdict on this
                // bundle, so it should be retried once the backend is available again.
                stoppedReason = e.message
                break
            }
        }
        onProgress(bundles.size, bundles.size)
        // Independent-extraction re-runs can occasionally still slip a near-identical panel
        // past the type-match check above (e.g. two differently-worded names for the same
        // panel) — sweep for and remove any resulting duplicates rather than leaving them.
        val duplicatesRemoved = deleteDuplicateReports(context)
        afterWrite(context)
        RecoveryResult(bundlesChecked = bundles.size, panelsRecovered = (recovered - duplicatesRemoved).coerceAtLeast(0), stoppedReason = stoppedReason)
    }

    private fun mimeForPath(path: String): String = when {
        path.endsWith(".png", true) -> "image/png"
        path.endsWith(".webp", true) -> "image/webp"
        path.endsWith(".pdf", true) -> "application/pdf"
        else -> "image/jpeg"
    }

    private fun findPrevious(context: Context, patient: String?, category: String, date: String, excludeId: String): MedicalReport? =
        LocalStore.findPreviousReport(context, patient, category, date, excludeId)

    /** Drops repeated medicines (same drug AND same power, e.g. a bracketed-salt-annotation
     *  variant of a name already seen at the same strength on this scan), keeping the first. Two
     *  different powers of the same drug on one prescription are NOT duplicates and both stay. */
    private fun dedupeMedications(meds: List<Medication>): List<Medication> {
        val seen = HashSet<String>()
        return meds.filter { m ->
            val key = MedName.strengthKey(m.name, m.dosage)
            key.isNotBlank() && seen.add(key)
        }
    }

    private fun autoResolvePending(context: Context, report: MedicalReport) {
        val raw = (report.extractedText ?: "").lowercase()
        val comments = (report.comments ?: "").lowercase()
        val type = (report.reportType ?: "").lowercase()
        for (pt in LocalStore.getPendingTests(context)) {
            if (pt.patientName != report.patientName || pt.status != "Pending") continue
            val clean = pt.testName.lowercase().replace(Regex("test|profile|check"), "").trim()
            if (clean.length > 2 && (raw.contains(clean) || comments.contains(clean) || type.contains(clean))) {
                LocalStore.upsertPendingTest(context, pt.copy(status = "Completed", resolvedReportId = report.id))
            }
        }
    }

    // ── Patient identity ────────────────────────────────────────────────────
    /**
     * Merges one patient name into another across everything keyed by patient — reports, medicine
     * intake logs, pending tests, reminder schedules and locked trend units — so a single
     * mis-scanned name variant (e.g. "Rajesh Kumr" → "Rajesh Kumar") stops fragmenting that
     * person's history, filter and trend lines. Case-insensitive match on the old name. Returns the
     * number of reports moved. No AI is involved.
     */
    suspend fun mergePatient(context: Context, fromName: String, toName: String): Int = withContext(Dispatchers.IO) {
        val from = fromName.trim()
        val to = toName.trim()
        if (from.isEmpty() || to.isEmpty() || from.equals(to, ignoreCase = true)) return@withContext 0

        // Reports: go through upsert (not a raw UPDATE) so the FTS index and userEmail scoping stay correct.
        val moved = LocalStore.getReports(context).filter { it.patientName.equals(from, ignoreCase = true) }
        for (r in moved) LocalStore.upsertReport(context, r.copy(patientName = to))

        LocalStore.renamePatientRecords(context, from, to)               // med_logs + pending_tests
        MedicineScheduleStore.renamePatient(context, from, to)           // reminder schedules
        MedicineReminderManager.scheduleAll(context)
        AppSettings.migrateTrendUnitsPatient(context, from, to)          // locked trend units

        if (moved.isNotEmpty()) afterWrite(context)
        moved.size
    }

    // ── Family members (persisted, user-managed) ────────────────────────────
    private val familyEmojis = listOf("👤", "🧑", "👩", "👨", "👵", "👴", "🧒", "👧", "👦")

    /**
     * The persisted family list, reconciled with the patient names actually present in reports so a
     * scanned-in person always appears (auto-seeded once, "Self" for the first). A member's `name`
     * is the join key to their records. Persists the reconciled list so ordering/details stick.
     */
    suspend fun familyMembers(context: Context): List<FamilyProfile> = withContext(Dispatchers.IO) {
        val stored = AppSettings.getFamilyProfilesRaw(context).toMutableList()
        val known = stored.map { it.name.trim().lowercase() }.toHashSet()
        val patientNames = LocalStore.getReports(context)
            .mapNotNull { it.patientName?.takeIf { n -> n.isNotBlank() } }.distinct()
        var changed = false
        val currentOwner = AppSettings.getUserEmail(context)
        for (n in patientNames) {
            if (known.add(n.trim().lowercase())) {
                stored.add(FamilyProfile(
                    id = LocalStore.newId(), name = n,
                    relation = if (stored.isEmpty()) "Self" else "Family",
                    avatarEmoji = familyEmojis[stored.size % familyEmojis.size],
                    ownerEmail = currentOwner
                ))
                changed = true
            }
        }
        if (changed) AppSettings.setFamilyProfiles(context, stored)
        // Hide profiles created while signed in as a DIFFERENT account than the current one (or
        // any account, while signed out) — a shared device must not hand one account's family
        // list to the next person who opens the app. Untagged (ownerEmail null) profiles predate
        // this and stay visible to everyone, exactly as before.
        stored.filter { it.ownerEmail == null || it.ownerEmail.equals(currentOwner, ignoreCase = true) }
    }

    /** Adds a new family member (a person you can scan into before any report exists). No-op on a
     *  blank or duplicate name. */
    suspend fun addFamilyMember(
        context: Context, name: String, relation: String, sex: String, dob: String, emoji: String
    ): Boolean = withContext(Dispatchers.IO) {
        val n = name.trim()
        if (n.isEmpty()) return@withContext false
        val list = AppSettings.getFamilyProfilesRaw(context).toMutableList()
        if (list.any { it.name.trim().equals(n, ignoreCase = true) }) return@withContext false
        list.add(FamilyProfile(
            LocalStore.newId(), n, relation.ifBlank { "Family" }, emoji.ifBlank { "👤" }, sex, dob,
            ownerEmail = AppSettings.getUserEmail(context)
        ))
        AppSettings.setFamilyProfiles(context, list)
        true
    }

    /** Edits a member's details. Renaming cascades to all their records (via [mergePatient]) and
     *  follows the active-patient selection. */
    suspend fun updateFamilyMember(
        context: Context, id: String, newName: String, relation: String, sex: String, dob: String, emoji: String
    ) = withContext(Dispatchers.IO) {
        val list = AppSettings.getFamilyProfilesRaw(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return@withContext
        val old = list[idx]
        val to = newName.trim().ifBlank { old.name }
        list[idx] = old.copy(name = to, relation = relation.ifBlank { old.relation },
            avatarEmoji = emoji.ifBlank { old.avatarEmoji }, sex = sex, dateOfBirth = dob)
        AppSettings.setFamilyProfiles(context, list)
        if (!to.equals(old.name, ignoreCase = true)) {
            mergePatient(context, old.name, to) // move their reports/reminders/logs/trend-units
            if (AppSettings.getActivePatient(context).equals(old.name, ignoreCase = true))
                AppSettings.setActivePatient(context, to)
        }
    }

    /** Removes a member from the family list. Their medical records are NOT deleted; if any remain,
     *  the member re-appears on next sync — so the UI should only offer removal for people with no
     *  records (rename/merge is the tool for a mis-scanned duplicate). */
    suspend fun removeFamilyMember(context: Context, id: String) = withContext(Dispatchers.IO) {
        val list = AppSettings.getFamilyProfilesRaw(context).toMutableList()
        val removed = list.firstOrNull { it.id == id }
        list.removeAll { it.id == id }
        AppSettings.setFamilyProfiles(context, list)
        if (removed != null && AppSettings.getActivePatient(context).equals(removed.name, ignoreCase = true))
            AppSettings.setActivePatient(context, null)
    }

    /** How many reports a given patient name has (for guarding family-member removal). */
    suspend fun reportCountFor(context: Context, patientName: String): Int = withContext(Dispatchers.IO) {
        LocalStore.getReports(context).count { it.patientName.equals(patientName, ignoreCase = true) }
    }

    // ── Portable export / import ────────────────────────────────────────────
    /** The distinct patient names in the current account, most-reports-first (for export UI). */
    suspend fun listPatients(context: Context): List<String> = withContext(Dispatchers.IO) {
        LocalStore.getReports(context).mapNotNull { it.patientName?.takeIf { n -> n.isNotBlank() } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }

    /**
     * Writes a portable export zip. [patientName] null = all patients. [onlySinceLastExport] true =
     * only reports created after the last export (delta). [fromDate]/[toDate] (YYYY-MM-DD, inclusive)
     * restrict to a report-date range for a "keep just this window" partial transfer. Returns the
     * file to share, or null if the selection is empty. Advances the delta marker only for a full
     * export (all patients, no date window) so a partial export can't skew "since last time".
     */
    suspend fun exportData(
        context: Context,
        patientName: String?,
        onlySinceLastExport: Boolean,
        fromDate: String? = null,
        toDate: String? = null
    ): java.io.File? = withContext(Dispatchers.IO) {
        val since = if (onlySinceLastExport) AppSettings.getLastExportAt(context) else null
        val from = fromDate?.takeIf { it.isNotBlank() }
        val to = toDate?.takeIf { it.isNotBlank() }
        val selected = LocalStore.getReports(context).filter { r ->
            val d = r.reportDate ?: r.createdAt.substringBefore("T")
            (patientName == null || r.patientName.equals(patientName, ignoreCase = true)) &&
                (since == null || r.createdAt > since) &&
                (from == null || d >= from) &&
                (to == null || d <= to)
        }
        if (selected.isEmpty()) return@withContext null
        // Include the profile(s) (name/relation/sex/DOB) for the exported people so their details
        // travel with the records — just the one member for a per-patient export, else everyone.
        val exportedNames = selected.mapNotNull { it.patientName?.trim()?.lowercase() }.toHashSet()
        val family = familyMembers(context).filter {
            patientName == null || it.name.trim().lowercase() in exportedNames
        }
        val file = ExportManager.export(context, selected, since, patientName, family)
        val isFullExport = patientName == null && from == null && to == null
        if (isFullExport) {
            selected.maxByOrNull { it.createdAt }?.createdAt?.let { AppSettings.setLastExportAt(context, it) }
        }
        file
    }

    /** Merges a portable export into this device (add-or-update by id, never a wipe). No AI is run —
     *  analysis rides inside the file. */
    suspend fun importData(context: Context, uri: android.net.Uri): ExportManager.ImportResult =
        withContext(Dispatchers.IO) {
            val result = ExportManager.import(context, uri)
            if (result.added > 0 || result.updated > 0) afterWrite(context)
            result
        }

    // ── Dashboard / summary ─────────────────────────────────────────────────
    // Scoped to the "active patient" chosen on Home (null = everyone), so Records, Medication
    // Tracker, Reminders and Pending Tests — all of which read this one method — follow the
    // family-member selector together.
    suspend fun getDashboard(context: Context, period: String?): DashboardData = withContext(Dispatchers.IO) {
        val active = AppSettings.getActivePatient(context)
        // Auto-fix any report that was filed under the wrong category (by scan tab rather than by its
        // real document type) before building the dashboard.
        val fixed = reclassifyMiscategorized(context, LocalStore.getReports(context))
        if (fixed > 0) Log.i("ScanDiag", "reclassified $fixed report(s) to their real document type")
        runCatching {
            val names = LocalStore.getReports(context)
                .flatMap { it.testResults?.parameters ?: emptyList() }
                .filter { it.name.isNotBlank() }
                .groupBy { DashboardEngine.canonicalParamName(it.name) }
            Log.i("ParamDiag", "distinctCanonical=${names.size}")
            names.entries.chunked(6).forEach { chunk ->
                Log.i("ParamDiag", chunk.joinToString(" ;; ") { (canon, ps) ->
                    "$canon <= ${ps.map { it.name }.distinct().joinToString("/")} [${ps.firstOrNull()?.unit}]"
                })
            }
        }
        var reports: List<MedicalReport> = filterByPeriod(LocalStore.getReports(context), period)
        var pending: List<PendingTest> = LocalStore.getPendingTests(context)
        if (active != null) {
            reports = reports.filter { it.patientName.equals(active, ignoreCase = true) }
            pending = pending.filter { it.patientName.equals(active, ignoreCase = true) }
        } else {
            // "Everyone" must mean everyone visible to the CURRENT viewer, not literally every
            // record ever stored — otherwise signing out (or switching accounts) would still
            // surface another account's family member's reports/tests via the "Everyone"
            // aggregate, even though that member is correctly hidden from the picker itself.
            val visibleNames = familyMembers(context).map { it.name.trim().lowercase() }.toHashSet()
            reports = reports.filter { it.patientName.isNullOrBlank() || visibleNames.contains(it.patientName!!.trim().lowercase()) }
            pending = pending.filter { visibleNames.contains(it.patientName.trim().lowercase()) }
        }
        DashboardEngine.buildDashboard(reports, pending)
    }

    suspend fun getHealthSummary(context: Context, patientName: String, period: String?): HealthSummary = withContext(Dispatchers.IO) {
        // Standard unit per test: for any test UnitConverter knows, it's fixed by the user's
        // unit-system setting (Conventional/Indian by default, or SI) so it's consistent across
        // patients and independent of which report was scanned first. For tests it doesn't know
        // (no conversion factor anyway), fall back to the first-seen unit — locked so it survives
        // deleting/filtering the report it first came from.
        val all = LocalStore.getReports(context).filter { it.patientName.equals(patientName, true) }
        val system = AppSettings.getUnitSystemEnum(context)
        val derived = DashboardEngine.resolveStandardUnits(all)
        derived.forEach { (canon, unit) ->
            AppSettings.lockTrendStandardUnitIfAbsent(context, "$patientName|$canon", unit)
        }
        val locked = AppSettings.getTrendStandardUnits(context)
        val standardUnits = derived.keys.associateWith { canon ->
            UnitConverter.standardUnitFor(canon, system)
                ?: locked["$patientName|$canon"]
                ?: derived.getValue(canon)
        }
        val reports = filterByPeriod(all, period)
        DashboardEngine.buildHealthSummary(patientName, reports, standardUnits)
    }

    private fun filterByPeriod(reports: List<MedicalReport>, period: String?): List<MedicalReport> {
        if (period == null || period == "all") return reports
        val months = mapOf("1m" to 1, "3m" to 3, "6m" to 6, "1y" to 12, "2y" to 24)[period] ?: return reports
        val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MONTH, -months) }
        val cutoff = isoDate.format(cal.time)
        return reports.filter { (it.reportDate ?: it.createdAt) >= cutoff }
    }

    // ── Pending tests ─────────────────────────────────────────────────────────
    suspend fun createPendingTest(context: Context, patientName: String, testName: String, dueDate: String?): PendingTest = withContext(Dispatchers.IO) {
        val pt = PendingTest(LocalStore.newId(), patientName, testName, dueDate?.ifBlank { null }, "Pending", null, nowIso())
        LocalStore.upsertPendingTest(context, pt); afterWrite(context); pt
    }

    suspend fun deletePendingTest(context: Context, id: String) = withContext(Dispatchers.IO) {
        LocalStore.deletePendingTest(context, id); afterWrite(context)
    }

    // ── Medication logs & edits ────────────────────────────────────────────────
    suspend fun logMedicationIntake(context: Context, patientName: String, medicineName: String, actionType: String, frequency: String?) = withContext(Dispatchers.IO) {
        LocalStore.addMedLog(context, MedLogEntry(LocalStore.newId(), patientName, medicineName, actionType, frequency, null, nowIso()))
        afterWrite(context)
    }

    suspend fun getMedicationLogs(context: Context, patientName: String, medicineName: String): List<MedLogEntry> = withContext(Dispatchers.IO) {
        LocalStore.getMedLogs(context, patientName, medicineName)
    }

    /** [new] wins when explicitly provided (blank clears the field to null); a null [new] means
     *  "caller didn't touch this field", so [old] is kept — same nullable-means-unset convention
     *  the dosage/frequency/etc params in this file already use, extended to support clearing a
     *  date since "" isn't itself a valid date the way it's a valid dosage/notes string. */
    private fun resolveOptionalDate(new: String?, old: String?): String? =
        if (new == null) old else new.trim().ifBlank { null }

    suspend fun updateMedicationDetails(context: Context, reportId: String, medicineName: String, patientName: String,
                                        dosage: String?, frequency: String?, duration: String?, isOptional: Boolean?, weeklySchedule: List<String>?, notes: String?,
                                        startDate: String? = null, endDate: String? = null) = withContext(Dispatchers.IO) {
        val report = LocalStore.getReport(context, reportId) ?: return@withContext
        val meds = report.medications.toMutableList()
        var found = false
        for (i in meds.indices) {
            if (meds[i].name.trim().equals(medicineName.trim(), true)) {
                meds[i] = meds[i].copy(
                    dosage = dosage ?: meds[i].dosage, frequency = frequency ?: meds[i].frequency,
                    duration = duration ?: meds[i].duration, isOptional = isOptional ?: meds[i].isOptional,
                    weeklySchedule = weeklySchedule ?: meds[i].weeklySchedule, notes = notes ?: meds[i].notes,
                    startDate = resolveOptionalDate(startDate, meds[i].startDate),
                    endDate = resolveOptionalDate(endDate, meds[i].endDate))
                found = true
            }
        }
        if (!found) meds.add(Medication(medicineName, dosage ?: "", frequency ?: "", duration ?: "", isOptional ?: false, weeklySchedule ?: listOf("Everyday"), notes ?: "",
            startDate = resolveOptionalDate(startDate, null), endDate = resolveOptionalDate(endDate, null)))
        LocalStore.upsertReport(context, report.copy(medications = meds))
        LocalStore.addMedLog(context, MedLogEntry(LocalStore.newId(), patientName, medicineName, "UPDATE_DETAILS", frequency, "Dosage: ${dosage ?: ""}", nowIso()))
        afterWrite(context)
    }

    /**
     * Corrects a medicine (e.g. a name mis-read from a handwritten prescription) and propagates
     * the fix everywhere it lives for that patient, so the user only edits it once:
     *
     *  - **Name**: renamed in EVERY report of the patient that carries the old name (a repeated
     *    mis-scan is fixed in one action), and the reminder schedule + intake logs are re-keyed to
     *    the new name so neither orphans.
     *  - **Dosage / frequency / duration / schedule / notes**: applied to the specific [reportId]
     *    instance only, so the per-report medication timeline (dosage changes over time) stays
     *    intact; the reminder schedule's shown dosage/frequency is refreshed to match.
     *
     * The medication tracker/history is derived from reports, so it updates automatically.
     */
    suspend fun updateMedicineEverywhere(
        context: Context, reportId: String, patientName: String, oldName: String, newName: String,
        dosage: String?, frequency: String?, duration: String?, isOptional: Boolean?,
        weeklySchedule: List<String>?, notes: String?,
        startDate: String? = null, endDate: String? = null
    ) = withContext(Dispatchers.IO) {
        val from = oldName.trim()
        val to = newName.trim().ifBlank { from }
        val nameChanged = !to.equals(from, ignoreCase = true)

        val patientReports = LocalStore.getReports(context)
            .filter { it.patientName.equals(patientName, ignoreCase = true) }
        for (r in patientReports) {
            var changed = false
            val meds = r.medications.map { m ->
                if (!m.name.trim().equals(from, ignoreCase = true)) return@map m
                changed = true
                if (r.id == reportId) {
                    // The edited occurrence: apply the new name AND the field edits.
                    m.copy(
                        name = to,
                        dosage = dosage ?: m.dosage, frequency = frequency ?: m.frequency,
                        duration = duration ?: m.duration, isOptional = isOptional ?: m.isOptional,
                        weeklySchedule = weeklySchedule ?: m.weeklySchedule, notes = notes ?: m.notes,
                        startDate = resolveOptionalDate(startDate, m.startDate),
                        endDate = resolveOptionalDate(endDate, m.endDate)
                    )
                } else if (nameChanged) {
                    // Other reports: only the identity (name) propagates; their own dosage/history stays.
                    m.copy(name = to)
                } else m
            }
            if (changed) LocalStore.upsertReport(context, r.copy(medications = meds))
        }

        // Re-key the reminder schedule to the new name and refresh its dosage/frequency.
        MedicineScheduleStore.rename(context, patientName, from, to, dosage, frequency)
        MedicineReminderManager.scheduleAll(context)

        // Move intake history to the new name so it isn't orphaned.
        if (nameChanged) LocalStore.renameMedLogs(context, patientName, from, to)
        LocalStore.addMedLog(context, MedLogEntry(
            LocalStore.newId(), patientName, to, "UPDATE_DETAILS", frequency,
            if (nameChanged) "Renamed from \"$from\"" else "Dosage: ${dosage ?: ""}", nowIso()
        ))
        afterWrite(context)
    }

    suspend fun bulkDeleteMedications(context: Context, items: List<MedicationBulkItem>) = withContext(Dispatchers.IO) {
        items.groupBy { it.reportId }.forEach { (reportId, list) ->
            val report = LocalStore.getReport(context, reportId) ?: return@forEach
            val names = list.map { it.medicineName.trim().lowercase() }
            LocalStore.upsertReport(context, report.copy(medications = report.medications.filterNot { it.name.trim().lowercase() in names }))
        }
        afterWrite(context)
    }

    suspend fun bulkUpdateMedications(context: Context, items: List<MedicationBulkItem>) = withContext(Dispatchers.IO) {
        items.groupBy { it.reportId }.forEach { (reportId, list) ->
            val report = LocalStore.getReport(context, reportId) ?: return@forEach
            val meds = report.medications.map { m ->
                val match = list.firstOrNull { it.medicineName.trim().equals(m.name.trim(), true) }
                if (match != null) m.copy(
                    frequency = match.frequency ?: m.frequency,
                    weeklySchedule = match.weeklySchedule ?: m.weeklySchedule,
                    isOptional = match.isOptional ?: m.isOptional
                ) else m
            }
            LocalStore.upsertReport(context, report.copy(medications = meds))
        }
        afterWrite(context)
    }

    // ── Chat ────────────────────────────────────────────────────────────────
    suspend fun chat(context: Context, request: ChatRequest): ChatResponse = withContext(Dispatchers.IO) {
        val all = LocalStore.getReports(context)
        val reports = when {
            !request.reportId.isNullOrBlank() -> all.filter { it.id == request.reportId }
            !request.patientName.isNullOrBlank() -> all.filter { it.patientName.equals(request.patientName, true) }.take(15)
            else -> all.take(15)
        }
        val (answer, source) = MedicalEngine.chat(context, request.question, reports, request.history, request.imagePath)
        ChatResponse(answer, source)
    }

    // ── Detailed analysis (cached on device) ──────────────────────────────────
    private fun detailedCacheFile(context: Context, id: String): File =
        File(LocalStore.detailedAnalysisDir(context), "$id.json")

    suspend fun getDetailedAnalysis(context: Context, reportId: String, refresh: Boolean): DetailedAnalysis = withContext(Dispatchers.IO) {
        val cache = detailedCacheFile(context, reportId)
        if (!refresh && cache.exists()) {
            runCatching { gson.fromJson(cache.readText(), DetailedAnalysis::class.java) }.getOrNull()?.let {
                if (it.sections.isNotEmpty()) return@withContext it.copy(cached = true)
            }
        }
        val report = LocalStore.getReport(context, reportId)
            ?: return@withContext DetailedAnalysis(summary = "Report not found.")
        val analysis = MedicalEngine.detailedAnalysis(context, report).copy(generatedAt = nowIso())
        runCatching { cache.writeText(gson.toJson(analysis)) }
        analysis.copy(cached = false)
    }

    // ── Compare two images (no save) ───────────────────────────────────────────
    suspend fun compare(context: Context, img1: ByteArray, mime1: String, scanType1: String, cat1: String,
                        img2: ByteArray, mime2: String, scanType2: String, cat2: String): CompareResponse = withContext(Dispatchers.IO) {
        val e1 = OcrEngine.scan(context, listOf(img1 to mime1), "", scanType1, cat1).merged()
        val e2 = OcrEngine.scan(context, listOf(img2 to mime2), "", scanType2, cat2).merged()
        val r1 = toScanned(e1, cat1, "Report 1")
        val r2 = toScanned(e2, cat2, "Report 2")
        // Reuse comparison by wrapping the extractions in temporary reports.
        val temp1 = MedicalReport(id = "cmp1", patientName = r1.patientName, reportDate = r1.reportDate,
            reportType = r1.reportType, extractedText = r1.rawText, comments = r1.comments, medications = r1.medications,
            imagePath = "", createdAt = nowIso(), testResults = r1.testResults, reportCategory = cat1)
        val temp2 = MedicalReport(id = "cmp2", patientName = r2.patientName, reportDate = r2.reportDate,
            reportType = r2.reportType, extractedText = r2.rawText, comments = r2.comments, medications = r2.medications,
            imagePath = "", createdAt = nowIso(), testResults = r2.testResults, reportCategory = cat2)
        val cmp = MedicalEngine.compareReports(context, temp2, temp1)
        CompareResponse(r1, r2, cmp)
    }

    private fun toScanned(e: ScanExtraction, category: String, fallbackName: String) = ScannedReportData(
        patientName = e.patientName ?: fallbackName,
        reportDate = validDate(e.reportDate) ?: today(),
        reportType = e.reportType ?: "Report",
        reportCategory = category,
        medications = e.medications,
        testResults = e.testResults ?: TestResults(),
        comments = e.comments ?: "",
        rawText = e.rawText ?: ""
    )

    // ── Medicine name correction ──────────────────────────────────────────────
    /** Renames a medicine in a specific report (fixes OCR misreads). */
    suspend fun renameMedicine(context: Context, reportId: String, oldName: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val report = LocalStore.getReport(context, reportId) ?: return@withContext false
        val meds = report.medications.map { m ->
            if (m.name.trim().equals(oldName.trim(), true)) m.copy(name = newName.trim()) else m
        }
        if (meds == report.medications) return@withContext false // no change
        LocalStore.upsertReport(context, report.copy(medications = meds))
        afterWrite(context)
        true
    }

    // ── helpers ────────────────────────────────────────────────────────────────
    private fun validDate(d: String?): String? {
        if (d.isNullOrBlank()) return null
        return try { isoDate.parse(d); d } catch (e: Exception) { null }
    }
}
