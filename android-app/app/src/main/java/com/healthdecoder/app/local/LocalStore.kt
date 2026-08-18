package com.healthdecoder.app.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.healthdecoder.app.local.db.MedicalDatabase
import com.healthdecoder.app.local.db.ReportSummary
import com.healthdecoder.app.model.MedLogEntry
import com.healthdecoder.app.model.MedicalReport
import com.healthdecoder.app.model.PendingTest
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * On-device store for all medical records, backed by Room/SQLite. Everything lives under
 * a single folder (filesDir/records) so the whole dataset can be zipped for backup in
 * one step:
 *
 *   records/
 *     medical_records.db  – SQLite database (reports, pending tests, med logs, FTS index)
 *     images/<id>.jpg     – captured report images
 *     sources/…           – preserved original imports (PDF/Word/images)
 *
 * Legacy JSON files (reports.json, pending_tests.json, med_logs.json) from older builds
 * are imported into the database on first open and renamed to *.imported as a fallback.
 *
 * All calls do synchronous IO — invoke them from Dispatchers.IO.
 */
object LocalStore {

    private val gson: Gson = GsonBuilder().setLenient().create()

    @Volatile
    private var database: MedicalDatabase? = null

    // ── Folder / file layout ────────────────────────────────────────────────
    fun recordsDir(context: Context): File =
        File(context.filesDir, "records").apply { if (!exists()) mkdirs() }

    fun imagesDir(context: Context): File =
        File(recordsDir(context), "images").apply { if (!exists()) mkdirs() }

    fun sourcesDir(context: Context): File =
        File(recordsDir(context), "sources").apply { if (!exists()) mkdirs() }

    /** Per-report cached AI "detailed analysis" JSON (deep-dive), keyed by report id. Lives under
     *  records/ so it rides along in backups and portable exports. */
    fun detailedAnalysisDir(context: Context): File =
        File(recordsDir(context), "detailed_analysis").apply { if (!exists()) mkdirs() }

    // ── Database lifecycle ──────────────────────────────────────────────────
    private fun db(context: Context): MedicalDatabase {
        database?.let { return it }
        synchronized(this) {
            database?.let { return it }
            val dbFile = File(recordsDir(context), "medical_records.db")

            // Initialize SQLCipher native libraries
            net.sqlcipher.database.SQLiteDatabase.loadLibs(context)
            val passphrase = SecureKeyManager.getDatabasePassphrase(context)
            val factory = net.sqlcipher.database.SupportFactory(passphrase)

            var instance: MedicalDatabase? = null
            try {
                instance = Room.databaseBuilder(
                    context.applicationContext,
                    MedicalDatabase::class.java,
                    dbFile.absolutePath
                )
                    .openHelperFactory(factory)
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    .addMigrations(MedicalDatabase.MIGRATION_1_2, MedicalDatabase.MIGRATION_2_3, MedicalDatabase.MIGRATION_3_4, MedicalDatabase.MIGRATION_4_5)
                    .build()

                // Force open the database to verify passphrase decryption is correct
                instance.openHelper.writableDatabase
            } catch (e: Exception) {
                e.printStackTrace()
                // Recover from decryption / key mismatch errors by recreating database
                runCatching { instance?.close() }
                dbFile.delete()

                instance = Room.databaseBuilder(
                    context.applicationContext,
                    MedicalDatabase::class.java,
                    dbFile.absolutePath
                )
                    .openHelperFactory(factory)
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    .addMigrations(MedicalDatabase.MIGRATION_1_2, MedicalDatabase.MIGRATION_2_3, MedicalDatabase.MIGRATION_3_4, MedicalDatabase.MIGRATION_4_5)
                    .build()
            }

            importLegacyJson(context, instance)
            database = instance
            return instance
        }
    }

    fun getDatabase(context: Context): MedicalDatabase = db(context)

    /**
     * Closes the database and drops the singleton. MUST be called before the records
     * folder is replaced or wiped (backup restore, clear-all-data), otherwise the open
     * connection would keep writing into deleted/replaced files.
     */
    fun closeDatabase() {
        synchronized(this) {
            database?.close()
            database = null
        }
    }

