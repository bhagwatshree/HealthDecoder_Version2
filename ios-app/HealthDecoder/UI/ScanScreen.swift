import SwiftUI
import PhotosUI

/// Camera / photo-library capture → on-device Gemini vision OCR → saved local report. Mirrors
/// `ui/ScanScreen.kt`'s Phase-1 subset: Camera and From Device are implemented; QR scanning and
/// the "Found in email" queue land in later phases.
struct ScanScreen: View {
    @Environment(\.dismiss) private var dismiss

    @State private var pages: [UIImage] = []
    @State private var scanType = "report" // "report" | "prescription"
    /// Chosen patient for this scan; empty = auto-detect from the report (matches Android).
    @State private var chosenPatient = ""
    @State private var isProcessing = false
    @State private var processingStatus = ""
    @State private var errorMessage: String?
    @State private var successMessage = ""
    @State private var showSuccessAlert = false
    @State private var showCamera = false
    @State private var showPhotoPicker = false

    private var cameraAvailable: Bool {
        UIImagePickerController.isSourceTypeAvailable(.camera)
    }

    var body: some View {
        VStack(spacing: 16) {
            Picker(tr("Document type"), selection: $scanType) {
                Text(tr("Lab Report / Diagnostic")).tag("report")
                Text(tr("Prescription")).tag("prescription")
            }
            .pickerStyle(.segmented)

            ScanPatientPicker(value: $chosenPatient)

            if pages.isEmpty {
                ContentUnavailableLike(text: "No pages added yet")
            } else {
                ScrollView(.horizontal) {
                    HStack(spacing: 10) {
                        ForEach(Array(pages.enumerated()), id: \.offset) { index, image in
                            ZStack(alignment: .topTrailing) {
                                Image(uiImage: image)
                                    .resizable()
                                    .scaledToFill()
                                    .frame(width: 90, height: 120)
                                    .clipShape(RoundedRectangle(cornerRadius: 8))
                                Button {
                                    pages.remove(at: index)
                                } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .foregroundColor(.white)
                                        .background(Circle().fill(Color.black.opacity(0.6)))
                                }
                                .padding(4)
                            }
                        }
                    }
                    .padding(.horizontal)
                }
                .frame(height: 130)
            }

            VStack(spacing: 12) {
                Button {
                    showCamera = true
                } label: {
                    Label(tr("Camera"), systemImage: "camera.fill")
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(cameraAvailable ? Color.medicalTeal : Color.gray)
                        .foregroundColor(.white)
                        .cornerRadius(10)
                }
                .disabled(!cameraAvailable || isProcessing)

                Button {
                    showPhotoPicker = true
                } label: {
                    Label(tr("From Device"), systemImage: "photo.on.rectangle")
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.medicalNavy)
                        .foregroundColor(.white)
                        .cornerRadius(10)
                }
                .disabled(isProcessing)
            }

            if let errorMessage {
                Text(errorMessage).foregroundColor(.red).font(.footnote).multilineTextAlignment(.center)
            }

            Spacer()

            if isProcessing {
                ProgressView(processingStatus.isEmpty ? "Working…" : processingStatus)
                    .frame(maxWidth: .infinity)
            } else {
                VStack(spacing: 10) {
                    Button {
                        Task { await processScan(uploadOnly: false) }
                    } label: {
                        Text(tr("Analyze")).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(pages.isEmpty)

                    Button {
                        Task { await processScan(uploadOnly: true) }
                    } label: {
                        Text(tr("Upload Only (No Scan)")).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(pages.isEmpty)
                }
            }
        }
        .padding()
        .navigationTitle(tr("Scan Report"))
        .navigationBarTitleDisplayMode(.inline)
        .fullScreenCover(isPresented: $showCamera) {
            CameraPicker(isPresented: $showCamera) { image in
                pages.append(image)
            }
            .ignoresSafeArea()
        }
        .sheet(isPresented: $showPhotoPicker) {
            PhotoLibraryPicker(isPresented: $showPhotoPicker) { images in
                pages.append(contentsOf: images)
            }
        }
        .alert("Saved", isPresented: $showSuccessAlert) {
            Button(tr("OK")) { dismiss() }
        } message: {
            Text(successMessage)
        }
    }

