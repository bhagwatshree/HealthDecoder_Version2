import SwiftUI

struct LoginScreen: View {
    @EnvironmentObject private var session: SessionStore
    @State private var email = ""
    @State private var password = ""
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var showRegister = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    Image("Logo")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 88, height: 88)
                        .clipShape(RoundedRectangle(cornerRadius: 18))
                        .padding(.top, 40)
                    Text(tr("Medical Assist"))
                        .font(.largeTitle.bold())

                    VStack(spacing: 12) {
                        TextField(tr("Email"), text: $email)
                            .textContentType(.emailAddress)
                            .keyboardType(.emailAddress)
                            .autocapitalization(.none)
                            .disableAutocorrection(true)
                            .textFieldStyle(.roundedBorder)
                        SecureField(tr("Password"), text: $password)
                            .textContentType(.password)
                            .textFieldStyle(.roundedBorder)
                    }

                    if let errorMessage {
                        Text(errorMessage)
                            .foregroundColor(.red)
                            .font(.footnote)
                            .multilineTextAlignment(.center)
                    }

                    Button {
                        Task { await login() }
                    } label: {
                        if isLoading {
                            ProgressView().frame(maxWidth: .infinity)
                        } else {
                            Text(tr("Sign In")).frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(email.isEmpty || password.isEmpty || isLoading)

                    Button(tr("Create an Account")) { showRegister = true }
                        .font(.footnote)

                    // Phone-OTP and "Continue with Google" are intentionally not shown here —
                    // no Firebase SDK is linked yet in this build (see AuthCapabilities). This
                    // matches how the Android app hides both when unconfigured.
                }
                .padding()
            }
            .navigationDestination(isPresented: $showRegister) { RegisterScreen() }
        }
    }

    private func login() async {
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
