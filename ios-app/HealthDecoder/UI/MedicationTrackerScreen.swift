import SwiftUI

struct MedicationTrackerScreen: View {
    @State private var medications: [Medication] = []
    
    var body: some View {
        List(medications, id: \.name) { med in
            VStack(alignment: .leading) {
                Text(med.name)
                    .font(.headline)
                if !med.dosage.isEmpty {
                    Text("\(med.dosage) - \(med.frequency)")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
            }
        }
        .navigationTitle("Medication Tracker")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink(destination: ChatScreen(context: "Medication Tracker")) {
                    Image(systemName: "message.fill")
                }
            }
        }
    }
}
