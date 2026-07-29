import Foundation

/// Pure on-device analysis over stored reports — port of `ai/DashboardEngine.kt`. No network.
/// This file currently carries the medication-history half; trend computation lands with the
/// Trends screen.
enum DashboardEngine {
    private struct MedPoint {
        let reportId: String
        let dosage: String
        let frequency: String
        let duration: String
        let isOptional: Bool
        let weeklySchedule: [String]
        let notes: String
        let date: String
    }

    /// Builds each patient's medicine list with Active / Changed / Discontinued status by
    /// walking their reports oldest-first and comparing successive dosage/frequency values.
    static func medicationHistory(reports: [MedicalReport]) -> [MedicationHistory] {
        // Chronological (oldest first) so dosage changes over time are detectable.
        let chrono = reports.sorted { ($0.reportDate ?? $0.createdAt) < ($1.reportDate ?? $1.createdAt) }

        var patientMed: [String: [String: [MedPoint]]] = [:]
        var latestDate: [String: String] = [:]

        for report in chrono {
            // Only reports that actually carry medicines (prescriptions) can change medication
            // status. Without this guard, scanning a newer blood report would mark every
            // medicine "Discontinued" — same reasoning as the Android original.
            guard report.medications.contains(where: { !$0.name.trimmingCharacters(in: .whitespaces).isEmpty }) else {
                continue
            }
            let patient = report.patientName?.isEmpty == false ? report.patientName! : "Unknown Patient"
            let date = report.reportDate ?? report.createdAt
            if (latestDate[patient] ?? "") <= date { latestDate[patient] = date }

            for medication in report.medications {
                let name = medication.name.trimmingCharacters(in: .whitespaces)
                guard !name.isEmpty else { continue }
                patientMed[patient, default: [:]][name, default: []].append(MedPoint(
                    reportId: report.id,
                    dosage: medication.dosage.isEmpty ? "1 tablet" : medication.dosage,
                    frequency: medication.frequency,
                    duration: medication.duration ?? "",
                    isOptional: medication.isOptional,
                    weeklySchedule: medication.weeklySchedule,
                    notes: medication.notes ?? "",
                    date: date
                ))
            }
        }

        var out: [MedicationHistory] = []
        for (patient, medMap) in patientMed {
            let latest = latestDate[patient] ?? ""
            for (medName, points) in medMap {
                guard let current = points.last else { continue }

                // The most recent point whose dosage/frequency actually differed — that's what
                // "previous" means to the user, not merely the second-to-last record.
                var previous: MedPoint?
                if points.count > 1 {
                    for index in stride(from: points.count - 2, through: 0, by: -1) {
                        if points[index].dosage != current.dosage || points[index].frequency != current.frequency {
                            previous = points[index]
                            break
                        }
                    }
                    if previous == nil { previous = points[points.count - 2] }
                }

                // Absent from the patient's newest prescription => no longer prescribed.
                let isOmitted = current.date < latest
                let status: String
                if isOmitted {
                    status = "Discontinued"
                } else if let previous, previous.dosage != current.dosage || previous.frequency != current.frequency {
                    status = "Changed"
                } else {
                    status = "Active"
                }

                out.append(MedicationHistory(
                    patientName: patient,
                    medicineName: medName,
                    currentDosage: current.dosage,
                    currentFrequency: current.frequency,
                    currentDuration: current.duration,
                    previousDosage: previous?.dosage ?? "",
                    previousFrequency: previous?.frequency ?? "",
                    status: status,
                    lastUpdated: current.date,
                    reportId: current.reportId,
                    isOptional: current.isOptional,
                    weeklySchedule: current.weeklySchedule,
                    notes: current.notes
                ))
            }
        }
        return out.sorted { ($0.patientName, $0.medicineName) < ($1.patientName, $1.medicineName) }
    }
}
