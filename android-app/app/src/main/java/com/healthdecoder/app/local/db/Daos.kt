package com.healthdecoder.app.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.healthdecoder.app.model.MedLogEntry
import com.healthdecoder.app.model.MedicalReport
import com.healthdecoder.app.model.PendingTest
import com.healthdecoder.app.model.ProcessedEmail

// All queries are blocking; LocalStore is only ever called from Dispatchers.IO.

@Dao
interface ReportDao {

    @Query("SELECT * FROM reports ORDER BY COALESCE(reportDate, createdAt) DESC")
    fun getAll(): List<MedicalReport>

    @Query("SELECT * FROM reports WHERE userEmail = :userEmail OR (userEmail IS NULL AND :userEmail = '') ORDER BY COALESCE(reportDate, createdAt) DESC")
    fun getAllFiltered(userEmail: String): List<MedicalReport>

    @Query("SELECT * FROM reports WHERE id = :id LIMIT 1")
    fun getById(id: String): MedicalReport?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(report: MedicalReport)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(reports: List<MedicalReport>)

    @Query("DELETE FROM reports WHERE id = :id")
    fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM reports")
    fun count(): Int

    @Query(
        """SELECT id, patientName, reportDate, reportType, reportCategory, imagePath, createdAt
           FROM reports WHERE userEmail = :userEmail OR (userEmail IS NULL AND :userEmail = '')
           ORDER BY COALESCE(reportDate, createdAt) DESC"""
    )
    fun getSummariesFiltered(userEmail: String): List<ReportSummary>

    /** Full-text search over patient name, report type, comments, and OCR text. */
    @Query(
        """SELECT r.id, r.patientName, r.reportDate, r.reportType, r.reportCategory, r.imagePath, r.createdAt
           FROM reports r JOIN reports_fts fts ON r.rowid = fts.rowid
           WHERE (r.userEmail = :userEmail OR (r.userEmail IS NULL AND :userEmail = ''))
             AND reports_fts MATCH :ftsQuery
           ORDER BY COALESCE(r.reportDate, r.createdAt) DESC"""
    )
    fun searchFiltered(ftsQuery: String, userEmail: String): List<ReportSummary>

    /**
     * How many OTHER reports still reference this image/source file. Reports saved from
     * one multi-report scan share their page files, so files are only deleted when the
     * last report using them goes.
     */
    @Query(
        """SELECT COUNT(*) FROM reports
           WHERE id != :excludeId AND (
             imagePath = :path
             OR imagePaths LIKE '%' || :path || '%'
             OR sourceFiles LIKE '%' || :path || '%')"""
    )
    fun countOtherReportsUsingPath(path: String, excludeId: String): Int

    /** id + page hashes of every report, for exact duplicate-file detection. */
    @Query("SELECT id, pageHashes FROM reports WHERE userEmail = :userEmail OR (userEmail IS NULL AND :userEmail = '')")
    fun getAllPageHashesFiltered(userEmail: String): List<ReportHashRow>

    /** Candidate reports for content-level duplicate detection. */
    @Query(
        """SELECT * FROM reports
           WHERE patientName = :patient COLLATE NOCASE AND reportDate = :date AND reportCategory = :category
             AND (userEmail = :userEmail OR (userEmail IS NULL AND :userEmail = ''))"""
    )
    fun findByPatientDateCategory(patient: String, date: String, category: String, userEmail: String): List<MedicalReport>

    /** Most recent earlier report of the same patient and category (for comparisons). */
    @Query(
        """SELECT * FROM reports
           WHERE id != :excludeId AND patientName = :patient AND reportCategory = :category
             AND COALESCE(reportDate, '') < :beforeDate
             AND (userEmail = :userEmail OR (userEmail IS NULL AND :userEmail = ''))
           ORDER BY COALESCE(reportDate, createdAt) DESC LIMIT 1"""
    )
    fun findPrevious(patient: String, category: String, beforeDate: String, excludeId: String, userEmail: String): MedicalReport?
}

@Dao
interface PendingTestDao {

    @Query("SELECT * FROM pending_tests ORDER BY createdAt DESC")
    fun getAll(): List<PendingTest>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(test: PendingTest)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(tests: List<PendingTest>)

    @Query("DELETE FROM pending_tests WHERE id = :id")
    fun deleteById(id: String)

    /** Re-keys a patient's pending tests when two mis-scanned name variants are merged. */
    @Query("UPDATE pending_tests SET patientName = :newName WHERE patientName = :oldName COLLATE NOCASE")
    fun renamePatient(oldName: String, newName: String)
}

@Dao
interface MedLogDao {

    @Query(
        """SELECT * FROM med_logs
           WHERE patientName = :patientName AND medicineName = :medicineName
           ORDER BY takenAt DESC"""
    )
    fun getFor(patientName: String, medicineName: String): List<MedLogEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entry: MedLogEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(entries: List<MedLogEntry>)

    /** Re-keys a patient's intake history when a medicine is renamed (e.g. correcting a bad
     *  handwriting scan), so its logs follow the new name instead of orphaning. Case-insensitive
     *  on the old name to match how medicines are compared everywhere else. */
    @Query(
        """UPDATE med_logs SET medicineName = :newName
           WHERE patientName = :patientName AND medicineName = :oldName COLLATE NOCASE"""
    )
    fun renameMedicine(patientName: String, oldName: String, newName: String)

    /** Re-keys a patient's intake logs when two mis-scanned name variants are merged. */
    @Query("UPDATE med_logs SET patientName = :newName WHERE patientName = :oldName COLLATE NOCASE")
    fun renamePatient(oldName: String, newName: String)
}

@Dao
interface ProcessedEmailDao {
    @Query("SELECT EXISTS(SELECT 1 FROM processed_emails WHERE messageId = :messageId LIMIT 1)")
    fun exists(messageId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(email: ProcessedEmail)

    @Query("DELETE FROM processed_emails")
    fun deleteAll()

    /** Reports a background scan (EmailScanWorker) downloaded but nobody has reviewed yet —
     *  ScanScreen surfaces these as the same import/skip dialog the "Check Email" button uses. */
    @Query("SELECT * FROM processed_emails WHERE pendingLocalPath IS NOT NULL ORDER BY processedAt DESC")
    fun getPending(): List<ProcessedEmail>

    @Query("UPDATE processed_emails SET pendingLocalPath = NULL WHERE messageId = :messageId")
    fun clearPending(messageId: String)
}
