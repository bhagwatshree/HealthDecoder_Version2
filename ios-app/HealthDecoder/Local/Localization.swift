import SwiftUI

/// The 11 languages the app offers — must stay in sync with Android's
/// `AppSettings.SUPPORTED_LANGUAGES` and the backend's `LANGUAGE_CODES`.
enum SupportedLanguages {
    static let all = [
        "English", "Hindi", "Marathi", "Gujarati", "Tamil",
        "Telugu", "Kannada", "Bengali", "Punjabi", "Malayalam", "Odia"
    ]
}

/// App-wide current language, published so switching it from the picker re-renders every
/// screen immediately — the SwiftUI analog of Android's Compose-state `AppLanguageState`.
@MainActor
final class AppLanguageState: ObservableObject {
    static let shared = AppLanguageState()

    @Published private(set) var current: String

    private init() {
        current = AppSettings.preferredLanguage
    }

    func select(_ language: String) {
        AppSettings.preferredLanguage = language
        current = language
        // Pull the latest strings for just this language, so a DB edit shows up without
        // waiting for a reinstall (matches Android's refreshLanguage-on-select).
        Task { await RemoteUiTranslations.refreshLanguage(language) }
    }

    /// Fetches all languages once ever, on the first launch that has network.
    func bootstrap() {
        Task { await RemoteUiTranslations.fetchAllIfNeverFetched() }
    }
}

/// Translates `text` into the app-wide preferred language. Lookup order mirrors Android's
/// `tr()`: the on-device cache of the backend's `ui_translations` table (DB is the source of
/// truth), then the bundled `UiTranslations.json` seed (works offline / before first fetch),
/// then the original English. No network call happens here.
///
/// Usage: `Text(tr("Scan Report"))`. Because `AppLanguageState` is an `ObservableObject` that
/// screens observe, changing language re-renders them with no restart.
@MainActor
func tr(_ text: String) -> String {
    guard !text.isEmpty else { return text }
    let language = AppLanguageState.shared.current
    guard language.caseInsensitiveCompare("English") != .orderedSame else { return text }
    return RemoteUiTranslations.get(language: language, text: text)
        ?? BundledUiTranslations.lookup(language: language, text: text)
        ?? text
}

/// The bundled seed, extracted from Android's `UiTranslations.kt` into
/// `Resources/UiTranslations.json` (see `scripts/extract_translations.py`) so both apps
/// share one source of truth instead of drifting apart.
enum BundledUiTranslations {
    private static let table: [String: [String: String]] = {
        guard
            let url = Bundle.main.url(forResource: "UiTranslations", withExtension: "json"),
            let data = try? Data(contentsOf: url),
            let parsed = try? JSONDecoder().decode([String: [String: String]].self, from: data)
        else { return [:] }
        return parsed
    }()

    static func lookup(language: String, text: String) -> String? {
        table[language]?[text]
    }
}

/// On-device cache of UI-chrome translations fetched from the backend's `ui_translations`
/// table — port of `local/RemoteUiTranslations.kt`.
enum RemoteUiTranslations {
    private static let fetchedOnceKey = "remote_translations_fetched_all_once"
    private static let prefix = "remote_translations_"

    // In-memory mirror so `tr()` (called on every render) never re-parses JSON.
    nonisolated(unsafe) private static var memoryCache: [String: [String: String]] = [:]
    private static let cacheLock = NSLock()

    static func get(language: String, text: String) -> String? {
        loadLanguageMap(language)[text]
    }

    private static func loadLanguageMap(_ language: String) -> [String: String] {
        cacheLock.lock()
        defer { cacheLock.unlock() }
        if let cached = memoryCache[language] { return cached }
        guard
            let data = UserDefaults.standard.data(forKey: prefix + language),
            let parsed = try? JSONDecoder().decode([String: String].self, from: data)
        else {
            memoryCache[language] = [:]
            return [:]
        }
        memoryCache[language] = parsed
        return parsed
    }

