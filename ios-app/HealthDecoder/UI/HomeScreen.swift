import SwiftUI

/// Home — port of `ui/HomeScreen.kt`, in Android's order: greeting with the family switcher,
/// the rotating health tip, an optional sign-in banner, the six action tiles, then the Smart
/// Health Lens banner.
struct HomeScreen: View {
    private struct HomeAction: Identifiable {
        let id = UUID()
        let label: String
        let emoji: String
        /// The pale card colour in light mode; becomes the FOREGROUND colour in dark mode.
        let container: Color
        /// The saturated label colour in light mode; becomes the card TINT in dark mode.
        let content: Color
        let route: AppRoute?
    }

    @EnvironmentObject private var session: SessionStore
    @Environment(\.colorScheme) private var colorScheme

    @State private var profiles: [FamilyProfile] = []
    @State private var selectedProfile: FamilyProfile?
    @State private var showFamilyManager = false
    @State private var showLogin = false
    @State private var refreshTick = 0

    /// The same six Android builds when `isBackendReady` is false — the three Discovery tiles
    /// ("Find Doctors/Labs/Hospitals") are gated off there, so they're absent here too.
    /// Doctor Appointments routes to Reminders because that screen owns appointments on both
    /// platforms (Android pushes `Reminders("appointments")`).
    private var actions: [HomeAction] {
        [
            HomeAction(label: "Scan Report", emoji: "📸",
                       container: Color(hex: 0xE8F5E9), content: Color(hex: 0x2E7D32), route: .scan),
            HomeAction(label: "Records", emoji: "📜",
                       container: Color(hex: 0xECEFF1), content: Color(hex: 0x455A64), route: .records),
            HomeAction(label: "Medication Reminders", emoji: "⏰",
                       container: Color(hex: 0xFFF3E0), content: Color(hex: 0xE65100), route: .reminders),
            HomeAction(label: "Doctor Appointments", emoji: "📅",
                       container: Color(hex: 0xE8EAF6), content: Color(hex: 0x283593), route: .reminders),
            HomeAction(label: "Medications", emoji: "💊",
                       container: Color(hex: 0xF3E5F5), content: Color(hex: 0x6A1B9A), route: .medicationTracker),
            HomeAction(label: "Pending Tests", emoji: "🚨",
                       container: Color(hex: 0xFFF9C4), content: Color(hex: 0xC62828), route: .pendingTests),
            // Android reaches Trends from its bottom nav bar. iOS has no tab shell yet, so it
            // sits here instead — the screen was previously unreachable by any route at all.
            HomeAction(label: "Health Trends", emoji: "📈",
                       container: Color(hex: 0xE3F2FD), content: Color(hex: 0x1565C0), route: .trends),
        ]
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                greetingRow

                HealthTipCard()

                // Shown alongside the tip card, not instead of it, every visit until the user
                // actually signs in — sign-in is optional and has no other entry point.
                if !session.isLoggedIn {
                    Button { showLogin = true } label: {
                        SignInBanner { showLogin = true }
                    }
                    .buttonStyle(.plain)
                }

                LazyVGrid(
                    columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)],
                    spacing: 12
                ) {
                    ForEach(actions) { action in
                        NavigationLink(value: action.route ?? .records) {
                            tile(action)
                        }
                        .buttonStyle(PressableTileStyle())
                    }
                }

                NavigationLink(value: AppRoute.smartHealthLens) {
                    SmartHealthLensBanner()
                }
                .buttonStyle(PressableTileStyle())

                Spacer(minLength: 12)
            }
            .padding(16)
        }
        .background(watermark)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                HStack(spacing: 8) {
                    Image("Logo")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 28, height: 28)
                    Text(tr("Health Decoder")).font(.headline.bold())
                }
            }
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                LanguagePickerIcon()
                NavigationLink(destination: SettingsScreen()) {
                    Image(systemName: "person.circle")
                }
            }
        }
        .onAppear(perform: loadFamily)
        .sheet(isPresented: $showFamilyManager) {
            FamilyManagerSheet { loadFamily() }
        }
        .sheet(isPresented: $showLogin) {
            LoginScreen()
        }
    }

    /// "Welcome" when there are no profiles yet, otherwise "emoji Hello, Name ▾".
    ///
    /// Never "Everyone": Android resolves a stale or missing active patient to the FIRST real
    /// profile rather than showing everyone's data under a personal greeting, because everything
    /// downstream (tips, trends, chat) scopes to whoever the header names. An earlier iOS build
    /// offered an explicit "Everyone" entry, which put those two out of step.
    private var greetingRow: some View {
        HStack {
            Menu {
                ForEach(profiles) { profile in
                    Button {
                        selectedProfile = profile
                        AppSettings.activePatient = profile.name
                        refreshTick += 1
                    } label: {
                        Text("\(profile.avatarEmoji) \(profile.name) (\(profile.relation))")
                    }
                }
                if !profiles.isEmpty { Divider() }
                Button { showFamilyManager = true } label: {
                    Text("➕ \(tr("Add family member"))")
                }
            } label: {
                HStack(spacing: 2) {
                    Text(greetingText)
                        .font(.title2.bold())
                        .foregroundColor(.primary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    if !profiles.isEmpty {
                        Image(systemName: "chevron.down")
                            .font(.caption)
                            .foregroundColor(.primary)
                    }
                }
            }

            Spacer(minLength: 8)

            Button {
                refreshTick += 1
                loadFamily()
            } label: {
                Image(systemName: "arrow.clockwise")
            }
            .accessibilityLabel(tr("Refresh"))
        }
    }

    private var greetingText: String {
        guard let profile = selectedProfile else { return tr("Welcome") }
        return "\(profile.avatarEmoji) \(tr("Hello,")) \(profile.name)"
    }

    private func tile(_ action: HomeAction) -> some View {
        // Light mode keeps the pale container behind a saturated label. Dark mode swaps the two
        // roles rather than reusing the pale hex as-is (which reads as a washed-out floating card)
        // or flattening every tile to one neutral dark surface (which loses the colour cue): the
        // saturated hue becomes a low-alpha tint, and the already-light container hue becomes the
        // readable foreground. Same treatment as Android's ActionSquare.
        let isDark = colorScheme == .dark
        let background = isDark ? action.content.opacity(0.22) : action.container
        let foreground = isDark ? action.container : action.content

        // Color.clear is flexible, so it takes the full cell width and the aspect ratio forces the
        // height; the content rides in an overlay. Sizing the VStack directly would instead shrink
        // the card to the text's intrinsic height and make rows uneven.
        return Color.clear
            .aspectRatio(1.6, contentMode: .fit)
            .overlay(
                VStack(spacing: 0) {
                    Text(action.emoji)
                        .font(.system(size: 24))
                        .padding(.bottom, 8)
                    // A fixed, slightly small label size leaves headroom for two lines within the
                    // same card height even at a large system font scale, so a wrapped tile never
                    // grows taller than its row neighbour.
                    Text(tr(action.label))
                        .font(.system(size: 12, weight: .bold))
                        .lineSpacing(2)
                        .foregroundColor(foreground)
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(8)
            )
            .background(
                ZStack {
                    // In dark mode the tint is translucent, so paint the surface underneath it —
                    // otherwise the watermark shows through the card.
                    if isDark { Color.medicalSurface }
                    background
                }
            )
            .clipShape(RoundedRectangle(cornerRadius: 18))
            .shadow(color: .black.opacity(isDark ? 0 : 0.12), radius: 3, y: 2)
    }

    /// Faint centered logo behind the grid — the analog of Android's `Modifier.appWatermark()`.
    private var watermark: some View {
        GeometryReader { geo in
            Image("Logo")
                .resizable()
                .scaledToFit()
                .frame(width: min(geo.size.width, geo.size.height) * 0.85)
                .opacity(0.06)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        }
    }

    private func loadFamily() {
        profiles = AppSettings.familyProfiles
        let active = AppSettings.activePatient

        // Resolve to a real profile: the named one if it still exists, otherwise the first.
        // A renamed or removed active patient must not leave the header showing nobody.
        if profiles.isEmpty {
            selectedProfile = nil
        } else if let active,
                  let match = profiles.first(where: { $0.name.caseInsensitiveCompare(active) == .orderedSame }) {
            selectedProfile = match
        } else {
            selectedProfile = profiles.first
        }

        // Keep the persisted active patient in step with what the header now shows — everything
        // downstream reads it, so a stale value silently scopes the app to the wrong person.
        if let resolved = selectedProfile {
            AppSettings.activePatient = resolved.name
        }
    }
}

/// Reproduces Android's `ActionSquare` press feedback (scale 0.95, elevation drop).
private struct PressableTileStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.95 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}
