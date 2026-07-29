import Foundation
import Security

/// Minimal Keychain wrapper for storing small secrets (DB passphrase, auth token, API keys) —
/// the iOS analog of Android's Keystore-backed `EncryptedSharedPreferences`
/// (`security-crypto`'s `EncryptedSharedPreferences`, see `SecureKeyManager.kt`).
///
/// Every item uses `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`: readable only after the
/// device has been unlocked at least once since boot, and never included in an iCloud/iTunes
/// backup or restorable onto a different device — matching Android's per-device, Keystore-tied
/// passphrase semantics (a backup zip is only restorable on the same physical device).
enum KeychainStore {
    private static let service = "com.example.medicalscanner"

    static func set(_ value: String, forKey key: String) {
        let data = Data(value.utf8)
        var query = baseQuery(forKey: key)
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly

        let status = SecItemAdd(query as CFDictionary, nil)
        if status == errSecDuplicateItem {
            let update: [String: Any] = [kSecValueData as String: data]
            SecItemUpdate(baseQuery(forKey: key) as CFDictionary, update as CFDictionary)
        }
    }

    static func get(_ key: String) -> String? {
        var query = baseQuery(forKey: key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func delete(_ key: String) {
        SecItemDelete(baseQuery(forKey: key) as CFDictionary)
    }

    private static func baseQuery(forKey key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
    }
}
