import Foundation
// NOTE: this is the one dependency I could not verify by compiling (no full Xcode available in
// the environment that wrote this). `sqlcipher/SQLCipher.swift` (added in project.yml) vends the
// standard SQLite3 C API (sqlite3_open_v2, sqlite3_key, sqlite3_prepare_v2, ...) with SQLCipher's
// encryption codec compiled in. If this `import SQLCipher` fails to resolve, open the target's
// "Frameworks, Libraries, and Embedded Content" list in Xcode, find the exact product name Xcode
// generated for the package, and swap it in here — the C API calls below don't need to change.
import SQLCipher

/// Thin wrapper around SQLCipher's C API (the same sqlite3.h surface Android's SQLCipher fork
/// uses under Room). Mirrors `local/db/MedicalDatabase.kt` + `Converters.kt`: nested Swift types
/// are stored as JSON-encoded TEXT columns, exactly like Room's TypeConverters.
final class Database {
    static let shared = Database()

    private var db: OpaquePointer?
    /// Serializes every access — SQLite connections aren't safe for concurrent use from
    /// multiple threads without this (Room handles this internally; we do it explicitly).
    private let queue = DispatchQueue(label: "com.example.medicalscanner.db")

    private init() {
        queue.sync { openAndMigrate() }
    }

    private var databaseURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("records", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("medical_records.sqlite")
    }

    private func openAndMigrate() {
        let path = databaseURL.path
        if sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, nil) != SQLITE_OK {
            fatalError("Could not open database at \(path)")
        }
        let passphrase = SecureKeyManager.databasePassphrase()
        sqlite3_key(db, passphrase, Int32(passphrase.utf8.count))

        // Verify the key is correct by touching the schema table. A failure here means either a
        // fresh (empty, unencrypted) file or a passphrase/corruption mismatch — mirrors Android's
        // LocalStore recovery: delete and rebuild rather than leaving the app permanently stuck.
        if sqlite3_exec(db, "SELECT count(*) FROM sqlite_master;", nil, nil, nil) != SQLITE_OK {
            sqlite3_close(db)
            try? FileManager.default.removeItem(at: databaseURL)
            if sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, nil) != SQLITE_OK {
                fatalError("Could not recreate database at \(path)")
            }
            sqlite3_key(db, passphrase, Int32(passphrase.utf8.count))
        }

        createTables()
    }

    private func createTables() {
        let statements = [
            """
            CREATE TABLE IF NOT EXISTS reports (
                id TEXT PRIMARY KEY NOT NULL,
                patientName TEXT,
                reportDate TEXT,
                reportType TEXT,
                extractedText TEXT,
                comments TEXT,
                medications TEXT NOT NULL DEFAULT '[]',
                imagePath TEXT NOT NULL DEFAULT '',
                imagePaths TEXT NOT NULL DEFAULT '[]',
                sourceFiles TEXT NOT NULL DEFAULT '[]',
                createdAt TEXT NOT NULL,
                testResults TEXT,
                comparisonResult TEXT,
                reportCategory TEXT,
                healthInsights TEXT,
                pageHashes TEXT NOT NULL DEFAULT '[]',
                userEmail TEXT,
                analyzed INTEGER NOT NULL DEFAULT 1
            );
            """,
            "CREATE INDEX IF NOT EXISTS idx_reports_patient_date ON reports(patientName, reportDate);",
            "CREATE INDEX IF NOT EXISTS idx_reports_date ON reports(reportDate);",
            "CREATE INDEX IF NOT EXISTS idx_reports_category ON reports(reportCategory);",
            """
            CREATE TABLE IF NOT EXISTS pending_tests (
                id TEXT PRIMARY KEY NOT NULL,
                patientName TEXT NOT NULL,
                testName TEXT NOT NULL,
                dueDate TEXT,
                status TEXT NOT NULL,
                resolvedReportId TEXT,
                createdAt TEXT NOT NULL
            );
            """,
            "CREATE INDEX IF NOT EXISTS idx_pending_patient ON pending_tests(patientName);",
            "CREATE INDEX IF NOT EXISTS idx_pending_status ON pending_tests(status);",
            """
            CREATE TABLE IF NOT EXISTS med_logs (
                id TEXT PRIMARY KEY NOT NULL,
                patientName TEXT NOT NULL,
                medicineName TEXT NOT NULL,
                actionType TEXT NOT NULL,
                frequency TEXT,
                notes TEXT,
                takenAt TEXT NOT NULL
            );
            """,
            "CREATE INDEX IF NOT EXISTS idx_medlog_patient_med ON med_logs(patientName, medicineName);"
        ]
        for sql in statements {
            if sqlite3_exec(db, sql, nil, nil, nil) != SQLITE_OK {
                let msg = String(cString: sqlite3_errmsg(db))
                fatalError("Schema migration failed: \(msg)\nSQL: \(sql)")
            }
        }
    }

    // MARK: - Execution

    /// Runs an INSERT/UPDATE/DELETE with positional `?` parameters.
    @discardableResult
    func run(_ sql: String, _ params: [Any?] = []) -> Bool {
        queue.sync {
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }
            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return false }
            bind(stmt, params)
            return sqlite3_step(stmt) == SQLITE_DONE
        }
    }

    /// Runs a SELECT, calling `map` once per row with a `Row` accessor.
    func query<T>(_ sql: String, _ params: [Any?] = [], _ map: (Row) -> T) -> [T] {
        queue.sync {
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }
            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
            bind(stmt, params)
            var results: [T] = []
            while sqlite3_step(stmt) == SQLITE_ROW {
                results.append(map(Row(stmt: stmt)))
            }
            return results
        }
    }

    private func bind(_ stmt: OpaquePointer?, _ params: [Any?]) {
        let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for (i, param) in params.enumerated() {
            let index = Int32(i + 1)
            switch param {
            case nil:
                sqlite3_bind_null(stmt, index)
            case let v as String:
                sqlite3_bind_text(stmt, index, v, -1, sqliteTransient)
            case let v as Int:
                sqlite3_bind_int64(stmt, index, Int64(v))
            case let v as Bool:
                sqlite3_bind_int(stmt, index, v ? 1 : 0)
            case let v as Double:
                sqlite3_bind_double(stmt, index, v)
            default:
                sqlite3_bind_null(stmt, index)
            }
        }
    }

    /// Read accessor for a single result row, addressed by column name.
    struct Row {
        let stmt: OpaquePointer?

        func string(_ column: String) -> String? {
            guard let idx = index(of: column), sqlite3_column_type(stmt, idx) != SQLITE_NULL else { return nil }
            return String(cString: sqlite3_column_text(stmt, idx))
        }

        func int(_ column: String) -> Int {
            guard let idx = index(of: column) else { return 0 }
            return Int(sqlite3_column_int64(stmt, idx))
        }

        func bool(_ column: String) -> Bool { int(column) != 0 }

        private func index(of column: String) -> Int32? {
            let count = sqlite3_column_count(stmt)
            for i in 0..<count {
                if let name = sqlite3_column_name(stmt, i), String(cString: name) == column {
                    return i
                }
            }
            return nil
        }
    }
}