    private func processScan(uploadOnly: Bool) async {
        errorMessage = nil
        isProcessing = true
        defer { isProcessing = false }

        let jpegs = pages.compactMap { ImageUtil.compressedJPEG(from: $0) }
        guard !jpegs.isEmpty else {
            errorMessage = "Add at least one page first."
            return
        }

        let hashes = jpegs.map { LocalRepository.sha256Hex($0) }
        if let existing = LocalRepository.findReportByAnyHash(hashes) {
            errorMessage = "This looks like a duplicate of an existing report (\(existing.patientName ?? existing.reportType ?? "Unknown"))."
            return
        }

        // Page images are saved once and shared across every sibling report a multi-report
        // scan produces, mirroring LocalStore's shared-files-per-scan layout.
        let scanId = UUID().uuidString
        var imagePaths: [String] = []
        for (index, jpeg) in jpegs.enumerated() {
            imagePaths.append(LocalRepository.saveImage(jpeg, reportId: scanId, pageIndex: index))
        }
        let createdAt = ISO8601DateFormatter().string(from: Date())

        if uploadOnly {
            let report = MedicalReport(
                id: UUID().uuidString,
                patientName: chosenPatient.isEmpty ? AppSettings.activePatient : chosenPatient,
                reportDate: nil,
                reportType: scanType == "prescription" ? "Prescription" : "Uploaded Document",
                extractedText: nil,
                comments: nil,
                imagePath: imagePaths.first ?? "",
                imagePaths: imagePaths,
                createdAt: createdAt,
                reportCategory: nil,
                pageHashes: hashes,
                userEmail: AppSettings.userEmail,
                analyzed: false
            )
            LocalRepository.saveReport(report)
            successMessage = "Saved without analysis. Open it from Records any time to Analyze Now."
            showSuccessAlert = true
            pages = []
            return
        }

        processingStatus = "Reading document…"
        let hint = await TextRecognizer.recognize(pages.first)

        processingStatus = "Asking Gemini to extract details…"
        let extraction = await OcrEngine.scan(
            images: jpegs.map { ($0, "image/jpeg") },
            localOcrText: hint,
            scanType: scanType,
            reportCategory: scanType == "prescription" ? "Prescription" : "General"
        )

        guard !extraction.reports.isEmpty else {
            errorMessage = "Could not extract anything from this scan. Try again, or use Upload Only to save it and analyze later."
            return
        }

        for section in extraction.reports {
            let report = MedicalReport(
                id: UUID().uuidString,
                // An explicitly chosen patient overrides what the AI read off the page —
                // that's the point of picking one before scanning.
                patientName: chosenPatient.isEmpty
                    ? (section.patientName ?? extraction.patientName ?? AppSettings.activePatient)
                    : chosenPatient,
                reportDate: section.reportDate,
                reportType: section.reportType ?? (scanType == "prescription" ? "Prescription" : "Lab Report"),
                extractedText: section.rawText,
                comments: section.comments,
                medications: section.medications,
                imagePath: imagePaths.first ?? "",
                imagePaths: imagePaths,
                createdAt: createdAt,
                testResults: section.testResults,
                reportCategory: section.reportName,
                pageHashes: hashes,
                userEmail: AppSettings.userEmail,
                analyzed: true
            )
            LocalRepository.saveReport(report)

            // Mirrors Android's post-save side effects: recommended tests become Pending Tests
            // rows, and each prescribed medicine gets a default reminder schedule (disabled
            // slots until the user turns them on) so it shows up in the Reminders screen.
            for recommended in section.recommendedTests
            where !recommended.testName.trimmingCharacters(in: .whitespaces).isEmpty {
                LocalRepository.savePendingTest(PendingTest(
                    id: UUID().uuidString,
                    patientName: report.patientName ?? "",
                    testName: recommended.testName,
                    dueDate: recommended.dueDate,
                    status: "Pending",
                    resolvedReportId: nil,
                    createdAt: createdAt
                ))
            }
            for medication in section.medications
            where !medication.name.trimmingCharacters(in: .whitespaces).isEmpty {
                MedicineScheduleStore.autoSeedIfAbsent(
                    medicineName: medication.name,
                    patientName: report.patientName ?? "",
                    dosage: medication.dosage,
                    frequency: medication.frequency,
                    activeSlots: Self.slotsFor(frequency: medication.frequency)
                )
            }
        }

        let count = extraction.reports.count
        successMessage = count == 1 ? "Report saved." : "\(count) reports saved from this scan."
        showSuccessAlert = true
        pages = []
    }
}

