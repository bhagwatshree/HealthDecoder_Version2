package com.healthdecoder.app.ai

/**
 * Converts a lab value between the units different labs print for the SAME test, so a trend
 * line can be compared across reports (e.g. T3 in ng/mL at one lab vs nmol/L at another).
 *
 * Used only when building trend charts — the report detail screen always shows the value
 * exactly as the report printed it. Conversions are deterministic, on-device, and use only
 * medically-verified constants; a pair with no known factor returns null (the caller keeps the
 * reading separate and flags it rather than plotting a guessed value onto medical data).
 *
 * Each test declares a CONVENTIONAL (Indian/US) unit and an SI unit, plus a multiplier for every
 * unit it can appear in that turns "1 of that unit" into the conventional unit (which is the base,
 * multiplier 1.0). Converting from→to is then `value * (fromMultiplier / toMultiplier)`, which
 * can't get a direction backwards. Which of the two units a chart standardises to is chosen once
 * by the user's [com.healthdecoder.app.local.AppSettings] unit-system preference — see
 * [standardUnitFor] — instead of being whatever unit the first-scanned report happened to use.
 *
 * Factor sources (standard clinical molar-mass conversions):
 *  - Glucose: 1 mmol/L = 18.016 mg/dL
 *  - Cholesterol/LDL/HDL: 1 mmol/L = 38.67 mg/dL
 *  - Triglycerides: 1 mmol/L = 88.57 mg/dL
 *  - Creatinine: 1 µmol/L = 0.011312 mg/dL  (÷88.4)
 *  - Hemoglobin: 1 g/L = 0.1 g/dL
 *  - Vitamin D (25-OH): 1 nmol/L = 0.4006 ng/mL  (÷2.496)
 *  - Vitamin B12: 1 pmol/L = 1.355 pg/mL
 *  - Total T3: 1 nmol/L = 0.651 ng/mL; 1 ng/dL = 0.01 ng/mL  (MW 651)
 *  - Total T4: 1 nmol/L = 0.0777 µg/dL  (MW 776.87)
 *  - WBC/Platelet count: 10³/µL ≡ 10⁹/L (same number); 1 cell/µL = 0.001 ×10³/µL;
 *    Indian platelet reports: 1 lakh/µL = 100 ×10³/µL
 *  - TSH: µIU/mL ≡ mIU/L ≡ µU/mL (1:1, handled in [canonicalizeUnitString])
 *  - HbA1c / SpO2 / Ejection Fraction: percentage only — no cross-unit conversion
 */
object UnitConverter {

    /** Which unit system a trend chart standardises every reading to. */
    enum class System { CONVENTIONAL, SI }

    /**
     * Normalizes a printed unit to a canonical spelling: lowercases, maps the micro sign to "u",
     * strips spaces, folds superscripts, and collapses pure-notation synonyms that mean the exact
     * same thing (so e.g. "mg %" and "mg/dL", or "lakhs/cumm" and "lakh/µL", are recognized as
     * identical). Returns the cleaned unit unchanged when it has no known synonym.
     */
    fun canonicalizeUnitString(unit: String): String {
        val cleaned = unit.trim().lowercase()
            .replace('µ', 'u')
            .replace("μ", "u")  // Greek mu, distinct code point from the micro sign above
            .replace('³', '3')
            .replace('⁹', '9')
            .replace('×', 'x')
            .replace(" ", "")
            // Indian lab reports commonly print "Lt" for "Liter" (e.g. "mmol/Lt", "mEq/Lt") —
            // without this, "mmol/lt" and "mmol/l" are treated as two different, unconvertible
            // units even though they're the exact same unit spelled differently.
            .replace(Regex("/lt$"), "/l")
        return when (cleaned) {
            "mg%", "mg/100ml", "mgs/dl", "mgs%", "mgpercent" -> "mg/dl"
            "gm%", "gm/dl", "gms/dl", "g%" -> "g/dl"
            "mcg/dl" -> "ug/dl"
            "micromol/l" -> "umol/l"
            "miu/l", "uu/ml", "uiu/l" -> "uiu/ml"  // TSH: µIU/mL ≡ mIU/L ≡ µU/mL (1:1)
            "percent", "percentage", "pct" -> "%"
            // Cell counts — thousands-per-µL scale (same number as 10⁹/L)
            "10^3/ul", "10e3/ul", "x10^3/ul", "10x3/ul", "thou/ul", "k/ul", "10^3/cumm" -> "10^3/ul"
            "10^9/l", "x10^9/l", "10e9/l" -> "10^9/l"
            // Cell counts — per-µL scale (e.g. 7500, 250000)
            "/ul", "cells/ul", "/cumm", "cells/cumm", "/mm3", "cells/mm3", "count/cumm" -> "/ul"
            // Indian platelet convention — lakhs (100,000) per µL / cumm
            "lakhs/cumm", "lakh/cumm", "lakhs/ul", "lakh/ul", "lacs/cumm", "lac/cumm", "lakhs/mm3", "lakh/mm3" -> "lakh/ul"
            else -> cleaned
        }
    }

