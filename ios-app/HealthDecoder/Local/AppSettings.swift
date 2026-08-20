import Foundation

/// Settings store mirroring `local/AppSettings.kt`'s Phase-1-relevant keys. Ordinary preferences
/// live in `UserDefaults` (same role as Android's plain `SharedPreferences`); the auth token and
/// API keys live in the Keychain instead (`KeychainStore`) — a small security improvement over
/// Android's plaintext-prefs token storage, not a behavior change.
enum AppSettings {
    private static let defaults = UserDefaults.standard
    private static let encoder = JSONEncoder()
    private static let decoder = JSONDecoder()

    // MARK: - Account / login

    private static let keychainAuthToken = "auth_token"
    private static let keychainUserEmail = "auth_user_email"

    static var authToken: String? {
        get { KeychainStore.get(keychainAuthToken) }
        set {
            if let newValue { KeychainStore.set(newValue, forKey: keychainAuthToken) }
            else { KeychainStore.delete(keychainAuthToken) }
        }
    }

    static var userEmail: String? {
        get { KeychainStore.get(keychainUserEmail) }
        set {
            if let newValue { KeychainStore.set(newValue, forKey: keychainUserEmail) }
            else { KeychainStore.delete(keychainUserEmail) }
        }
    }

    static var isLoggedIn: Bool { (authToken?.isEmpty == false) }

    /// Logs out. There's no local key state to clear anymore — AI calls always go through the
    /// backend proxy (`BackendAiClient`), which falls back to the anonymous device pool once
    /// logged out. Mirrors `AppSettings.kt`'s `logout()`.
    static func logout() {
        authToken = nil
        userEmail = nil
    }

    // MARK: - Anonymous device identity (no OTP/login)

    // Backs the AI proxy (BackendAiClient / DeviceIdentity): phone OTP sign-in is optional/off
    // by default, so most installs authenticate as this anonymous device instead of a real
    // user. installId is generated once and never changes; deviceToken is what DeviceIdentity
    // gets back from POST /api/device/register using that id. Mirrors AppSettings.kt's
    // getOrCreateInstallId/getDeviceToken/setDeviceToken. deviceToken lives in the Keychain
    // (not UserDefaults, unlike Android's plaintext prefs) — same security improvement as
    // authToken above, not a behavior change.
    private static let keyInstallId = "device_install_id"
    private static let keychainDeviceToken = "device_auth_token"

    static func getOrCreateInstallId() -> String {
        if let stored = defaults.string(forKey: keyInstallId), !stored.isEmpty { return stored }
        let fresh = UUID().uuidString
        defaults.set(fresh, forKey: keyInstallId)
        return fresh
    }

    static var deviceToken: String? {
        get { KeychainStore.get(keychainDeviceToken) }
        set {
            if let newValue { KeychainStore.set(newValue, forKey: keychainDeviceToken) }
            else { KeychainStore.delete(keychainDeviceToken) }
        }
    }

    // MARK: - Server URL (mirrors IPConfigScreen's override)

    private static let keyServerURL = "server_url_override"
    static let defaultServerURL = "https://k6tdi2uzoh.execute-api.us-east-1.amazonaws.com"

    static var serverURL: String {
        get {
            let stored = defaults.string(forKey: keyServerURL) ?? ""
            return stored.isEmpty ? defaultServerURL : stored
        }
        set { defaults.set(newValue.trimmingCharacters(in: .whitespaces), forKey: keyServerURL) }
    }

    // MARK: - Disclaimer / theme / language

    static var isDisclaimerAccepted: Bool {
        get { defaults.bool(forKey: "medical_disclaimer_accepted") }
        set { defaults.set(newValue, forKey: "medical_disclaimer_accepted") }
    }

    static let themeSystem = "system"
    static let themeLight = "light"
    static let themeDark = "dark"

    static var themeMode: String {
        get { defaults.string(forKey: "theme_mode") ?? themeSystem }
        set { defaults.set(newValue, forKey: "theme_mode") }
    }

    /// On the first read ever (key never set), seed from the device's keyboard languages rather
    /// than hardcoding English, then persist so this only runs once — same policy as Android's
    /// `DeviceLanguageDetector`, which reads the active keyboard subtype. iOS has no API for
    /// "current keyboard", but `UITextInputMode.activeInputModes` lists the installed keyboards,
    /// which is the closest equivalent signal.
    static var preferredLanguage: String {
        get {
            if let stored = defaults.string(forKey: "preferred_language") { return stored }
            let detected = DeviceLanguageDetector.detect()
            defaults.set(detected, forKey: "preferred_language")
            return detected
        }
        set { defaults.set(newValue, forKey: "preferred_language") }
    }

    // MARK: - Family members / active patient

    static var familyProfiles: [FamilyProfile] {
        get {
            guard let data = defaults.data(forKey: "family_profiles") else { return [] }
            return (try? decoder.decode([FamilyProfile].self, from: data)) ?? []
        }
        set {
            if let data = try? encoder.encode(newValue) { defaults.set(data, forKey: "family_profiles") }
        }
    }

    static var activePatient: String? {
        get { defaults.string(forKey: "active_patient")?.trimmingCharacters(in: .whitespaces).nonEmpty }
        set { defaults.set(newValue?.trimmingCharacters(in: .whitespaces) ?? "", forKey: "active_patient") }
    }

    // MARK: - Scan pipeline tuning (internal, no UI — matches AppSettings.kt's defaults)

    static var scanChunkPages: Int {
        get { defaults.object(forKey: "scan_chunk_pages") as? Int ?? 6 }
        set { defaults.set(min(max(newValue, 1), 30), forKey: "scan_chunk_pages") }
    }

    static var scanMaxPages: Int {
        get { defaults.object(forKey: "scan_max_pages") as? Int ?? 60 }
        set { defaults.set(min(max(newValue, 5), 200), forKey: "scan_max_pages") }
    }

    static var aiMinRequestIntervalMs: Int {
        get { defaults.object(forKey: "ai_min_request_interval_ms") as? Int ?? 3200 }
        set { defaults.set(min(max(newValue, 0), 30_000), forKey: "ai_min_request_interval_ms") }
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
