import SwiftUI
import Charts

/// Lab-value trends over time — port of `ui/TrendsScreen.kt`.
///
/// Replaces a 21-line placeholder that read "Charts will be rendered here" and was unreachable:
/// nothing routed to it. Series come from `DashboardEngine.buildHealthSummary`, with each test's
/// readings standardised to one unit so values printed by different labs are comparable.
struct TrendsScreen: View {
    @State private var patients: [String] = []
    @State private var selectedPatient: String = AppSettings.activePatient ?? ""
    @State private var selectedCategory = DashboardEngine.categoryAll
    @State private var trends: [ParameterTrend] = []
    @State private var isLoading = true

    /// Only tests with something to plot. A single reading still charts — it shows the value and
    /// its normal band, which is more useful than hiding the test entirely.
    private var withData: [ParameterTrend] {
        trends.filter { !$0.dataPoints.filter { $0.numericValue != nil }.isEmpty }
    }

    /// "All tests", then only the panels actually present, then "Other tests" if anything landed
    /// there. Offering an empty panel would be a dead end.
    private var availableCategories: [String] {
        let present = Set(withData.map { DashboardEngine.categoryOf($0.name) })
        return [DashboardEngine.categoryAll]
            + DashboardEngine.trendCategories.map(\.name).filter { present.contains($0) }
            + (present.contains(DashboardEngine.categoryOther) ? [DashboardEngine.categoryOther] : [])
    }

    private var visibleTrends: [ParameterTrend] {
        selectedCategory == DashboardEngine.categoryAll
            ? withData
            : withData.filter { DashboardEngine.categoryOf($0.name) == selectedCategory }
    }

    var body: some View {
        Group {
            if isLoading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if withData.isEmpty {
                emptyState
            } else {
                content
            }
        }
        .navigationTitle(tr("Health Trends"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { load() } label: { Image(systemName: "arrow.clockwise") }
                    .accessibilityLabel(tr("Refresh trends"))
            }
        }
        .onAppear(perform: load)
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "chart.xyaxis.line")
                .font(.system(size: 44))
                .foregroundColor(.secondary)
            Text(tr("No test values to chart"))
                .font(.headline)
            Text(tr("Scan a lab report with numeric results and its values will appear here over time."))
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var content: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if patients.count > 1 {
                    Picker(tr("Patient"), selection: $selectedPatient) {
                        ForEach(patients, id: \.self) { Text($0).tag($0) }
                    }
                    .pickerStyle(.menu)
                    .onChange(of: selectedPatient) { _, _ in load() }
                }

                Picker(tr("Test group"), selection: $selectedCategory) {
                    ForEach(availableCategories, id: \.self) { category in
                        let count = category == DashboardEngine.categoryAll
                            ? withData.count
                            : withData.filter { DashboardEngine.categoryOf($0.name) == category }.count
                        Text("\(tr(category)) (\(count))").tag(category)
                    }
                }
                .pickerStyle(.menu)

                Text(tr("Tap any point to open that report"))
                    .font(.caption)
                    .foregroundColor(.secondary)

                ForEach(visibleTrends) { trend in
                    TrendCard(trend: trend)
                }
            }
            .padding(16)
        }
    }

    private func load() {
        isLoading = true
        let allReports = LocalRepository.getAllReports()
        patients = Array(Set(allReports.compactMap { $0.patientName?.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty })).sorted()

        if selectedPatient.isEmpty || !patients.contains(selectedPatient) {
            selectedPatient = patients.first ?? ""
        }

        let scoped = selectedPatient.isEmpty
            ? allReports
            : allReports.filter { $0.patientName?.caseInsensitiveCompare(selectedPatient) == .orderedSame }

        // Standard units are resolved from the patient's FULL history, not the filtered set, so
        // the axis unit doesn't change when a filter happens to exclude the earliest report.
        let units = DashboardEngine.resolveStandardUnits(scoped)
        trends = DashboardEngine.buildHealthSummary(reports: scoped, standardUnits: units).parameterTrends

        if !availableCategories.contains(selectedCategory) {
            selectedCategory = DashboardEngine.categoryAll
        }
        isLoading = false
    }
}

