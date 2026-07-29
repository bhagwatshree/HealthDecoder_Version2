import Foundation

/// Generates and persists the random passphrase that encrypts the local SQLCipher database —
/// the iOS analog of `SecureKeyManager.kt`. Unlike Android (which can hit a Keystore that fails
/// on some custom ROMs and needs a plaintext fallback + `usedInsecureFallback` warning flag),
/// Keychain access on iOS essentially never fails this way, so there's no fallback path here.
enum SecureKeyManager {
    private static let keychainKey = "db_passphrase"

    /// Returns the existing passphrase, or generates, persists, and returns a new random one.
    static func databasePassphrase() -> String {
        if let existing = KeychainStore.get(keychainKey), !existing.isEmpty {
            return existing
        }
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed generating DB passphrase")
        let passphrase = Data(bytes).base64EncodedString()
        KeychainStore.set(passphrase, forKey: keychainKey)
        return passphrase
    }
}
