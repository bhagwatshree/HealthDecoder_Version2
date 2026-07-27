import SwiftUI

struct ReportDetailScreen: View {
    var report: MedicalReport
    
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(report.patientName ?? "Unknown Patient")
                    .font(.title)
                
                Text(report.reportType ?? "Medical Report")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                
                Divider()
                
                if report.analyzed {
                    Text("Analysis Results")
                        .font(.headline)
                    
                    if let results = report.testResults {
                        ForEach(results.parameters, id: \.name) { param in
                            HStack {
                                Text(param.name)
                                Spacer()
                                Text("\(param.value) \(param.unit)")
                                    .fontWeight(.bold)
                            }
                        }
                    }
                } else {
                    Text("This report was uploaded without analysis.")
                        .italic()
                    Button("Analyze Now") {
                        // Call backend to analyze
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .padding()
        }
        .navigationTitle("Report Details")
    }
}
