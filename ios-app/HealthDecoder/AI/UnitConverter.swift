import Foundation

/// Converts a lab value between the units different labs print for the SAME test, so a trend
/// line can be compared across reports (e.g. T3 in ng/mL at one lab vs nmol/L at another).
///
/// Direct port of `ai/UnitConverter.kt` — same specs, same factors, same null-on-unknown contract.
///
/// Used only when building trend charts — the report detail screen always shows the value
/// exactly as the report printed it. Conversions are deterministic, on-device, and use only
/// medically-verified constants; a pair with no known factor returns nil (the caller keeps the
/// reading separate and flags it rather than plotting a guessed value onto medical data).
///
/// Each test declares a CONVENTIONAL (Indian/US) unit and an SI unit, plus a multiplier for every
/// unit it can appear in that turns "1 of that unit" into the conventional unit (which is the base,
/// multiplier 1.0). Converting from→to is then `value * (fromMultiplier / toMultiplier)`, which
/// can't get a direction backwards. Which of the two units a chart standardises to is chosen once
/// by the user's `AppSettings` unit-system preference — see `standardUnit(for:system:)` — instead
/// of being whatever unit the first-scanned report happened to use.
///
/// Factor sources (standard clinical molar-mass conversions):
///  - Glucose: 1 mmol/L = 18.016 mg/dL
///  - Cholesterol/LDL/HDL: 1 mmol/L = 38.67 mg/dL
///  - Triglycerides: 1 mmol/L = 88.57 mg/dL
///  - Creatinine: 1 µmol/L = 0.011312 mg/dL  (÷88.4)
///  - Hemoglobin: 1 g/L = 0.1 g/dL
///  - Vitamin D (25-OH): 1 nmol/L = 0.4006 ng/mL  (÷2.496)
///  - Vitamin B12: 1 pmol/L = 1.355 pg/mL
///  - Total T3: 1 nmol/L = 0.651 ng/mL; 1 ng/dL = 0.01 ng/mL  (MW 651)
///  - Total T4: 1 nmol/L = 0.0777 µg/dL  (MW 776.87)
///  - WBC/Platelet count: 10³/µL ≡ 10⁹/L (same number); 1 cell/µL = 0.001 ×10³/µL;
///    Indian platelet reports: 1 lakh/µL = 100 ×10³/µL
///  - TSH: µIU/mL ≡ mIU/L ≡ µU/mL (1:1, handled in `canonicalizeUnitString`)
///  - HbA1c / SpO2 / Ejection Fraction: percentage only — no cross-unit conversion
enum UnitConverter {

    /// Which unit system a trend chart standardises every reading to.
    enum System {
        case conventional
        case si
    }

    /// Normalizes a printed unit to a canonical spelling: lowercases, maps the micro sign to "u",
    /// strips spaces, folds superscripts, and collapses pure-notation synonyms that mean the exact
    /// same thing (so e.g. "mg %" and "mg/dL", or "lakhs/cumm" and "lakh/µL", are recognized as
    /// identical). Returns the cleaned unit unchanged when it has no known synonym.
    static func canonicalizeUnitString(_ unit: String) -> String {
        var cleaned = unit.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            .replacingOccurrences(of: "µ", with: "u")
            .replacingOccurrences(of: "μ", with: "u")  // Greek mu, distinct code point from the micro sign
            .replacingOccurrences(of: "³", with: "3")
            .replacingOccurrences(of: "⁹", with: "9")
            .replacingOccurrences(of: "×", with: "x")
            .replacingOccurrences(of: " ", with: "")
        // Indian lab reports commonly print "Lt" for "Liter" (e.g. "mmol/Lt", "mEq/Lt") —
        // without this, "mmol/lt" and "mmol/l" are treated as two different, unconvertible
        // units even though they're the exact same unit spelled differently.
        if cleaned.hasSuffix("/lt") {
            cleaned = String(cleaned.dropLast(3)) + "/l"
        }

        switch cleaned {
        case "mg%", "mg/100ml", "mgs/dl", "mgs%", "mgpercent": return "mg/dl"
        case "gm%", "gm/dl", "gms/dl", "g%": return "g/dl"
        case "mcg/dl": return "ug/dl"
        case "micromol/l": return "umol/l"
        case "miu/l", "uu/ml", "uiu/l": return "uiu/ml"  // TSH: µIU/mL ≡ mIU/L ≡ µU/mL (1:1)
        case "percent", "percentage", "pct": return "%"
        // Cell counts — thousands-per-µL scale (same number as 10⁹/L)
        case "10^3/ul", "10e3/ul", "x10^3/ul", "10x3/ul", "thou/ul", "k/ul", "10^3/cumm": return "10^3/ul"
        case "10^9/l", "x10^9/l", "10e9/l": return "10^9/l"
        // Cell counts — per-µL scale (e.g. 7500, 250000)
        case "/ul", "cells/ul", "/cumm", "cells/cumm", "/mm3", "cells/mm3", "count/cumm": return "/ul"
        // Indian platelet convention — lakhs (100,000) per µL / cumm
        case "lakhs/cumm", "lakh/cumm", "lakhs/ul", "lakh/ul", "lacs/cumm", "lac/cumm",
             "lakhs/mm3", "lakh/mm3": return "lakh/ul"
        default: return cleaned
        }
    }

