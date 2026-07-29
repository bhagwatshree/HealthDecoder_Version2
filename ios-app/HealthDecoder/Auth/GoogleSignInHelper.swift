import Foundation

/// What the auth UI can offer right now. Phase 1 links no Firebase SDK at all, so phone-OTP and
/// Google Sign-In are unavailable — the login/register screens hide those options entirely,
/// mirroring how the Android app hides them when `google-services.json` is a placeholder.
///
/// PHASE 4 INTEGRATION POINT: add the Firebase iOS SDK (`FirebaseAuth`) + Google Sign-In iOS SDK
/// as SPM packages, drop a real `GoogleService-Info.plist` into this target, implement phone
/// verification here (mirroring `auth/PhoneAuthHelper.kt`) and native Google account picking
/// (mirroring `auth/GoogleSignInHelper.kt`), then flip these flags on.
enum AuthCapabilities {
    static let phoneAuthAvailable = false
    static let googleSignInAvailable = false
}