    // ── Legacy JSON import (one-time migration from the file-based store) ───
    private fun <T> readLegacyList(file: File, type: java.lang.reflect.Type): List<T> {
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText()
            if (text.isBlank()) emptyList()
            else gson.fromJson<List<T>>(text, type) ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun importLegacyJson(context: Context, db: MedicalDatabase) {
        val dir = recordsDir(context)
        val reportsFile = File(dir, "reports.json")
        val pendingFile = File(dir, "pending_tests.json")
        val logsFile = File(dir, "med_logs.json")
        if (!reportsFile.exists() && !pendingFile.exists() && !logsFile.exists()) return

        try {
            db.runInTransaction {
                readLegacyList<MedicalReport>(
                    reportsFile, object : TypeToken<List<MedicalReport>>() {}.type
                ).takeIf { it.isNotEmpty() }?.let { db.reportDao().insertAll(it) }

                readLegacyList<PendingTest>(
                    pendingFile, object : TypeToken<List<PendingTest>>() {}.type
                ).takeIf { it.isNotEmpty() }?.let { db.pendingTestDao().insertAll(it) }

                readLegacyList<MedLogEntry>(
                    logsFile, object : TypeToken<List<MedLogEntry>>() {}.type
                ).takeIf { it.isNotEmpty() }?.let { db.medLogDao().insertAll(it) }
            }
            // Keep the originals as a fallback, renamed so the import never re-runs.
            for (f in listOf(reportsFile, pendingFile, logsFile)) {
                if (f.exists()) f.renameTo(File(dir, f.name + ".imported"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── Reports ─────────────────────────────────────────────────────────────
    // Room and Gson (portable import) both bypass Kotlin null-safety, so the declared-non-null
    // String fields on Medication AND TestParameter can be null at runtime — enough to crash a
    // screen that calls e.g. dosage.isNotBlank() (Records) or unit.trim()/value.toFloatOrNull()
    // (Trends/dashboard). Normalize every report the moment it leaves the store so no downstream
    // screen has to defend against it, whatever the data's origin.
    private fun MedicalReport.sanitized(): MedicalReport {
        val meds = if (medications.isEmpty()) medications else medications.map { m ->
            m.copy(
                name = m.name.orEmpty(),
                dosage = m.dosage.orEmpty(),
                frequency = m.frequency.orEmpty(),
                weeklySchedule = m.weeklySchedule ?: emptyList()
            )
        }
        val results = testResults?.let { t ->
            t.copy(
                parameters = t.parameters.map { p ->
                    p.copy(
                        name = p.name.orEmpty(),
                        value = p.value.orEmpty(),
                        unit = p.unit.orEmpty(),
                        referenceRange = p.referenceRange.orEmpty()
                        // status/trendCategory/trendCondition are already nullable-typed
                    )
                },
                findings = t.findings.filterNotNull()
            )
        }
        return if (meds === medications && results === testResults) this
        else copy(medications = meds, testResults = results)
    }

    fun getReports(context: Context): MutableList<MedicalReport> =
        db(context).reportDao().getAllFiltered(AppSettings.getUserEmail(context) ?: "")
            .map { it.sanitized() }.toMutableList()

    fun getReport(context: Context, id: String): MedicalReport? =
        db(context).reportDao().getById(id)?.sanitized()

    /** Light rows (no OCR text / analysis JSON) for list screens. */
    fun getReportSummaries(context: Context): List<ReportSummary> =
        db(context).reportDao().getSummariesFiltered(AppSettings.getUserEmail(context) ?: "")

    /** Full-text search over patient, type, comments, and extracted OCR text. */
    fun searchReports(context: Context, query: String): List<ReportSummary> {
        val email = AppSettings.getUserEmail(context) ?: ""
        val ftsQuery = query.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { "\"${it.replace("\"", "")}\"*" }
        if (ftsQuery.isBlank()) return getReportSummaries(context)
        return try {
            db(context).reportDao().searchFiltered(ftsQuery, email)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /** Most recent earlier report of the same patient and category (for comparisons). */
    fun findPreviousReport(
        context: Context,
        patient: String?,
        category: String,
        beforeDate: String,
        excludeId: String
    ): MedicalReport? =
        db(context).reportDao().findPrevious(patient ?: "", category, beforeDate, excludeId, AppSettings.getUserEmail(context) ?: "")
            ?.sanitized() // feeds AI/local comparison, which reads med + parameter fields

    fun upsertReport(context: Context, report: MedicalReport) {
        val userEmail = AppSettings.getUserEmail(context) ?: ""
        val associatedReport = if (report.userEmail.isNullOrBlank() && userEmail.isNotBlank()) {
            report.copy(userEmail = userEmail)
        } else {
            report
        }
        db(context).reportDao().upsert(associatedReport)
    }

    // ── Duplicate detection ─────────────────────────────────────────────────
    /** SHA-256 hex digest, used to fingerprint scanned pages / imported files. */
    fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** Existing report that already contains ANY of these page/file hashes (exact re-scan). */
    fun findReportByAnyHash(context: Context, hashes: Collection<String>): MedicalReport? {
        if (hashes.isEmpty()) return null
        val set = hashes.toSet()
        val email = AppSettings.getUserEmail(context) ?: ""
        val id = db(context).reportDao().getAllPageHashesFiltered(email)
            .firstOrNull { row -> row.pageHashes.any { it in set } }?.id
            ?: return null
        return getReport(context, id)
    }

    /**
     * Existing report that looks like the same document scanned again: same patient,
     * date, and category, with near-identical extracted text (>= 85% token overlap).
     */
    fun findContentDuplicate(
        context: Context,
        patient: String,
        date: String,
        category: String,
        extractedText: String
    ): MedicalReport? {
        val newTokens = tokenize(extractedText)
        if (newTokens.isEmpty()) return null
        val email = AppSettings.getUserEmail(context) ?: ""
        return db(context).reportDao().findByPatientDateCategory(patient, date, category, email)
            .firstOrNull { candidate ->
                val existing = tokenize(candidate.extractedText ?: "")
                existing.isNotEmpty() && jaccard(newTokens, existing) >= 0.85
            }
    }

    /**
     * Finds reports ALREADY in the store that duplicate an earlier report — same patient,
     * date, and category with near-identical text, or sharing a page-file hash. Returns
     * the NEWER copies (by createdAt), which are the safe ones to delete; the original
     * of each group is always kept.
     */
    fun findStoredDuplicates(context: Context): List<MedicalReport> {
        val email = AppSettings.getUserEmail(context) ?: ""
        val all = db(context).reportDao().getAllFiltered(email).sortedBy { it.createdAt }
        val kept = mutableListOf<MedicalReport>()
        val duplicates = mutableListOf<MedicalReport>()
        for (report in all) {
            val tokens = tokenize(report.extractedText ?: "")
            val isDup = kept.any { earlier ->
                val sameSlot = (earlier.patientName ?: "").equals(report.patientName ?: "", true) &&
                    earlier.reportDate == report.reportDate &&
                    (earlier.reportCategory ?: "") == (report.reportCategory ?: "")
                if (!sameSlot) return@any false
                val earlierTokens = tokenize(earlier.extractedText ?: "")
                val textMatch = tokens.isNotEmpty() && earlierTokens.isNotEmpty() &&
                    jaccard(tokens, earlierTokens) >= 0.85
                // Hash match alone is NOT enough: sibling reports saved from one
                // multi-report scan share their page files on purpose. Only treat a
                // shared hash as a duplicate when neither report has readable text.
                val hashOnlyMatch = tokens.isEmpty() && earlierTokens.isEmpty() &&
                    report.pageHashes.isNotEmpty() &&
                    report.pageHashes.any { it in earlier.pageHashes }
                textMatch || hashOnlyMatch
            }
            if (isDup) duplicates.add(report) else kept.add(report)
        }
        return duplicates
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        val intersection = a.count { it in b }
        val union = a.size + b.size - intersection
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    fun deleteReport(context: Context, id: String) {
        val removed = db(context).reportDao().getById(id)
        db(context).reportDao().deleteById(id)
        // Remove associated page images and preserved source files inside our records
        // folder — but only files no sibling report (from the same scan) still uses.
        val paths = (removed?.imagePaths?.takeIf { it.isNotEmpty() } ?: listOfNotNull(removed?.imagePath)) +
            (removed?.sourceFiles?.map { it.path } ?: emptyList())
        for (path in paths) {
            try {
                if (path.isBlank()) continue
                if (db(context).reportDao().countOtherReportsUsingPath(path, id) > 0) continue
                val f = File(path)
                if (f.exists() && f.absolutePath.startsWith(recordsDir(context).absolutePath)) f.delete()
            } catch (_: Exception) { }
        }
    }

    // ── Pending tests ───────────────────────────────────────────────────────
    fun getPendingTests(context: Context): MutableList<PendingTest> =
        db(context).pendingTestDao().getAll().toMutableList()

    fun upsertPendingTest(context: Context, test: PendingTest) {
        db(context).pendingTestDao().upsert(test)
    }

    fun deletePendingTest(context: Context, id: String) {
        db(context).pendingTestDao().deleteById(id)
    }

    // ── Medication logs ─────────────────────────────────────────────────────
    fun getMedLogs(context: Context, patientName: String, medicineName: String): List<MedLogEntry> =
        db(context).medLogDao().getFor(patientName, medicineName)

    fun addMedLog(context: Context, entry: MedLogEntry) {
        db(context).medLogDao().insert(entry)
    }

    /** Moves a patient's intake logs from an old medicine name to a new one after a rename. */
    fun renameMedLogs(context: Context, patientName: String, oldName: String, newName: String) {
        db(context).medLogDao().renameMedicine(patientName, oldName, newName)
    }

    /** Re-keys a patient's intake logs and pending tests when two name variants are merged. */
    fun renamePatientRecords(context: Context, oldName: String, newName: String) {
        db(context).medLogDao().renamePatient(oldName, newName)
        db(context).pendingTestDao().renamePatient(oldName, newName)
    }

    // ── Image storage ───────────────────────────────────────────────────────
    /** Copies raw image bytes into the records/images folder and returns the absolute path. */
    fun saveImage(context: Context, id: String, bytes: ByteArray): String {
        val file = File(imagesDir(context), "$id.jpg")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    /** Preserves an original imported file (PDF/Word/image) so the user can download it later. */
    fun saveSourceFile(context: Context, reportId: String, index: Int, name: String, bytes: ByteArray): String {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file" }
        val file = File(sourcesDir(context), "${reportId}_${index}_$safe")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    /** Utility for generating record IDs without external dependencies. */
    fun newId(): String = java.util.UUID.randomUUID().toString()
}