    private static func saveLanguageMap(_ language: String, _ map: [String: String]) {
        cacheLock.lock()
        memoryCache[language] = map
        cacheLock.unlock()
        if let data = try? JSONEncoder().encode(map) {
            UserDefaults.standard.set(data, forKey: prefix + language)
        }
    }

    /// Fetches every language once ever; retries on a later launch if it never succeeded
    /// (e.g. the first launch was offline).
    static func fetchAllIfNeverFetched() async {
        guard !UserDefaults.standard.bool(forKey: fetchedOnceKey) else { return }
        guard let all = try? await APIClient.shared.allTranslations() else { return }
        for (language, map) in all { saveLanguageMap(language, map) }
        UserDefaults.standard.set(true, forKey: fetchedOnceKey)
    }

    /// Re-fetches just one language — called when the user selects it.
    static func refreshLanguage(_ language: String) async {
        guard language.caseInsensitiveCompare("English") != .orderedSame else { return }
        guard let map = try? await APIClient.shared.translations(language: language) else { return }
        saveLanguageMap(language, map)
    }
}

/// Maps language names to BCP-47 tags for speech recognition and text-to-speech, and
/// translates AI-generated content via Sarvam — port of `util/LanguageUtil.kt`.
enum LanguageUtil {
    private static let bcp47 = [
        "English": "en-IN", "Hindi": "hi-IN", "Marathi": "mr-IN", "Gujarati": "gu-IN",
        "Tamil": "ta-IN", "Telugu": "te-IN", "Kannada": "kn-IN", "Bengali": "bn-IN",
        "Punjabi": "pa-IN", "Malayalam": "ml-IN", "Odia": "or-IN"
    ]

    static func tag(for language: String) -> String { bcp47[language] ?? "en-IN" }

    static func locale(for language: String) -> Locale { Locale(identifier: tag(for: language)) }

    /// Translates English text into the target language via Sarvam. Returns the input
    /// unchanged when the target is English, the key is missing, or the call fails — the
    /// same graceful degradation the Android version has.
    static func translate(_ text: String, to targetLanguage: String) async -> String {
        guard targetLanguage.caseInsensitiveCompare("English") != .orderedSame, !text.isEmpty else {
            return text
        }
        let apiKey = AppSettings.sarvamKey
        guard !apiKey.isEmpty else { return text }

        // Paragraph-chunked, matching Android — Sarvam has a per-request length limit.
        var translated: [String] = []
        for paragraph in text.components(separatedBy: "\n\n") {
            if paragraph.trimmingCharacters(in: .whitespaces).isEmpty {
                translated.append("")
            } else {
                translated.append(await translateChunk(paragraph, to: tag(for: targetLanguage), apiKey: apiKey))
            }
        }
        return translated.joined(separator: "\n\n")
    }

    private static func translateChunk(_ text: String, to targetCode: String, apiKey: String) async -> String {
        guard let url = URL(string: "https://api.sarvam.ai/translate") else { return text }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 15
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "api-subscription-key")
        request.httpBody = try? JSONSerialization.data(withJSONObject: [
            "input": text,
            "source_language_code": "en-IN",
            "target_language_code": targetCode,
            "model": "sarvam-translate:v1"
        ])

        guard
            let (data, response) = try? await URLSession.shared.data(for: request),
            let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode),
            let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let result = root["translated_text"] as? String
        else { return text }
        return result
    }
}

/// Top-bar language picker — port of `LanguagePickerIcon()` in `ui/Localization.kt`.
struct LanguagePickerIcon: View {
    @ObservedObject private var languageState = AppLanguageState.shared

    var body: some View {
        Menu {
            ForEach(SupportedLanguages.all, id: \.self) { language in
                Button {
                    languageState.select(language)
                } label: {
                    if language == languageState.current {
                        Label(language, systemImage: "checkmark")
                    } else {
                        Text(language)
                    }
                }
            }
        } label: {
            Image(systemName: "globe")
        }
    }
}
