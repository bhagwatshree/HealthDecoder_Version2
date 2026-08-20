import Foundation

/// Small text-formatting helper shared by callers that parse the backend AI proxy's JSON
/// responses.
///
/// The actual Gemini calls used to happen directly from the phone here (with an embedded API
/// key); that path is dead code and has been removed — all AI calls now go through the backend
/// proxy (see `BackendAiClient` — `POST /api/ai/generate`), so no key ships in the app bundle.
///
/// Direct port of `ai/GeminiClient.kt`, which was cut down to exactly this the same way when
/// Android moved behind the proxy (commits `acc74ae` / `cab3aa6`). Note there is no on-device
/// "bring your own key" path on either platform to preserve here: a BYOK key a user enters in
/// Settings is submitted to and resolved by the backend per call, never used for a direct
/// device-to-Gemini request — so this file is not, and never was after the migration, a
/// BYOK-specific client.
enum GeminiClient {
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
