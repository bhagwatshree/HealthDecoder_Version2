import SwiftUI

/// View/edit/delete a single report, plus "Analyze Now" for upload-only reports — mirrors the
/// Phase-1-relevant subset of `ui/ReportDetailScreen.kt`. Takes an id (not a `MedicalReport`
/// directly) so it always reflects the current on-disk state, including right after an edit or
/// a reprocess.
struct ReportDetailScreen: View {
    let reportId: String
    @Environment(\.dismiss) private var dismiss

    @State private var report: MedicalReport?
    @State private var isEditing = false
    @State private var editedPatientName = ""
    @State private var editedReportDate = ""
    @State private var editedReportType = ""
    @State private var editedComments = ""

    @State private var isBusy = false
    @State private var busyStatus = ""
    @State private var errorMessage: String?
    @State private var showDeleteConfirm = false

    var body: some View {
        Group {
            if let report {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        if isEditing {
                            editForm
                        } else {
                            readOnlyContent(report)
                        }
                        if let errorMessage {
                            Text(errorMessage).foregroundColor(.red).font(.footnote)
                        }
                    }
                    .padding()
                }
            } else {
                ProgressView()
            }
        }
                .toolbar {
            ToolbarItem(placement: .principal) { TopBarTitle(title: tr("Report Details")) }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                if report != nil && !isBusy {
                    Menu {
                        Button(isEditing ? "Cancel Edit" : "Edit") { toggleEdit() }
                        if isEditing {
                            Button(tr("Save Changes")) { saveEdits() }
                        }
                        Button(tr("Delete"), role: .destructive) { showDeleteConfirm = true }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
        }
        .onAppear(perform: load)
        .alert("Delete this report?", isPresented: $showDeleteConfirm) {
            Button(tr("Delete"), role: .destructive) { delete() }
            Button(tr("Cancel"), role: .cancel) {}
        } message: {
            Text(tr("This can't be undone."))
        }
    }

    // MARK: - Read-only content

    @ViewBuilder
    private func readOnlyContent(_ report: MedicalReport) -> some View {
        if let firstPath = report.imagePaths.first, let image = LocalRepository.loadImage(relativePath: firstPath) {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .frame(maxHeight: 240)
                .clipShape(RoundedRectangle(cornerRadius: 10))
        }

        Text(report.patientName?.isEmpty == false ? report.patientName! : "Unknown Patient")
            .font(.title2.bold())

        Text([report.reportType, report.reportDate].compactMap { $0 }.joined(separator: " • "))
            .font(.subheadline)
            .foregroundColor(.secondary)

        if let comments = report.comments, !comments.isEmpty {
            Divider()
            Text(tr("Details")).font(.headline)
            Text(comments)
        }

        if !report.medications.isEmpty {
            Divider()
            Text(tr("Medications")).font(.headline)
            ForEach(Array(report.medications.enumerated()), id: \.offset) { _, med in
                VStack(alignment: .leading, spacing: 2) {
                    Text(med.name).fontWeight(.semibold)
                    if !med.dosage.isEmpty || !med.frequency.isEmpty {
                        Text([med.dosage, med.frequency].filter { !$0.isEmpty }.joined(separator: " • "))
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    }
                }
            }
        }

        if report.analyzed {
            if let results = report.testResults, !results.parameters.isEmpty {
                Divider()
                Text(tr("Details")).font(.headline)
                ForEach(Array(results.parameters.enumerated()), id: \.offset) { _, param in
                    HStack {
                        Text(param.name)
                        Spacer()
                        Text("\(param.value) \(param.unit)")
                            .fontWeight(.bold)
                            .foregroundColor(color(for: param.status))
                    }
                }
            }
            if let results = report.testResults, !results.findings.isEmpty {
                Divider()
                Text(tr("Clinical Insights")).font(.headline)
                ForEach(Array(results.findings.enumerated()), id: \.offset) { _, finding in
                    Text("• \(finding)")
                }
            }
        } else {
            Divider()
            Text(tr("This report was uploaded without analysis."))
                .italic()
                .foregroundColor(.secondary)
            if isBusy {
                ProgressView(busyStatus.isEmpty ? "Analyzing…" : busyStatus)
            } else {
                Button(tr("Analyze")) { Task { await analyzeNow() } }
                    .buttonStyle(.borderedProminent)
            }
        }

        if let extractedText = report.extractedText, !extractedText.isEmpty {
            Divider()
            Text(tr("Raw Text")).font(.headline)
            Text(extractedText).font(.footnote).foregroundColor(.secondary)
        }
    }

    private func color(for status: String?) -> Color {
        switch status?.lowercased() {
        case "high": return .statusHigh
        case "low": return .statusLow
        default: return .primary
        }
    }

    // MARK: - Edit form

    @ViewBuilder
    private var editForm: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(tr("Patient Name")).font(.caption).foregroundColor(.secondary)
            TextField(tr("Patient Name"), text: $editedPatientName).textFieldStyle(.roundedBorder)

            Text(tr("Report Date (YYYY-MM-DD)")).font(.caption).foregroundColor(.secondary)
            TextField(tr("YYYY-MM-DD"), text: $editedReportDate).textFieldStyle(.roundedBorder)

            Text(tr("Report Category")).font(.caption).foregroundColor(.secondary)
            TextField(tr("Report Category"), text: $editedReportType).textFieldStyle(.roundedBorder)

            Text(tr("Details")).font(.caption).foregroundColor(.secondary)
            TextEditor(text: $editedComments)
                .frame(height: 100)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(Color(.separator)))

            Button(tr("Save Changes")) { saveEdits() }
                .buttonStyle(.borderedProminent)
        }
    }

    private func toggleEdit() {
        guard let report else { return }
        if !isEditing {
            editedPatientName = report.patientName ?? ""
            editedReportDate = report.reportDate ?? ""
            editedReportType = report.reportType ?? ""
            editedComments = report.comments ?? ""
        }
        isEditing.toggle()
    }

    private func saveEdits() {
        guard var report else { return }
        report.patientName = editedPatientName.trimmingCharacters(in: .whitespaces)
        report.reportDate = editedReportDate.trimmingCharacters(in: .whitespaces)
        report.reportType = editedReportType.trimmingCharacters(in: .whitespaces)
        report.comments = editedComments
        LocalRepository.saveReport(report)
        self.report = report
        isEditing = false
    }

    // MARK: - Load / delete / reprocess

    private func load() {
        report = LocalRepository.getReport(id: reportId)
    }

    private func delete() {
        LocalRepository.deleteReport(id: reportId)
        dismiss()
    }

    private func analyzeNow() async {
        guard let report else { return }
        errorMessage = nil
        isBusy = true
        defer { isBusy = false }

        let images: [(data: Data, mimeType: String)] = report.imagePaths.compactMap { path in
            guard let image = LocalRepository.loadImage(relativePath: path),
                  let data = ImageUtil.compressedJPEG(from: image) else { return nil }
            return (data, "image/jpeg")
        }
        guard !images.isEmpty else {
            errorMessage = "No stored image to analyze."
            return
        }

        busyStatus = "Asking Gemini to extract details…"
        let scanType = report.reportType?.lowercased().contains("prescription") == true ? "prescription" : "report"
        let extraction = await OcrEngine.scan(
            images: images, localOcrText: "", scanType: scanType,
            reportCategory: report.reportCategory ?? "General"
        )
        guard let section = extraction.reports.first else {
            errorMessage = "Could not extract anything from the stored image."
            return
        }

        var updated = report
        updated.patientName = section.patientName ?? extraction.patientName ?? report.patientName
        updated.reportDate = section.reportDate ?? report.reportDate
        updated.reportType = section.reportType ?? report.reportType
        updated.extractedText = section.rawText
        updated.comments = section.comments
        updated.medications = section.medications
        updated.testResults = section.testResults
        updated.reportCategory = section.reportName ?? report.reportCategory
        updated.analyzed = true
        LocalRepository.saveReport(updated)
        self.report = updated
    }
}
