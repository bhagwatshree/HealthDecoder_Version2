import Foundation

/// Direct port of the two `ai/MedicalEngine.kt` functions Smart Health Lens needs: identifying a
/// medicine from a photo, and looking up patient-friendly reference info about it. (Compare,
/// health insights, chat, and detailed analysis are ported in a later phase.)
enum MedicalEngine {
    /// Identifies the medicine visible in a photo via Gemini vision; returns "" when nothing
    /// legible is found.
    static func identifyMedicine(imageData: Data, mimeType: String) async -> String {
        let prompt = """
        This photo shows a medicine (a strip, box, bottle, or printed label). Identify it.
        Return ONLY the medicine's name exactly as printed — brand name if visible, otherwise the
        generic/salt name — with no other words, no punctuation and no explanation.
        If no medicine name is legible, return exactly: NONE
        """
        do {
            let raw = try await GeminiClient.shared.generateFromImages(prompt: prompt, images: [(imageData, mimeType)])
            var cleaned = GeminiClient.stripJsonFences(raw).trimmingCharacters(in: .whitespacesAndNewlines)
            if cleaned.hasPrefix("\""), cleaned.hasSuffix("\""), cleaned.count > 1 {
                cleaned = String(cleaned.dropFirst().dropLast())
            }
            cleaned = cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
            if cleaned.caseInsensitiveCompare("NONE") == .orderedSame || cleaned.isEmpty { return "" }
            return cleaned.components(separatedBy: .newlines).first?.trimmingCharacters(in: .whitespaces) ?? ""
        } catch {
            return ""
        }
    }

    /// Looks up basic info about a medicine via Gemini; results are cached on-device per
    /// name+language so repeated lookups are instant, same as Android.
    static func lookupMedicineInfo(name: String) async -> MedicineInfo {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return MedicineInfo(basicUse: "Please enter a medicine name.") }

        let lang = AppSettings.preferredLanguage
        let key = "\(trimmed.lowercased())::\(lang.lowercased())"

        var cache = loadCache()
        if let cached = cache[key] { return cached }

        let langLine = lang.caseInsensitiveCompare("English") == .orderedSame ? "" :
            "Write \"basicUse\" and each \"keyNotes\" item in \(lang) using simple everyday words a common person understands. Keep \"genericName\" and \"category\" values in English."

        let prompt = """
        For the medicine "\(trimmed)", provide a brief patient-friendly reference.
        \(langLine)
        Return ONLY raw JSON (no code fences):
        {"category":"<one of: Antibiotic, Antacid, Painkiller, Anti-inflammatory, Vitamin/Supplement, Antidiabetic, Antihypertensive, Antihistamine, Steroid, Antifungal, Antiviral, Bronchodilator, Laxative, Probiotic, Cardiac, Antipyretic, Muscle Relaxant, Antidepressant, Other>","genericName":"<generic salt name if this is a brand, or common brand names if this is a generic>","basicUse":"<1-2 sentence patient-friendly explanation of why doctors prescribe this medicine>","keyNotes":["<practical tip 1, e.g. Take after food>","<practical tip 2>","<practical tip 3>"]}
        If you don't recognise the medicine name or it seems misspelled, set category to "Unknown" and basicUse to a suggestion like "This name was not recognised. Please check the spelling."
        """

        let result: MedicineInfo
        do {
            let raw = try await GeminiClient.shared.generateText(prompt: prompt)
            let json = GeminiClient.stripJsonFences(raw)
            if let data = json.data(using: .utf8), let decoded = try? JSONDecoder().decode(MedicineInfo.self, from: data) {
                result = decoded
            } else {
                result = MedicineInfo(category: "Unknown", basicUse: "Could not look up this medicine. Check your internet connection or Gemini API key.")
            }
        } catch {
            result = MedicineInfo(category: "Unknown", basicUse: "Could not look up this medicine. Check your internet connection or Gemini API key.")
        }

