import Foundation

/// Anonymous per-install identity for the AI proxy (see `BackendAiClient`). Phone OTP sign-in
/// is optional/off by default, so most installs never become a logged-in user — this registers
/// a UUID the app generates once with the backend (no SMS, no login) and gets back a
/// long-lived device token, so `/api/ai/generate` can still meter/pool a Gemini key per install.
///
/// Direct port of `local/DeviceIdentity.kt`. Kotlin's version is synchronous/blocking (called
/// off the main thread, matching GeminiClient/BackendAiClient's old style); this is an `actor`
/// instead so registration is safely async/await, and concurrent callers awaiting `ensureToken()`
/// while a registration is already in flight share that one request rather than each firing
/// their own `POST /api/device/register`.
actor DeviceIdentity {
    static let shared = DeviceIdentity()

    private init() {}

    private let session = URLSession(configuration: {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 30
        c.timeoutIntervalForResource = 30
        return c
    }())

    private var inFlight: Task<String?, Never>?

    /// Returns a usable device token, registering with the backend first if none is cached yet.
    /// Returns nil if registration fails (e.g. no connectivity) — callers must surface a clear
    /// "can't reach the server" error rather than falling back to any embedded key.
    func ensureToken() async -> String? {
        if let cached = AppSettings.deviceToken, !cached.isEmpty { return cached }

        if let inFlight { return await inFlight.value }

        let task = Task<String?, Never> { [weak self] in
            await self?.register()
        }
        inFlight = task
        let result = await task.value
        inFlight = nil
        return result
    }

    private func register() async -> String? {
        let deviceId = AppSettings.getOrCreateInstallId()
        guard let url = URL(string: "\(APIClient.aiProxyBaseURL)api/device/register") else { return nil }
        guard let bodyData = try? JSONSerialization.data(withJSONObject: ["deviceId": deviceId]) else { return nil }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("true", forHTTPHeaderField: "ngrok-skip-browser-warning")
        request.httpBody = bodyData

        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else { return nil }
            guard
                let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                let token = (root["token"] as? String)?.trimmingCharacters(in: .whitespaces), !token.isEmpty
            else { return nil }
            AppSettings.deviceToken = token
            return token
        } catch {
            return nil
        }
    }
}
