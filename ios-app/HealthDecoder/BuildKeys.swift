import Foundation

/// API keys baked into the app at build time from `Secrets.xcconfig` (gitignored, per-machine),
/// via Info.plist substitution — the iOS analog of Android's `BuildKeys.kt`/`BuildConfig` pair.
///
/// SECURITY: keys compiled into an app bundle CAN be extracted by anyone who has the .ipa.
/// This is acceptable for private testing only. Before any public/App Store release, move
/// these to a server-side proxy or rotate/revoke them (same caveat as the Android build).
enum BuildKeys {
    static let geminiApiKey: String = infoPlistString("GEMINI_API_KEY")
    static let sarvamApiKey: String = infoPlistString("SARVAM_API_KEY")
    static let googleWebClientID: String = infoPlistString("GOOGLE_WEB_CLIENT_ID")

    private static func infoPlistString(_ key: String) -> String {
        Bundle.main.object(forInfoDictionaryKey: key) as? String ?? ""
    }
}
