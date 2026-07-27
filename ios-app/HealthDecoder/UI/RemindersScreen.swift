import SwiftUI

struct RemindersScreen: View {
    var body: some View {
        VStack {
            Text("Today's Medicines")
                .font(.headline)
            Spacer()
            Text("No reminders scheduled for today.")
                .foregroundColor(.secondary)
            Spacer()
        }
        .navigationTitle("Reminders")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink(destination: ChatScreen(context: "Reminders")) {
                    Image(systemName: "message.fill")
                }
            }
        }
    }
}
