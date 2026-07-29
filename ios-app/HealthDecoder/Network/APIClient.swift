import Foundation

enum APIError: Error, LocalizedError {
    case invalidURL
    case http(status: Int, message: String?)
    case decoding(Error)
    case notAuthenticated

    var errorDescription: String? {
        switch self {
        case .invalidURL: return "Invalid server URL."
        case .http(let status, let message): return message ?? "Server error (\(status))."
        case .decoding: return "Could not read the server's response."
        case .notAuthenticated: return "Please log in again."
        }
    }
}

/// `URLSession`-based client for the same backend Android talks to (`network/NetworkModule.kt`).
/// The backend is used only for account auth and issuing an AI-provider key + quota — every
/// scan/chat/analysis call goes straight from this phone to Google/Sarvam (`GeminiClient`),
/// never through this API.
final class APIClient {
    static let shared = APIClient()
    private init() {}

    private let session = URLSession(configuration: {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 60
        return config
    }())

    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        return e
    }()

    private let decoder = JSONDecoder()

    private var baseURL: URL {
        var raw = AppSettings.serverURL.trimmingCharacters(in: .whitespaces)
        if !raw.hasPrefix("http://") && !raw.hasPrefix("https://") {
            raw = "https://\(raw)"
        }
        return URL(string: raw) ?? URL(string: AppSettings.defaultServerURL)!
    }

    // MARK: - Auth

    func signup(_ request: SignupRequest) async throws -> AuthResponse {
        try await post("api/auth/signup", body: request, authenticated: false)
    }

    func login(_ request: AuthRequest) async throws -> AuthResponse {
        try await post("api/auth/login", body: request, authenticated: false)
    }

    func loginPhone(_ request: PhoneLoginRequest) async throws -> AuthResponse {
        try await post("api/auth/login-phone", body: request, authenticated: false)
    }

    func googleSignIn(_ request: GoogleSignInRequest) async throws -> AuthResponse {
        try await post("api/auth/google-signin", body: request, authenticated: false)
    }

    func me() async throws -> UserAccount {
        try await get("api/auth/me")
    }

    func assignedKeys() async throws -> KeyAssignment {
        try await get("api/auth/keys")
    }

    func usage() async throws -> KeyAssignment {
        try await get("api/auth/usage")
    }

    func changePassword(_ request: ChangePasswordRequest) async throws -> SimpleResponse {
        try await post("api/auth/change-password", body: request)
    }

    func resetPasswordOtp(_ request: ResetPasswordRequest) async throws -> SimpleResponse {
        try await post("api/auth/reset-password-otp", body: request, authenticated: false)
    }

    // MARK: - UI-chrome translations (the ui_translations table is the source of truth)

    /// Every language at once — `{ "Hindi": { "Save": "…" }, … }`.
    func allTranslations() async throws -> [String: [String: String]] {
        try await send(path: "api/translations", method: "GET",
                       body: Optional<String>.none, authenticated: false)
    }

    /// One language — `{ "Save": "…" }`.
    func translations(language: String) async throws -> [String: String] {
        let encoded = language.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? language
        return try await send(path: "api/translations?language=\(encoded)", method: "GET",
                              body: Optional<String>.none, authenticated: false)
    }

    // MARK: - Generic request helpers

    private func get<Response: Decodable>(_ path: String) async throws -> Response {
        try await send(path: path, method: "GET", body: Optional<String>.none, authenticated: true)
    }

    private func post<Body: Encodable, Response: Decodable>(
        _ path: String, body: Body, authenticated: Bool = true
    ) async throws -> Response {
        try await send(path: path, method: "POST", body: body, authenticated: authenticated)
    }

    private func send<Body: Encodable, Response: Decodable>(
        path: String, method: String, body: Body?, authenticated: Bool
    ) async throws -> Response {
        // Resolve against the base rather than appendingPathComponent, which would
        // percent-escape the "?" in paths that carry a query string.
        guard let url = URL(string: path, relativeTo: baseURL.appendingPathComponent("")) else {
            throw APIError.invalidURL
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("true", forHTTPHeaderField: "ngrok-skip-browser-warning")

        if authenticated {
            guard let token = AppSettings.authToken, !token.isEmpty else {
                throw APIError.notAuthenticated
            }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        if let body {
            request.httpBody = try encoder.encode(body)
        }

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw APIError.http(status: -1, message: nil)
        }
        guard (200...299).contains(http.statusCode) else {
            let message = (try? decoder.decode([String: String].self, from: data))?["error"]
            throw APIError.http(status: http.statusCode, message: message)
        }
        do {
            return try decoder.decode(Response.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }
}
