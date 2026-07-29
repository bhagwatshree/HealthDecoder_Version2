import Foundation

/// Future test the doctor recommended, extracted from a scan.
struct RecommendedTest: Codable, Equatable {
    var testName: String
    var dueDate: String? = nil
}

/// One date visible on a page together with its printed label ("Reported", "Collected"...).
struct FoundDate: Codable, Equatable {
    var label: String? = nil
    var date: String? = nil
}

/// Structured result of scanning one report/prescription image.
struct ScanExtraction: Codable, Equatable {
    var patientName: String? = nil
    var reportName: String? = nil
    var reportDate: String? = nil
    var dateSource: String? = nil
    var datesFound: [FoundDate] = []
    var reportType: String? = nil
    var comments: String? = nil
    var medications: [Medication] = []
    var recommendedTests: [RecommendedTest] = []
    var testResults: TestResults? = nil
    var rawText: String? = nil
}

/// Full extraction of a scan, which may contain SEVERAL distinct reports (e.g. a bundle of
/// CBC + lipid profile + 2D Echo pages), each with its own name and dates.
struct MultiScanExtraction: Codable, Equatable {
    var patientName: String? = nil
    var reports: [ScanExtraction] = []
    var rawText: String? = nil
}

/// Direct port of `ai/OcrEngine.kt`. Sends the page image(s) to Gemini vision (so handwriting is
/// read directly), with any on-device OCR text as a hint. Falls back to a light local parse if
/// Gemini is unavailable.
enum OcrEngine {
    /// Scans one or more page images. The pages may contain several distinct reports; each
    /// comes back as its own entry with its own name and correctly chosen date.
    ///
    /// Large batches are processed CHUNK BY CHUNK (`AppSettings.scanChunkPages` pages per AI
    /// request — one giant request exceeds free-tier limits and fails), then the chunk results
    /// are merged; a report whose pages span two chunks is recombined by matching name + date.
    /// A failed chunk is skipped rather than failing the whole scan.
    static func scan(
        images: [(data: Data, mimeType: String)],
        localOcrText: String,
        scanType: String,
        reportCategory: String
    ) async -> MultiScanExtraction {
        let chunkSize = AppSettings.scanChunkPages
        let chunks: [[(data: Data, mimeType: String)]] = images.isEmpty
            ? [[]]
            : stride(from: 0, to: images.count, by: chunkSize).map {
                Array(images[$0..<min($0 + chunkSize, images.count)])
              }

        var results: [MultiScanExtraction] = []
        for (index, chunk) in chunks.enumerated() {
            // The device-OCR hint text belongs to the first page; only give it to chunk 1.
            let ref = index == 0 ? localOcrText : ""
            if let result = await scanChunk(
                images: chunk, referenceText: ref, scanType: scanType,
                reportCategory: reportCategory, part: index + 1, totalParts: chunks.count
            ) {
                results.append(result)
            }
        }
        if results.isEmpty { return localFallback(localOcrText: localOcrText, scanType: scanType) }
        return mergeChunks(results)
    }

    private static func scanChunk(
        images: [(data: Data, mimeType: String)], referenceText: String, scanType: String,
        reportCategory: String, part: Int, totalParts: Int
    ) async -> MultiScanExtraction? {
        let prompt = buildPrompt(
            referenceText: referenceText, scanType: scanType, reportCategory: reportCategory,
            pageCount: images.count, part: part, totalParts: totalParts
        )
        do {
            let raw = try await GeminiClient.shared.generateFromImages(prompt: prompt, images: images)
            return parse(GeminiClient.stripJsonFences(raw))
        } catch {
            print("OcrEngine chunk \(part)/\(totalParts) failed: \(error)")
            return nil
        }
    }

