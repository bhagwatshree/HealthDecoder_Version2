import SwiftUI

/// Dashboard tile grid — a 1:1 port of `ui/HomeScreen.kt`: same tile order, same emoji, and the
/// same per-tile pastel container/content colour pairs (which are hardcoded on Android too, so
/// they deliberately stay pastel in dark mode rather than following the theme).
struct HomeScreen: View {
    private struct HomeAction: Identifiable {
        let id = UUID()
        let label: String
        let emoji: String
        let container: Color
        let content: Color
        let route: AppRoute?
    }

    @EnvironmentObject private var session: SessionStore
    @State private var profiles: [FamilyProfile] = []
    @State private var selectedProfile: FamilyProfile?
    @State private var showFamilyManager = false
    @State private var refreshTick = 0

    /// Discovery ("Find Doctors/Labs/Hospitals") is gated off on Android too — `isBackendReady`
    /// is false there — so those three tiles are intentionally absent here as well.
    private var actions: [HomeAction] {
        [
            HomeAction(label: "Scan Report", emoji: "📸", container: Color(hex: 0xE8F5E9), content: Color(hex: 0x2E7D32), route: .scan),
            HomeAction(label: "Records", emoji: "📜", container: Color(hex: 0xECEFF1), content: Color(hex: 0x455A64), route: .records),
            HomeAction(label: "Reminders", emoji: "⏰", container: Color(hex: 0xFFF3E0), content: Color(hex: 0xE65100), route: .reminders),
            HomeAction(label: "Medications", emoji: "💊", container: Color(hex: 0xF3E5F5), content: Color(hex: 0x6A1B9A), route: .medicationTracker),
            HomeAction(label: "Pending Tests", emoji: "🚨", container: Color(hex: 0xFFF9C4), content: Color(hex: 0xC62828), route: .pendingTests),
            HomeAction(label: "Trends", emoji: "📈", container: Color(hex: 0xE3F2FD), content: Color(hex: 0x1565C0), route: nil),
            HomeAction(label: "Smart Health Lens", emoji: "👁️‍🗨️", container: Color(hex: 0xEDE7F6), content: Color(hex: 0x4527A0), route: .smartHealthLens),
            HomeAction(label: "Doctor Brief", emoji: "👨‍⚕️", container: Color(hex: 0xE0F2F1), content: Color(hex: 0x00695C), route: .doctorBrief(patientName: selectedProfile?.name ?? ""))
        ]
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            ScrollView {
                VStack(spacing: 16) {
                    // Android puts the family switcher in the top bar as the title; iOS drops a
                    // `principal` toolbar item when the bar is this crowded, so it lives here as
                    // a header row instead — same control, same behaviour, always visible.
                    familySwitcher
                        .frame(maxWidth: .infinity, alignment: .leading)

                    LazyVGrid(
                        columns: [GridItem(.flexible(), spacing: 16), GridItem(.flexible(), spacing: 16)],
                        spacing: 16
                    ) {
                        ForEach(actions) { action in
                            if let route = action.route {
                                NavigationLink(value: route) { tile(action, enabled: true) }
                                    .buttonStyle(PressableTileStyle())
                            } else {
                                tile(action, enabled: false)
                            }
                        }
                    }
                }
                .padding(16)
                .padding(.bottom, 80) // clearance for the floating Voice Search button
            }
            .background(watermark)

            voiceSearchButton
                .padding(.trailing, 16)
                .padding(.bottom, 16)
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // Matches Android: Account + Chat at top-left, family switcher as the title,
            // Refresh + Compare + language picker at top-right.
            ToolbarItemGroup(placement: .navigationBarLeading) {
                NavigationLink(destination: AccountScreen()) {
                    Image(systemName: "person.circle")
                }
                NavigationLink(destination: ChatScreen(context: "")) {
                    Image(systemName: "bubble.left.and.bubble.right")
                }
            }
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                Button { refreshTick += 1; loadFamily() } label: {
                    Image(systemName: "arrow.clockwise")
                }
                // Compare is wired once CompareScreen lands — shown disabled rather than
                // omitted so the top bar keeps Android's shape.
                Button {} label: { Image(systemName: "arrow.left.arrow.right") }
                    .disabled(true)
                LanguagePickerIcon()
            }
        }
        .onAppear(perform: loadFamily)
        .sheet(isPresented: $showFamilyManager) {
            FamilyManagerSheet { loadFamily() }
        }
    }

    private var familySwitcher: some View {
        Menu {
            Button {
                selectedProfile = nil
                AppSettings.activePatient = nil
            } label: {
                Text("👨‍👩‍👧 \(tr("Everyone"))")
            }
            ForEach(profiles) { profile in
                Button {
                    selectedProfile = profile
                    AppSettings.activePatient = profile.name
                } label: {
                    Text("\(profile.avatarEmoji) \(profile.name) (\(profile.relation))")
                }
            }
            Divider()
            Button {
                showFamilyManager = true
            } label: {
                Text("⚙️ \(tr("Manage / edit family"))")
            }
        } label: {
            HStack(spacing: 4) {
                Text(selectedProfile.map { "\($0.avatarEmoji) \($0.name)" } ?? "👨‍👩‍👧 \(tr("Everyone"))")
                    .font(.headline)
                    .lineLimit(1)
                Image(systemName: "chevron.down").font(.caption2)
            }
            .foregroundColor(.medicalTeal)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(Color.medicalSurfaceVariant)
            .clipShape(Capsule())
        }
    }

    private var voiceSearchButton: some View {
        NavigationLink(value: AppRoute.chat(contextHint: "")) {
            HStack(spacing: 8) {
                Image(systemName: "mic.fill")
                Text(tr("Voice Search")).fontWeight(.bold)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
            .background(Color.medicalSurfaceVariant)
            .foregroundColor(.medicalNavy)
            .clipShape(Capsule())
            .shadow(color: .black.opacity(0.15), radius: 6, y: 3)
        }
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

    private func tile(_ action: HomeAction, enabled: Bool) -> some View {
        // Color.clear is flexible, so it takes the full cell width and aspectRatio then forces
        // the square; the content rides in an overlay. Sizing the VStack directly would instead
        // shrink the square down to the text's intrinsic height.
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay(
                VStack(spacing: 6) {
                    Text(action.emoji).font(.system(size: 38))
                    Text(tr(action.label))
                        .font(.body.bold())
                        .foregroundColor(action.content)
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                        .minimumScaleFactor(0.75)
                        .fixedSize(horizontal: false, vertical: true)
                    if !enabled {
                        Text(tr("Coming soon"))
                            .font(.caption2)
                            .foregroundColor(action.content.opacity(0.7))
                    }
                }
                .padding(12)
            )
            .background(action.container)
            .clipShape(RoundedRectangle(cornerRadius: 20))
            .shadow(color: .black.opacity(enabled ? 0.12 : 0), radius: 3, y: 2)
            .opacity(enabled ? 1 : 0.55)
    }

    private func loadFamily() {
        profiles = AppSettings.familyProfiles
        let active = AppSettings.activePatient
        selectedProfile = active.flatMap { name in
            profiles.first { $0.name.caseInsensitiveCompare(name) == .orderedSame }
        }
        // If the active member was renamed/removed out from under us, fall back to Everyone.
        if active != nil && selectedProfile == nil { AppSettings.activePatient = nil }
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
