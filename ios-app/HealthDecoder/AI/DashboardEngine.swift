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

// MARK: - Trend series

extension DashboardEngine {

    static let categoryAll = "All tests"
    static let categoryOther = "Other tests"

    /// Trend panels shown in the Trends picker, in display order. A test lands in the FIRST
    /// category listing its canonical name; anything unmatched falls into "Other tests", so no
    /// scanned value can ever be hidden from the screen.
    static let trendCategories: [(name: String, members: [String])] = [
        ("Blood Count", [
            "Hemoglobin", "RBC Count", "WBC", "Platelets", "Hematocrit (PCV)",
            "MCV", "MCH", "MCHC", "RDW",
            "Neutrophils", "Lymphocytes", "Monocytes", "Eosinophils", "Basophils", "ESR",
        ]),
        ("Diabetes", ["Blood Sugar", "HbA1c"]),
        ("Heart & Cholesterol", [
            "Total Cholesterol", "LDL", "HDL", "VLDL", "Triglycerides",
            "Ejection Fraction", "Oxygen (SpO2)", "Troponin", "BNP", "CPK / CK-MB",
        ]),
        ("Coagulation (PT/INR)", ["Prothrombin Time", "INR", "APTT", "Fibrinogen", "D-Dimer"]),
        ("Electrolytes & Minerals", [
            "Sodium", "Potassium", "Chloride", "Calcium", "Magnesium", "Phosphorus", "Bicarbonate",
        ]),
        ("Kidney & Liver", [
            "Creatinine", "Urea", "Uric Acid", "eGFR",
            "Bilirubin (Total)", "Bilirubin (Direct)", "SGOT (AST)", "SGPT (ALT)",
            "Alkaline Phosphatase", "GGT", "Total Protein", "Albumin", "Globulin",
        ]),
        ("Thyroid & Vitamins", [
            "TSH", "T3", "T4", "Vitamin D", "Vitamin B12", "Folate", "Iron", "Ferritin", "TIBC", "CRP",
        ]),
    ]

    /// The panel a canonical test name belongs to — `categoryOther` when it matches none.
    static func categoryOf(_ canonicalName: String) -> String {
        trendCategories.first { $0.members.contains(canonicalName) }?.name ?? categoryOther
    }

    /// Test condition for a blood-sugar reading (Fasting / PP / Random), or "" if unspecified —
    /// shown per-point alongside the value, since a fasting and a post-meal reading aren't
    /// directly comparable even though they share one trend line.
    static func bloodSugarContext(_ raw: String) -> String {
        let n = raw.lowercased()
        if n.contains("urine") { return "" }
        if n.contains("fbs") || n.contains("bsf") || n.contains("fasting") { return "Fasting" }
        if n.contains("ppbs") || n.contains("post prandial") || n.contains("postprandial")
            || n.contains("post-meal") || n.contains("post meal") || n.contains("pp2")
            || (n.contains("pp") && (n.contains("sugar") || n.contains("glucose"))) { return "PP" }
        if n.contains("rbs") || n.contains("grbs") || n.contains("random") { return "Random" }
        return ""
    }

