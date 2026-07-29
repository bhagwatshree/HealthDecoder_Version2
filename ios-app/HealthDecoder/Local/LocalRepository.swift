import Foundation
import UIKit
import CryptoKit

/// CRUD + file storage for locally-persisted data, mirroring `local/LocalRepository.kt` +
/// `local/LocalStore.kt`'s Phase-1-relevant surface. All patient data lives only on-device, in
/// the SQLCipher-encrypted `Database` plus plain files under `records/{images,sources}/` —
/// nothing here ever leaves the phone except the AI extraction request itself (see
/// `GeminiClient`/`OcrEngine`), same as Android.
enum LocalRepository {
    private static let db = Database.shared
    private static let encoder = JSONEncoder()
    private static let decoder = JSONDecoder()

    // MARK: - File storage

    private static var recordsDirectory: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("records", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static var imagesDirectory: URL {
        let dir = recordsDirectory.appendingPathComponent("images", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static var sourcesDirectory: URL {
        let dir = recordsDirectory.appendingPathComponent("sources", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Saves a page's JPEG bytes to disk; returns the filename to store in
    /// `MedicalReport.imagePaths` (paths are always relative to `imagesDirectory`).
    static func saveImage(_ data: Data, reportId: String, pageIndex: Int) -> String {
        let filename = "\(reportId)_\(pageIndex).jpg"
        try? data.write(to: imagesDirectory.appendingPathComponent(filename))
        return filename
    }

    static func loadImage(relativePath: String) -> UIImage? {
        UIImage(contentsOfFile: imagesDirectory.appendingPathComponent(relativePath).path)
    }

    static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - Reports

    static func getAllReports() -> [MedicalReport] {
        db.query("SELECT * FROM reports ORDER BY createdAt DESC", []) { report(from: $0) }
    }

    static func getReport(id: String) -> MedicalReport? {
        db.query("SELECT * FROM reports WHERE id = ? LIMIT 1", [id]) { report(from: $0) }.first
    }

    /// Any existing report sharing at least one page hash with `hashes` — used to reject an
    /// exact re-scan before it's ever saved (mirrors `LocalStore.findReportByAnyHash`; the
    /// Jaccard near-duplicate check Android also does is deferred to a later phase).
    static func findReportByAnyHash(_ hashes: [String]) -> MedicalReport? {
        guard !hashes.isEmpty else { return nil }
        let target = Set(hashes)
        return getAllReports().first { !Set($0.pageHashes).isDisjoint(with: target) }
    }

    static func saveReport(_ report: MedicalReport) {
        db.run("""
            INSERT OR REPLACE INTO reports
            (id, patientName, reportDate, reportType, extractedText, comments, medications,
             imagePath, imagePaths, sourceFiles, createdAt, testResults, comparisonResult,
             reportCategory, healthInsights, pageHashes, userEmail, analyzed)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, [
                report.id, report.patientName, report.reportDate, report.reportType,
                report.extractedText, report.comments, encodeJSON(report.medications),
                report.imagePath, encodeJSON(report.imagePaths), encodeJSON(report.sourceFiles),
                report.createdAt, encodeJSONOptional(report.testResults),
                encodeJSONOptional(report.comparisonResult), report.reportCategory,
                encodeJSONOptional(report.healthInsights), encodeJSON(report.pageHashes),
                report.userEmail, report.analyzed
            ])
    }

    /// How many stored reports belong to a patient — used to block removing a family member who
    /// still has records (rename/merge is the right tool there).
    static func reportCount(forPatient name: String) -> Int {
        db.query("SELECT COUNT(*) AS c FROM reports WHERE patientName = ? COLLATE NOCASE", [name]) {
            $0.int("c")
        }.first ?? 0
    }

    /// Renames a patient across every stored report — the local half of Android's
    /// `mergePatient` cascade (trends/reminders/intake-log re-keying lands with those features).
    static func renamePatient(from oldName: String, to newName: String) {
        db.run("UPDATE reports SET patientName = ? WHERE patientName = ? COLLATE NOCASE", [newName, oldName])
        db.run("UPDATE pending_tests SET patientName = ? WHERE patientName = ? COLLATE NOCASE", [newName, oldName])
        db.run("UPDATE med_logs SET patientName = ? WHERE patientName = ? COLLATE NOCASE", [newName, oldName])
        if AppSettings.activePatient?.caseInsensitiveCompare(oldName) == .orderedSame {
            AppSettings.activePatient = newName
        }
    }

    /// Updates a medicine's name/dosage/frequency across every report that carries it for a
    /// patient — mirrors Android's cascading medicine edit so nothing orphans.
    static func updateMedicine(
        patientName: String, oldName: String, newName: String, dosage: String, frequency: String
    ) {
        for var report in getAllReports()
        where (report.patientName ?? "").caseInsensitiveCompare(patientName) == .orderedSame {
            var changed = false
            report.medications = report.medications.map { medication in
                guard medication.name.caseInsensitiveCompare(oldName) == .orderedSame else { return medication }
                changed = true
                var updated = medication
                updated.name = newName
                updated.dosage = dosage
                updated.frequency = frequency
                return updated
            }
            if changed { saveReport(report) }
        }
    }

    /// Removes a medicine from every report belonging to a patient.
    static func deleteMedicine(patientName: String, medicineName: String) {
        for var report in getAllReports()
        where (report.patientName ?? "").caseInsensitiveCompare(patientName) == .orderedSame {
            let before = report.medications.count
            report.medications.removeAll { $0.name.caseInsensitiveCompare(medicineName) == .orderedSame }
            if report.medications.count != before { saveReport(report) }
        }
    }

    /// Renames a medicine within one report (used by `MedicineInfoSheet`'s OCR-misread
    /// correction). Scoped to a single report — Android's `renameMedicine` also cascades across
    /// every report/reminder for the patient, which is a later-phase feature once reminders
    /// exist on iOS.
    @discardableResult
    static func renameMedicine(reportId: String, oldName: String, newName: String) -> Bool {
        guard var report = getReport(id: reportId) else { return false }
        var changed = false
        report.medications = report.medications.map { med in
            guard med.name.caseInsensitiveCompare(oldName) == .orderedSame else { return med }
            changed = true
            var updated = med
            updated.name = newName
            return updated
        }
        guard changed else { return false }
        saveReport(report)
        return true
    }

    static func deleteReport(id: String) {
        if let report = getReport(id: id) {
            for path in report.imagePaths {
                try? FileManager.default.removeItem(at: imagesDirectory.appendingPathComponent(path))
            }
            for source in report.sourceFiles {
                try? FileManager.default.removeItem(at: sourcesDirectory.appendingPathComponent(source.path))
            }
        }
        db.run("DELETE FROM reports WHERE id = ?", [id])
    }

    // MARK: - Pending tests / medication logs
    // Storage only for Phase 1 — Pending Tests and Medication Tracker screens land in Phase 2,
    // but `OcrEngine`'s extraction already produces data these tables can hold.

    static func getAllPendingTests() -> [PendingTest] {
        db.query("SELECT * FROM pending_tests ORDER BY dueDate ASC", []) { pendingTest(from: $0) }
    }

    static func savePendingTest(_ test: PendingTest) {
        db.run("""
            INSERT OR REPLACE INTO pending_tests
            (id, patientName, testName, dueDate, status, resolvedReportId, createdAt)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, [test.id, test.patientName, test.testName, test.dueDate, test.status,
                  test.resolvedReportId, test.createdAt])
    }

    /// Intake-log rows whose `takenAt` starts with `datePrefix` (an ISO "yyyy-MM-dd"), used to
    /// restore today's "taken" ticks on the Reminders screen.
    static func medLogs(onDatePrefix datePrefix: String, actionType: String) -> [MedLogEntry] {
        db.query(
            "SELECT * FROM med_logs WHERE actionType = ? AND takenAt LIKE ? ORDER BY takenAt DESC",
            [actionType, "\(datePrefix)%"]
        ) { medLog(from: $0) }
    }

    /// Every intake/change log for one medicine, newest first.
    static func medLogs(patientName: String, medicineName: String) -> [MedLogEntry] {
        db.query(
            """
            SELECT * FROM med_logs
            WHERE patientName = ? COLLATE NOCASE AND medicineName = ? COLLATE NOCASE
            ORDER BY takenAt DESC
            """,
            [patientName, medicineName]
        ) { medLog(from: $0) }
    }

    static func deletePendingTest(id: String) {
        db.run("DELETE FROM pending_tests WHERE id = ?", [id])
    }

    static func saveMedLog(_ log: MedLogEntry) {
        db.run("""
            INSERT OR REPLACE INTO med_logs
            (id, patientName, medicineName, actionType, frequency, notes, takenAt)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, [log.id, log.patientName, log.medicineName, log.actionType, log.frequency,
                  log.notes, log.takenAt])
    }

    // MARK: - Row <-> model mapping

    private static func report(from row: Database.Row) -> MedicalReport {
        MedicalReport(
            id: row.string("id") ?? UUID().uuidString,
            patientName: row.string("patientName"),
            reportDate: row.string("reportDate"),
            reportType: row.string("reportType"),
            extractedText: row.string("extractedText"),
            comments: row.string("comments"),
            medications: decodeJSON([Medication].self, row.string("medications")) ?? [],
            imagePath: row.string("imagePath") ?? "",
            imagePaths: decodeJSON([String].self, row.string("imagePaths")) ?? [],
            sourceFiles: decodeJSON([SourceFile].self, row.string("sourceFiles")) ?? [],
            createdAt: row.string("createdAt") ?? "",
            testResults: decodeJSON(TestResults.self, row.string("testResults")),
            comparisonResult: decodeJSON(ComparisonResult.self, row.string("comparisonResult")),
            reportCategory: row.string("reportCategory"),
            healthInsights: decodeJSON(HealthInsights.self, row.string("healthInsights")),
            pageHashes: decodeJSON([String].self, row.string("pageHashes")) ?? [],
            userEmail: row.string("userEmail"),
            analyzed: row.bool("analyzed")
        )
    }

    private static func medLog(from row: Database.Row) -> MedLogEntry {
        MedLogEntry(
            id: row.string("id") ?? UUID().uuidString,
            patientName: row.string("patientName") ?? "",
            medicineName: row.string("medicineName") ?? "",
            actionType: row.string("actionType") ?? "TAKEN",
            frequency: row.string("frequency"),
            notes: row.string("notes"),
            takenAt: row.string("takenAt") ?? ""
        )
    }

    private static func pendingTest(from row: Database.Row) -> PendingTest {
        PendingTest(
            id: row.string("id") ?? UUID().uuidString,
            patientName: row.string("patientName") ?? "",
            testName: row.string("testName") ?? "",
            dueDate: row.string("dueDate"),
            status: row.string("status") ?? "Pending",
            resolvedReportId: row.string("resolvedReportId"),
            createdAt: row.string("createdAt") ?? ""
        )
    }

    private static func encodeJSON<T: Encodable>(_ value: T) -> String {
        (try? encoder.encode(value)).flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
    }

    private static func encodeJSONOptional<T: Encodable>(_ value: T?) -> String? {
        guard let value else { return nil }
        return (try? encoder.encode(value)).flatMap { String(data: $0, encoding: .utf8) }
    }

    private static func decodeJSON<T: Decodable>(_ type: T.Type, _ json: String?) -> T? {
        guard let json, let data = json.data(using: .utf8) else { return nil }
        return try? decoder.decode(type, from: data)
    }
}
