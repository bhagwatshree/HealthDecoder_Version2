import Foundation

/// Seeds a small, obviously-fake patient history so screens that need data — Records, Report
/// Detail, Trends, Insights — can be exercised without scanning a real report.
///
/// Port of `local/DemoDataSeeder.kt`, reduced to what iOS renders today. Android also surfaces
/// this from Settings ("Add Demo Data" / "Remove Demo Data") and shows a banner while it's
/// active; that UI lands with the Settings work.
///
/// The patient name is deliberately unmistakable so demo rows can never be confused with a real
/// person's records, and removal keys off exactly that name.
enum DemoDataSeeder {
    static let demoPatientName = "Demo Patient"

    /// Three visits over five months, with values that move — a diabetic/lipid picture that
    /// improves — so a trend line has something real to show rather than a flat pair of points.
    static func seed() {
        guard !isActive() else { return }

        let visits: [(date: String, sugar: String, hba1c: String, ldl: String, hb: String, tsh: String, status: (String, String, String))] = [
            ("2026-03-14", "168", "8.2", "182", "12.1", "5.8", ("High", "High", "High")),
            ("2026-05-20", "142", "7.4", "150", "12.8", "4.6", ("High", "High", "High")),
            ("2026-08-02", "108", "6.4", "96",  "13.6", "3.1", ("Normal", "Normal", "Normal")),
        ]

        for (index, visit) in visits.enumerated() {
            var report = MedicalReport(
                id: "demo-report-\(index + 1)",
                imagePath: "",
                createdAt: "\(visit.date)T09:00:00Z"
            )
            report.patientName = demoPatientName
            report.reportDate = visit.date
            report.reportType = "Lab Report"
            report.reportCategory = "Comprehensive Health Panel"
            report.comments = "Demo data — not a real report."
            report.analyzed = true
            report.testResults = TestResults(parameters: [
                parameter("Fasting Blood Sugar (FBS)", visit.sugar, "mg/dL", "70-100", visit.status.0),
                parameter("HbA1c", visit.hba1c, "%", "4.0-5.6", visit.status.1),
                parameter("LDL Cholesterol", visit.ldl, "mg/dL", "<100", visit.status.2),
                parameter("Haemoglobin", visit.hb, "g/dL", "13.0-17.0", index < 2 ? "Low" : "Normal"),
                parameter("TSH", visit.tsh, "uIU/mL", "0.4-4.0", index < 2 ? "High" : "Normal"),
            ])
            report.medications = index == 2
                ? [Medication(name: "Metformin", dosage: "500mg", frequency: "1-0-1")]
                : [Medication(name: "Metformin", dosage: "500mg", frequency: "1-0-0")]
            LocalRepository.saveReport(report)
        }
    }

    static func remove() {
        for report in LocalRepository.getAllReports()
        where report.patientName?.caseInsensitiveCompare(demoPatientName) == .orderedSame {
            LocalRepository.deleteReport(id: report.id)
        }
    }

    static func isActive() -> Bool {
        LocalRepository.getAllReports().contains {
            $0.patientName?.caseInsensitiveCompare(demoPatientName) == .orderedSame
        }
    }

    private static func parameter(
        _ name: String, _ value: String, _ unit: String, _ ref: String, _ status: String
    ) -> TestParameter {
        var p = TestParameter()
        p.name = name
        p.value = value
        p.unit = unit
        p.referenceRange = ref
        p.status = status
        p.excludeFromTrend = false
        return p
    }
}
