import Foundation

/// Non-secret build-time config embedded in the app at build time from `Secrets.xcconfig`
/// (gitignored, per-machine), via Info.plist substitution — the iOS analog of Android's
/// `BuildKeys.kt`/`BuildConfig` pair.
///
/// Gemini/Sarvam API keys used to live here too; they've been removed — the app no longer
/// embeds any AI provider key, since all AI calls are proxied through the backend (see
/// `AI/BackendAiClient.swift` — `POST /api/ai/generate`, `/api/ai/tts`, `/api/ai/translate`).
enum BuildKeys {
    // Google OAuth "Web application" client ID — powers native Sign in with Google. Not a
    // secret: it identifies the app to Google's consent screen, it doesn't authorize anything
    // by itself.
    static let googleWebClientID: String = infoPlistString("GOOGLE_WEB_CLIENT_ID")

    private static func infoPlistString(_ key: String) -> String {
        Bundle.main.object(forInfoDictionaryKey: key) as? String ?? ""
    }
}
