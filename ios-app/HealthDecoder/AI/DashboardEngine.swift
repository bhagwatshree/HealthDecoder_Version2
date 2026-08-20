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

extension DashboardEngine {

    /// Collapses the many ways labs spell the same test into one canonical name, so readings from
    /// different labs land on the same trend line and the same tip rule.
    ///
    /// Direct port of `DashboardEngine.kt`'s `canonicalParamName`. ORDER IS LOAD-BEARING: several
    /// rules only work because a more specific one already claimed the string (VLDL before Total
    /// Cholesterol, MCHC before MCH), and the negative guards keep genuinely different tests apart
    /// — urine creatinine is not serum creatinine, MCH is not hemoglobin, and eAG is calculated
    /// from HbA1c rather than measured. Changing the order silently mixes unrelated results onto
    /// one line, which is a clinical error, not a cosmetic one.
    static func canonicalParamName(_ raw: String) -> String {
        let n = raw.lowercased()
        func has(_ s: String) -> Bool { n.contains(s) }
        func word(_ pattern: String) -> Bool {
            n.range(of: "\\b\(pattern)\\b", options: .regularExpression) != nil
        }

        if has("hba1c") || has("glycated") || has("glycosylated") { return "HbA1c" }
        // Blood sugar readings all plot on ONE line regardless of test condition (fasting /
        // post-meal / random). Excluded: urine dipstick glucose (semi-quantitative, not a mg/dL
        // reading) and "Estimated Average Glucose (eAG)", which is derived from HbA1c.
        if !has("urine") && !has("estimated average") && !has("eag")
            && (has("glucose") || has("sugar") || has("fbs") || has("bsf")
                || has("ppbs") || has("rbs") || has("grbs")) { return "Blood Sugar" }
        if has("tsh") || (has("thyroid") && has("stimulating")) { return "TSH" }
        // Word-boundary match, not exact-equals — real reports say "T3 (Total), Serum", "Free T3".
        if has("triiodothyronine") || has("free t3") || has("ft3") || word("t3") { return "T3" }
        if has("thyroxine") || has("free t4") || has("ft4") || word("t4") { return "T4" }
        // Exclude MCH / MCHC ("mean corpuscular hemoglobin…"), separate CBC indices.
        if (has("hemoglobin") || has("haemoglobin") || n == "hb" || n == "hgb")
            && !has("corpuscular") && !has("mch") { return "Hemoglobin" }
        if has("wbc") || has("white blood") || has("leucocyte") || has("leukocyte") { return "WBC" }
        // Exclude MPV / platelet distribution width, which aren't the platelet count.
        if has("platelet") && !has("volume") && !has("mpv") && !has("distribution") { return "Platelets" }
        // VLDL first: "VLDL Cholesterol" must not fall through to Total Cholesterol.
        if has("vldl") { return "VLDL" }
        if has("ldl") { return "LDL" }
        if has("hdl") { return "HDL" }
        if has("triglyceride") { return "Triglycerides" }
        if has("cholesterol") { return "Total Cholesterol" }
        // Exclude urinary/spot creatinine and the Albumin:Creatinine Ratio.
        if has("creatinine") && !has("urin") { return "Creatinine" }
        if has("spo2") || has("oxygen") || has("saturation") { return "Oxygen (SpO2)" }
        if has("ejection") || n == "ef" || has("lvef") { return "Ejection Fraction" }
        if has("vitamin d") || has("25-oh") || has("25 oh") { return "Vitamin D" }
        if has("b12") || has("cobalamin") { return "Vitamin B12" }

        // Coagulation (PT/INR) — critical for anyone on a blood thinner.
        if has("inr") || has("international normali") { return "INR" }
        if has("prothrombin") || word("pt") { return "Prothrombin Time" }
        if has("aptt") || has("activated partial") { return "APTT" }
        if has("fibrinogen") { return "Fibrinogen" }
        if has("d-dimer") || has("d dimer") { return "D-Dimer" }

        // Electrolytes & minerals.
        if has("sodium") || word("na\\+?") { return "Sodium" }
        // Bare "K" means potassium — but never inside "Vitamin K".
        if has("potassium") || (!has("vitamin") && word("k\\+?")) { return "Potassium" }
        if has("chloride") { return "Chloride" }
        if has("bicarbonate") || has("hco3") { return "Bicarbonate" }
        if has("calcium") && !has("urin") { return "Calcium" }
        if has("magnesium") { return "Magnesium" }
        if has("phosphor") || has("phosphate") { return "Phosphorus" }

        // Kidney.
        if has("egfr") || has("gfr") { return "eGFR" }
        if has("uric acid") { return "Uric Acid" }
        if (has("urea") || has("bun")) && !has("urin") { return "Urea" }

        // Liver.
        if has("bilirubin") && has("direct") { return "Bilirubin (Direct)" }
        if has("bilirubin") { return "Bilirubin (Total)" }
        if has("sgot") || has("aspartate") || word("ast") { return "SGOT (AST)" }
        if has("sgpt") || has("alanine") || word("alt") { return "SGPT (ALT)" }
        if has("alkaline phosphat") || word("alp") { return "Alkaline Phosphatase" }
        if has("ggt") || has("gamma gluta") || has("gamma-gluta") { return "GGT" }
        if has("globulin") && !has("ratio") { return "Globulin" }
        if has("albumin") && !has("urin") && !has("micro") && !has("ratio") { return "Albumin" }
        if has("protein") && !has("urin") && !has("c-reactive") { return "Total Protein" }

        // Heart.
        if has("troponin") { return "Troponin" }
        if has("bnp") || has("natriuretic") { return "BNP" }
        if has("ck-mb") || has("ckmb") || has("creatine kinase") || has("cpk") { return "CPK / CK-MB" }

        // Blood count (CBC) extras.
        if has("rbc") || has("red blood") || has("erythrocyte count") { return "RBC Count" }
        if has("hematocrit") || has("haematocrit") || has("pcv") { return "Hematocrit (PCV)" }
        if has("mchc") { return "MCHC" }
        if has("mcv") || has("mean corpuscular volume") { return "MCV" }
        if has("mch") || has("mean corpuscular h") { return "MCH" }
        if has("rdw") { return "RDW" }
        if has("neutrophil") { return "Neutrophils" }
        if has("lymphocyte") { return "Lymphocytes" }
        if has("monocyte") { return "Monocytes" }
        if has("eosinophil") { return "Eosinophils" }
        if has("basophil") { return "Basophils" }
        if has("esr") || has("sedimentation") { return "ESR" }

        // Vitamins, iron & inflammation.
        if has("ferritin") { return "Ferritin" }
        if has("tibc") || has("iron binding") { return "TIBC" }
        if has("iron") { return "Iron" }
        if has("folate") || has("folic") { return "Folate" }
        if has("c-reactive") || has("crp") { return "CRP" }

        return raw.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