    /** A test's canonical units and the multiplier (to the conventional/base unit) for each unit
     *  it can be printed in. [conventional] and [si] must both be keys in [multipliers]. */
    private data class Spec(
        val conventional: String,
        val si: String,
        val multipliers: Map<String, Double>
    )

    // category (lowercased) -> unit spec. Base unit is always the conventional one (multiplier 1.0).
    private val specs: Map<String, Spec> = mapOf(
        "blood sugar" to Spec("mg/dl", "mmol/l", mapOf("mg/dl" to 1.0, "mmol/l" to 18.016)),
        "total cholesterol" to Spec("mg/dl", "mmol/l", mapOf("mg/dl" to 1.0, "mmol/l" to 38.67)),
        "ldl" to Spec("mg/dl", "mmol/l", mapOf("mg/dl" to 1.0, "mmol/l" to 38.67)),
        "hdl" to Spec("mg/dl", "mmol/l", mapOf("mg/dl" to 1.0, "mmol/l" to 38.67)),
        "triglycerides" to Spec("mg/dl", "mmol/l", mapOf("mg/dl" to 1.0, "mmol/l" to 88.57)),
        "creatinine" to Spec("mg/dl", "umol/l", mapOf("mg/dl" to 1.0, "umol/l" to 0.011312)),
        "hemoglobin" to Spec("g/dl", "g/l", mapOf("g/dl" to 1.0, "g/l" to 0.1)),
        "vitamin d" to Spec("ng/ml", "nmol/l", mapOf("ng/ml" to 1.0, "nmol/l" to 0.4006)),
        "vitamin b12" to Spec("pg/ml", "pmol/l", mapOf("pg/ml" to 1.0, "pmol/l" to 1.355)),
        "t3" to Spec("ng/ml", "nmol/l", mapOf("ng/ml" to 1.0, "nmol/l" to 0.651, "ng/dl" to 0.01)),
        "t4" to Spec("ug/dl", "nmol/l", mapOf("ug/dl" to 1.0, "nmol/l" to 0.0777)),
        // µIU/mL ≡ mIU/L (1:1) — canonicalization already folds the spellings together.
        "tsh" to Spec("uiu/ml", "uiu/ml", mapOf("uiu/ml" to 1.0)),
        // Counts: 10³/µL ≡ 10⁹/L; per-µL is ×1000 smaller; a lakh is 100 ×10³/µL.
        "wbc" to Spec("10^3/ul", "10^9/l",
            mapOf("10^3/ul" to 1.0, "10^9/l" to 1.0, "/ul" to 0.001)),
        "platelets" to Spec("10^3/ul", "10^9/l",
            mapOf("10^3/ul" to 1.0, "10^9/l" to 1.0, "/ul" to 0.001, "lakh/ul" to 100.0)),
        // Percentage-only tests — no cross-unit conversion, just spelling normalisation.
        "hba1c" to Spec("%", "%", mapOf("%" to 1.0)),
        "oxygen (spo2)" to Spec("%", "%", mapOf("%" to 1.0)),
        "ejection fraction" to Spec("%", "%", mapOf("%" to 1.0)),
        // Electrolytes — monovalent ions (Na+, K+, Cl-, HCO3-): mEq/L and mmol/L are numerically
        // IDENTICAL (valence 1), so both map to the same base with multiplier 1.0 — no guessing.
        "sodium" to Spec("mmol/l", "mmol/l", mapOf("mmol/l" to 1.0, "meq/l" to 1.0)),
        "potassium" to Spec("mmol/l", "mmol/l", mapOf("mmol/l" to 1.0, "meq/l" to 1.0)),
        "chloride" to Spec("mmol/l", "mmol/l", mapOf("mmol/l" to 1.0, "meq/l" to 1.0)),
        "bicarbonate" to Spec("mmol/l", "mmol/l", mapOf("mmol/l" to 1.0, "meq/l" to 1.0)),
        // Minerals — divalent ions (Ca2+, Mg2+): mEq/L = 2 x mmol/L, so NOT 1:1 with mmol/L.
        // mg/dL is the conventional Indian-report unit; standard molar-mass conversions below.
        "calcium" to Spec("mg/dl", "mmol/l", mapOf("mg/dl" to 1.0, "mmol/l" to 4.008, "meq/l" to 2.004)),
        "magnesium" to Spec("mg/dl", "mmol/l", mapOf("mg/dl" to 1.0, "mmol/l" to 2.431, "meq/l" to 1.2153)),
        // Phosphorus is monatomic (no mEq/L convention in practice) — just mg/dL <-> mmol/L.
        "phosphorus" to Spec("mg/dl", "mmol/l", mapOf("mg/dl" to 1.0, "mmol/l" to 3.097)),
    )

