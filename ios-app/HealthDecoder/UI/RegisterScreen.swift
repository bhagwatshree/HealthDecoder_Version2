import SwiftUI

/// The backend's `/api/auth/signup` unconditionally requires a verified Firebase phone-OTP
/// token (`server.js`: "Phone verification is required." with no fallback), and Phase 1 links
/// no Firebase SDK — so a genuinely new account can't be created from this build yet. Rather
/// than ship a form that always fails, this screen says so plainly and points at the two real
/// options, matching what was agreed for Phase 1.
struct RegisterScreen: View {
    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "phone.badge.checkmark")
                .font(.system(size: 48))
                .foregroundColor(.secondary)
            Text(tr("Account creation needs phone verification"))
                .font(.title3.bold())
                .multilineTextAlignment(.center)
            Text("""
            Creating a brand-new HealthDecoder account requires a one-time phone verification, \
            which this build doesn't support yet.

            For now:
            • If you already have an account (e.g. created on the Android app), just log in \
            with that email and password.
            • Otherwise, create your account on the Android app first, then log in here with \
            the same credentials.
            """)
            .font(.subheadline)
            .foregroundColor(.secondary)
            .multilineTextAlignment(.leading)
            .fixedSize(horizontal: false, vertical: true)
            Spacer()
        }
        .padding()
        .navigationTitle(tr("Create Account"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