        cache[key] = result
        saveCache(cache)
        return result
    }

    // MARK: - Chat (DocBot)

    /// Answers a question about the patient's own records. Returns the answer plus its source
    /// ("ai" or "local"), mirroring `MedicalEngine.chat`.
    static func chat(
        question: String, reports: [MedicalReport], history: [ChatMessage], imageData: Data? = nil
    ) async -> (answer: String, source: String) {
        let language = AppSettings.preferredLanguage
        let context = buildReportsContext(reports)
        let historyText = history.suffix(6)
            .map { "\($0.role == "user" ? "Patient" : "Assistant"): \($0.content)" }
            .joined(separator: "\n")

        let prompt = """
        You are a friendly, conversational medical assistant helping a patient understand their own records. Answer in clear, simple, plain language. Be warm, supportive, and factual.
        CRITICAL LANGUAGE INSTRUCTION: You MUST reply entirely in the patient's preferred language: \(language). Do not reply in English unless the preferred language is English.
        If the patient's question is vague (e.g., "why is my report bad?", "what does this mean?"), you MUST ask them a clarifying question about their history, specific symptoms, or which report they are referring to before giving an assessment.
        When asked about specific details or "why" something is happening, correlate findings and trends across the patient's historical reports provided below.
        SAFETY & MEDICAL DISCLAIMER: You are NOT a doctor; do not diagnose, prescribe, or give medical advice. Ground all correlations purely in the records provided. If the patient asks which doctor or specialist they should see based on their results, recommend the type of medical specialist (e.g., Endocrinologist for Thyroid, Cardiologist for Cardiac/Lipids).

        IMPORTANT: At the end of every response, you MUST append this exact patient disclaimer (translated into \(language)):
        "Disclaimer: This information is purely educational and informational. It is not a confirmed medical diagnosis or appointment. Please consult a doctor and do not rely solely on this information."
        Keep answers concise (3-5 sentences).

        PATIENT'S HISTORICAL RECORDS:
        \(context.isEmpty ? "No reports available yet." : context)
        \(historyText.isEmpty ? "" : "CONVERSATION SO FAR:\n\(historyText)\n")
        \(imageData != nil ? "[An image is attached to this request]" : "")
        PATIENT'S QUESTION: \(question)
        Answer (in \(language)):
        """

        do {
            let answer: String
            if let imageData {
                answer = try await GeminiClient.shared.generateFromImages(
                    prompt: prompt, images: [(imageData, "image/jpeg")]
                )
            } else {
                answer = try await GeminiClient.shared.generateText(prompt: prompt)
            }
            let trimmed = answer.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { return (trimmed, "ai") }
        } catch {
            // fall through to the offline answer
        }
        return (localChat(question: question, reports: reports), "local")
    }

    private static func localChat(question: String, reports: [MedicalReport]) -> String {
        let disclaimer = "\n\nDisclaimer: This information is purely educational and informational. It is not a confirmed medical diagnosis or appointment. Please consult a doctor and do not rely solely on this information."
        guard !reports.isEmpty else {
            return "I don't have any of your reports on file yet. Once you scan a report, I can help explain your results, medicines, and doctor's notes. For any medical concern, please consult your doctor.\(disclaimer)"
        }
        let suffix = "(Offline mode — check your connection or Gemini API key for smarter answers.)\(disclaimer)"
        let q = question.lowercased()
        if q.contains("doctor") || q.contains("specialist") || q.contains("physician") {
            return "Based on your saved reports, you should discuss abnormal findings with a suitable specialist (e.g. Cardiologist for lipids/heart, Endocrinologist for thyroid, Diabetologist for high sugar). \(suffix)"
        }
        return "Based on your saved reports:\n\n\(buildReportsContext(reports))\n\nAnything marked abnormal is worth discussing with your doctor. \(suffix)"
    }

    private static func buildReportsContext(_ reports: [MedicalReport]) -> String {
        guard !reports.isEmpty else { return "" }
        return reports.prefix(12).enumerated().map { index, report in
            var lines = ["Report \(index + 1) — \(report.reportDate ?? "—") — \(report.reportType ?? "Report") (\(report.reportCategory ?? "-"))"]
            if let patient = report.patientName, !patient.isEmpty { lines.append("  Patient: \(patient)") }
            let abnormal = (report.testResults?.parameters ?? []).filter {
                let status = ($0.status ?? "").lowercased()
                return !status.isEmpty && status != "normal"
            }.map { "\($0.name): \($0.value) \($0.unit) (ref \($0.referenceRange), \($0.status ?? ""))" }
            if !abnormal.isEmpty { lines.append("  Abnormal: \(abnormal.joined(separator: "; "))") }
            let findings = (report.testResults?.findings ?? []).prefix(4)
            if !findings.isEmpty { lines.append("  Findings: \(findings.joined(separator: "; "))") }
            let meds = report.medications.map { medication -> String in
                var text = medication.name
                if !medication.dosage.isEmpty { text += " \(medication.dosage)" }
                if !medication.frequency.isEmpty { text += " [\(medication.frequency)]" }
                return text
            }
            if !meds.isEmpty { lines.append("  Medications: \(meds.joined(separator: "; "))") }
            if let comments = report.comments, !comments.isEmpty {
                lines.append("  Comments: \(comments.prefix(200))")
            }
            return lines.joined(separator: "\n")
        }.joined(separator: "\n\n")
    }

    // MARK: - Health insights & comparison

    /// Patient-friendly interpretation of one report; falls back to rule-based local logic.
    static func healthInsights(report: MedicalReport) async -> HealthInsights {
        let encoder = JSONEncoder()
        func json<T: Encodable>(_ value: T) -> String {
            (try? encoder.encode(value)).flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
        }
        let prompt = """
        You are a medical AI. Analyze this report and return patient-friendly insights in plain language. Be reassuring and factual.
        Category: \(report.reportCategory ?? "-") Type: \(report.reportType ?? "-")
        Comments: \(report.comments ?? "")
        Parameters: \(json(report.testResults?.parameters ?? []))
        Findings: \(json(report.testResults?.findings ?? []))
        Medications: \(json(report.medications))
        Return ONLY raw JSON with schema:
        {"interpretation":"3-4 sentences","specialistRecommendations":[{"specialist":"","reason":"","urgency":"Routine|Soon|Urgent"}],"prescriptionAlignment":{"aligned":true,"score":"Good|Partial|Poor|N/A","analysis":"","flags":[]},"sideEffects":[{"medicine":"","commonEffects":[],"seriousEffects":[],"severity":"Mild|Moderate|Serious","tips":""}]}
        Only recommend specialists if findings warrant it. Empty sideEffects if no medications.
        """
        if let raw = try? await GeminiClient.shared.generateText(prompt: prompt),
           let data = GeminiClient.stripJsonFences(raw).data(using: .utf8),
           let decoded = try? JSONDecoder().decode(HealthInsights.self, from: data) {
            return decoded
        }
        return localInsights(report: report)
    }

    private static func localInsights(report: MedicalReport) -> HealthInsights {
        let params = report.testResults?.parameters ?? []
        let findings = report.testResults?.findings ?? []
        let abnormal = params.filter {
            let status = ($0.status ?? "").lowercased()
            return !status.isEmpty && status != "normal"
        }

        let interpretation: String
        if abnormal.isEmpty && findings.isEmpty {
            interpretation = "Your \(report.reportCategory ?? "medical") report results appear within normal reference ranges. This is a positive sign. Please still discuss with your doctor at your next visit."
        } else {
            var text = "Your report shows some values that need attention. "
            if !abnormal.isEmpty {
                let list = abnormal.map { "\($0.name) (\($0.value) \($0.unit), ref \($0.referenceRange))" }
                text += "Outside the normal range: \(list.joined(separator: ", ")). "
            }
            if !findings.isEmpty { text += "Key findings: \(findings.prefix(2).joined(separator: ". ")). " }
            text += "Please follow up with your doctor to discuss these results."
            interpretation = text
        }

        // Keyword → specialist map, same pairs as the Android original.
        let specialistMap: [(keys: [String], specialist: String, reason: String)] = [
            (["tsh", "thyroid", "t3", "t4"], "Endocrinologist", "Thyroid parameter abnormality detected"),
            (["ejection fraction", "lvef", "mitral", "aortic"], "Cardiologist", "Cardiac finding detected"),
            (["liver", "hepatic", "sgpt", "sgot", "alt", "ast", "bilirubin"], "Hepatologist", "Liver abnormality detected"),
            (["creatinine", "kidney", "renal", "gfr", "urea"], "Nephrologist", "Kidney function abnormality detected"),
            (["hemoglobin", "rbc", "anemia", "platelet", "wbc", "cbc"], "Hematologist", "Blood count outside normal range"),
            (["glucose", "blood sugar", "hba1c", "diabetes"], "Diabetologist / Endocrinologist", "Blood sugar finding detected"),
            (["lung", "chest", "pleural", "pneumonia"], "Pulmonologist", "Respiratory finding detected"),
            (["cholesterol", "ldl", "hdl", "triglyceride", "lipid"], "Cardiologist", "Lipid profile abnormality — cardiovascular risk")
        ]
        let allText = (params.map(\.name) + findings + [report.comments ?? "", report.reportCategory ?? ""])
            .joined(separator: " ").lowercased()
        let urgency = abnormal.contains {
            let status = ($0.status ?? "").lowercased()
            return status == "high" || status == "low"
        } ? "Soon" : "Routine"

        var specialists: [SpecialistRecommendation] = []
        for entry in specialistMap {
            if entry.keys.contains(where: { allText.contains($0) }),
               !specialists.contains(where: { $0.specialist == entry.specialist }) {
                specialists.append(SpecialistRecommendation(
                    specialist: entry.specialist, reason: entry.reason, urgency: urgency
                ))
            }
        }
        if specialists.isEmpty && !abnormal.isEmpty {
            specialists.append(SpecialistRecommendation(
                specialist: "General Physician",
                reason: "Some parameters are outside normal range and need clinical correlation",
                urgency: "Routine"
            ))
        }

        let score = report.medications.isEmpty ? "N/A" : "Good"
        let analysis: String
        if report.medications.isEmpty {
            analysis = "No medications are listed in this report, so alignment cannot be assessed."
        } else if abnormal.isEmpty && findings.isEmpty {
            analysis = "The \(report.medications.count) prescribed medicine(s) are noted and results appear normal."
        } else {
            analysis = "Review the report findings against current prescriptions with your doctor."
        }

        let sideEffects = report.medications.map { medication in
            MedicineSideEffect(
                medicine: medication.name,
                commonEffects: ["Nausea or stomach upset", "Dizziness", "Headache"],
                seriousEffects: ["Allergic reaction — seek care if breathing difficulty"],
                severity: "Mild",
                tips: "Take as prescribed. Report unusual symptoms."
            )
        }

        return HealthInsights(
            interpretation: interpretation,
            specialistRecommendations: specialists,
            prescriptionAlignment: PrescriptionAlignment(
                aligned: score != "Poor", score: score, analysis: analysis, flags: []
            ),
            sideEffects: sideEffects
        )
    }

    /// Compares a new report against the previous one for the same patient/category.
    static func compareReports(newReport: MedicalReport, previous: MedicalReport?) -> ComparisonResult {
        guard let previous else { return ComparisonResult(hasComparison: false) }

        let prevMeds = previous.medications
        let curMeds = newReport.medications
        let added = curMeds.filter { c in !prevMeds.contains { $0.name.caseInsensitiveCompare(c.name) == .orderedSame } }.map(\.name)
        let removed = prevMeds.filter { p in !curMeds.contains { $0.name.caseInsensitiveCompare(p.name) == .orderedSame } }.map(\.name)
        let changed = curMeds.compactMap { c -> String? in
            guard let p = prevMeds.first(where: { $0.name.caseInsensitiveCompare(c.name) == .orderedSame }) else { return nil }
            guard p.dosage != c.dosage || p.frequency != c.frequency else { return nil }
            return "\(c.name) (from \(p.dosage) [\(p.frequency)] to \(c.dosage) [\(c.frequency)])"
        }

        let prevParams = previous.testResults?.parameters ?? []
        let curParams = newReport.testResults?.parameters ?? []
        var differences: [TestDifference] = []
        var improved = 0
        var worsened = 0

        for c in curParams {
            guard let p = prevParams.first(where: { $0.name.caseInsensitiveCompare(c.name) == .orderedSame }) else { continue }
            var change = "stable"
            var status = "no_change"
            if let pv = Double(p.value), let cv = Double(c.value) {
                change = cv > pv ? "increased" : (cv < pv ? "decreased" : "stable")
                let cNormal = (c.status ?? "").caseInsensitiveCompare("normal") == .orderedSame
                let pNormal = (p.status ?? "").caseInsensitiveCompare("normal") == .orderedSame
                if cNormal && !pNormal { status = "improved"; improved += 1 }
                else if !cNormal && pNormal { status = "worsened"; worsened += 1 }
                else if change != "stable" { status = "changed" }
            }
            differences.append(TestDifference(
                name: c.name,
                previous: "\(p.value) \(p.unit)".trimmingCharacters(in: .whitespaces),
                current: "\(c.value) \(c.unit)".trimmingCharacters(in: .whitespaces),
                change: change, status: status
            ))
        }

        let overall: String
        if improved > 0 && worsened == 0 { overall = "improved" }
        else if worsened > 0 && improved == 0 { overall = "worsened" }
        else if improved > 0 && worsened > 0 { overall = "mixed" }
        else { overall = "no_change" }

        var parts: [String] = []
        if !added.isEmpty { parts.append("added: \(added.joined(separator: ", "))") }
        if !removed.isEmpty { parts.append("discontinued: \(removed.joined(separator: ", "))") }
        if !changed.isEmpty { parts.append("modified: \(changed.joined(separator: ", "))") }
        if !differences.isEmpty {
            parts.append("parameters changed: \(differences.map { "\($0.name) \($0.change)" }.joined(separator: ", "))")
        }
        let summary = "Compared to \(previous.reportDate ?? "the previous report"), " +
            (parts.isEmpty ? "no significant changes detected." : parts.joined(separator: "; ") + ".")

        return ComparisonResult(
            hasComparison: true,
            previousReportId: previous.id,
            previousReportDate: previous.reportDate,
            comparisonSummary: summary,
            status: overall,
            differences: differences,
            medicationChanges: MedicationChanges(added: added, removed: removed, changed: changed)
        )
    }

    // MARK: - Detailed analysis

    static func detailedAnalysis(report: MedicalReport) async -> DetailedAnalysis {
        let disclaimer = "This detailed analysis is AI-generated to help you understand your report in plain language. It is educational only and is NOT a medical diagnosis. Always confirm decisions with your doctor."
        let encoder = JSONEncoder()
        func json<T: Encodable>(_ value: T) -> String {
            (try? encoder.encode(value)).flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
        }
        let abnormal = (report.testResults?.parameters ?? []).filter {
            let status = ($0.status ?? "").lowercased()
            return !status.isEmpty && status != "normal"
        }
        let prompt = """
        You are a clinician writing a thorough, easy-to-understand analysis of ONE report for the patient. Go deeper than a summary: explain the "why", connect findings to medicines, give practical guidance. Warm, plain language, never alarmist.
        Patient: \(report.patientName ?? "-") Date: \(report.reportDate ?? "-") Type: \(report.reportType ?? "-") (\(report.reportCategory ?? "-"))
        Parameters: \(json(report.testResults?.parameters ?? []))
        Abnormal: \(json(abnormal))
        Findings: \(json(report.testResults?.findings ?? []))
        Medications: \(json(report.medications))
        Comments: \(report.comments ?? "")
        Return ONLY raw JSON:
        {"summary":"2-3 sentences","sections":[{"title":"Detailed Interpretation","content":""},{"title":"Parameter-by-Parameter Breakdown","content":""},{"title":"Possible Causes & Context","content":""},{"title":"Medicines Explained","content":""},{"title":"Lifestyle & Diet Guidance","content":""},{"title":"Questions to Ask Your Doctor","content":""},{"title":"When to Seek Care Sooner","content":""}]}
        Use short paragraphs and dashed lists ("- item"). If a section has nothing, keep it and say so briefly.
        """

        if let raw = try? await GeminiClient.shared.generateText(prompt: prompt),
           let data = GeminiClient.stripJsonFences(raw).data(using: .utf8),
           var decoded = try? JSONDecoder().decode(DetailedAnalysis.self, from: data),
           !decoded.sections.isEmpty {
            decoded.disclaimer = disclaimer
            decoded.source = "ai"
            return decoded
        }

        var local = localDetailed(report: report)
        local.disclaimer = disclaimer
        local.source = "local"
        return local
    }

    private static func localDetailed(report: MedicalReport) -> DetailedAnalysis {
        let insights = localInsights(report: report)
        let params = report.testResults?.parameters ?? []
        let findings = report.testResults?.findings ?? []
        let abnormal = params.filter {
            let status = ($0.status ?? "").lowercased()
            return !status.isEmpty && status != "normal"
        }

        var sections = [DetailedAnalysisSection(title: "Detailed Interpretation", content: insights.interpretation)]
        if !params.isEmpty {
            let breakdown = params.map { parameter -> String in
                let status = (parameter.status ?? "")
                let flag = !status.isEmpty && status.caseInsensitiveCompare("normal") != .orderedSame
                    ? " — \(status.uppercased())" : " — within normal range"
                return "- \(parameter.name): \(parameter.value) \(parameter.unit) (ref \(parameter.referenceRange))\(flag)"
            }.joined(separator: "\n")
            sections.append(DetailedAnalysisSection(title: "Parameter-by-Parameter Breakdown", content: breakdown))
        } else if !findings.isEmpty {
            sections.append(DetailedAnalysisSection(
                title: "Key Findings", content: findings.map { "- \($0)" }.joined(separator: "\n")
            ))
        }
        if !abnormal.isEmpty {
            sections.append(DetailedAnalysisSection(
                title: "Possible Causes & Context",
                content: "Values outside range: \(abnormal.map(\.name).joined(separator: ", ")). These can be influenced by diet, hydration, time of day, recent illness, or medication. A single reading is not a diagnosis — trends over time matter more."
            ))
        }
        sections.append(DetailedAnalysisSection(
            title: "Medicines Explained",
            content: insights.sideEffects.isEmpty
                ? "No medicines are listed in this report."
                : insights.sideEffects.map { "- \($0.medicine): \($0.tips)" }.joined(separator: "\n")
        ))
        sections.append(DetailedAnalysisSection(
            title: "Questions to Ask Your Doctor",
            content: "- What do my abnormal values mean for me?\n- Do my medicines need any change?\n- When should I repeat this test?"
        ))
        sections.append(DetailedAnalysisSection(
            title: "When to Seek Care Sooner",
            content: "Contact your doctor promptly for new or worsening symptoms (chest pain, severe breathlessness, persistent high fever, fainting). How you feel matters too."
        ))

        let summary = abnormal.isEmpty
            ? "This report looks largely within normal ranges. Below is a detailed, plain-language breakdown."
            : "This report has \(abnormal.count) value(s) worth discussing with your doctor. Below is a detailed, plain-language breakdown."
        return DetailedAnalysis(summary: summary, sections: sections)
    }

    // MARK: - On-disk cache

    private static var cacheFileURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("medicine_info_cache", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("cache.json")
    }

    private static func loadCache() -> [String: MedicineInfo] {
        guard let data = try? Data(contentsOf: cacheFileURL) else { return [:] }
        return (try? JSONDecoder().decode([String: MedicineInfo].self, from: data)) ?? [:]
    }

    private static func saveCache(_ cache: [String: MedicineInfo]) {
        guard let data = try? JSONEncoder().encode(cache) else { return }
        try? data.write(to: cacheFileURL)
    }
}
