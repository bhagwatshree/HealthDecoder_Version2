import SwiftUI

struct AccountScreen: View {
    @EnvironmentObject private var session: SessionStore
    @State private var preferredLanguage = AppSettings.preferredLanguage
    @State private var labUnitPreference = "Conventional"

    var body: some View {
        Form {
            if let user = session.currentUser {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text([user.firstName, user.lastName].compactMap { $0 }.joined(separator: " "))
                            .font(.headline)
                        Text(user.email).font(.subheadline).foregroundColor(.secondary)
                    }
                }
            }

            Section(header: Text(tr("Details"))) {
                Picker(tr("Language"), selection: $preferredLanguage) {
                    Text(tr("English")).tag("English")
                }
                .disabled(true) // Phase 1 ships English only; other languages land in a later phase.

                Picker(tr("Lab Units"), selection: $labUnitPreference) {
                    Text(tr("Conventional (Indian)")).tag("Conventional")
                    Text(tr("SI (International)")).tag("SI")
                }
                .disabled(true) // Trends screen (which this affects) hasn't landed yet.
            }

            Section(header: Text(tr("Server Settings"))) {
                Text(tr("Backup & Restore, Transfer Records, and Fix/Merge Patient land in a later phase."))
                    .font(.footnote)
                    .foregroundColor(.secondary)
            }

            Section {
                Button(tr("Log Out"), role: .destructive) {
                    session.logout()
                }
            }
        }
        .navigationTitle(tr("Account"))
    }
}
