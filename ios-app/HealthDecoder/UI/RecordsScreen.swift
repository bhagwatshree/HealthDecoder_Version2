import SwiftUI

/// Searchable report list, mirroring `ui/RecordsScreen.kt`. FTS-backed search on Android;
/// Phase 1 does a simple in-memory substring filter until the FTS mirror table lands.
struct RecordsScreen: View {
    @State private var reports: [MedicalReport] = []
    @State private var searchText = ""

    private var filtered: [MedicalReport] {
        guard !searchText.trimmingCharacters(in: .whitespaces).isEmpty else { return reports }
        let query = searchText.lowercased()
        return reports.filter {
            ($0.patientName ?? "").lowercased().contains(query) ||
            ($0.reportType ?? "").lowercased().contains(query) ||
            ($0.reportCategory ?? "").lowercased().contains(query) ||
            ($0.extractedText ?? "").lowercased().contains(query)
        }
    }

    var body: some View {
        Group {
            if reports.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "folder")
                        .font(.system(size: 40))
                        .foregroundColor(.secondary)
                    Text(tr("No reports yet"))
                        .foregroundColor(.secondary)
                    Text(tr("Scan a report to see it here."))
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
            } else {
                List(filtered) { report in
                    NavigationLink(value: AppRoute.reportDetail(id: report.id)) {
                        RecordRow(report: report)
                    }
                }
                .searchable(text: $searchText, prompt: Text(tr("Search records")))
            }
        }
        .navigationTitle(tr("Records"))
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink(destination: ChatScreen(context: "Records")) {
                    Image(systemName: "message.fill")
                }
            }
        }
        .onAppear { reports = LocalRepository.getAllReports() }
        .refreshable { reports = LocalRepository.getAllReports() }
    }
}

private struct RecordRow: View {
    let report: MedicalReport

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(report.patientName?.isEmpty == false ? report.patientName! : "Unknown Patient")
                    .font(.headline)
                Text([report.reportType, report.reportDate].compactMap { $0 }.joined(separator: " • "))
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
            Spacer()
            if !report.analyzed {
                Text(tr("PROCESSING"))
                    .font(.caption2)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.statusLow.opacity(0.2))
                    .foregroundColor(.statusLow)
                    .cornerRadius(6)
            }
        }
    }
}
