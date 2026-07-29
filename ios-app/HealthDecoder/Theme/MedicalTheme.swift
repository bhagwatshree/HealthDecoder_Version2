import SwiftUI

/// Direct port of `theme/Color.kt`'s tokens. Primary/secondary/tertiary/background/surface
/// pairs are defined as asset-catalog Color Sets (see `Assets.xcassets`) so they automatically
/// swap between light and dark values — the same light/dark pairing `theme/Theme.kt` builds
/// into `LightColorScheme`/`DarkColorScheme`. The semantic status/report/AI-accent colors are
/// flat constants in Android too (not part of the light/dark swap), so they're plain values here.
extension Color {
    // ── Brand tokens (light/dark aware via Assets.xcassets) ──
    static let medicalTeal = Color("MedicalTeal")
    static let medicalNavy = Color("MedicalNavy")
    static let medicalAmber = Color("MedicalAmber")
    static let medicalBackground = Color("MedicalBackground")
    static let medicalSurface = Color("MedicalSurface")
    static let medicalSurfaceVariant = Color("MedicalSurfaceVariant")

    // ── Semantic medical status colors (flat — same in light/dark, matches Color.kt) ──
    static let statusNormal = Color(hex: 0x2E7D32)   // Green — normal/healthy/improved
    static let statusHigh = Color(hex: 0xC62828)     // Red — high/critical/worsened
    static let statusLow = Color(hex: 0xE65100)      // Orange — low/warning/changed
    static let statusNeutral = Color(hex: 0x607D8B)  // Blue-grey — neutral/stable

    static let statusNormalBg = Color(hex: 0xE8F5E9)
    static let statusHighBg = Color(hex: 0xFFEBEE)
    static let statusLowBg = Color(hex: 0xFFF3E0)

    // ── Report type colors ──
    static let reportPrescription = Color(hex: 0x1565C0)
    static let reportPrescriptionBg = Color(hex: 0xE3F2FD)
    static let reportLab = Color(hex: 0x2E7D32)
    static let reportLabBg = Color(hex: 0xE8F5E9)

    // ── AI / Analysis accent ──
    static let aiAccent = Color(hex: 0x3F51B5)
    static let aiAccentBg = Color(hex: 0xE8EAF6)

    // ── WhatsApp-style green used for share/read-aloud cards (Doctor Brief, matches Android's
    // literal Color(0xFF25D366) etc. in DoctorBriefScreen.kt) ──
    static let whatsappGreen = Color(hex: 0x25D366)
    static let brandCard = Color(hex: 0xDCF8C6)
    static let brandCardText = Color(hex: 0x075E54)
    static let brandBlue = Color(hex: 0x1565C0)
    static let brandBlueBg = Color(hex: 0xE3F2FD)

    init(hex: UInt32, alpha: Double = 1.0) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }
}
