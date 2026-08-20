import SwiftUI

/// App-wide login state, read from/written to `AppSettings`' Keychain-backed auth token — the
/// SwiftUI equivalent of Android checking `AppSettings.isLoggedIn(context)` once at startup in
/// `Navigation.kt`'s `MainNavigation()`.
@MainActor
final class SessionStore: ObservableObject {
    @Published var isLoggedIn: Bool
    @Published var currentUser: UserAccount?

    init() {
        isLoggedIn = AppSettings.isLoggedIn
    }

    func login(response: AuthResponse) {
        AppSettings.authToken = response.token
        AppSettings.userEmail = response.user.email
        currentUser = response.user
        isLoggedIn = true
    }

    func logout() {
        AppSettings.logout()
        currentUser = nil
        isLoggedIn = false
    }
}

@main
struct HealthDecoderApp: App {
    @StateObject private var session = SessionStore()
    @StateObject private var language = AppLanguageState.shared

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(session)
                .environmentObject(language)
                // Re-render the whole tree when the language changes, so tr() re-evaluates
                // everywhere at once — the analog of Compose reading AppLanguageState.current.
                .id(language.current)
                .task {
                    // Pull the DB-backed UI strings once, on the first launch with network.
                    language.bootstrap()
                    // Re-arm reminders on every launch: iOS keeps scheduled notifications across
                    // reboots by itself, but this also picks up schedules saved while permission
                    // was still denied (the analog of Android's BootReceiver re-arm).
                    NotificationManager.rescheduleAll()
                    // Personalized health tips live in the backend's health_tips table so a new
                    // tip reaches every install without an app release. Fetched once ever, and
                    // retried on a later launch if this one has no network. Never blocks the UI:
                    // PersonalizedTips has a complete bundled fallback.
                    await RemoteHealthTips.fetchIfNeverFetched()
                }
        }
    }
}
