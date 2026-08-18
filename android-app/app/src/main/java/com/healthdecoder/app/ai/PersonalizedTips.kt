package com.healthdecoder.app.ai

import android.content.Context
import com.healthdecoder.app.local.RemoteHealthTips
import com.healthdecoder.app.model.MedicalReport

/**
 * Which of the patient's OWN results a tip was derived from — kept as its three parts rather
 * than a pre-built sentence so the sentence can be assembled from a translated template at
 * render time (see HealthTipCard). Baking the values into an English string here would produce
 * a different string for every patient, which no translation map can ever match.
 */
data class TipSource(
    val param: String,
    val status: String,
    val date: String
)

data class HealthTip(
    val headline: String,
    val detail: String,
    // Null for the general (non-personalized) tip rotation, which isn't tied to any specific
    // data point. Shown on "Learn More" so a personalized tip is traceable back to its source
    // rather than reading as an unexplained claim — both for the user's own trust and for
    // health-content policy review.
    val source: TipSource? = null
)

/**
 * Turns the patient's own recent abnormal (High/Low) test results into natural lifestyle tips —
 * diet, hydration, sleep, movement — NEVER a medicine, supplement dose, or "take X drug"
 * suggestion. These rotate on-screen automatically with no per-user review, so they must stay
 * squarely in general-lifestyle territory; anything that could be mistaken for medical/dosing
 * advice does not belong here. Entirely rule-based, no AI call — free and instant, and never
 * invents a tip for a test it doesn't recognize.
 *
 * Content for a given test+status comes from [RemoteHealthTips] (the backend's health_tips
 * table) when available — so a NEW test type gets a tip the moment it's added to the DB, with
 * no app release needed — falling back to the maps below, which exist purely as an offline /
 * first-launch-before-first-sync safety net, not as the thing that has to be edited to add tips.
 */
object PersonalizedTips {

    private val LOW_TIPS: Map<String, HealthTip> = mapOf(
        "Sodium" to HealthTip(
            "Your sodium ran low",
            "Coconut water, buttermilk (chaas), or a pinch of rock salt in water can help — especially in hot weather or after heavy sweating. If it stays low, that's worth a doctor's visit rather than adjusting salt alone."
        ),
        "Potassium" to HealthTip(
            "Your potassium ran low",
            "Bananas, coconut water, potatoes (with skin), spinach, and citrus fruits are everyday sources that support normal levels."
        ),
        "Hemoglobin" to HealthTip(
            "Your hemoglobin ran low",
            "Iron-rich foods — leafy greens, jaggery, dates, pomegranate, and lean meat if you eat non-veg — paired with vitamin C (citrus, amla) improves iron absorption."
        ),
        "Vitamin D" to HealthTip(
            "Your Vitamin D ran low",
            "10-15 minutes of morning sunlight a few times a week, plus foods like eggs, mushrooms, and fortified milk, are simple everyday sources."
        ),
        "Vitamin B12" to HealthTip(
            "Your Vitamin B12 ran low",
            "Dairy, eggs, and (if you eat non-veg) fish and meat are the main natural sources — B12 is one of the few nutrients hard to get from a purely plant diet."
        ),
        "Calcium" to HealthTip(
            "Your calcium ran low",
            "Dairy, sesame seeds (til), ragi, and leafy greens are everyday calcium sources — a short daily walk also helps your body put calcium to use."
        ),
        "Blood Sugar" to HealthTip(
            "Your blood sugar ran low",
            "Keep a small snack (fruit, nuts, or a glucose candy) on hand between meals, and avoid long gaps without eating — that's the most common everyday cause."
        ),
        "Iron" to HealthTip(
            "Your iron ran low",
            "Leafy greens, jaggery, dates, and pomegranate — eaten alongside vitamin C (citrus, amla) — help your body absorb iron better from food."
        )
    )

    private val HIGH_TIPS: Map<String, HealthTip> = mapOf(
        "Blood Sugar" to HealthTip(
            "Your blood sugar ran high",
            "A 10-15 minute walk after meals is one of the simplest everyday habits that helps the body use up blood sugar. Cutting back on refined carbs and sugary drinks helps too."
        ),
        "Total Cholesterol" to HealthTip(
            "Your cholesterol ran high",
            "Swapping fried snacks for nuts/seeds, adding more fibre (oats, whole grains, vegetables), and regular walking are everyday habits that support healthier cholesterol."
        ),
        "LDL" to HealthTip(
            "Your LDL (\"bad\" cholesterol) ran high",
            "More fibre (oats, legumes, vegetables), less fried/processed food, and regular movement are the everyday habits that help bring LDL down over time."
        ),
        "Triglycerides" to HealthTip(
            "Your triglycerides ran high",
            "Cutting back on sugar, refined carbs, and alcohol tends to move triglycerides more than any other single habit — along with regular physical activity."
        ),
        "Uric Acid" to HealthTip(
            "Your uric acid ran high",
            "Cutting back on red meat, organ meats, and sugary drinks, plus staying well hydrated, are the everyday habits that help most."
        ),
        "Sodium" to HealthTip(
            "Your sodium ran high",
            "Cutting back on packaged/processed foods (a major hidden salt source) and drinking enough water are the simplest everyday habits that help."
        ),
        "TSH" to HealthTip(
            "Your TSH ran outside the usual range",
            "Consistent sleep timing and regular meals support general thyroid-friendly habits — thyroid levels are usually medication-managed, so treat this as a supporting habit, not a fix on its own."
        )
    )

    /**
     * One tip per distinct abnormal parameter, most recent report first, deduped so the same
     * test doesn't repeat across older reports. Returns an empty list when there's nothing
     * abnormal (or recognized) to base a tip on — the caller falls back to the general rotation.
     */
    fun tipsFor(context: Context, reports: List<MedicalReport>): List<HealthTip> {
        if (reports.isEmpty()) return emptyList()
        val chrono = reports.sortedByDescending { it.reportDate?.takeIf { d -> d.isNotBlank() } ?: it.createdAt }
        val seen = HashSet<String>()
        val out = mutableListOf<HealthTip>()
        for (r in chrono) {
            for (p in r.testResults?.parameters ?: emptyList()) {
                if (p.excludeFromTrend == true) continue
                val canon = DashboardEngine.canonicalParamName(p.trendCategory?.takeIf { it.isNotBlank() } ?: p.name)
                if (!seen.add(canon)) continue
                val status = (p.status ?: "").trim()
                val statusKey = status.lowercase()
                if (statusKey != "low" && statusKey != "high") continue
                val base = RemoteHealthTips.get(context, canon, statusKey)
                    ?: (if (statusKey == "low") LOW_TIPS[canon] else HIGH_TIPS[canon])
                    ?: continue
                val date = r.reportDate?.takeIf { it.isNotBlank() } ?: r.createdAt.take(10)
                out.add(base.copy(source = TipSource(param = canon, status = status, date = date)))
            }
        }
        return out
    }
}
