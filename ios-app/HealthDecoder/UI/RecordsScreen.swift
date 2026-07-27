import SwiftUI

struct RecordsScreen: View {
    @State private var reports: [MedicalReport] = []
    
    var body: some View {
        List(reports) { report in
            VStack(alignment: .leading) {
                Text(report.patientName ?? "Unknown Patient")
                    .font(.headline)
                Text(report.reportType ?? "Document")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
        }
        .navigationTitle("Records")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink(destination: ChatScreen(context: "Records")) {
                    Image(systemName: "message.fill")
                }
            }
        }
    }
}
