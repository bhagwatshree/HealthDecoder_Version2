package com.healthdecoder.app.ai

import android.content.Context
import com.healthdecoder.app.local.AppSettings
import com.healthdecoder.app.model.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * On-device port of the Node backend's clinical logic: report comparison, health insights,
 * chat, and detailed analysis. Each method tries Gemini (via the backend AI proxy — the key
 * never touches the device) and falls back to rule-based local logic so the app stays usable
 * offline or when the proxy is unreachable.
 */
object MedicalEngine {

    private val gson: Gson = GsonBuilder().setLenient().create()

    private fun aiJson(context: Context, prompt: String, operation: String): String? {
        return try {
            val raw = BackendAiClient.generateText(context, prompt, operation)
            GeminiClient.stripJsonFences(raw)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ══════════════════════════ COMPARISON ══════════════════════════
    /** @param allowAi false = go straight to the local comparison (saves free-tier quota on bulk imports). */
    fun compareReports(context: Context, newReport: MedicalReport, previous: MedicalReport?, allowAi: Boolean = true): ComparisonResult {
        if (previous == null) return ComparisonResult(hasComparison = false)
        if (!allowAi) return localComparison(newReport, previous)

        val prompt = """
Compare the previous medical report with the new one for patient "${newReport.patientName}".
PREVIOUS: date=${previous.reportDate} type=${previous.reportType} meds=${gson.toJson(previous.medications)} results=${gson.toJson(previous.testResults)} comments=${previous.comments}
NEW: date=${newReport.reportDate} type=${newReport.reportType} meds=${gson.toJson(newReport.medications)} results=${gson.toJson(newReport.testResults)} comments=${newReport.comments}
Return ONLY raw JSON (no code fences) with this schema:
{"hasComparison":true,"previousReportId":"${previous.id}","previousReportDate":"${previous.reportDate}","comparisonSummary":"2-3 patient-friendly sentences","status":"improved|worsened|no_change|mixed","differences":[{"name":"","previous":"","current":"","change":"increased|decreased|stable|changed","status":"improved|worsened|no_change"}],"medicationChanges":{"added":[],"removed":[],"changed":[]}}
""".trim()

        aiJson(context, prompt, operation = "compare")?.let { json ->
            runCatching { gson.fromJson(json, ComparisonResult::class.java) }.getOrNull()?.let { return it }
        }
        return localComparison(newReport, previous)
    }

    private fun localComparison(newReport: MedicalReport, previous: MedicalReport): ComparisonResult {
        val prevMeds = previous.medications
        val curMeds = newReport.medications
        val added = curMeds.filter { c -> prevMeds.none { it.name.equals(c.name, true) } }.map { it.name }
        val removed = prevMeds.filter { p -> curMeds.none { it.name.equals(p.name, true) } }.map { it.name }
        val changed = curMeds.mapNotNull { c ->
            val p = prevMeds.firstOrNull { it.name.equals(c.name, true) } ?: return@mapNotNull null
            if (p.dosage != c.dosage || p.frequency != c.frequency)
                "${c.name} (from ${p.dosage} [${p.frequency}] to ${c.dosage} [${c.frequency}])" else null
        }

        val prevParams = previous.testResults?.parameters ?: emptyList()
        val curParams = newReport.testResults?.parameters ?: emptyList()
        val differences = mutableListOf<TestDifference>()
        var improved = 0; var worsened = 0
        for (c in curParams) {
            val p = prevParams.firstOrNull { it.name.equals(c.name, true) } ?: continue
            val pv = p.value.toFloatOrNull(); val cv = c.value.toFloatOrNull()
            var change = "stable"; var status = "no_change"
            if (pv != null && cv != null) {
                change = if (cv > pv) "increased" else if (cv < pv) "decreased" else "stable"
                if (c.status.equals("normal", true) && !p.status.equals("normal", true)) { status = "improved"; improved++ }
                else if (!c.status.equals("normal", true) && p.status.equals("normal", true)) { status = "worsened"; worsened++ }
                else if (change != "stable") status = "changed"
            }
            differences.add(TestDifference(c.name, "${p.value} ${p.unit}".trim(), "${c.value} ${c.unit}".trim(), change, status))
        }
        val overall = when {
            improved > 0 && worsened == 0 -> "improved"
            worsened > 0 && improved == 0 -> "worsened"
            improved > 0 && worsened > 0 -> "mixed"
            else -> "no_change"
        }
        val parts = mutableListOf<String>()
        if (added.isNotEmpty()) parts.add("added: ${added.joinToString()}")
        if (removed.isNotEmpty()) parts.add("discontinued: ${removed.joinToString()}")
        if (changed.isNotEmpty()) parts.add("modified: ${changed.joinToString()}")
        if (differences.isNotEmpty()) parts.add("parameters changed: ${differences.joinToString { "${it.name} ${it.change}" }}")
        val summary = "Compared to ${previous.reportDate}, " + (if (parts.isEmpty()) "no significant changes detected." else parts.joinToString("; ") + ".")

        return ComparisonResult(
            hasComparison = true,
            previousReportId = previous.id,
            previousReportDate = previous.reportDate,
            comparisonSummary = summary,
            status = overall,
            differences = differences,
            medicationChanges = MedicationChanges(added, removed, changed)
        )
    }

    // ══════════════════════════ HEALTH INSIGHTS ══════════════════════════
    /** @param allowAi false = go straight to the local insights (saves free-tier quota on bulk imports). */
    fun healthInsights(context: Context, report: MedicalReport, allowAi: Boolean = true): HealthInsights {
        if (!allowAi) return localInsights(report)
        val prompt = """
You are a medical AI. Analyze this report and return patient-friendly insights in plain language. Be reassuring and factual.
Category: ${report.reportCategory} Type: ${report.reportType}
Comments: ${report.comments}
Parameters: ${gson.toJson(report.testResults?.parameters ?: emptyList<TestParameter>())}
Findings: ${gson.toJson(report.testResults?.findings ?: emptyList<String>())}
Medications: ${gson.toJson(report.medications)}
Return ONLY raw JSON with schema:
{"interpretation":"3-4 sentences","specialistRecommendations":[{"specialist":"","reason":"","urgency":"Routine|Soon|Urgent"}],"prescriptionAlignment":{"aligned":true,"score":"Good|Partial|Poor|N/A","analysis":"","flags":[]},"sideEffects":[{"medicine":"","commonEffects":[],"seriousEffects":[],"severity":"Mild|Moderate|Serious","tips":""}]}
Only recommend specialists if findings warrant it. Empty sideEffects if no medications.
""".trim()

        aiJson(context, prompt, operation = "insights")?.let { json ->
            runCatching { gson.fromJson(json, HealthInsights::class.java) }.getOrNull()?.let { return it }
        }
        return localInsights(report)
    }

    private val specialistMap = listOf(
        Triple(listOf("tsh", "thyroid", "t3", "t4", "hypothyroid", "hyperthyroid"), "Endocrinologist", "Thyroid parameter abnormality detected"),
        Triple(listOf("ejection fraction", "ef", "lvef", "wall motion", "mitral", "aortic"), "Cardiologist", "Cardiac finding detected"),
        Triple(listOf("fatty liver", "liver", "hepatic", "sgpt", "sgot", "alt", "ast", "bilirubin"), "Hepatologist", "Liver abnormality detected"),
        Triple(listOf("creatinine", "kidney", "renal", "gfr", "urea"), "Nephrologist", "Kidney function abnormality detected"),
        Triple(listOf("hemoglobin", "rbc", "anemia", "platelet", "wbc", "cbc"), "Hematologist", "Blood count outside normal range"),
        Triple(listOf("glucose", "blood sugar", "hba1c", "diabetes", "fbs", "ppbs"), "Diabetologist / Endocrinologist", "Blood sugar finding detected"),
        Triple(listOf("lung", "chest", "pleural", "pneumonia", "pulmonary"), "Pulmonologist", "Respiratory finding detected"),
        Triple(listOf("cholesterol", "ldl", "hdl", "triglyceride", "lipid"), "Cardiologist", "Lipid profile abnormality — cardiovascular risk"),
    )

    private val sideEffectsDict: Map<String, MedicineSideEffect> = mapOf(
        "metformin" to MedicineSideEffect("", listOf("Nausea", "Diarrhea", "Stomach upset"), listOf("Lactic acidosis (rare)"), "Mild", "Take with food. Stay hydrated."),
        "levothyroxine" to MedicineSideEffect("", listOf("Palpitations", "Sweating", "Nervousness"), listOf("Chest pain if dose too high"), "Mild", "Take on empty stomach, 30-60 min before breakfast."),
        "atorvastatin" to MedicineSideEffect("", listOf("Muscle aches", "Joint pain", "Headache"), listOf("Rhabdomyolysis (rare)"), "Mild", "Report muscle pain. Avoid grapefruit."),
        "amlodipine" to MedicineSideEffect("", listOf("Ankle swelling", "Flushing", "Dizziness"), listOf("Severe low BP"), "Mild", "Rise slowly to avoid dizziness."),
        "paracetamol" to MedicineSideEffect("", listOf("Well tolerated at normal doses"), listOf("Liver damage if overdosed"), "Mild", "Do not exceed 4g/day. Avoid alcohol."),
        "pantoprazole" to MedicineSideEffect("", listOf("Headache", "Diarrhea", "Nausea"), listOf("Bone/magnesium issues long-term"), "Mild", "Take 30-60 min before meals."),
        "telmisartan" to MedicineSideEffect("", listOf("Dizziness", "Back pain", "Fatigue"), listOf("High potassium"), "Mild", "Monitor BP at home."),
        "neomercazole" to MedicineSideEffect("", listOf("Nausea", "Skin rash", "Joint pain"), listOf("Agranulocytosis — seek care for fever/sore throat"), "Serious", "Get periodic blood tests. Watch for fever/sore throat."),
        "levipil" to MedicineSideEffect("", listOf("Dizziness", "Drowsiness", "Weakness"), listOf("Mood changes"), "Moderate", "Do not stop suddenly. Avoid alcohol."),
        "stamlo" to MedicineSideEffect("", listOf("Ankle swelling", "Flushing", "Headache"), listOf("Severe low BP"), "Mild", "Rise slowly. Monitor BP."),
        "atorva" to MedicineSideEffect("", listOf("Muscle aches", "Headache"), listOf("Rhabdomyolysis (rare)"), "Mild", "Report muscle pain to doctor."),
        "vertin" to MedicineSideEffect("", listOf("Nausea", "Headache"), listOf("Rarely serious"), "Mild", "Take with food to reduce nausea."),
        "augmentin" to MedicineSideEffect("", listOf("Diarrhea", "Nausea", "Rash"), listOf("Allergic reaction"), "Mild", "Take with food. Finish the course."),
        "ciplox" to MedicineSideEffect("", listOf("Nausea", "Diarrhea", "Dizziness"), listOf("Tendon rupture"), "Moderate", "Avoid antacids within 2h."),
    )

    private fun localInsights(report: MedicalReport): HealthInsights {
        val params = report.testResults?.parameters ?: emptyList()
        val findings = report.testResults?.findings ?: emptyList()
        val meds = report.medications
        val comments = report.comments ?: ""
        val abnormals = params.filter { !it.status.isNullOrEmpty() && !it.status.equals("normal", true) }

        val interpretation = if (abnormals.isEmpty() && findings.isEmpty()) {
            "Your ${report.reportCategory ?: "medical"} report results appear within normal reference ranges. This is a positive sign. Please still discuss with your doctor at your next visit."
        } else {
            val ab = abnormals.joinToString { "${it.name} (${it.value} ${it.unit}, ref ${it.referenceRange})" }
            buildString {
                append("Your report shows some values that need attention. ")
                if (abnormals.isNotEmpty()) append("Outside the normal range: $ab. ")
                if (findings.isNotEmpty()) append("Key findings: ${findings.take(2).joinToString(". ")}. ")
                append("Please follow up with your doctor to discuss these results.")
            }
        }

        val allText = (params.map { it.name } + findings + comments + (report.reportCategory ?: "")).joinToString(" ").lowercase()
        val specialists = mutableListOf<SpecialistRecommendation>()
        for ((keys, spec, reason) in specialistMap) {
            if (keys.any { allText.contains(it) } && specialists.none { it.specialist == spec }) {
                specialists.add(SpecialistRecommendation(spec, reason, if (abnormals.any { it.status.equals("High", true) || it.status.equals("Low", true) }) "Soon" else "Routine"))
            }
        }
        if (specialists.isEmpty() && abnormals.isNotEmpty()) {
            specialists.add(SpecialistRecommendation("General Physician", "Some parameters are outside normal range and need clinical correlation", "Routine"))
        }

        val flags = mutableListOf<String>()
        var score = if (meds.isEmpty()) "N/A" else "Good"
        val analysis = when {
            meds.isEmpty() -> "No medications are listed in this report, so alignment cannot be assessed."
            abnormals.isEmpty() && findings.isEmpty() -> "The ${meds.size} prescribed medicine(s) are noted and results appear normal."
            else -> "Review the report findings against current prescriptions with your doctor."
        }

        val sideEffects = meds.map { med ->
            val key = sideEffectsDict.keys.firstOrNull { med.name.lowercase().contains(it) }
            if (key != null) sideEffectsDict[key]!!.copy(medicine = med.name)
            else MedicineSideEffect(med.name, listOf("Nausea or stomach upset", "Dizziness", "Headache"), listOf("Allergic reaction — seek care if breathing difficulty"), "Mild", "Take as prescribed. Report unusual symptoms.")
        }

        return HealthInsights(
            interpretation = interpretation,
            specialistRecommendations = specialists,
            prescriptionAlignment = PrescriptionAlignment(score == "Good" || score == "N/A", score, analysis, flags),
            sideEffects = sideEffects
        )
    }

    // ══════════════════════════ CHAT ══════════════════════════
    fun chat(context: Context, question: String, reports: List<MedicalReport>, history: List<ChatMessage>, imagePath: String? = null): Pair<String, String> {
        val language = AppSettings.getPreferredLanguage(context)
        val ctx = buildReportsContext(reports)
        val historyText = history.takeLast(6).joinToString("\n") { "${if (it.role == "user") "Patient" else "Assistant"}: ${it.content}" }
        val prompt = """
You are a friendly, conversational medical assistant helping a patient understand their own records. Answer in clear, simple, plain language. Be warm, supportive, and factual.
CRITICAL LANGUAGE INSTRUCTION: You MUST reply entirely in the patient's preferred language: $language. Do not reply in English unless the preferred language is English.
If the patient's question is vague (e.g., "why is my report bad?", "what does this mean?"), you MUST ask them a clarifying question about their history, specific symptoms, or which report they are referring to before giving an assessment.
When asked about specific details or "why" something is happening, correlate findings and trends across the patient's historical reports provided below. When analyzing their overall history, you can reassure them with friendly phrasing like, "From your overall history, this may be normal, but please check with your doctor to be sure."
SAFETY & MEDICAL DISCLAIMER: You are NOT a doctor; do not diagnose, prescribe, or give medical advice. Ground all correlations purely in the records provided. If the patient asks which doctor or specialist they should see based on their results, recommend the type of medical specialist (e.g., Endocrinologist for Thyroid, Cardiologist for Cardiac/Lipids). If they ask where to do recommended tests or checkups, inform them they can check with nearby path labs or hospitals using the "Find Care" search feature.

TOOL USE / ACTION CAPABILITY:
If the user explicitly asks you to take an action, you can output a special tool command at the very end of your response. 
Available tools:
1. [TOOL: navigate(FindCare)] - Use this if the user asks where to find a doctor, hospital, or lab.
2. [TOOL: setReminder(MedicineName, Time)] - Use this if the user asks you to remind them to take a medication (e.g., [TOOL: setReminder(Metformin, 08:00)]). Time must be HH:MM format.
3. [TOOL: addAppointment(DoctorName, Date, Time)] - Use this if the user asks to book or add a doctor's appointment. IMPORTANT: If the user asks to add an appointment but DOES NOT provide the doctor's name, the date, OR the time, you MUST ask them for the missing details BEFORE emitting the tool. Do not guess the details. Once you have all 3 details, emit the tool command.
4. [TOOL: scanDocument] - Use this if the user has uploaded an image or prescription and you have asked them if they want it scanned, and they replied YES.
PROACTIVE ATTACHMENT HANDLING: If the user attaches an image/document but doesn't say what to do with it, or asks what it is, you can describe it briefly and proactively ask: "Would you like me to scan and analyze this to your records?".

IMPORTANT: Only output the [TOOL: ...] block if the user asks for the action (or agrees to it). Include it at the very end of your message.

IMPORTANT: At the end of every response, you MUST append this exact patient disclaimer (translated into $language):
"Disclaimer: This information is purely educational and informational. It is not a confirmed medical diagnosis or appointment. Please consult a doctor and do not rely solely on this information."
Keep answers concise (3-5 sentences).

PATIENT'S HISTORICAL RECORDS:
${ctx.ifBlank { "No reports available yet." }}
${if (historyText.isNotBlank()) "CONVERSATION SO FAR:\n$historyText\n" else ""}
${if (imagePath != null) "[An image is attached to this request]" else ""}
PATIENT'S QUESTION: $question
Answer (in $language):
""".trim()

        return try {
            val answer = if (imagePath != null) {
                val file = java.io.File(imagePath)
                if (file.exists()) {
                    val bytes = file.readBytes()
                    val mime = if (imagePath.endsWith(".png", true)) "image/png" else "image/jpeg"
                    BackendAiClient.generateFromImage(context, prompt, bytes, mime, operation = "chat").trim()
                } else {
                    BackendAiClient.generateText(context, prompt, operation = "chat").trim()
                }
            } else {
                BackendAiClient.generateText(context, prompt, operation = "chat").trim()
            }
            if (answer.isNotBlank()) {
                answer to "ai"
            } else {
                val localAns = localChat(question, reports)
                val translatedLocal = if (language.equals("English", true)) localAns else com.healthdecoder.app.util.LanguageUtil.translate(context, localAns, language)
                translatedLocal to "local"
            }
        } catch (e: Exception) {
            val localAns = localChat(question, reports)
            val translatedLocal = if (language.equals("English", true)) localAns else com.healthdecoder.app.util.LanguageUtil.translate(context, localAns, language)
            translatedLocal to "local"
        }
    }

    private fun localChat(question: String, reports: List<MedicalReport>): String {
        val disclaimer = "\n\nDisclaimer: This information is purely educational and informational. It is not a confirmed medical diagnosis or appointment. Please consult a doctor and do not rely solely on this information."
        if (reports.isEmpty()) return "I don't have any of your reports on file yet. Once you scan a report, I can help explain your results, medicines, and doctor's notes. For any medical concern, please consult your doctor.$disclaimer"
        
        val q = question.lowercase()
        val suffix = "(Offline mode — set a Gemini key in Settings for smarter answers. You can also search for nearby labs and doctors using the 'Find Care' screen.)$disclaimer"
        
        if (q.contains("doctor") || q.contains("doct") || q.contains("specialist") || q.contains("physician")) {
            return "Based on your saved reports, you should discuss abnormal findings with a suitable specialist (e.g. Cardiologist for Lipids/heart, Endocrinologist for Thyroid, Diabetologist for high sugar). You can find nearby doctor clinics using the 'Find Care' screen on the dashboard. $suffix"
        }
        if (q.contains("test") || q.contains("lab") || q.contains("path") || q.contains("hospital")) {
            return "For diagnostic tests or health screenings, you can look up nearby path labs and hospitals offering these services using the 'Find Care' screen on the dashboard. $suffix"
        }
        return "Based on your saved reports:\n\n${buildReportsContext(reports)}\n\nAnything marked abnormal is worth discussing with your doctor. $suffix"
    }

    private fun buildReportsContext(reports: List<MedicalReport>): String {
        if (reports.isEmpty()) return ""
        return reports.take(12).mapIndexed { i, r ->
            val abnormal = (r.testResults?.parameters ?: emptyList()).filter { !it.status.isNullOrEmpty() && !it.status.equals("normal", true) }
                .map { "${it.name}: ${it.value} ${it.unit} (ref ${it.referenceRange}, ${it.status})" }
            val meds = r.medications.map { "${it.name}${if (it.dosage.isNotEmpty()) " " + it.dosage else ""}${if (it.frequency.isNotEmpty()) " [" + it.frequency + "]" else ""}" }
            buildString {
                append("Report ${i + 1} — ${r.reportDate} — ${r.reportType} (${r.reportCategory})\n")
                r.patientName?.let { append("  Patient: $it\n") }
                if (abnormal.isNotEmpty()) append("  Abnormal: ${abnormal.joinToString("; ")}\n")
                (r.testResults?.findings ?: emptyList()).take(4).let { if (it.isNotEmpty()) append("  Findings: ${it.joinToString("; ")}\n") }
                if (meds.isNotEmpty()) append("  Medications: ${meds.joinToString("; ")}\n")
                r.comments?.let { if (it.isNotBlank()) append("  Comments: ${it.take(200)}") }
            }
        }.joinToString("\n\n")
    }

    // ══════════════════════════ DETAILED ANALYSIS ══════════════════════════
    fun detailedAnalysis(context: Context, report: MedicalReport): DetailedAnalysis {
        val disclaimer = "This detailed analysis is AI-generated to help you understand your report in plain language. It is educational only and is NOT a medical diagnosis. Always confirm decisions with your doctor."
        val abnormal = (report.testResults?.parameters ?: emptyList()).filter { !it.status.isNullOrEmpty() && !it.status.equals("normal", true) }
        val prompt = """
You are a clinician writing a thorough, easy-to-understand analysis of ONE report for the patient. Go deeper than a summary: explain the "why", connect findings to medicines, give practical guidance. Warm, plain language, never alarmist.
Patient: ${report.patientName} Date: ${report.reportDate} Type: ${report.reportType} (${report.reportCategory})
Parameters: ${gson.toJson(report.testResults?.parameters ?: emptyList<TestParameter>())}
Abnormal: ${gson.toJson(abnormal)}
Findings: ${gson.toJson(report.testResults?.findings ?: emptyList<String>())}
Medications: ${gson.toJson(report.medications)}
Comments: ${report.comments}
Return ONLY raw JSON:
{"summary":"2-3 sentences","sections":[{"title":"Detailed Interpretation","content":""},{"title":"Parameter-by-Parameter Breakdown","content":""},{"title":"Possible Causes & Context","content":""},{"title":"Medicines Explained","content":""},{"title":"Lifestyle & Diet Guidance","content":""},{"title":"Questions to Ask Your Doctor","content":""},{"title":"When to Seek Care Sooner","content":""}]}
Use short paragraphs and dashed lists ("- item"). If a section has nothing, keep it and say so briefly.
""".trim()

        val language = AppSettings.getPreferredLanguage(context)

        val rawAnalysis = aiJson(context, prompt, operation = "detailed-analysis")?.let { json ->
            runCatching { gson.fromJson(json, DetailedAnalysis::class.java) }.getOrNull()?.let {
                if (it.sections.isNotEmpty()) it.copy(disclaimer = disclaimer, source = "ai") else null
            }
        } ?: localDetailed(report).copy(disclaimer = disclaimer, source = "local")

        if (language.equals("English", true)) {
            return rawAnalysis
        }

        // Translate the analysis using Sarvam API via LanguageUtil
        val translatedSummary = com.healthdecoder.app.util.LanguageUtil.translate(context, rawAnalysis.summary ?: "", language)
        val translatedDisclaimer = com.healthdecoder.app.util.LanguageUtil.translate(context, rawAnalysis.disclaimer ?: "", language)
        val translatedSections = rawAnalysis.sections.map { section ->
            DetailedAnalysisSection(
                title = com.healthdecoder.app.util.LanguageUtil.translate(context, section.title, language),
                content = com.healthdecoder.app.util.LanguageUtil.translate(context, section.content, language)
            )
        }
        return rawAnalysis.copy(
            summary = translatedSummary,
            disclaimer = translatedDisclaimer,
            sections = translatedSections
        )
    }

    private fun localDetailed(report: MedicalReport): DetailedAnalysis {
        val insights = localInsights(report)
        val params = report.testResults?.parameters ?: emptyList()
        val findings = report.testResults?.findings ?: emptyList()
        val abnormal = params.filter { !it.status.isNullOrEmpty() && !it.status.equals("normal", true) }
        val sections = mutableListOf<DetailedAnalysisSection>()

        sections.add(DetailedAnalysisSection("Detailed Interpretation", insights.interpretation))
        if (params.isNotEmpty()) {
            sections.add(DetailedAnalysisSection("Parameter-by-Parameter Breakdown", params.joinToString("\n") {
                val flag = if (!it.status.isNullOrEmpty() && !it.status.equals("normal", true)) " — ${it.status!!.uppercase()}" else " — within normal range"
                "- ${it.name}: ${it.value} ${it.unit} (ref ${it.referenceRange})$flag"
            }))
        } else if (findings.isNotEmpty()) {
            sections.add(DetailedAnalysisSection("Key Findings", findings.joinToString("\n") { "- $it" }))
        }
        if (abnormal.isNotEmpty()) {
            sections.add(DetailedAnalysisSection("Possible Causes & Context", "Values outside range: ${abnormal.joinToString { it.name }}. These can be influenced by diet, hydration, time of day, recent illness, or medication. A single reading is not a diagnosis — trends over time matter more."))
        }
        sections.add(DetailedAnalysisSection("Medicines Explained", if (insights.sideEffects.isEmpty()) "No medicines are listed in this report." else insights.sideEffects.joinToString("\n") { "- ${it.medicine}: ${it.tips}" }))
        sections.add(DetailedAnalysisSection("Questions to Ask Your Doctor", "- What do my abnormal values mean for me?\n- Do my medicines need any change?\n- When should I repeat this test?"))
        sections.add(DetailedAnalysisSection("When to Seek Care Sooner", "Contact your doctor promptly for new or worsening symptoms (chest pain, severe breathlessness, persistent high fever, fainting). How you feel matters too."))

        val summary = if (abnormal.isNotEmpty())
            "This report has ${abnormal.size} value(s) worth discussing with your doctor. Below is a detailed, plain-language breakdown."
        else "This report looks largely within normal ranges. Below is a detailed, plain-language breakdown."
        return DetailedAnalysis(summary = summary, sections = sections)
    }

    // ══════════════════════════ MEDICINE INFO LOOKUP ══════════════════════════

    private fun medicineInfoCacheFile(context: Context): java.io.File {
        val dir = java.io.File(context.filesDir, "medicine_info_cache").apply { if (!exists()) mkdirs() }
        return java.io.File(dir, "cache.json")
    }

    private val cacheType = object : com.google.gson.reflect.TypeToken<MutableMap<String, MedicineInfo>>() {}.type

    private fun loadInfoCache(context: Context): MutableMap<String, MedicineInfo> {
        val f = medicineInfoCacheFile(context)
        if (!f.exists()) return mutableMapOf()
        return try {
            gson.fromJson<MutableMap<String, MedicineInfo>>(f.readText(), cacheType) ?: mutableMapOf()
        } catch (_: Exception) { mutableMapOf() }
    }

    private fun saveInfoCache(context: Context, cache: Map<String, MedicineInfo>) {
        runCatching { medicineInfoCacheFile(context).writeText(gson.toJson(cache)) }
    }

    /**
     * Identifies the medicine visible in a photo (a strip, box, bottle or label) via Gemini vision
     * and returns just its name, or "" when nothing legible is found. The caller then feeds the
     * name into [lookupMedicineInfo] for the usage/interaction details.
     */
    fun identifyMedicineFromImage(context: Context, imageBytes: ByteArray, mimeType: String): String {
        val prompt = """
            This photo shows a medicine (a strip, box, bottle, or printed label). Identify it.
            Return ONLY the medicine's name exactly as printed — brand name if visible, otherwise the
            generic/salt name — with no other words, no punctuation and no explanation.
            If no medicine name is legible, return exactly: NONE
        """.trimIndent()
        return try {
            val raw = BackendAiClient.generateFromImage(context, prompt, imageBytes, mimeType, operation = "medicine-identify")
            val cleaned = GeminiClient.stripJsonFences(raw).trim().removeSurrounding("\"").trim()
            if (cleaned.equals("NONE", ignoreCase = true) || cleaned.isBlank()) "" else cleaned.lines().first().trim()
        } catch (e: Exception) {
            e.printStackTrace(); ""
        }
    }

    /**
     * Looks up basic information about a medicine using Gemini AI.
     * Results are cached on-device so repeated lookups are instant.
     */
    fun lookupMedicineInfo(context: Context, medicineName: String): MedicineInfo {
        val trimmed = medicineName.trim()
        if (trimmed.isEmpty()) return MedicineInfo(basicUse = "Please enter a medicine name.")

        // Explanations are shown in the user's preferred language; cache per language.
        val lang = AppSettings.getPreferredLanguage(context)
        val key = "${trimmed.lowercase()}::${lang.lowercase()}"

        // Check cache first
        val cache = loadInfoCache(context)
        cache[key]?.let { return it }

        val langLine = if (!lang.equals("English", true))
            "Write \"basicUse\" and each \"keyNotes\" item in $lang using simple everyday words a common person understands. Keep \"genericName\" and \"category\" values in English."
        else ""

        val prompt = """
For the medicine "$medicineName", provide a brief patient-friendly reference.
$langLine
Return ONLY raw JSON (no code fences):
{"category":"<one of: Antibiotic, Antacid, Painkiller, Anti-inflammatory, Vitamin/Supplement, Antidiabetic, Antihypertensive, Antihistamine, Steroid, Antifungal, Antiviral, Bronchodilator, Laxative, Probiotic, Cardiac, Antipyretic, Muscle Relaxant, Antidepressant, Other>","genericName":"<generic salt name if this is a brand, or common brand names if this is a generic>","basicUse":"<1-2 sentence patient-friendly explanation of why doctors prescribe this medicine>","keyNotes":["<practical tip 1, e.g. Take after food>","<practical tip 2>","<practical tip 3>"]}
If you don't recognise the medicine name or it seems misspelled, set category to "Unknown" and basicUse to a suggestion like "This name was not recognised. Please check the spelling."
""".trim()

        val json = aiJson(context, prompt, operation = "medicine-lookup")
        val result = json?.let {
            runCatching { gson.fromJson(it, MedicineInfo::class.java) }.getOrNull()
        } ?: MedicineInfo(
            category = "Unknown",
            basicUse = "Could not look up this medicine. Check your internet connection or Gemini API key."
        )

        // Cache only a real answer — never a "no internet" failure, so it can be retried later.
        if (json != null) {
            cache[key] = result
            saveInfoCache(context, cache)
        }

        return result
    }

    /** The saved reference for a medicine if it was looked up before (no network/AI call), else null. */
    fun getCachedMedicineInfo(context: Context, medicineName: String): MedicineInfo? {
        val trimmed = medicineName.trim()
        if (trimmed.isEmpty()) return null
        val lang = AppSettings.getPreferredLanguage(context)
        return loadInfoCache(context)["${trimmed.lowercase()}::${lang.lowercase()}"]
    }
}