/// One test's chart: the line, its normal-range band where the report printed one, and the latest
/// reading called out above it.
private struct TrendCard: View {
    let trend: ParameterTrend

    private var points: [TrendDataPoint] {
        trend.dataPoints.filter { $0.numericValue != nil }
    }
    private var latest: TrendDataPoint? { points.last }

    /// The band is only drawn when every point agrees on it — a range that shifts between reports
    /// would shade a region that isn't true for the whole line.
    private var band: (low: Float, high: Float)? {
        guard let low = points.first?.refLow, let high = points.first?.refHigh else { return nil }
        let consistent = points.allSatisfy { $0.refLow == low && $0.refHigh == high }
        return consistent ? (low, high) : nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text(trend.name).font(.headline)
                Spacer()
                trendBadge
            }

            if let latest {
                HStack(spacing: 6) {
                    Text(tr("Latest reading"))
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text("\(latest.value) \(latest.unit)")
                        .font(.subheadline.bold())
                        .foregroundColor(statusColor(latest.status))
                    if !latest.context.isEmpty {
                        Text("(\(latest.context))").font(.caption).foregroundColor(.secondary)
                    }
                }
            }

            chart
                .frame(height: 170)

            if points.contains(where: \.converted) {
                // Cross-lab conversion is invisible otherwise, and a reader comparing the chart
                // against the printed report needs to know the figures were restated.
                Text(tr("Some readings were converted to a common unit for comparison."))
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemGroupedBackground)))
    }

    @ViewBuilder
    private var chart: some View {
        Chart {
            if let band {
                RectangleMark(
                    yStart: .value("Normal low", band.low),
                    yEnd: .value("Normal high", band.high)
                )
                .foregroundStyle(Color.green.opacity(0.10))
            }

            ForEach(points) { point in
                if let value = point.numericValue {
                    LineMark(
                        x: .value("Date", point.date),
                        y: .value(trend.name, value)
                    )
                    .foregroundStyle(Color.medicalTeal)
                    .interpolationMethod(.monotone)

                    PointMark(
                        x: .value("Date", point.date),
                        y: .value(trend.name, value)
                    )
                    .foregroundStyle(statusColor(point.status))
                    .symbolSize(point.id == latest?.id ? 90 : 45)
                }
            }
        }
        .chartYAxis { AxisMarks(position: .leading) }
        .chartXAxis {
            // Dates are ISO strings on a categorical axis; showing every one overlaps badly once
            // a patient has more than a handful of reports.
            AxisMarks(values: .automatic(desiredCount: 4)) { value in
                AxisGridLine()
                AxisValueLabel {
                    if let raw = value.as(String.self) { Text(shortDate(raw)).font(.caption2) }
                }
            }
        }
    }

    private var trendBadge: some View {
        let (label, color): (String, Color) = switch trend.trend {
        case "improving": (tr("Improving"), .green)
        case "worsening": (tr("Worsening"), .red)
        case "increasing": (tr("Increasing"), .orange)
        case "decreasing": (tr("Decreasing"), .orange)
        default: (tr("Stable"), .secondary)
        }
        return Text(label)
            .font(.caption2.bold())
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Capsule().fill(color.opacity(0.15)))
            .foregroundColor(color)
    }

    private func statusColor(_ status: String) -> Color {
        switch status.lowercased() {
        case "high": .statusHigh
        case "low": .statusLow
        case "normal": .statusNormal
        default: .statusNeutral
        }
    }

    /// "2026-03-12" → "12 Mar". Keeps the axis readable without a date-parsing round trip.
    private func shortDate(_ iso: String) -> String {
        let parts = iso.split(separator: "-")
        guard parts.count == 3, let month = Int(parts[1]) else { return iso }
        let names = ["Jan", "Feb", "Mar", "Apr", "May", "Jun",
                     "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
        guard (1...12).contains(month) else { return iso }
        return "\(parts[2]) \(names[month - 1])"
    }
}
