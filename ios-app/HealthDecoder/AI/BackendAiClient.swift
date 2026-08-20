import Foundation

enum BackendAiError: Error, LocalizedError {
    case message(String)

    var errorDescription: String? {
        switch self {
        case .message(let text): return text
        }
    }
}

/// Calls Gemini through our own backend (`POST /api/ai/generate`) instead of Google directly —
/// the API key never touches the device; the backend resolves a pooled or BYOK key per call.
/// Authenticates with the logged-in user's session if any, else the anonymous device token
/// (see `DeviceIdentity`) — no OTP/login required either way.
///
/// Same call shape as `GeminiClient`'s old direct-call methods (the path this replaces —
/// `GeminiClient` now only holds the `stripJsonFences` helper), so callers needed no change
/// beyond swapping which type they call. On any failure — including the backend being
/// unreachable — this throws rather than falling back to any key stored on the device.
///
/// Direct port of `ai/BackendAiClient.kt`.
actor BackendAiClient {
    static let shared = BackendAiClient()

    private init() {}

    private let session = URLSession(configuration: {
        let c = URLSessionConfiguration.default
        // Matches Android's connectTimeout(60s)/readTimeout(150s) — longer than the Lambda's
        // own 120s timeout (see template.yaml), so a slow-but-legitimate Gemini response always
        // finishes server-side before the client gives up.
        c.timeoutIntervalForRequest = 150
        c.timeoutIntervalForResource = 150
        return c
    }())

    // NOTE on request pacing: GeminiClient (the direct-to-Google path this replaces) paced
    // requests client-side via AppSettings.aiMinRequestIntervalMs to keep bulk scans under
    // Gemini's free-tier requests-per-minute limit when every phone called Google individually.
    // That no longer makes sense here: every install now funnels through one shared backend,
    // which is what actually talks to Gemini and is the only place that can see (and smooth)
    // the aggregate request rate. Client-side pacing per device wouldn't protect anything the
    // backend doesn't already guard via its own pooled-key/BYOK/daily-quota logic (the 429
    // response below), so it's deliberately not reproduced here — same call, no artificial
    // delay before it goes out.

    func generateText(prompt: String) async throws -> String {
        try await generate(prompt: prompt, images: [])
    }

    func generateFromImages(prompt: String, images: [(data: Data, mimeType: String)]) async throws -> String {
        try await generate(prompt: prompt, images: images)
    }

    /// Text-to-speech via `POST /api/ai/tts` — returns base64-encoded audio clips (WAV), or an
    /// empty array on ANY failure (missing auth, network error, backend miss). TTS is a nice-to-
    /// have, never worth crashing or blocking the UI over, so this deliberately never throws.
    func tts(text: String, language: String, engine: String) async -> [String] {
        guard let token = await authToken() else { return [] }
        guard let url = URL(string: "\(APIClient.aiProxyBaseURL)api/ai/tts") else { return [] }
        guard let bodyData = try? JSONSerialization.data(withJSONObject: [
            "text": text, "language": language, "engine": engine
        ]) else { return [] }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("true", forHTTPHeaderField: "ngrok-skip-browser-warning")
        request.httpBody = bodyData

        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else { return [] }
            guard
                let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                let audios = root["audios"] as? [String]
            else { return [] }
            return audios
        } catch {
            return []
        }
    }

    /// Translation via `POST /api/ai/translate` — returns the translated text, or the ORIGINAL
    /// `text` on ANY failure, so a translation miss never blocks the caller.
    func translate(text: String, to targetLanguage: String) async -> String {
        guard let token = await authToken() else { return text }
        guard let url = URL(string: "\(APIClient.aiProxyBaseURL)api/ai/translate") else { return text }
        guard let bodyData = try? JSONSerialization.data(withJSONObject: [
            "text": text, "targetLanguage": targetLanguage
        ]) else { return text }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("true", forHTTPHeaderField: "ngrok-skip-browser-warning")
        request.httpBody = bodyData

        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else { return text }
            guard
                let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                let translated = root["translated_text"] as? String
            else { return text }
            return translated
        } catch {
            return text
        }
    }

    private func authToken() async -> String? {
        if let auth = AppSettings.authToken, !auth.isEmpty { return auth }
        if let device = AppSettings.deviceToken, !device.isEmpty { return device }
        return await DeviceIdentity.shared.ensureToken()
    }

    private func generate(prompt: String, images: [(data: Data, mimeType: String)]) async throws -> String {
        guard let token = await authToken() else {
            throw BackendAiError.message("Can't reach the analysis server. Check your connection and try again.")
        }
        guard let url = URL(string: "\(APIClient.aiProxyBaseURL)api/ai/generate") else {
            throw BackendAiError.message("Can't reach the analysis server. Check your connection and try again.")
        }

        let imagesJson = images.map { ["data": $0.data.base64EncodedString(), "mimeType": $0.mimeType] }
        guard let bodyData = try? JSONSerialization.data(withJSONObject: [
            "prompt": prompt, "images": imagesJson, "operation": "scan"
        ]) else {
            throw BackendAiError.message("Could not build the analysis request.")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("true", forHTTPHeaderField: "ngrok-skip-browser-warning")
        request.httpBody = bodyData

        // Retry transient backend/upstream failures (Lambda cold start, Gemini hiccup) with
        // backoff. NOT retried: 429 (our own daily quota — retrying won't help) and other 4xx
        // (a client-side problem with this exact request).
        let maxAttempts = 3
        for attempt in 0..<maxAttempts {
            do {
                let (data, response) = try await session.data(for: request)
                guard let http = response as? HTTPURLResponse else {
                    throw BackendAiError.message("No response from server.")
                }
                if (200...299).contains(http.statusCode) {
                    return try extractText(from: data)
                }

                let serverMessage = errorMessage(from: data)
                switch http.statusCode {
                case 429:
                    throw BackendAiError.message(serverMessage ?? "Daily free analysis limit reached.")
                case 502, 503, 504:
                    if attempt < maxAttempts - 1 {
                        try? await Task.sleep(nanoseconds: UInt64(Double(attempt + 1) * 2_000_000_000))
                        continue
                    }
                    throw BackendAiError.message(
                        serverMessage ?? "The analysis server is temporarily unavailable. Please try again shortly."
                    )
                default:
                    throw BackendAiError.message(serverMessage ?? "Request failed (\(http.statusCode)).")
                }
            } catch let error as BackendAiError {
                throw error
            } catch {
                // A network-layer failure (no connectivity, timeout, ...) rather than an HTTP
                // error response — retry with the same backoff as a 502/503/504, then give up.
                if attempt < maxAttempts - 1 {
                    try? await Task.sleep(nanoseconds: UInt64(Double(attempt + 1) * 2_000_000_000))
                    continue
                }
            }
        }
        throw BackendAiError.message("Can't reach the analysis server. Check your connection and try again.")
    }

    private func errorMessage(from data: Data) -> String? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return root["error"] as? String
    }

    private func extractText(from data: Data) throws -> String {
        guard
            let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let text = root["text"] as? String
        else {
            throw BackendAiError.message("Could not parse the analysis response.")
        }
        return text
    }
}
