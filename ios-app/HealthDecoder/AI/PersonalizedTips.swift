import Foundation

/// Which of the patient's OWN results a tip was derived from — kept as its three parts rather
/// than a pre-built sentence so the sentence can be assembled from a translated template at
/// render time (see `HealthTipCard`). Baking the values into an English string here would produce
/// a different string for every patient, which no translation map can ever match.
struct TipSource: Equatable {
    let param: String
    let status: String
    let date: String
}

struct HealthTip: Equatable {
    let headline: String
    let detail: String
    /// Nil for the general (non-personalized) rotation, which isn't tied to any specific data
    /// point. Shown on "Learn More" so a personalized tip is traceable back to its source rather
    /// than reading as an unexplained claim — both for the user's own trust and for health-content
    /// policy review.
    var source: TipSource? = nil
}

/// Turns the patient's own recent abnormal (High/Low) test results into natural lifestyle tips —
/// diet, hydration, sleep, movement — NEVER a medicine, supplement dose, or "take X drug"
/// suggestion. These rotate on-screen automatically with no per-user review, so they must stay
/// squarely in general-lifestyle territory; anything that could be mistaken for medical/dosing
/// advice does not belong here. Entirely rule-based, no AI call — free and instant, and never
/// invents a tip for a test it doesn't recognize.
///
/// Direct port of `ai/PersonalizedTips.kt`. Content for a given test+status comes from
/// `RemoteHealthTips` (the backend's health_tips table) when available — so a NEW test type gets a
/// tip the moment it's added to the DB, with no app release needed — falling back to the maps
/// below, which exist purely as an offline / first-launch-before-first-sync safety net, not as the
/// thing that has to be edited to add tips.
enum PersonalizedTips {

    private static let lowTips: [String: HealthTip] = [
        "Sodium": HealthTip(
            headline: "Your sodium ran low",
            detail: "Coconut water, buttermilk (chaas), or a pinch of rock salt in water can help — especially in hot weather or after heavy sweating. If it stays low, that's worth a doctor's visit rather than adjusting salt alone."),
        "Potassium": HealthTip(
            headline: "Your potassium ran low",
            detail: "Bananas, coconut water, potatoes (with skin), spinach, and citrus fruits are everyday sources that support normal levels."),
        "Hemoglobin": HealthTip(
            headline: "Your hemoglobin ran low",
            detail: "Iron-rich foods — leafy greens, jaggery, dates, pomegranate, and lean meat if you eat non-veg — paired with vitamin C (citrus, amla) improves iron absorption."),
        "Vitamin D": HealthTip(
            headline: "Your Vitamin D ran low",
            detail: "10-15 minutes of morning sunlight a few times a week, plus foods like eggs, mushrooms, and fortified milk, are simple everyday sources."),
        "Vitamin B12": HealthTip(
            headline: "Your Vitamin B12 ran low",
            detail: "Dairy, eggs, and (if you eat non-veg) fish and meat are the main natural sources — B12 is one of the few nutrients hard to get from a purely plant diet."),
        "Calcium": HealthTip(
            headline: "Your calcium ran low",
            detail: "Dairy, sesame seeds (til), ragi, and leafy greens are everyday calcium sources — a short daily walk also helps your body put calcium to use."),
        "Blood Sugar": HealthTip(
            headline: "Your blood sugar ran low",
            detail: "Keep a small snack (fruit, nuts, or a glucose candy) on hand between meals, and avoid long gaps without eating — that's the most common everyday cause."),
        "Iron": HealthTip(
            headline: "Your iron ran low",
            detail: "Leafy greens, jaggery, dates, and pomegranate — eaten alongside vitamin C (citrus, amla) — help your body absorb iron better from food."),
    ]

    private static let highTips: [String: HealthTip] = [
        "Blood Sugar": HealthTip(
            headline: "Your blood sugar ran high",
            detail: "A 10-15 minute walk after meals is one of the simplest everyday habits that helps the body use up blood sugar. Cutting back on refined carbs and sugary drinks helps too."),
        "Total Cholesterol": HealthTip(
            headline: "Your cholesterol ran high",
            detail: "Swapping fried snacks for nuts/seeds, adding more fibre (oats, whole grains, vegetables), and regular walking are everyday habits that support healthier cholesterol."),
        "LDL": HealthTip(
            headline: "Your LDL (\"bad\" cholesterol) ran high",
            detail: "More fibre (oats, legumes, vegetables), less fried/processed food, and regular movement are the everyday habits that help bring LDL down over time."),
        "Triglycerides": HealthTip(
            headline: "Your triglycerides ran high",
            detail: "Cutting back on sugar, refined carbs, and alcohol tends to move triglycerides more than any other single habit — along with regular physical activity."),
        "Uric Acid": HealthTip(
            headline: "Your uric acid ran high",
            detail: "Cutting back on red meat, organ meats, and sugary drinks, plus staying well hydrated, are the everyday habits that help most."),
        "Sodium": HealthTip(
            headline: "Your sodium ran high",
            detail: "Cutting back on packaged/processed foods (a major hidden salt source) and drinking enough water are the simplest everyday habits that help."),
        "TSH": HealthTip(
            headline: "Your TSH ran outside the usual range",
            detail: "Consistent sleep timing and regular meals support general thyroid-friendly habits — thyroid levels are usually medication-managed, so treat this as a supporting habit, not a fix on its own."),
    ]

    /// One tip per distinct abnormal parameter, most recent report first, deduped so the same
    /// test doesn't repeat across older reports. Returns an empty array when there's nothing
    /// abnormal (or recognized) to base a tip on — the caller falls back to the general rotation.
    static func tips(for reports: [MedicalReport]) -> [HealthTip] {
        guard !reports.isEmpty else { return [] }
        let chronological = reports.sorted {
            let a = ($0.reportDate?.isEmpty == false ? $0.reportDate! : $0.createdAt)
            let b = ($1.reportDate?.isEmpty == false ? $1.reportDate! : $1.createdAt)
            return a > b
        }

        var seen = Set<String>()
        var out: [HealthTip] = []
        for report in chronological {
            for parameter in report.testResults?.parameters ?? [] {
                if parameter.excludeFromTrend == true { continue }
                let rawName = (parameter.trendCategory?.isEmpty == false)
                    ? parameter.trendCategory! : parameter.name
                let canonical = DashboardEngine.canonicalParamName(rawName)
                guard seen.insert(canonical).inserted else { continue }

                let status = (parameter.status ?? "").trimmingCharacters(in: .whitespaces)
                let statusKey = status.lowercased()
                guard statusKey == "low" || statusKey == "high" else { continue }
                guard var tip = RemoteHealthTips.get(canonicalParam: canonical, status: statusKey)
                    ?? (statusKey == "low" ? lowTips[canonical] : highTips[canonical])
                else { continue }

                let date = (report.reportDate?.isEmpty == false)
                    ? report.reportDate! : String(report.createdAt.prefix(10))
                tip.source = TipSource(param: canonical, status: status, date: date)
                out.append(tip)
            }
        }
        return out
    }
}