    /// Merges per-chunk extractions into one result. Sections with the same report name and
    /// date (a report whose pages landed in different chunks) are combined; distinct reports
    /// stay separate.
    static func mergeChunks(_ chunks: [MultiScanExtraction]) -> MultiScanExtraction {
        if chunks.count == 1 { return chunks[0] }

        var order: [String] = []
        var merged: [String: ScanExtraction] = [:]

        for chunk in chunks {
            for section in chunk.reports {
                let namePart = (section.reportName ?? section.reportType ?? "report")
                    .trimmingCharacters(in: .whitespaces).lowercased()
                let key = "\(namePart)|\(section.reportDate ?? "")"

                if let prev = merged[key] {
                    var combined = prev
                    let comments = [prev.comments, section.comments]
                        .compactMap { $0?.trimmingCharacters(in: .whitespaces) }
                        .filter { !$0.isEmpty }
                    combined.comments = orderedUnique(comments).joined(separator: "\n")
                    combined.medications = prev.medications + section.medications
                    combined.recommendedTests = orderedUnique(
                        prev.recommendedTests + section.recommendedTests,
                        key: { $0.testName.trimmingCharacters(in: .whitespaces).lowercased() }
                    )
                    combined.datesFound = orderedUnique(prev.datesFound + section.datesFound)
                    let params = (prev.testResults?.parameters ?? []) + (section.testResults?.parameters ?? [])
                    let findings = orderedUnique((prev.testResults?.findings ?? []) + (section.testResults?.findings ?? []))
                    combined.testResults = TestResults(parameters: params, findings: findings)
                    let rawTexts = [prev.rawText, section.rawText]
                        .compactMap { $0?.trimmingCharacters(in: .whitespaces) }
                        .filter { !$0.isEmpty }
                    combined.rawText = rawTexts.joined(separator: "\n\n")
                    merged[key] = combined
                } else {
                    merged[key] = section
                    order.append(key)
                }
            }
        }

        let patientName = chunks.compactMap { $0.patientName?.trimmingCharacters(in: .whitespaces) }
            .first { !$0.isEmpty }
        let rawText = chunks.compactMap { $0.rawText?.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .joined(separator: "\n\n")

        return MultiScanExtraction(
            patientName: patientName,
            reports: order.compactMap { merged[$0] },
            rawText: rawText
        )
    }

    /// Parses the new multi-report shape, falling back to the legacy single-report shape.
    private static func parse(_ json: String) -> MultiScanExtraction? {
        guard let data = json.data(using: .utf8) else { return nil }
        let decoder = JSONDecoder()
        if let multi = try? decoder.decode(MultiScanExtraction.self, from: data), !multi.reports.isEmpty {
            return multi
        }
        guard let legacy = try? decoder.decode(ScanExtraction.self, from: data) else { return nil }
        return MultiScanExtraction(patientName: legacy.patientName, reports: [legacy], rawText: legacy.rawText)
    }

    private static func buildPrompt(
        referenceText: String, scanType: String, reportCategory: String,
        pageCount: Int, part: Int = 1, totalParts: Int = 1
    ) -> String {
        var pagesNote = ""
        if pageCount > 1 { pagesNote += "The document is provided as \(pageCount) page images." }
        if totalParts > 1 {
            pagesNote += " NOTE: these pages are part \(part) of \(totalParts) of a larger scan batch processed in " +
                "chunks. Extract ONLY what is visible on these pages; other parts are processed " +
                "separately. A report may continue in another part — still extract everything visible here."
        }

        let categoryText = scanType == "prescription"
            ? "This document is a Medicine Prescription. Focus heavily on identifying the doctor's prescribed medications, dosages, frequency, durations, and instruction comments."
            : "This document is a Medical/Diagnostic Report of category \"\(reportCategory)\". Focus on patient name, dates, and extracting findings, observations, conclusions, and test parameters (values, units, reference ranges, abnormal flags)."

        let refBlock = referenceText.trimmingCharacters(in: .whitespaces).isEmpty ? "" :
            "Here is auxiliary on-device OCR text to assist accuracy. It may be incomplete or miss handwriting, so ALWAYS prefer what you can read directly from the image:\n\"\"\"\n\(referenceText)\n\"\"\"\n"

        return """
        Analyze this medical report, lab result, or prescription image and extract the details as a JSON object.
        \(pagesNote)
        \(refBlock)
        Context instructions:
        \(categoryText)

        IMPORTANT: This document may contain HANDWRITTEN text (a doctor's handwriting, margin notes, ticked boxes, or corrections). Read handwritten medicines, dosages, frequencies, and comments carefully and include them — do NOT ignore handwriting. If partly illegible, transcribe your best interpretation.

        MULTIPLE REPORTS: The pages may contain SEVERAL distinct reports (for example a CBC, a lipid profile, and a 2D Echo bundled together), each with its own report name and its own dates. Return one entry in "reports" for EACH distinct report. Pages belonging to the same report must be merged into ONE entry. If everything is one single report, return a single entry.

        DATES — read these rules very carefully:
        A page often shows several dates with different labels: "Printed on", "Registered on", "Collected on" / "Sample collected", "Reported on" / "Reporting date" / "Report date", "Date of procedure" / "Study date" / "Date of examination", a visit date, or a bare date with no label. For EACH report:
        1. List EVERY visible date with its label in "datesFound" (use label "" for an unlabeled date).
        2. Choose "reportDate" by these priority rules:
           - Blood / urine / any sample-based lab report: use the REPORTED / REPORTING date. If missing, the sample COLLECTED date. NEVER the printed date.
           - Procedure or imaging report (2D Echo, Sonography/USG, X-Ray, ECG, CT, MRI, Doppler, Endoscopy...): use the PROCEDURE / STUDY / EXAMINATION date — the date it was performed.
           - Prescription: the visit / consultation date.
           - A bare unlabeled date: use it only when none of the above exist.
        3. Set "dateSource" to the label of the date you chose (e.g. "Reported", "Procedure", "Visit", "Unlabeled").
        4. Convert ALL dates to YYYY-MM-DD. Dates may be printed day-first in Indian formats (12/03/2026, 12-03-26, 12.Mar.2026, 12 March 2026). Do not guess; if no date is visible for a report, set reportDate to null.

        Also ensure that:
        1. Patient name is identified accurately.
        2. "reportName" is the specific printed name of each report (e.g. "Complete Blood Count", "Lipid Profile", "2D Echocardiography").
        3. Comments, instructions, remarks, or advice are extracted per report.
        4. Medicines mentioned are extracted as an array in the report where they appear.
        5. Future recommended tests go into that report's "recommendedTests".
        6. Test results go into that report's "testResults": lab parameters into "parameters"; scan/diagnostic conclusions into "findings".
        7. For each parameter, also classify it for trend-charting across multiple reports over time:
           - "trendCategory": if it matches one of these, use that EXACT text (case-sensitive) —
             Blood Sugar, HbA1c, TSH, T3, T4, Hemoglobin, WBC, Platelets, Total Cholesterol, LDL, HDL,
             Triglycerides, Creatinine, Oxygen (SpO2), Ejection Fraction, Vitamin D, Vitamin B12
             — otherwise a short clean name of your own for that specific test. Never merge two
             DIFFERENT tests into one category just because they share a word or organ — e.g. serum
             creatinine and urinary creatinine are different categories; blood glucose and urine
             glucose are different categories; an actual measured value and a value CALCULATED from
             a different test (e.g. HbA1c's "estimated average glucose") are different categories.
           - "trendCondition": the condition it was measured under, if the report states one — mainly
             relevant to blood sugar: "Fasting", "PP" (post-meal), or "Random". Empty string otherwise.
           - "excludeFromTrend": true only when the value is NOT a direct numeric measurement — e.g. a
             value calculated/derived from another test, or a semi-quantitative dipstick result like
             "+", "++", "+++", "Negative", "Trace". False for every normal numeric lab result.

        The response MUST be a JSON object with this schema:
        {
          "patientName": "Name or null",
          "reports": [
            {
              "reportName": "Specific report name or null",
              "reportType": "Prescription | Lab Report | Diagnostic Scan | Other",
              "reportDate": "YYYY-MM-DD or null",
              "dateSource": "Reported | Collected | Procedure | Visit | Unlabeled | null",
              "datesFound": [ { "label": "Reported", "date": "YYYY-MM-DD" } ],
              "comments": "Doctor's instructions/advice/notes for THIS report",
              "medications": [
                { "name": "", "dosage": "", "frequency": "", "duration": "", "isOptional": false, "weeklySchedule": ["Everyday"], "notes": "" }
              ],
              "recommendedTests": [ { "testName": "", "dueDate": "YYYY-MM-DD or null" } ],
              "testResults": {
                "parameters": [
                  {
                    "name": "", "value": "", "unit": "", "referenceRange": "", "status": "High | Low | Normal",
                    "trendCategory": "", "trendCondition": "", "excludeFromTrend": false
                  }
                ],
                "findings": [ "" ]
              },
              "rawText": "Markdown transcription of THIS report's pages"
            }
          ],
          "rawText": "A clean, markdown-formatted full transcription of ALL visible text."
        }

        Return ONLY raw JSON. No markdown code fences, no extra text.
        """
    }

    /// Minimal offline fallback: keep the OCR text and a best-effort patient name.
    private static func localFallback(localOcrText: String, scanType: String) -> MultiScanExtraction {
        let name = extractPatientName(localOcrText)
        let type: String
        switch scanType {
        case "prescription": type = "Prescription"
        case "report": type = "Lab Report"
        default: type = "Other"
        }
        let section = ScanExtraction(
            reportDate: nil,
            reportType: type,
            comments: "Parsed on-device from OCR text (AI unavailable).",
            testResults: TestResults(),
            rawText: localOcrText
        )
        var withName = section
        withName.patientName = name
        return MultiScanExtraction(patientName: name, reports: [withName], rawText: localOcrText)
    }

    private static func extractPatientName(_ text: String) -> String {
        guard !text.trimmingCharacters(in: .whitespaces).isEmpty else { return "Unknown Patient" }
        let pattern = #"(?:Name|Patient|Patient\s*Name)\s*[:\-]?\s*(?:Mr\.|Mrs\.|Ms\.)?\s*([A-Za-z ]{3,})"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive) else {
            return "Unknown Patient"
        }
        let range = NSRange(text.startIndex..., in: text)
        guard let match = regex.firstMatch(in: text, range: range),
              let group = Range(match.range(at: 1), in: text) else {
            return "Unknown Patient"
        }
        let candidate = text[group]
            .trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        return candidate.count > 3 ? candidate : "Unknown Patient"
    }

