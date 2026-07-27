import SwiftUI

struct AccountScreen: View {
    @State private var preferredLanguage = "English"
    @State private var labUnitPreference = "Conventional"
    
    var body: some View {
        Form {
            Section(header: Text("Preferences")) {
                Picker("Language", selection: $preferredLanguage) {
                    Text("English").tag("English")
                    Text("Hindi").tag("Hindi")
                }
                
                Picker("Lab Units", selection: $labUnitPreference) {
                    Text("Conventional (Indian)").tag("Conventional")
                    Text("SI (International)").tag("SI")
                }
            }
            
            Section(header: Text("Server Settings")) {
                Button("Fix / Merge Patient") { }
                Button("Backup & Restore") { }
                Button("Transfer Records") { }
            }
            
            Section(header: Text("Account")) {
                Button("Log Out") { }
                    .foregroundColor(.red)
            }
        }
        .navigationTitle("Account")
    }
}
