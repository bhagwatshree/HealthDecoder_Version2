import Foundation
import UIKit

/// Picks the app's initial language — the iOS counterpart of `util/DeviceLanguageDetector.kt`.
///
/// Android reads the *currently active* keyboard subtype, on the theory that the keyboard a
/// person is typing in beats the system UI language as a signal of what they comfortably read.
/// iOS deliberately does **not** expose the active keyboard to an app that isn't first
/// responder — `UITextInputMode.activeInputModes` lists every *installed* keyboard, which is a
/// far weaker signal: plenty of people keep an Indic keyboard installed while reading English.
///
/// So the order here is inverted relative to Android on purpose. `Locale.preferredLanguages` is
/// the user's own explicit ranking in iOS Settings, so it wins; installed keyboards are only
/// consulted when none of those languages is supported. Preferring keyboards would mean a
/// device whose #1 language is English gets a Hindi UI just because a Hindi keyboard exists —
/// which is what this originally did, and it was wrong.
enum DeviceLanguageDetector {
    private static let isoToSupportedName = [
        "en": "English", "hi": "Hindi", "mr": "Marathi", "gu": "Gujarati",
        "ta": "Tamil", "te": "Telugu", "kn": "Kannada", "bn": "Bengali",
        "pa": "Punjabi", "ml": "Malayalam", "or": "Odia"
    ]

    static func detect() -> String {
        // The user's explicit language ranking from iOS Settings, most-preferred first.
        for tag in Locale.preferredLanguages {
            if let iso = languageCode(from: tag), let name = isoToSupportedName[iso] { return name }
        }
        // No system language is one we support — fall back to an installed keyboard, which at
        // least indicates a language this person reads.
        for tag in UITextInputMode.activeInputModes.compactMap(\.primaryLanguage) {
            if let iso = languageCode(from: tag), let name = isoToSupportedName[iso] { return name }
        }
        return "English"
    }

    private static func languageCode(from tag: String) -> String? {
        // "hi-IN" / "hi_IN" / "hi" all reduce to "hi".
        tag.split(whereSeparator: { $0 == "-" || $0 == "_" }).first.map(String.init)?.lowercased()
    }
}
