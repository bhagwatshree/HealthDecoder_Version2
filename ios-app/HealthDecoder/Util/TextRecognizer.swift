import UIKit
@preconcurrency import Vision

/// One line the on-device recognizer found, with where it sits on the page.
///
/// `box` is in image pixel coordinates with the origin at the TOP-left, matching `UIImage` and
/// `CGContext` drawing — Vision itself reports a normalized box with the origin at the
/// BOTTOM-left, which is converted once here so no caller has to remember the flip.
struct RecognizedLine {
    let text: String
    /// The line's vertical position as 0…1 down the page, used to tell the letterhead and
    /// signature bands apart from the body. Mirrors the `topFraction` argument ML Kit's boxes
    /// feed to Android's `PiiRedactor`.
    let topFraction: CGFloat
    let box: CGRect
}

/// On-device text recognition via Apple's `Vision` framework — the analog of ML Kit's on-device
/// recognizer in `ScanScreen.kt`. Used both to produce a hint string passed alongside the page
/// image to Gemini (see `OcrEngine`), and to locate identity regions for `PiiRedactor` to paint
/// out before the page is uploaded.
/// (Android additionally uses this pass to route Indic-script pages to Sarvam instead of ML Kit
/// — iOS has no Sarvam client yet, so that routing decision is still deferred.)
enum TextRecognizer {

    /// Recognized lines with their boxes. Returns an empty array when the image can't be read.
    static func recognizeLines(_ image: UIImage?) async -> [RecognizedLine] {
        guard let image, let cgImage = image.cgImage else { return [] }
        let width = CGFloat(cgImage.width)
        let height = CGFloat(cgImage.height)
        guard width > 0, height > 0 else { return [] }

        return await withCheckedContinuation { continuation in
            // Vision can call a request's completion handler more than once in some failure
            // paths, and `perform` can also throw after it has already run. A checked
            // continuation traps on a second resume, so gate it: whoever gets here first wins.
            let resumed = ResumeGuard()

            let request = VNRecognizeTextRequest { request, _ in
                let observations = (request.results as? [VNRecognizedTextObservation]) ?? []
                let lines: [RecognizedLine] = observations.compactMap { observation in
                    guard let text = observation.topCandidates(1).first?.string else { return nil }
                    let b = observation.boundingBox   // normalized, origin bottom-left
                    let box = CGRect(
                        x: b.minX * width,
                        y: (1 - b.maxY) * height,     // flip to top-left origin
                        width: b.width * width,
                        height: b.height * height
                    )
                    return RecognizedLine(text: text, topFraction: 1 - b.maxY, box: box)
                }
                if resumed.claim() { continuation.resume(returning: lines) }
            }
            request.recognitionLevel = .accurate

            let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    try handler.perform([request])
                } catch {
                    // A synchronous throw here means the request's own completion handler
                    // never runs — resume here too, or the continuation would hang forever.
                    if resumed.claim() { continuation.resume(returning: []) }
                }
            }
        }
    }

    /// The recognized text as one newline-joined string — the hint passed to Gemini.
    static func recognize(_ image: UIImage?) async -> String {
        await recognizeLines(image).map(\.text).joined(separator: "\n")
    }
}

/// Lets exactly one caller resume a continuation, whichever arrives first.
private final class ResumeGuard: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}
