import Foundation

/// Verifies and resolves the clinical date of a scanned report. The AI is asked to pick the right
/// date, but this re-checks its work against the full list of labeled dates it found on the page,
/// so a "Printed on" date never becomes a lab report's date:
///
///  - sample-based lab reports (blood/urine)  → Reported date, else Collected date
///  - procedure reports (2D Echo, USG, X-ray) → Procedure/Study/Examination date
///  - prescriptions                           → Visit/consultation date
///  - a bare unlabeled date only as fallback; a Printed date only as the very last resort
///
/// All dates are normalized from common Indian day-first formats to YYYY-MM-DD and sanity checked
/// (not in the future, not implausibly old).
///
/// Direct port of `ai/DateResolver.kt`.
enum DateResolver {

    /// Kinds of dates that appear on medical documents, from their printed labels.
    private enum DateKind {
        case reported, collected, procedure, visit, unlabeled, printed
    }

    private enum DocKind {
        case lab, procedure, prescription, other
    }

    /// Priority ladder per document kind (earlier = preferred).
    private static let priorities: [DocKind: [DateKind]] = [
        .lab: [.reported, .collected, .unlabeled, .visit, .procedure, .printed],
        .procedure: [.procedure, .reported, .unlabeled, .collected, .visit, .printed],
        .prescription: [.visit, .unlabeled, .reported, .procedure, .collected, .printed],
        .other: [.reported, .procedure, .unlabeled, .collected, .visit, .printed],
    ]

    /// Picks the correct date for one extracted report section. Returns an ISO date, or nil when
    /// no plausible date exists anywhere on the page.
    static func resolve(_ section: ScanExtraction, category: String) -> String? {
        let ladder = priorities[classify(section, category: category)] ?? priorities[.other]!

        // Labeled candidates the model saw on the page, invalid dates dropped.
        let candidates: [(kind: DateKind, iso: String)] = section.datesFound.compactMap { found in
            guard let iso = normalize(found.date) else { return nil }
            return (labelKind(found.label), iso)
        }

        for kind in ladder {
            if let match = candidates.first(where: { $0.kind == kind }) { return match.iso }
        }
        // Nothing labeled/parsable — fall back to the date the model chose itself.
        return normalize(section.reportDate)
    }

    private static func classify(_ section: ScanExtraction, category: String) -> DocKind {
        let hints = [category, section.reportType ?? "", section.reportName ?? ""]
            .joined(separator: " ")
            .lowercased()
        func matches(_ pattern: String) -> Bool {
            hints.range(of: pattern, options: .regularExpression) != nil
        }

        if matches("prescription") { return .prescription }
        if matches("echo|sonograph|ultrasound|usg|x[- ]?ray|xray|ecg|ekg|ct\\b|mri|doppler|endoscop|colonoscop|mammograph|angiograph|scan") {
            return .procedure
        }
        if matches("blood|urine|stool|serum|lab|patholog|hba1c|lipid|cbc|thyroid|culture") { return .lab }
        return .other
    }

    private static func labelKind(_ label: String?) -> DateKind {
        let l = (label ?? "").lowercased()
        func matches(_ pattern: String) -> Bool {
            l.range(of: pattern, options: .regularExpression) != nil
        }

        if l.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return .unlabeled }
        if matches("print") { return .printed }
        if matches("report") { return .reported }
        if matches("collect|sample|drawn|received|regist") { return .collected }
        if matches("procedure|study|exam|perform|scan|investigat") { return .procedure }
        if matches("visit|consult|prescri|opd|admission") { return .visit }
        return .unlabeled
    }

    // MARK: - Parsing & sanity checks

    /// Day-first formats first: Indian reports print 12/03/2026 meaning 12 March.
    private static let patterns = [
        "yyyy-MM-dd", "yyyy/MM/dd",
        "dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy",
        "dd/MM/yy", "dd-MM-yy", "dd.MM.yy",
        "dd MMM yyyy", "dd-MMM-yyyy", "dd.MMM.yyyy", "dd MMMM yyyy",
        "MMM dd, yyyy", "MMMM dd, yyyy",
    ]

    /// Normalizes a date string in any common medical-report format to YYYY-MM-DD. Returns nil
    /// when unparsable or outside the plausible range (future dates and dates more than 60 years
    /// old are rejected — usually OCR misreads).
    static func normalize(_ raw: String?) -> String? {
        parseFirstPlausible(raw, isPlausible: isPlausibleReportDate)
    }

    /// Same parsing as `normalize` but for a medicine's start/end date, which is legitimately in
    /// the future (a course starting next month, an end date months out) — `normalize`'s "not in
    /// the future" check exists for REPORT dates and must stay that way for `resolve`. Still
    /// rejects implausible OCR garbage (more than ~5 years out either direction).
    static func normalizeMedicineDate(_ raw: String?) -> String? {
        parseFirstPlausible(raw, isPlausible: isPlausibleMedicineDate)
    }

    /// Tries every known format in order. A pattern that parses but yields an implausible date
    /// (e.g. a 2-digit year matched by a 4-digit-year pattern, reading "26" as year 26 AD) is
    /// skipped in favour of the NEXT pattern rather than failing outright — some formats are
    /// ambiguous and only a later pattern disambiguates them correctly.
    private static func parseFirstPlausible(
        _ raw: String?,
        isPlausible: (Date) -> Bool
    ) -> String? {
        guard let text = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty
        else { return nil }

        // Strip a time portion ("12/03/2026 10:45 AM" → "12/03/2026").
        let dateOnly = text
            .replacingOccurrences(of: "[T ]\\d{1,2}:\\d{2}.*$", with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)

        for pattern in patterns {
            let formatter = DateFormatter()
            // POSIX locale and an explicit calendar/timezone: without them a device set to a
            // non-Gregorian calendar (Buddhist, Japanese) or a Hindi locale parses "12 Mar 2026"
            // into a different era entirely, which is a real configuration on Indian devices.
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.calendar = Calendar(identifier: .gregorian)
            formatter.timeZone = .current
            formatter.dateFormat = pattern
            formatter.isLenient = false

            guard let parsed = formatter.date(from: dateOnly), isPlausible(parsed) else { continue }
            return isoFormatter.string(from: parsed)
        }
        return nil
    }

    private static let isoFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = .current
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    private static func isPlausibleReportDate(_ date: Date) -> Bool {
        let calendar = Calendar(identifier: .gregorian)
        guard
            let tomorrow = calendar.date(byAdding: .day, value: 1, to: Date()),
            let oldest = calendar.date(byAdding: .year, value: -60, to: Date())
        else { return false }
        return date < tomorrow && date > oldest
    }

    private static func isPlausibleMedicineDate(_ date: Date) -> Bool {
        let calendar = Calendar(identifier: .gregorian)
        guard
            let earliest = calendar.date(byAdding: .year, value: -5, to: Date()),
            let latest = calendar.date(byAdding: .year, value: 5, to: Date())
        else { return false }
        return date > earliest && date < latest
    }
}
