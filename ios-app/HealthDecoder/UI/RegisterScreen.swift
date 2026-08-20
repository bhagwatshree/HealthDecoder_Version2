import SwiftUI

/// Account creation — port of `ui/RegisterScreen.kt` with `PHONE_AUTH_ENABLED` off, which is the
/// shipped Android default: email + password only, no phone field, no "Send OTP" step.
///
/// An earlier version of this screen refused to show a form at all, on the belief that
/// `/api/auth/signup` "unconditionally requires a verified Firebase phone-OTP token". That is not
/// what the backend does — `server.js` treats `phoneIdToken` as OPTIONAL and synthesises an
/// `email_<hash>` placeholder MSISDN when it's absent, precisely so signup works without a billed
/// SMS. Verified against the live API before this was written.
struct RegisterScreen: View {
    @EnvironmentObject private var session: SessionStore
    @Environment(\.dismiss) private var dismiss

    @State private var firstName = ""
    @State private var lastName = ""
    @State private var dateOfBirth = Calendar.current.date(byAdding: .year, value: -30, to: Date()) ?? Date()
    @State private var gender = "prefer_not_to_say"
    @State private var email = ""
    @State private var password = ""
    @State private var passwordVisible = false
    /// Optional bring-your-own Gemini key, attached to the account after signup — same field
    /// Android collects here, and the same one Settings exposes later.
    @State private var apiKeyInput = ""

    @State private var isLoading = false
    @State private var errorMessage: String?

    /// Mirrors Android's `GENDER_OPTIONS`; the values are the four the backend accepts
    /// (`VALID_GENDERS` in server.js) — anything else is rejected with a 400.
    private let genderOptions: [(label: String, value: String)] = [
        ("Male", "male"),
        ("Female", "female"),
        ("Other", "other"),
        ("Prefer not to say", "prefer_not_to_say"),
    ]

    private var canSubmit: Bool {
        !firstName.trimmingCharacters(in: .whitespaces).isEmpty
            && !lastName.trimmingCharacters(in: .whitespaces).isEmpty
            && !email.trimmingCharacters(in: .whitespaces).isEmpty
            // The backend requires at least 6 characters; checking here turns a round-trip
            // rejection into immediate feedback.
            && password.count >= 6
            && !isLoading
    }

    var body: some View {
        Form {
            Section(header: Text(tr("Your details"))) {
                TextField(tr("First name"), text: $firstName)
                    .textContentType(.givenName)
                TextField(tr("Last name"), text: $lastName)
                    .textContentType(.familyName)
                DatePicker(tr("Date of birth"), selection: $dateOfBirth,
                           in: ...Date(), displayedComponents: .date)
                Picker(tr("Gender"), selection: $gender) {
                    ForEach(genderOptions, id: \.value) { option in
                        Text(tr(option.label)).tag(option.value)
                    }
                }
            }

            Section(header: Text(tr("Sign-in details"))) {
                TextField(tr("Email"), text: $email)
                    .textContentType(.emailAddress)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .disableAutocorrection(true)

                HStack {
                    Group {
                        if passwordVisible {
                            TextField(tr("Password"), text: $password)
                        } else {
                            SecureField(tr("Password"), text: $password)
                        }
                    }
                    .textContentType(.newPassword)
                    .textInputAutocapitalization(.never)
                    .disableAutocorrection(true)

                    Button {
                        passwordVisible.toggle()
                    } label: {
                        Image(systemName: passwordVisible ? "eye.slash" : "eye")
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(passwordVisible ? tr("Hide password") : tr("Show password"))
                }

                if !password.isEmpty && password.count < 6 {
                    Text(tr("Password must be at least 6 characters."))
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
            }

            Section {
                TextField(tr("Gemini API Key (AIzaSy...)"), text: $apiKeyInput)
                    .textInputAutocapitalization(.never)
                    .disableAutocorrection(true)
                    .font(.system(.body, design: .monospaced))
            } header: {
                Text(tr("Free Gemini API Key (Optional)"))
            } footer: {
                Text(tr("Leave blank to use the shared pool. Adding your own key gives you your own quota."))
            }

            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .foregroundColor(.red)
                        .font(.footnote)
                }
            }

            Section {
                Button {
                    Task { await createAccount() }
                } label: {
                    HStack {
                        Spacer()
                        if isLoading {
                            ProgressView()
                        } else {
                            Label(tr("Create Account"), systemImage: "person.badge.plus")
                                .fontWeight(.semibold)
                        }
                        Spacer()
                    }
                }
                .disabled(!canSubmit)
            }
        }
        .navigationTitle(tr("Create Account"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func createAccount() async {
        errorMessage = nil
        isLoading = true
        defer { isLoading = false }

        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"

        do {
            let response = try await APIClient.shared.signup(
                SignupRequest(
                    firstName: firstName.trimmingCharacters(in: .whitespaces),
                    lastName: lastName.trimmingCharacters(in: .whitespaces),
                    dateOfBirth: formatter.string(from: dateOfBirth),
                    gender: gender,
                    email: email.trimmingCharacters(in: .whitespaces),
                    password: password,
                    // Empty on purpose: the backend synthesises a placeholder MSISDN rather than
                    // demanding an OTP. Sending a non-empty value here would make the server try
                    // to verify it against Firebase and fail.
                    phoneIdToken: ""
                )
            )
            // Sign in first, then attach the key: signup already succeeded, so a key-save hiccup
            // must not block or fail the whole flow. Same fire-and-forget shape as Android's
            // `attachApiKeyIfProvided`.
            session.login(response: response)
            await attachApiKeyIfProvided()
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func attachApiKeyIfProvided() async {
        let key = apiKeyInput.trimmingCharacters(in: .whitespaces)
        guard !key.isEmpty else { return }
        _ = try? await APIClient.shared.setGeminiKeyOnAccount(ApiKeyRequest(apiKey: key))
    }
}
