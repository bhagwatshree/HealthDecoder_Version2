import SwiftUI

/// Sign-in — port of `ui/LoginScreen.kt` with `PHONE_AUTH_ENABLED` and `GOOGLE_SIGNIN_ENABLED`
/// off, which is the shipped Android default. Android hides the Email/Phone segmented control
/// entirely when phone auth is off, leaving exactly this: logo, email, password, Log In, and a
/// link to create an account. The layout below mirrors that — centred logo and wordmark, a card
/// holding the fields, leading icons on each field, and a password visibility toggle.
struct LoginScreen: View {
    @EnvironmentObject private var session: SessionStore
    @State private var email = ""
    @State private var password = ""
    @State private var passwordVisible = false
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var showRegister = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    header

                    VStack(spacing: 16) {
                        field(
                            icon: "envelope",
                            placeholder: tr("Email"),
                            text: $email,
                            contentType: .emailAddress,
                            keyboard: .emailAddress
                        )

                        passwordField

                        if let errorMessage {
                            Text(errorMessage)
                                .foregroundColor(.red)
                                .font(.footnote)
                                .multilineTextAlignment(.center)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }

                        Button {
                            Task { await login() }
                        } label: {
                            HStack {
                                Spacer()
                                if isLoading {
                                    ProgressView().tint(.white)
                                } else {
                                    Text(tr("Log In")).fontWeight(.semibold)
                                }
                                Spacer()
                            }
                            .padding(.vertical, 6)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                        .disabled(email.isEmpty || password.isEmpty || isLoading)
                    }
                    .padding(20)
                    .background(
                        RoundedRectangle(cornerRadius: 16)
                            .fill(Color(.secondarySystemGroupedBackground))
                    )

                    Button(tr("New here? Create an account")) { showRegister = true }
                        .font(.subheadline)

                    // Phone OTP and "Sign In with Google" are deliberately absent: no Firebase
                    // SDK is linked yet (see AuthCapabilities), and Android hides both when
                    // they're unconfigured rather than showing controls that can't work.
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 32)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity)
            }
            .background(Color(.systemGroupedBackground))
            .navigationDestination(isPresented: $showRegister) { RegisterScreen() }
        }
    }

    private var header: some View {
        VStack(spacing: 12) {
            Image("Logo")
                .resizable()
                .scaledToFit()
                .frame(width: 96, height: 96)
                .padding(.top, 48)
            Text(tr("Health Decoder"))
                .font(.largeTitle.bold())
            Text(tr("Understand your medical reports"))
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    private var passwordField: some View {
        HStack(spacing: 12) {
            Image(systemName: "lock")
                .foregroundColor(.secondary)
                .frame(width: 20)
            Group {
                if passwordVisible {
                    TextField(tr("Password"), text: $password)
                } else {
                    SecureField(tr("Password"), text: $password)
                }
            }
            .textContentType(.password)
            .textInputAutocapitalization(.never)
            .disableAutocorrection(true)
            .submitLabel(.go)
            .onSubmit { Task { await login() } }

            Button {
                passwordVisible.toggle()
            } label: {
                Image(systemName: passwordVisible ? "eye.slash" : "eye")
                    .foregroundColor(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(passwordVisible ? tr("Hide password") : tr("Show password"))
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color(.tertiarySystemFill)))
    }

    private func field(
        icon: String,
        placeholder: String,
        text: Binding<String>,
        contentType: UITextContentType,
        keyboard: UIKeyboardType
    ) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundColor(.secondary)
                .frame(width: 20)
            TextField(placeholder, text: text)
                .textContentType(contentType)
                .keyboardType(keyboard)
                .textInputAutocapitalization(.never)
                .disableAutocorrection(true)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color(.tertiarySystemFill)))
    }

    private func login() async {
        guard !email.isEmpty, !password.isEmpty, !isLoading else { return }
        errorMessage = nil
        isLoading = true
        defer { isLoading = false }
        do {
            let response = try await APIClient.shared.login(
                AuthRequest(email: email.trimmingCharacters(in: .whitespaces), password: password)
            )
            session.login(response: response)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
