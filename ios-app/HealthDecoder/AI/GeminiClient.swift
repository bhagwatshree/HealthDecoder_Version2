import Foundation

enum GeminiError: Error, LocalizedError {
    case missingKey
    case http(status: Int, message: String)

    var errorDescription: String? {
        switch self {
        case .missingKey: return "Gemini API key is not set. Add it in Settings."
        case .http(let status, let message): return "Gemini request failed (\(status)): \(message)"
        }
    }
}

/// Direct port of `ai/GeminiClient.kt` — calls the Gemini REST API straight from the phone (no
/// backend involved): same model id, same endpoint, same request pacing, same 429/503
/// retry-with-backoff (4 attempts).
actor GeminiClient {
    static let shared = GeminiClient()

    private let model = "gemini-3.6-flash"
    private let session = URLSession(configuration: {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 120
        c.timeoutIntervalForResource = 120
        return c
    }())

    private var nextAllowedAt: Date = .distantPast

    private init() {}

    func generateText(prompt: String) async throws -> String {
        try await generate(prompt: prompt, images: [])
    }

    func generateFromImages(prompt: String, images: [(data: Data, mimeType: String)]) async throws -> String {
        try await generate(prompt: prompt, images: images)
    }

    // Global pacing so bulk scans don't burst past the free tier's requests-per-minute limit
    // (which turns every call into a 429). Interval is configurable via AppSettings.
    private func pace() async {
        let intervalMs = AppSettings.aiMinRequestIntervalMs
        guard intervalMs > 0 else { return }
        let interval = TimeInterval(intervalMs) / 1000
        let now = Date()
        let start = max(now, nextAllowedAt)
        let wait = start.timeIntervalSince(now)
        nextAllowedAt = start.addingTimeInterval(interval)
        if wait > 0 {
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
        }
    }

    private func generate(prompt: String, images: [(data: Data, mimeType: String)]) async throws -> String {
        let apiKey = AppSettings.geminiKey
        guard !apiKey.isEmpty else { throw GeminiError.missingKey }
        await pace()

        var parts: [[String: Any]] = images.map {
            ["inline_data": ["mime_type": $0.mimeType, "data": $0.data.base64EncodedString()]]
        }
        parts.append(["text": prompt])
        let body: [String: Any] = ["contents": [["parts": parts]]]
        let bodyData = try JSONSerialization.data(withJSONObject: body)

        let url = URL(string: "https://generativelanguage.googleapis.com/v1beta/models/\(model):generateContent")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "x-goog-api-key")
        request.httpBody = bodyData

        let maxAttempts = 4
        for attempt in 0..<maxAttempts {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw GeminiError.http(status: -1, message: "No response from server.")
            }
            if (200...299).contains(http.statusCode) {
                return extractText(from: data)
            }
            if http.statusCode == 429 || http.statusCode == 503 {
                if attempt < maxAttempts - 1 {
                    let retryAfter = http.value(forHTTPHeaderField: "Retry-After").flatMap(Double.init)
                    let waitSeconds = retryAfter ?? Double(attempt + 1) * 3.5
                    try? await Task.sleep(nanoseconds: UInt64(waitSeconds * 1_000_000_000))
                    continue
                }
                throw GeminiError.http(status: http.statusCode, message: "Gemini is busy (free-tier limit). Please wait a minute and try again.")
            }
            let message = String(data: data.prefix(300), encoding: .utf8) ?? ""
            throw GeminiError.http(status: http.statusCode, message: message)
        }
        throw GeminiError.http(status: -1, message: "Gemini is temporarily unavailable. Please try again.")
    }

    private func extractText(from data: Data) -> String {
        guard
            let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let candidates = root["candidates"] as? [[String: Any]],
            let first = candidates.first,
            let content = first["content"] as? [String: Any],
            let parts = content["parts"] as? [[String: Any]]
        else { return "" }
        let text = parts.compactMap { $0["text"] as? String }.joined()
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Strips ```json fences some models add around JSON output.
    static func stripJsonFences(_ text: String) -> String {
        var t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.hasPrefix("```") else { return t }
        t = t.hasPrefix("```json") ? String(t.dropFirst("```json".count)) : String(t.dropFirst("```".count))
        t = t.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.hasSuffix("```") { t = String(t.dropLast("```".count)) }
        return t.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