    // Human-facing spelling for each canonical (lowercased) unit — the internal keys are
    // case/notation-folded for robust matching, but charts should show "mg/dL", not "mg/dl".
    // canonicalizeUnitString() maps every pretty spelling back to its key, so returning a pretty
    // unit from standardUnitFor still converts and compares correctly downstream.
    private val prettySpelling = mapOf(
        "mg/dl" to "mg/dL", "mmol/l" to "mmol/L", "umol/l" to "µmol/L",
        "g/dl" to "g/dL", "g/l" to "g/L",
        "ng/ml" to "ng/mL", "ng/dl" to "ng/dL", "nmol/l" to "nmol/L",
        "pg/ml" to "pg/mL", "pmol/l" to "pmol/L",
        "ug/dl" to "µg/dL", "uiu/ml" to "µIU/mL",
        "10^3/ul" to "10³/µL", "10^9/l" to "10⁹/L", "/ul" to "/µL", "lakh/ul" to "lakh/µL",
        "%" to "%",
    )

    /**
     * The unit a chart should standardise [category] to under the chosen [system], or null when
     * the test isn't one this converter knows (the caller then falls back to the first-seen unit).
     */
    fun standardUnitFor(category: String, system: System): String? {
        val spec = specs[category.trim().lowercase()] ?: return null
        val canonical = if (system == System.SI) spec.si else spec.conventional
        return prettySpelling[canonical] ?: canonical
    }

    /**
     * Converts [value] of [category] from [fromUnit] to [toUnit]. Returns the converted value,
     * or null when there is no verified factor for that pair (so the caller must NOT plot it as
     * comparable). Same-unit (after canonicalization) returns [value] unchanged.
     */
    fun convert(category: String, value: Float, fromUnit: String, toUnit: String): Float? {
        val from = canonicalizeUnitString(fromUnit)
        val to = canonicalizeUnitString(toUnit)
        if (from == to) return value
        val table = specs[category.trim().lowercase()]?.multipliers ?: return null
        val fromMult = table[from] ?: return null
        val toMult = table[to] ?: return null
        return (value * (fromMult / toMult)).toFloat()
    }
}
