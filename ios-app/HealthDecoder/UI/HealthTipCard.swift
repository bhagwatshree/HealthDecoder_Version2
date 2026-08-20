import SwiftUI

/// Local rotation — no ad SDK, no advertising identifier, no third-party call. Port of
/// `ui/HealthTipCard.kt`.
///
/// Personalized tips are derived on-device (see `PersonalizedTips`, rule-based, no AI call) from
/// the active patient's own recent abnormal results. This needs reports, NOT an account: they come
/// from the local store and work fully signed-out, so nothing here is gated on sign-in.
///
/// When the patient has personalized tips they are the whole rotation; the general list is the
/// fallback for someone with no reports yet or nothing currently abnormal. The card is never empty
/// and never mixes the two.
struct HealthTipCard: View {
    @State private var personalized: [HealthTip] = []
    @State private var slot: Int = HealthTipCard.currentSlot()
    @State private var showDetail = false

    /// Two minutes, matching Android's `ROTATE_INTERVAL_MS`.
    private static let rotateInterval: TimeInterval = 2 * 60

    private var tips: [HealthTip] {
        personalized.isEmpty ? Self.generalTips : personalized
    }

    private var tip: HealthTip {
        let all = tips
        // `slot` is derived from the wall clock and can exceed the array, so wrap it.
        return all[((slot % all.count) + all.count) % all.count]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                ZStack {
                    Circle()
                        .fill(Color.aiAccent.opacity(0.2))
                        .frame(width: 28, height: 28)
                    Image(systemName: "lightbulb.fill")
                        .font(.system(size: 14))
                        .foregroundColor(Color.aiAccent)
                }
                Text(tip.source != nil ? tr("PERSONALIZED FOR YOU") : tr("HEALTH TIP"))
                    .font(.system(size: 12, weight: .heavy))
                    .kerning(0.8)
                    .foregroundColor(Color.aiAccent)
            }

            Text(tip.headline)
                .font(.headline)
                .foregroundColor(.primary)
                .fixedSize(horizontal: false, vertical: true)

            Text(tip.detail)
                .font(.subheadline)
                .foregroundColor(.secondary)
                .lineLimit(2)

            Button(tr("Learn More")) { showDetail = true }
                .font(.subheadline.bold())
                .padding(.top, 2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color.aiAccent.opacity(0.16))
        )
        .task {
            personalized = PersonalizedTips.tips(for: LocalRepository.getAllReports())
        }
        .task {
            // Wake on the interval boundary, not a full interval from now, so the tip changes on
            // the same cadence no matter when this screen was opened. Deriving the slot from the
            // wall clock (rather than counting up from zero) also means it keeps advancing while
            // the user is on another screen — Android hit exactly this bug, where navigating away
            // and back reset the counter and the card looked frozen on tip #1.
            while !Task.isCancelled {
                let now = Date().timeIntervalSince1970
                let untilBoundary = Self.rotateInterval - now.truncatingRemainder(dividingBy: Self.rotateInterval)
                try? await Task.sleep(nanoseconds: UInt64(untilBoundary * 1_000_000_000))
                if Task.isCancelled { return }
                slot = Self.currentSlot()
            }
        }
        .alert(tip.headline, isPresented: $showDetail) {
            Button(tr("Got it"), role: .cancel) {}
        } message: {
            Text(detailMessage)
        }
    }

    private var detailMessage: String {
        guard let source = tip.source else { return tip.detail }
        // Traceability for a personalized tip: exactly which result it came from, plus a plain
        // lifestyle-only disclaimer. Neither is shown for the general rotation, which isn't tied
        // to the patient's own data.
        return """
        \(tip.detail)

        \(tr("Based on your")) \(source.param) \(tr("result")) (\(tr(source.status))) \
        \(tr("from your report dated")) \(source.date).

        \(tr("General lifestyle suggestion, not medical advice — talk to your doctor before making changes based on a lab result."))
        """
    }

    private static func currentSlot() -> Int {
        Int(Date().timeIntervalSince1970 / rotateInterval)
    }

    /// Offline seed rotation, shown when the patient has no reports yet or nothing abnormal.
    /// On Android this list can also be superseded by the backend's health_tips table; iOS has no
    /// remote-tips client yet, so this is currently the whole general rotation.
    private static let generalTips: [HealthTip] = [
        HealthTip(headline: "Hydrate before a blood draw",
                  detail: "Drinking water in the hours before a blood test makes your veins easier to find and can make results like electrolytes more accurate."),
        HealthTip(headline: "Take medicines the same way each day",
                  detail: "Some medicines work best with food, others on an empty stomach. Sticking to one routine helps your body absorb them consistently."),
        HealthTip(headline: "Keep a symptom log before your visit",
                  detail: "Jot down when a symptom started, how often it happens, and what makes it better or worse — it helps your doctor a lot more than \"it's been a while.\""),
        HealthTip(headline: "Bring your last 2–3 reports to appointments",
                  detail: "Trends matter more than a single number. Having recent reports on hand lets your doctor see the direction things are moving, not just today's snapshot."),
        HealthTip(headline: "Fasting tests mean fasting tests",
                  detail: "For fasting blood sugar or lipid panels, even black coffee or chewing gum can skew results. Water is usually fine — check with your lab if unsure."),
        HealthTip(headline: "Don't stop a medicine without asking first",
                  detail: "Some medications (like steroids or blood pressure pills) can cause problems if stopped suddenly, even if you're feeling better."),
        HealthTip(headline: "Store medicines away from heat and moisture",
                  detail: "The bathroom cabinet is often the worst place — humidity can degrade tablets faster than a cool, dry drawer."),
        HealthTip(headline: "Double-check dosage units",
                  detail: "Mixing up mg and mcg, or ml and mg, is one of the most common medication mistakes. When in doubt, ask your pharmacist to confirm."),
        HealthTip(headline: "Set reminders for refills, not just doses",
                  detail: "Running out of a medicine for a few days can undo weeks of consistent treatment — a refill reminder a week early avoids the gap."),
        HealthTip(headline: "Share your full medicine list with every new doctor",
                  detail: "Interactions are easy to miss when each doctor only sees the prescriptions they wrote. A full list — including over-the-counter drugs — helps catch them."),
    ]
}

/// Slim tappable banner for Smart Health Lens — not a floating overlay icon (competes with other
/// chrome for space) and not a 7th grid tile (breaks the 6-tile rule). Sits near Scan Report
/// conceptually (capture-then-analyze vs. live-analyze) without taking a full tile slot.
struct SmartHealthLensBanner: View {
    var body: some View {
        HStack(spacing: 10) {
            Text("🔬").font(.system(size: 20))
            VStack(alignment: .leading, spacing: 2) {
                Text(tr("Try Smart Health Lens"))
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(Color.aiAccent)
                Text(tr("Live camera scan — point and get instant answers"))
                    .font(.system(size: 12))
                    .foregroundColor(.secondary)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .foregroundColor(Color.aiAccent)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 14)
                .fill(Color.aiAccent.opacity(0.16))
        )
    }
}

/// Sign-in is optional and isn't surfaced from Settings, so this is the one persistent entry
/// point for it — shown alongside the tip card, not instead of it, every visit until the user
/// actually signs in.
struct SignInBanner: View {
    let onTap: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(tr("Sign in to sync your records"))
                    .font(.subheadline.bold())
                Text(tr("Optional — keeps your data available across devices"))
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Spacer(minLength: 0)
            Button(tr("Sign In"), action: onTap)
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 14)
                .fill(Color.medicalTeal.opacity(0.14))
        )
    }
}