    /// Collapses `MultiScanExtraction` into legacy-style `ScanExtraction` (used by Compare, a
    /// later phase) and generically by callers that just want "the one report" from a scan.
    static func merged(_ extraction: MultiScanExtraction) -> ScanExtraction {
        guard let first = extraction.reports.first else {
            return ScanExtraction(patientName: extraction.patientName, rawText: extraction.rawText)
        }
        let comments = extraction.reports.compactMap { $0.comments?.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        return ScanExtraction(
            patientName: extraction.patientName,
            reportName: first.reportName,
            reportDate: first.reportDate,
            dateSource: first.dateSource,
            datesFound: extraction.reports.flatMap { $0.datesFound },
            reportType: first.reportType,
            comments: comments.joined(separator: "\n"),
            medications: extraction.reports.flatMap { $0.medications },
            recommendedTests: extraction.reports.flatMap { $0.recommendedTests },
            testResults: TestResults(
                parameters: extraction.reports.flatMap { $0.testResults?.parameters ?? [] },
                findings: extraction.reports.flatMap { $0.testResults?.findings ?? [] }
            ),
            rawText: extraction.rawText ?? first.rawText
        )
    }
}

private func orderedUnique(_ items: [String]) -> [String] {
    var seen = Set<String>()
    return items.filter { seen.insert($0).inserted }
}

private func orderedUnique(_ items: [FoundDate]) -> [FoundDate] {
    var seen = Set<String>()
    return items.filter { seen.insert(($0.label ?? "") + "|" + ($0.date ?? "")).inserted }
}

private func orderedUnique(_ items: [RecommendedTest], key: (RecommendedTest) -> String) -> [RecommendedTest] {
    var seen = Set<String>()
    return items.filter { seen.insert(key($0)).inserted }
}