    /// The canonical trend category for a parameter, or nil if it should NOT be trended (excluded
    /// by the AI, or a non-numeric reading masquerading under a numeric test's name).
    static func trendCategoryOf(_ p: TestParameter) -> String? {
        if p.name.trimmingCharacters(in: .whitespaces).isEmpty { return nil }
        if p.excludeFromTrend == true { return nil }
        // Normalize BOTH paths through canonicalParamName: the AI's own trendCategory is free text
        // ("Serum Sodium", "S. Creatinine"), so without this the same test fragments into separate
        // lines depending on which report it came from.
        let raw = (p.trendCategory?.isEmpty == false) ? p.trendCategory! : p.name
        var canon = canonicalParamName(raw)
        // A urine dipstick's semi-quantitative "++" (or "Negative"/"Trace") can share a bare name
        // like "Glucose" with no "urine" qualifier — never let a non-numeric reading merge onto a
        // numeric mg/dL line regardless of spelling.
        if canon == "Blood Sugar" && Float(p.value) == nil {
            canon = p.name.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return canon
    }

    /// The unit each trendable test is standardized to: the FIRST non-blank unit seen for it in
    /// chronological order, so later readings in a different unit convert to it rather than the
    /// axis flipping around.
    static func resolveStandardUnits(_ reports: [MedicalReport]) -> [String: String] {
        var out: [String: String] = [:]
        for report in chronological(reports) {
            for p in report.testResults?.parameters ?? [] {
                guard let canon = trendCategoryOf(p) else { continue }
                let unit = p.unit.trimmingCharacters(in: .whitespacesAndNewlines)
                if !unit.isEmpty && out[canon] == nil { out[canon] = unit }
            }
        }
        return out
    }

    /// Parses a printed reference range into numeric (low, high) bounds, nil where a bound is
    /// open. Handles the common lab spellings: "70-100", "70 – 100 mg/dL", "<200", "> 40",
    /// "Up to 5.5", "Less than 200". Returns (nil, nil) for non-numeric ranges ("Negative").
    static func parseRefRange(_ raw: String?) -> (low: Float?, high: Float?) {
        let s = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if s.isEmpty { return (nil, nil) }

        let numbers: [Float] = {
            // The sign is only a sign when nothing numeric precedes it. Without the lookbehind
            // "70-100" reads as [70, -100] and the normal band is shaded from 70 down to -100 —
            // the separating hyphen gets eaten as a minus. (Kotlin's DashboardEngine.parseRefRange
            // uses the unguarded pattern and has this bug today; en-dash ranges like "13 – 17"
            // happen to escape it, ASCII-hyphen ranges do not.)
            guard let re = try? NSRegularExpression(pattern: "(?<![\\d.])[-+]?\\d*\\.?\\d+") else { return [] }
            let range = NSRange(s.startIndex..<s.endIndex, in: s)
            return re.matches(in: s, range: range).compactMap {
                guard let r = Range($0.range, in: s) else { return nil }
                return Float(s[r])
            }
        }()

        let upperOpeners = ["<", "less than", "up to", "upto", "below"]
        let lowerOpeners = [">", "greater than", "more than", "above", "at least"]
        if upperOpeners.contains(where: { s.hasPrefix($0) }) { return (nil, numbers.first) }
        if lowerOpeners.contains(where: { s.hasPrefix($0) }) { return (numbers.first, nil) }

        let hasSpan = s.range(of: "\\d\\s*(?:-|–|—|to)\\s*\\d", options: .regularExpression) != nil
        if numbers.count >= 2 && hasSpan { return (numbers[0], numbers[1]) }
        // A single bare number with no direction — can't place a band safely.
        return (nil, nil)
    }

    /// Formats a converted value without noisy trailing zeros (8.0 -> "8", 0.4400 -> "0.44").
    static func formatNumber(_ v: Float) -> String {
        if v == Float(Int(v)) { return String(Int(v)) }
        var s = String(format: "%.2f", v)
        while s.hasSuffix("0") { s.removeLast() }
        if s.hasSuffix(".") { s.removeLast() }
        return s
    }

    private static func chronological(_ reports: [MedicalReport]) -> [MedicalReport] {
        reports.sorted {
            let a = ($0.reportDate?.isEmpty == false ? $0.reportDate! : $0.createdAt)
            let b = ($1.reportDate?.isEmpty == false ? $1.reportDate! : $1.createdAt)
            return a < b
        }
    }

    /// Builds the per-test trend series for one patient. Port of `buildHealthSummary`'s trend half.
    static func buildHealthSummary(
        reports: [MedicalReport],
        standardUnits: [String: String] = [:]
    ) -> HealthSummary {
        guard !reports.isEmpty else {
            return HealthSummary(overallNarrative: "No reports found for this patient in the selected period.")
        }

        // At most ONE value per test per report, so a single report can't put two points on the
        // same line (e.g. Hemoglobin and MCH collapsing together).
        var order: [String] = []
        var byParam: [String: [TrendDataPoint]] = [:]
        var seenPerReport = Set<String>()

        for report in chronological(reports) {
            let date = String((report.reportDate?.isEmpty == false ? report.reportDate! : report.createdAt)
                .split(separator: "T").first ?? "")
            for p in report.testResults?.parameters ?? [] {
                guard let canon = trendCategoryOf(p) else { continue }
                guard seenPerReport.insert("\(canon)|\(report.id)").inserted else { continue }

                let context = (p.trendCondition?.isEmpty == false)
                    ? p.trendCondition!
                    : (canon == "Blood Sugar" ? bloodSugarContext(p.name) : "")

                // Standardize this reading to the test's locked unit so the line is comparable
                // across labs. Same unit ignoring notation → adopt the standard spelling. Different
                // unit with a verified factor → convert. Different unit with no factor → keep as
                // printed and let the chart flag it. Never guess.
                let rawUnit = p.unit.trimmingCharacters(in: .whitespacesAndNewlines)
                var value = p.value
                var unit = rawUnit
                var originalValue = ""
                var originalUnit = ""
                var converted = false

                if let std = standardUnits[canon], !rawUnit.isEmpty {
                    if UnitConverter.canonicalizeUnitString(rawUnit) == UnitConverter.canonicalizeUnitString(std) {
                        unit = std
                    } else if let num = Float(p.value),
                              let conv = UnitConverter.convert(category: canon, value: num, fromUnit: rawUnit, toUnit: std) {
                        value = formatNumber(conv)
                        unit = std
                        originalValue = p.value
                        originalUnit = rawUnit
                        converted = true
                    }
                }

                // Normal-range band, expressed in the same unit as the plotted value (converting
                // the bounds too when the value was converted).
                var (refLow, refHigh) = parseRefRange(p.referenceRange)
                if converted {
                    refLow = refLow.flatMap { UnitConverter.convert(category: canon, value: $0, fromUnit: rawUnit, toUnit: unit) }
                    refHigh = refHigh.flatMap { UnitConverter.convert(category: canon, value: $0, fromUnit: rawUnit, toUnit: unit) }
                }

                if byParam[canon] == nil { order.append(canon) }
                byParam[canon, default: []].append(
                    TrendDataPoint(
                        date: date, value: value, unit: unit, status: p.status ?? "",
                        reportId: report.id, context: context,
                        originalValue: originalValue, originalUnit: originalUnit,
                        converted: converted, refLow: refLow, refHigh: refHigh
                    )
                )
            }
        }

        let trends: [ParameterTrend] = order.map { name in
            let points = byParam[name] ?? []
            var direction = "stable"
            let numeric = points.filter { $0.numericValue != nil }
            if numeric.count >= 2,
               let first = numeric.first?.numericValue, let last = numeric.last?.numericValue {
                let lastStatus = (numeric.last?.status ?? "").lowercased()
                let firstStatus = (numeric.first?.status ?? "").lowercased()
                if lastStatus == "normal" && firstStatus != "normal" {
                    direction = "improving"
                } else if lastStatus != "normal" && firstStatus == "normal" {
                    direction = "worsening"
                } else {
                    let base = abs(first) == 0 ? 1 : abs(first)
                    if abs(last - first) / base < 0.05 { direction = "stable" }
                    else if last < first { direction = "decreasing" }
                    else { direction = "increasing" }
                }
            }
            return ParameterTrend(name: name, dataPoints: points, trend: direction)
        }

        return HealthSummary(overallNarrative: "", parameterTrends: trends)
    }
}
