import UIKit

/// Paints over identity-bearing regions of a scanned page before the image is uploaded for
/// analysis, so the AI provider receives the clinical content without the patient's name, the
/// doctor, the lab's letterhead, or account/contact identifiers.
///
/// Direct port of `ai/PiiRedactor.kt` — same rules, same regexes, same ordering. The only
/// difference is the source of the line boxes: Vision here, ML Kit there (see `TextRecognizer`).
///
/// BEST-EFFORT, NOT A GUARANTEE — read this before describing the behaviour to a user. Redaction
/// keys off the on-device OCR's line boxes, so anything the OCR misses (or renders as an
/// unlabelled bare name, a logo drawn as artwork, a handwritten name) is not covered and is
/// uploaded. Treat this as materially reducing what leaves the device, not as de-identification
/// in the regulatory sense. The un-redacted originals always stay on the device.
///
/// The rules deliberately favour UNDER-redacting over destroying clinical data: a page here is
/// about to be read for medication dosages and lab values, and a black box across the wrong line
/// is a wrong dosage, which is far worse than a lab's name reaching the model. Every rule below
/// therefore matches an explicit identity signal rather than guessing from position alone.
enum PiiRedactor {

    /// "Name:", "Patient Name -" … — the person's own name. This one REQUIRES the trailing
    /// colon/dash: the bare word "Name" is also a lab-table column header ("Test Name  Value  Unit"),
    /// and covering that header would take the whole results table's first column with it.
    private static let nameLabel = regex(
        #"\b(patient\s*name|pt\.?\s*name|patient|name)\b\s*[:\-]"#
    )

    /// Account/record identifiers. Unlike a name these need no punctuation guard — "UHID",
    /// "Accession", "Barcode" and friends do not occur in clinical prose, so the label alone is
    /// signal enough, and requiring a colon missed real formats like "Accession No. AC-99120".
    private static let idLabel = regex(
        #"\b(uhid|mrn|mr\.?\s*no|reg(istration)?\.?\s*no|patient\s*id|pid|op\.?\s*no|ip\.?\s*no|"#
        + #"visit\s*id|lab\s*(no|id)|accession|sample\s*id|barcode|srf\s*id|abha|aadhaar|"#
        + #"insurance|policy\s*no)\b"#
    )

    /// Email addresses and Indian mobile numbers, including the spaced "98765 43210" form.
    private static let contact = regex(
        #"[\w.+-]+@[\w-]+\.[\w.]+|(\+?91[\s-]?)?\b[6-9]\d{4}[\s-]?\d{5}\b"#
    )

    /// The lab/hospital's own name, which lives in the letterhead or the footer.
    private static let providerKeyword = regex(
        #"\b(laborator(y|ies)|labs|diagnostics?|patholog(y|ists?)|hospitals?|clinics?|"#
        + #"polyclinics?|healthcare|health\s*care|medical\s*cent(re|er)|"#
        + #"diagnostic\s*cent(re|er)|scan\s*cent(re|er)|nursing\s*homes?)\b"#
    )

    /// Headings that legitimately contain a provider-ish word ("PATHOLOGY REPORT", "LAB REPORT").
    /// These carry the document's TYPE, which the extraction depends on to classify the report —
    /// covering them would cost more than the letterhead is worth.
    private static let reportHeading = regex(
        #"\b(report|result|profile|panel|test|summary|prescription|investigation|"#
        + #"h(a)?emogram|findings|impression)\b"#
    )

    /// Credential/referral wording that marks a line as being about a clinician.
    private static let doctorContext = regex(
        #"\b(ref(erred)?\.?\s*(by|dr)|consultant|physician|pathologist|radiologist|surgeon|"#
        + #"m\.?b\.?b\.?s|m\.?d\b|d\.?n\.?b|d\.?g\.?o|m\.?s\b|dm\b|reg\.?\s*no)\b"#
    )

    /// A line that simply opens with "Dr Somebody".
    private static let doctorPrefix = regex(#"^\s*dr\.?\s+\p{L}"#)

    /// Decides whether one recognised line should be covered. `topFraction` is the line's vertical
    /// position as 0…1 down the page, used only to separate the letterhead/signature bands from
    /// the body — a bare "Dr. …" in the body of a prescription is very often a MEDICINE brand
    /// ("Dr. Reddy's"), so it is left alone there and only covered in the bands where a clinician's
    /// name actually appears.
    static func shouldRedact(_ rawLine: String, topFraction: CGFloat) -> Bool {
        let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
        if line.isEmpty { return false }

        if matches(nameLabel, line) { return true }
        if matches(idLabel, line) { return true }
        if matches(contact, line) { return true }
        if matches(doctorContext, line) { return true }

        let inLetterheadOrFooter = topFraction < 0.20 || topFraction > 0.80
        if matches(providerKeyword, line) && !matches(reportHeading, line) { return true }
        if matches(doctorPrefix, line) && inLetterheadOrFooter { return true }

        return false
    }

    /// Returns a copy of `source` with identity regions painted out, or nil when the page has
    /// nothing to redact — the caller then uploads the ORIGINAL bytes rather than a re-encoded
    /// copy, avoiding a pointless round of JPEG generation loss on a page the model has to read
    /// small printed digits from.
    static func redactedCopy(of source: UIImage, lines: [RecognizedLine]) -> UIImage? {
        guard let cgImage = source.cgImage else { return nil }
        let size = CGSize(width: cgImage.width, height: cgImage.height)
        guard size.width > 0, size.height > 0 else { return nil }

        // Pad slightly: the recognizer's box hugs the glyphs, leaving ascenders/descenders and
        // anti-aliased edges legible at the boundary.
        let boxes = lines
            .filter { shouldRedact($0.text, topFraction: $0.topFraction) }
            .map { $0.box.insetBy(dx: -4, dy: -4) }
        if boxes.isEmpty { return nil }

        print("ScanDiag: REDACTED \(boxes.count) line(s) of identity data before upload")

        // Draw at pixel scale with the image's own orientation already baked in by drawing the
        // CGImage directly — `UIImage.draw` would re-apply orientation and misplace the boxes.
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: size, format: format)

        return renderer.image { context in
            let cg = context.cgContext
            cg.translateBy(x: 0, y: size.height)
            cg.scaleBy(x: 1, y: -1)
            cg.draw(cgImage, in: CGRect(origin: .zero, size: size))
            cg.scaleBy(x: 1, y: -1)
            cg.translateBy(x: 0, y: -size.height)

            cg.setFillColor(UIColor.black.cgColor)
            cg.fill(boxes)
        }
    }

    // MARK: - Regex helpers

    private static func regex(_ pattern: String) -> NSRegularExpression {
        // These patterns are compile-time constants; a failure here is a programming error that
        // would silently disable a redaction rule, so fail loudly in development instead.
        do {
            return try NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
        } catch {
            preconditionFailure("PiiRedactor pattern failed to compile: \(pattern) — \(error)")
        }
    }

    private static func matches(_ expression: NSRegularExpression, _ line: String) -> Bool {
        let range = NSRange(line.startIndex..<line.endIndex, in: line)
        return expression.firstMatch(in: line, options: [], range: range) != nil
    }
}
