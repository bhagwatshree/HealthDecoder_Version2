import SwiftUI

/// Searchable report list — port of `ui/RecordsScreen.kt`: logo top bar, rounded search field,
/// period filter chips, report cards on the tinted background with the logo watermark, and a
/// FAB that jumps to Scan.
struct RecordsScreen: View {
    @State private var reports: [MedicalReport] = []
    @State private var searchText = ""
    @State private var selectedPeriod: String?

    /// (value, label) pairs, matching Android's period chips.
    private let periods: [(value: String?, label: String)] = [
        (nil, "All Time"), ("1m", "1 Month"), ("3m", "3 Months"), ("6m", "6 Months")
    ]

    private var filtered: [MedicalReport] {
        var result = reports

        if let period = selectedPeriod, let cutoff = cutoffDate(for: period) {
            let formatter = DateFormatter()
            formatter.dateFormat = "yyyy-MM-dd"
            let cutoffString = formatter.string(from: cutoff)
            result = result.filter { report in
                let date = report.reportDate ?? String(report.createdAt.prefix(10))
                return date >= cutoffString
            }
        }

        let query = searchText.trimmingCharacters(in: .whitespaces).lowercased()
        guard !query.isEmpty else { return result }
        return result.filter { report in
            (report.patientName ?? "").lowercased().contains(query)
                || (report.reportType ?? "").lowercased().contains(query)
                || (report.reportCategory ?? "").lowercased().contains(query)
                || (report.comments ?? "").lowercased().contains(query)
                || (report.extractedText ?? "").lowercased().contains(query)
                || report.medications.contains { $0.name.lowercased().contains(query) }
        }
    }

    private func cutoffDate(for period: String) -> Date? {
        let months: Int
        switch period {
        case "1m": months = 1
        case "3m": months = 3
        case "6m": months = 6
        default: return nil
        }
        return Calendar.current.date(byAdding: .month, value: -months, to: Date())
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            VStack(spacing: 0) {
                MedicalSearchField(
                    text: $searchText,
                    placeholder: tr("Search reports, patient, or document text...")
                )
                .padding(16)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(periods, id: \.label) { period in
                            MedicalFilterChip(
                                label: tr(period.label),
                                isSelected: selectedPeriod == period.value
                            ) {
                                selectedPeriod = period.value
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                }
                .padding(.bottom, 8)

                if filtered.isEmpty {
                    EmptyStateView(
                        icon: "clock.arrow.circlepath",
                        title: searchText.isEmpty ? tr("No scanned history") : tr("No matching reports"),
                        description: searchText.isEmpty
                            ? tr("Press 'Scan Report' to upload your first prescription.")
                            : tr("Try searching a different name")
                    )
                } else {
                    ScrollView {
                        LazyVStack(spacing: 12) {
                            ForEach(filtered) { report in
                                NavigationLink(value: AppRoute.reportDetail(id: report.id)) {
                                    ReportItemCard(report: report)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(16)
                        .padding(.bottom, 80) // clearance for the FAB
                    }
                }
            }

            NavigationLink(value: AppRoute.scan) {
                MedicalFAB { Image(systemName: "plus") }
            }
            .padding(20)
        }
        .medicalScreenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { TopBarTitle(title: tr("Records")) }
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink(value: AppRoute.chat(contextHint: "Records")) {
                    Image(systemName: "bubble.left.and.bubble.right")
                }
            }
        }
        .onAppear { reports = LocalRepository.getAllReports() }
    }
}
