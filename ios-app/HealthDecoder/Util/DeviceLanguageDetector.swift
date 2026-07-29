import Foundation
import UIKit

/// Picks the app's initial language from the device's installed keyboards, falling back to the
/// system's preferred locales — the iOS counterpart of `util/DeviceLanguageDetector.kt`.
///
/// Android reads the *currently active* keyboard subtype on the theory that the keyboard a
/// person types in is a better signal of what they comfortably read than the system UI
/// language. iOS exposes no "current keyboard" API to an app that isn't first responder, so
/// this checks the installed keyboards (`UITextInputMode.activeInputModes`) first, then falls
/// back to `Locale.preferredLanguages`, and finally to English.
enum DeviceLanguageDetector {
    private static let isoToSupportedName = [
        "en": "English", "hi": "Hindi", "mr": "Marathi", "gu": "Gujarati",
        "ta": "Tamil", "te": "Telugu", "kn": "Kannada", "bn": "Bengali",
        "pa": "Punjabi", "ml": "Malayalam", "or": "Odia"
    ]

    static func detect() -> String {
        // An installed non-English Indic keyboard is the strongest signal available.
        let keyboardLanguages = UITextInputMode.activeInputModes.compactMap(\.primaryLanguage)
        for tag in keyboardLanguages {
            guard let iso = languageCode(from: tag), let name = isoToSupportedName[iso] else { continue }
            if name != "English" { return name }
        }
        // Then the system's own language ordering.
        for tag in Locale.preferredLanguages {
            if let iso = languageCode(from: tag), let name = isoToSupportedName[iso] { return name }
        }
        // Finally, an English keyboard if that's all that's installed.
        for tag in keyboardLanguages {
            if let iso = languageCode(from: tag), let name = isoToSupportedName[iso] { return name }
        }
        return "English"
    }

    private static func languageCode(from tag: String) -> String? {
        // "hi-IN" / "hi_IN" / "hi" all reduce to "hi".
        tag.split(whereSeparator: { $0 == "-" || $0 == "_" }).first.map(String.init)?.lowercased()
    }
}