    /// A test's canonical units and the multiplier (to the conventional/base unit) for each unit
    /// it can be printed in. `conventional` and `si` must both be keys in `multipliers`.
    private struct Spec {
        let conventional: String
        let si: String
        let multipliers: [String: Double]
    }

    // category (lowercased) -> unit spec. Base unit is always the conventional one (multiplier 1.0).
    private static let specs: [String: Spec] = [
        "blood sugar": Spec(conventional: "mg/dl", si: "mmol/l", multipliers: ["mg/dl": 1.0, "mmol/l": 18.016]),
        "total cholesterol": Spec(conventional: "mg/dl", si: "mmol/l", multipliers: ["mg/dl": 1.0, "mmol/l": 38.67]),
        "ldl": Spec(conventional: "mg/dl", si: "mmol/l", multipliers: ["mg/dl": 1.0, "mmol/l": 38.67]),
        "hdl": Spec(conventional: "mg/dl", si: "mmol/l", multipliers: ["mg/dl": 1.0, "mmol/l": 38.67]),
        "triglycerides": Spec(conventional: "mg/dl", si: "mmol/l", multipliers: ["mg/dl": 1.0, "mmol/l": 88.57]),
        "creatinine": Spec(conventional: "mg/dl", si: "umol/l", multipliers: ["mg/dl": 1.0, "umol/l": 0.011312]),
        "hemoglobin": Spec(conventional: "g/dl", si: "g/l", multipliers: ["g/dl": 1.0, "g/l": 0.1]),
        "vitamin d": Spec(conventional: "ng/ml", si: "nmol/l", multipliers: ["ng/ml": 1.0, "nmol/l": 0.4006]),
        "vitamin b12": Spec(conventional: "pg/ml", si: "pmol/l", multipliers: ["pg/ml": 1.0, "pmol/l": 1.355]),
        "t3": Spec(conventional: "ng/ml", si: "nmol/l", multipliers: ["ng/ml": 1.0, "nmol/l": 0.651, "ng/dl": 0.01]),
        "t4": Spec(conventional: "ug/dl", si: "nmol/l", multipliers: ["ug/dl": 1.0, "nmol/l": 0.0777]),
        // µIU/mL ≡ mIU/L (1:1) — canonicalization already folds the spellings together.
        "tsh": Spec(conventional: "uiu/ml", si: "uiu/ml", multipliers: ["uiu/ml": 1.0]),
        // Counts: 10³/µL ≡ 10⁹/L; per-µL is ×1000 smaller; a lakh is 100 ×10³/µL.
        "wbc": Spec(conventional: "10^3/ul", si: "10^9/l",
                    multipliers: ["10^3/ul": 1.0, "10^9/l": 1.0, "/ul": 0.001]),
        "platelets": Spec(conventional: "10^3/ul", si: "10^9/l",
                          multipliers: ["10^3/ul": 1.0, "10^9/l": 1.0, "/ul": 0.001, "lakh/ul": 100.0]),
        // Percentage-only tests — no cross-unit conversion, just spelling normalisation.
        "hba1c": Spec(conventional: "%", si: "%", multipliers: ["%": 1.0]),
        "oxygen (spo2)": Spec(conventional: "%", si: "%", multipliers: ["%": 1.0]),
        "ejection fraction": Spec(conventional: "%", si: "%", multipliers: ["%": 1.0]),
        // Electrolytes — monovalent ions (Na+, K+, Cl-, HCO3-): mEq/L and mmol/L are numerically
        // IDENTICAL (valence 1), so both map to the same base with multiplier 1.0 — no guessing.
        "sodium": Spec(conventional: "mmol/l", si: "mmol/l", multipliers: ["mmol/l": 1.0, "meq/l": 1.0]),
        "potassium": Spec(conventional: "mmol/l", si: "mmol/l", multipliers: ["mmol/l": 1.0, "meq/l": 1.0]),
        "chloride": Spec(conventional: "mmol/l", si: "mmol/l", multipliers: ["mmol/l": 1.0, "meq/l": 1.0]),
        "bicarbonate": Spec(conventional: "mmol/l", si: "mmol/l", multipliers: ["mmol/l": 1.0, "meq/l": 1.0]),
        // Minerals — divalent ions (Ca2+, Mg2+): mEq/L = 2 x mmol/L, so NOT 1:1 with mmol/L.
        // mg/dL is the conventional Indian-report unit; standard molar-mass conversions below.
        "calcium": Spec(conventional: "mg/dl", si: "mmol/l",
                        multipliers: ["mg/dl": 1.0, "mmol/l": 4.008, "meq/l": 2.004]),
        "magnesium": Spec(conventional: "mg/dl", si: "mmol/l",
                          multipliers: ["mg/dl": 1.0, "mmol/l": 2.431, "meq/l": 1.2153]),
        // Phosphorus is monatomic (no mEq/L convention in practice) — just mg/dL <-> mmol/L.
        "phosphorus": Spec(conventional: "mg/dl", si: "mmol/l", multipliers: ["mg/dl": 1.0, "mmol/l": 3.097]),
    ]

