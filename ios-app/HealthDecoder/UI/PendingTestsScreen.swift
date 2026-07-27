import SwiftUI

struct PendingTestsScreen: View {
    var body: some View {
        List {
            Text("No pending tests.")
                .foregroundColor(.secondary)
        }
        .navigationTitle("Pending Tests")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink(destination: ChatScreen(context: "Pending Tests")) {
                    Image(systemName: "message.fill")
                }
            }
        }
    }
}