extension ScanScreen {
    /// Best-effort mapping from a printed frequency ("1-0-1", "twice daily", "at night") to the
    /// time-of-day slots a reminder should default to.
    static func slotsFor(frequency: String) -> [String] {
        let f = frequency.lowercased()

        // Indian prescriptions commonly write dosing as morning-afternoon-night digits.
        let digits = f.filter { $0.isNumber || $0 == "-" }
        if digits.contains("-") {
            let parts = digits.split(separator: "-").map { Int($0) ?? 0 }
            if parts.count == 3 {
                var slots: [String] = []
                if parts[0] > 0 { slots.append("Morning") }
                if parts[1] > 0 { slots.append("Afternoon") }
                if parts[2] > 0 { slots.append("Night") }
                if !slots.isEmpty { return slots }
            }
        }

        if f.contains("four") || f.contains("qid") || f.contains("4 time") {
            return ["Morning", "Afternoon", "Evening", "Night"]
        }
        if f.contains("thrice") || f.contains("three") || f.contains("tid") || f.contains("3 time") {
            return ["Morning", "Afternoon", "Night"]
        }
        if f.contains("twice") || f.contains("bid") || f.contains("bd") || f.contains("2 time") {
            return ["Morning", "Night"]
        }
        if f.contains("night") || f.contains("bedtime") || f.contains("hs") { return ["Night"] }
        if f.contains("evening") { return ["Evening"] }
        if f.contains("afternoon") || f.contains("lunch") { return ["Afternoon"] }
        return ["Morning"] // once daily / unspecified
    }
}

private struct ContentUnavailableLike: View {
    let text: String
    var body: some View {
        VStack {
            Image(systemName: "doc.text.image")
                .font(.system(size: 36))
                .foregroundColor(.secondary)
            Text(text).foregroundColor(.secondary)
        }
        .frame(height: 130)
    }
}

// MARK: - UIKit picker bridges

private struct CameraPicker: UIViewControllerRepresentable {
    @Binding var isPresented: Bool
    var onCapture: (UIImage) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            if let image = info[.originalImage] as? UIImage {
                parent.onCapture(image)
            }
            parent.isPresented = false
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.isPresented = false
        }
    }
}

private struct PhotoLibraryPicker: UIViewControllerRepresentable {
    @Binding var isPresented: Bool
    var onPick: ([UIImage]) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.filter = .images
        config.selectionLimit = 0
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let parent: PhotoLibraryPicker
        init(_ parent: PhotoLibraryPicker) { self.parent = parent }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            parent.isPresented = false
            let providers = results.map(\.itemProvider).filter { $0.canLoadObject(ofClass: UIImage.self) }
            guard !providers.isEmpty else { return }

            var images: [UIImage] = []
            let group = DispatchGroup()
            for provider in providers {
                group.enter()
                provider.loadObject(ofClass: UIImage.self) { object, _ in
                    if let image = object as? UIImage { images.append(image) }
                    group.leave()
                }
            }
            group.notify(queue: .main) { [onPick = parent.onPick] in
                onPick(images)
            }
        }
    }
}
