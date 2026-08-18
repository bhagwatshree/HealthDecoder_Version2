package com.healthdecoder.app.util

/**
 * Curated, factual, plain-language descriptions of common lab tests, so a non-medical person
 * understands what each level on a graph means. This is a fixed reference (NOT AI-generated)
 * to avoid any inaccuracy in medical data. Descriptions explain what the test measures and
 * the general meaning of high/low — they are educational, not a diagnosis.
 */
data class TestInfo(val title: String, val description: String)

object TestReference {

    // Order matters: more specific entries are matched before general ones.
    private val entries: List<Pair<List<String>, TestInfo>> = listOf(
        listOf("hba1c", "glycated", "glycosylated") to TestInfo(
            "HbA1c (Average Blood Sugar)",
            "Your average blood sugar over the past 2–3 months. Used to diagnose and monitor diabetes."
        ),
        listOf("mchc") to TestInfo(
            "MCHC — Mean Corpuscular Hemoglobin Concentration",
            "The average concentration of hemoglobin packed inside your red blood cells. Used with MCH and MCV to understand the type of anaemia. It is NOT the same as your Hemoglobin level."
        ),
        listOf("mch", "mean corpuscular hemoglobin", "mean corpuscular haemoglobin") to TestInfo(
            "MCH — Mean Corpuscular Hemoglobin",
            "The average amount of hemoglobin inside a single red blood cell. It helps classify anaemia. This is different from your total Hemoglobin level."
        ),
        listOf("mcv", "mean corpuscular volume") to TestInfo(
            "MCV — Mean Corpuscular Volume",
            "The average size of your red blood cells. Cells larger or smaller than normal point to different causes of anaemia."
        ),
        listOf("rdw") to TestInfo(
            "RDW — Red Cell Distribution Width",
            "How much your red blood cells vary in size. Higher values can be an early clue to certain anaemias."
        ),
        listOf("mpv") to TestInfo(
            "MPV — Mean Platelet Volume",
            "The average size of your platelets, which can give clues about how platelets are being produced."
        ),
        listOf("hemoglobin", "haemoglobin", "hgb") to TestInfo(
            "Hemoglobin",
            "The protein in red blood cells that carries oxygen around your body. Low levels can mean anaemia (tiredness, paleness); high levels can occur with dehydration or other conditions."
        ),
        listOf("hematocrit", "haematocrit", "pcv", "packed cell") to TestInfo(
            "Hematocrit (PCV)",
            "The percentage of your blood made up of red blood cells. Low can indicate anaemia; high can indicate dehydration."
        ),
        listOf("wbc", "white blood", "leucocyte", "leukocyte", "total leucocyte", "tlc") to TestInfo(
            "WBC — White Blood Cell Count",
            "Cells that fight infection. High counts often suggest infection or inflammation; low counts can affect your immunity."
        ),
        listOf("platelet") to TestInfo(
            "Platelet Count",
            "Cell fragments that help your blood clot. Low levels raise bleeding risk; very high levels can affect clotting."
        ),
        listOf("rbc", "red blood cell", "red cell count") to TestInfo(
            "RBC — Red Blood Cell Count",
            "The number of red blood cells that carry oxygen. Abnormal counts can relate to anaemia or dehydration."
        ),
        listOf("tsh", "thyroid stimulating") to TestInfo(
            "TSH — Thyroid Stimulating Hormone",
            "A hormone that controls your thyroid gland. HIGH TSH usually means an under-active thyroid (slow metabolism); LOW TSH an over-active thyroid."
        ),
        listOf("free t3", "triiodothyronine") to TestInfo("T3 (Thyroid Hormone)", "A thyroid hormone that regulates metabolism (how your body uses energy). Affects weight, energy, heart rate and mood."),
        listOf("free t4", "thyroxine") to TestInfo("T4 (Thyroid Hormone)", "A thyroid hormone that regulates metabolism. Works together with T3 and TSH to control your energy levels."),
        listOf("t3") to TestInfo("T3 (Thyroid Hormone)", "A thyroid hormone that regulates metabolism (how your body uses energy)."),
        listOf("t4") to TestInfo("T4 (Thyroid Hormone)", "A thyroid hormone that regulates metabolism, working with T3 and TSH."),
        listOf("hba1c") to TestInfo("HbA1c (Average Blood Sugar)", "Your average blood sugar over the past 2–3 months."),
        listOf("glucose", "sugar", "fbs", "ppbs", "rbs") to TestInfo(
            "Blood Sugar (Glucose)",
            "The amount of sugar in your blood. Consistently high levels relate to diabetes; very low levels can cause dizziness or weakness."
        ),
        listOf("vldl") to TestInfo("VLDL Cholesterol", "A type of cholesterol that carries triglycerides. High levels can add to heart-disease risk."),
        listOf("ldl") to TestInfo("LDL — 'Bad' Cholesterol", "Cholesterol that can build up in arteries. Lower levels are generally better for your heart."),
        listOf("hdl") to TestInfo("HDL — 'Good' Cholesterol", "Cholesterol that helps remove other cholesterol from your blood. Higher levels are generally protective."),
        listOf("triglyceride") to TestInfo("Triglycerides", "A type of fat in your blood. High levels are linked to heart-disease risk and are influenced by diet and lifestyle."),
        listOf("cholesterol") to TestInfo("Total Cholesterol", "The total amount of cholesterol (a fat) in your blood. High levels can raise heart-disease risk."),
        listOf("creatinine") to TestInfo("Creatinine", "A waste product filtered out by your kidneys. Higher levels can indicate reduced kidney function."),
        listOf("urea", "bun") to TestInfo("Urea (BUN)", "A waste product from protein breakdown, filtered by the kidneys. Used with creatinine to assess kidney function."),
        listOf("uric acid") to TestInfo("Uric Acid", "A waste product; high levels can cause gout (painful joints) or relate to kidney issues."),
        listOf("sgpt", "alt", "alanine") to TestInfo("SGPT / ALT (Liver Enzyme)", "A liver enzyme. Raised levels can indicate that the liver is irritated or under stress."),
        listOf("sgot", "ast", "aspartate") to TestInfo("SGOT / AST (Liver Enzyme)", "An enzyme found in the liver and muscles. Raised levels can point to liver or muscle stress."),
        listOf("bilirubin") to TestInfo("Bilirubin", "A yellow substance from the breakdown of red blood cells, processed by the liver. High levels can cause jaundice (yellow skin/eyes)."),
        listOf("vitamin d", "25-oh", "25 oh") to TestInfo("Vitamin D", "Important for strong bones and immunity. Low levels are common and can cause tiredness or bone aches."),
        listOf("b12", "cobalamin") to TestInfo("Vitamin B12", "Needed for healthy nerves and red blood cells. Low levels can cause tiredness, tingling, or anaemia."),
        listOf("spo2", "oxygen", "saturation") to TestInfo("Oxygen (SpO₂)", "The percentage of oxygen your blood is carrying. Normally around 95–100%; lower can indicate breathing or lung issues."),
        listOf("ejection", "lvef") to TestInfo("Ejection Fraction", "The percentage of blood your heart pumps out with each beat. Lower values can indicate reduced heart pumping strength."),
    )

    /** Returns a plain-language description for a test name, or null if not in the reference. */
    fun describe(name: String): TestInfo? {
        val n = name.lowercase()
        for ((keys, info) in entries) {
            if (keys.any { n.contains(it) }) return info
        }
        return null
    }
}
