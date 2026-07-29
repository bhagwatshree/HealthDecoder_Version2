import UIKit
@preconcurrency import Vision

/// On-device text recognition via Apple's `Vision` framework — the analog of ML Kit's on-device
/// recognizer in `ScanScreen.kt`. Used purely to produce a hint string passed alongside the page
/// image to Gemini (see `OcrEngine`); Gemini still does the real extraction, same as Android.
/// (Android additionally uses this pass to route Indic-script pages to Sarvam instead of ML Kit
/// — Phase 1 has no Sarvam client yet, so that routing decision is deferred to a later phase.)
enum TextRecognizer {
    static func recognize(_ image: UIImage?) async -> String {
        guard let image, let cgImage = image.cgImage else { return "" }
        return await withCheckedContinuation { continuation in
            let request = VNRecognizeTextRequest { request, _ in
                let text = (request.results as? [VNRecognizedTextObservation])?
                    .compactMap { $0.topCandidates(1).first?.string }
                    .joined(separator: "\n") ?? ""
                continuation.resume(returning: text)
            }
            request.recognitionLevel = .accurate
            let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    try handler.perform([request])
                } catch {
                    // A synchronous throw here means the request's own completion handler
                    // never runs — resume here too, or the continuation would hang forever.
                    continuation.resume(returning: "")
                }
            }
        }
    }
}