    // Human-facing spelling for each canonical (lowercased) unit — the internal keys are
    // case/notation-folded for robust matching, but charts should show "mg/dL", not "mg/dl".
    // canonicalizeUnitString() maps every pretty spelling back to its key, so returning a pretty
    // unit from standardUnit(for:system:) still converts and compares correctly downstream.
    private static let prettySpelling: [String: String] = [
        "mg/dl": "mg/dL", "mmol/l": "mmol/L", "umol/l": "µmol/L",
        "g/dl": "g/dL", "g/l": "g/L",
        "ng/ml": "ng/mL", "ng/dl": "ng/dL", "nmol/l": "nmol/L",
        "pg/ml": "pg/mL", "pmol/l": "pmol/L",
        "ug/dl": "µg/dL", "uiu/ml": "µIU/mL",
        "10^3/ul": "10³/µL", "10^9/l": "10⁹/L", "/ul": "/µL", "lakh/ul": "lakh/µL",
        "%": "%",
    ]

    /// The unit a chart should standardise `category` to under the chosen `system`, or nil when
    /// the test isn't one this converter knows (the caller then falls back to the first-seen unit).
    static func standardUnit(for category: String, system: System) -> String? {
        guard let spec = specs[category.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()]
        else { return nil }
        let canonical = system == .si ? spec.si : spec.conventional
        return prettySpelling[canonical] ?? canonical
    }

    /// Converts `value` of `category` from `fromUnit` to `toUnit`. Returns the converted value,
    /// or nil when there is no verified factor for that pair (so the caller must NOT plot it as
    /// comparable). Same-unit (after canonicalization) returns `value` unchanged.
    static func convert(category: String, value: Float, fromUnit: String, toUnit: String) -> Float? {
        let from = canonicalizeUnitString(fromUnit)
        let to = canonicalizeUnitString(toUnit)
        if from == to { return value }
        guard
            let table = specs[category.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()]?.multipliers,
            let fromMultiplier = table[from],
            let toMultiplier = table[to]
        else { return nil }
        return Float(Double(value) * (fromMultiplier / toMultiplier))
    }
}
