import Foundation

/// Type-safe destinations pushed onto the post-login `NavigationStack` — the Phase-1 subset of
/// `NavigationKeys.kt`'s back-stack keys. Other tiles on Home push nothing yet (their screens
/// land in later phases); Login/Register aren't part of this stack at all — `ContentView`
/// switches between the auth flow and this one based on `SessionStore.isLoggedIn`, the SwiftUI
/// equivalent of `Navigation.kt`'s JWT-gated `MainNavigation()`.
enum AppRoute: Hashable {
    case scan
    case records
    case reportDetail(id: String)
    case smartHealthLens
    case doctorBrief(patientName: String)
    case chat(contextHint: String)
    case reminders
    case medicationTracker
    case pendingTests
}
