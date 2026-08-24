import SwiftUI

/// Settings — port of the parts of `ui/SettingsScreen.kt` that iOS can actually back today.
///
/// Replaces a 49-line Account screen whose every control was disabled. Where Android has a
/// section iOS cannot yet honour (full encrypted backup/restore, the email scanner, fix/merge
/// patient), it is absent rather than shown-and-inert: a switch that silently does nothing is
/// worse than one that isn't there.
struct SettingsScreen: View {
    @EnvironmentObject private var session: SessionStore
    @EnvironmentObject private var language: AppLanguageState

    @State private var themeMode = AppSettings.themeMode
    @State private var labUnits = AppSettings.labUnitSystem
    @State private var serverURL = AppSettings.serverURL
    @State private var geminiKey = ""
    @State private var keyStatus: String?

    @State private var demoActive = DemoDataSeeder.isActive()
    @State private var reportCount = 0

    @State private var showDeleteAllConfirm = false
    @State private var showLogin = false
    @State private var exportDocument: RecordsExportDocument?
    @State private var showExporter = false

    private var appVersion: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "\(v) (\(b))"
    }

    var body: some View {
        Form {
            accountSection
            appearanceSection
            unitsSection
            dataSection
            scanSection
            serverSection
            aboutSection
        }
        .navigationTitle(tr("Settings"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            reportCount = LocalRepository.getAllReports().count
            demoActive = DemoDataSeeder.isActive()
        }
        .sheet(isPresented: $showLogin) { LoginScreen() }
        .fileExporter(
            isPresented: $showExporter,
            document: exportDocument,
            contentType: .json,
            defaultFilename: "health-decoder-records"
        ) { _ in }
        .alert(tr("Delete all data?"), isPresented: $showDeleteAllConfirm) {
            Button(tr("Cancel"), role: .cancel) {}
            Button(tr("Delete Everything"), role: .destructive) { deleteAllData() }
        } message: {
            Text(tr("Every report, medicine and reminder on this device is removed. This can't be undone — export first if you want a copy."))
        }
    }

    // MARK: - Sections

    private var accountSection: some View {
        Section(header: Text(tr("Account"))) {
            if session.isLoggedIn {
                if let user = session.currentUser {
                    VStack(alignment: .leading, spacing: 2) {
                        Text([user.firstName, user.lastName].compactMap { $0 }.joined(separator: " "))
                            .font(.headline)
                        Text(user.email).font(.caption).foregroundColor(.secondary)
                    }
                } else if let email = AppSettings.userEmail {
                    Text(email).font(.subheadline)
                }
                Button(tr("Log Out"), role: .destructive) { session.logout() }
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    Text(tr("Not signed in")).font(.subheadline)
                    Text(tr("Sign-in is optional — records, reminders and analysis all work without an account."))
                        .font(.caption).foregroundColor(.secondary)
                }
                Button(tr("Sign In")) { showLogin = true }
            }

            // Bring-your-own key, the same field Register offers. Only meaningful with an account,
            // since the key is stored against the user server-side.
            if session.isLoggedIn {
                TextField(tr("Gemini API Key (AIzaSy...)"), text: $geminiKey)
                    .textInputAutocapitalization(.never)
                    .disableAutocorrection(true)
                    .font(.system(.body, design: .monospaced))
                Button(tr("Save API Key")) { Task { await saveKey() } }
                    .disabled(geminiKey.trimmingCharacters(in: .whitespaces).isEmpty)
                if let keyStatus {
                    Text(keyStatus).font(.caption).foregroundColor(.secondary)
                }
            }
        }
    }

    private var appearanceSection: some View {
        Section(header: Text(tr("Appearance"))) {
            Picker(tr("App Theme"), selection: $themeMode) {
                Text(tr("System")).tag("system")
                Text(tr("Light")).tag("light")
                Text(tr("Dark")).tag("dark")
            }
            .onChange(of: themeMode) { _, new in AppSettings.themeMode = new }

            Picker(tr("Language"), selection: Binding(
                get: { language.current },
                set: { language.select($0) }
            )) {
                ForEach(SupportedLanguages.all, id: \.self) { Text($0).tag($0) }
            }
        }
    }

    private var unitsSection: some View {
        Section {
            Picker(tr("Lab Units"), selection: $labUnits) {
                Text(tr("Conventional (Indian)")).tag("Conventional")
                Text(tr("SI (International)")).tag("SI")
            }
            .onChange(of: labUnits) { _, new in AppSettings.labUnitSystem = new }
        } footer: {
            Text(tr("Chooses the unit trend charts standardise to. Readings with no verified conversion are always shown exactly as the lab printed them."))
        }
    }

    private var dataSection: some View {
        Section(header: Text(tr("Data Management"))) {
            LabeledContent(tr("Reports on this device"), value: "\(reportCount)")

            Button(tr("Export Records")) { exportRecords() }
                .disabled(reportCount == 0)

            if demoActive {
                Button(tr("Remove Demo Data")) {
                    DemoDataSeeder.remove()
                    demoActive = false
                    reportCount = LocalRepository.getAllReports().count
                }
            } else {
                Button(tr("Add Demo Data")) {
                    DemoDataSeeder.seed()
                    demoActive = true
                    reportCount = LocalRepository.getAllReports().count
                }
            }

            Button(tr("Delete All Data"), role: .destructive) { showDeleteAllConfirm = true }
                .disabled(reportCount == 0)
        }
    }

    private var scanSection: some View {
        Section {
            LabeledContent(tr("Pages per AI request"), value: "\(AppSettings.scanChunkPages)")
            LabeledContent(tr("Maximum pages per scan"), value: "\(AppSettings.scanMaxPages)")
        } header: {
            Text(tr("Scanning"))
        } footer: {
            Text(tr("A large document is sent in chunks so one request never exceeds the model's limits."))
        }
    }

    private var serverSection: some View {
        Section {
            TextField(tr("Server URL"), text: $serverURL)
                .textInputAutocapitalization(.never)
                .disableAutocorrection(true)
                .keyboardType(.URL)
                .font(.system(.footnote, design: .monospaced))
            Button(tr("Save")) { AppSettings.serverURL = serverURL }
            Button(tr("Reset to default")) {
                AppSettings.serverURL = AppSettings.defaultServerURL
                serverURL = AppSettings.defaultServerURL
            }
        } header: {
            Text(tr("Server"))
        } footer: {
            Text(tr("Only change this if you were told to. The app talks to the default server otherwise."))
        }
    }

    private var aboutSection: some View {
        Section(header: Text(tr("About"))) {
            LabeledContent(tr("Version"), value: appVersion)
            Text(tr("Health Decoder is not a medical device and does not provide medical advice. Always consult a qualified doctor."))
                .font(.caption)
                .foregroundColor(.secondary)
        }
    }

    // MARK: - Actions

    private func saveKey() async {
        let key = geminiKey.trimmingCharacters(in: .whitespaces)
        guard !key.isEmpty else { return }
        do {
            _ = try await APIClient.shared.setGeminiKeyOnAccount(ApiKeyRequest(apiKey: key))
            keyStatus = tr("Saved. Your own quota is used from now on.")
            geminiKey = ""
        } catch {
            keyStatus = error.localizedDescription
        }
    }

    private func exportRecords() {
        let reports = LocalRepository.getAllReports()
        guard let data = try? JSONEncoder.exportEncoder.encode(reports) else { return }
        exportDocument = RecordsExportDocument(data: data)
        showExporter = true
    }

    private func deleteAllData() {
        for report in LocalRepository.getAllReports() {
            LocalRepository.deleteReport(id: report.id)
        }
        reportCount = 0
        demoActive = false
    }
}

private extension JSONEncoder {
    /// Pretty-printed with sorted keys so an exported file is readable and diffable rather than
    /// one long line — this is a file a person may open, not just re-import.
    static var exportEncoder: JSONEncoder {
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        return e
    }
}
