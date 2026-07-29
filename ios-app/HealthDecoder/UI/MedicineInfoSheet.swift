import SwiftUI

/// A sheet showing medicine info (category, basic use, key notes) and letting the user edit the
/// name and save corrections for OCR misreads — mirrors `ui/MedicineInfoSheet.kt`.
struct MedicineInfoSheet: View {
    let medicineName: String
    var reportId: String? = nil
    var onNameCorrected: ((_ oldName: String, _ newName: String) -> Void)? = nil

    @State private var editableName: String
    @State private var currentLookupName: String
    @State private var info: MedicineInfo?
    @State private var isLoading = true
    @State private var isSaving = false
    @State private var saveSuccess: Bool?

    init(medicineName: String, reportId: String? = nil, onNameCorrected: ((String, String) -> Void)? = nil) {
        self.medicineName = medicineName
        self.reportId = reportId
        self.onNameCorrected = onNameCorrected
        _editableName = State(initialValue: medicineName)
        _currentLookupName = State(initialValue: medicineName)
    }

    private var trimmedEditableName: String { editableName.trimmingCharacters(in: .whitespaces) }

    private var nameChanged: Bool {
        trimmedEditableName != medicineName.trimmingCharacters(in: .whitespaces) &&
        trimmedEditableName != currentLookupName.trimmingCharacters(in: .whitespaces)
    }

    private var canSaveName: Bool {
        trimmedEditableName != medicineName.trimmingCharacters(in: .whitespaces) &&
        !trimmedEditableName.isEmpty && reportId != nil
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Medicine Reference").font(.title2.bold())

                TextField("Medicine Name", text: $editableName)
                    .textFieldStyle(.roundedBorder)

                if nameChanged {
                    Button {
                        Task { await lookup(name: trimmedEditableName) }
                    } label: {
                        Label("Look Up \u{201c}\(trimmedEditableName)\u{201d}", systemImage: "magnifyingglass")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }

                if isLoading {
                    HStack {
                        Spacer()
                        VStack(spacing: 12) {
                            ProgressView()
                            Text("Looking up \(currentLookupName)…").foregroundColor(.secondary)
                        }
                        Spacer()
                    }
                    .frame(height: 160)
                } else if let info {
                    infoContent(info)
                }
            }
            .padding()
        }
        .task { await lookup(name: medicineName) }
        .presentationDragIndicator(.visible)
    }

    @ViewBuilder
    private func infoContent(_ info: MedicineInfo) -> some View {
        let s = MedicineCategoryStyle.style(for: info.category)

        HStack(spacing: 14) {
            Image(systemName: s.icon)
                .font(.system(size: 26))
                .foregroundColor(s.color)
                .frame(width: 52, height: 52)
                .background(Circle().fill(s.color.opacity(0.15)))
            VStack(alignment: .leading, spacing: 2) {
                Text(s.label).font(.headline).foregroundColor(s.color)
                if !info.genericName.isEmpty {
                    Text(info.genericName).font(.subheadline).foregroundColor(.secondary)
                }
            }
            Spacer()
        }
        .padding(16)
        .background(s.color.opacity(0.1))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(s.color.opacity(0.3)))
        .cornerRadius(16)

        VStack(alignment: .leading, spacing: 8) {
            Label("Why It's Prescribed", systemImage: "info.circle")
                .font(.headline)
                .foregroundColor(.accentColor)
            Text(info.basicUse)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.accentColor.opacity(0.1))
        .cornerRadius(16)

        if !info.keyNotes.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Label("Key Notes", systemImage: "lightbulb")
                    .font(.headline)
                    .foregroundColor(.orange)
                ForEach(info.keyNotes, id: \.self) { note in
                    HStack(alignment: .top, spacing: 8) {
                        Circle().fill(Color.accentColor).frame(width: 6, height: 6).padding(.top, 7)
                        Text(note)
                    }
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground))
            .cornerRadius(16)
        }

        if canSaveName {
            VStack(alignment: .leading, spacing: 10) {
                Label("Name Correction", systemImage: "pencil")
                    .font(.subheadline.bold())
                    .foregroundColor(.orange)
                Text("The name \u{201c}\(medicineName)\u{201d} will be updated to \u{201c}\(trimmedEditableName)\u{201d} in this report.")
                    .font(.footnote)
                if saveSuccess == true {
                    Label("Name saved!", systemImage: "checkmark.circle.fill").foregroundColor(.green)
                } else {
                    Button {
                        Task { await saveCorrectedName() }
                    } label: {
                        if isSaving { ProgressView() } else { Text("Save Corrected Name").frame(maxWidth: .infinity) }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.orange)
                    .disabled(isSaving)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.yellow.opacity(0.15))
            .cornerRadius(16)
        }

        Text("\u{2139}\u{fe0f} AI-generated reference. Always consult your doctor for medical advice.")
            .font(.caption2)
            .foregroundColor(.secondary)
            .frame(maxWidth: .infinity, alignment: .center)
    }

    private func lookup(name: String) async {
        currentLookupName = name
        isLoading = true
        info = await MedicalEngine.lookupMedicineInfo(name: name)
        isLoading = false
    }

    private func saveCorrectedName() async {
        guard let reportId else { return }
        isSaving = true
        let ok = LocalRepository.renameMedicine(reportId: reportId, oldName: medicineName, newName: trimmedEditableName)
        isSaving = false
        saveSuccess = ok
        if ok { onNameCorrected?(medicineName, trimmedEditableName) }
    }
}

struct MedicineCategoryStyle {
    let label: String
    let icon: String
    let color: Color

    private static let styles: [String: MedicineCategoryStyle] = [
        "antibiotic": .init(label: "🧬 Antibiotic", icon: "cross.case", color: Color(red: 0.08, green: 0.4, blue: 0.75)),
        "antacid": .init(label: "🩹 Antacid", icon: "bandage", color: Color(red: 0, green: 0.51, blue: 0.56)),
        "painkiller": .init(label: "💊 Painkiller", icon: "pills", color: Color(red: 0.85, green: 0.27, blue: 0.08)),
        "anti-inflammatory": .init(label: "🔥 Anti-inflammatory", icon: "flame", color: Color(red: 0.94, green: 0.42, blue: 0)),
        "vitamin/supplement": .init(label: "💪 Vitamin/Supplement", icon: "figure.strengthtraining.traditional", color: Color(red: 0.18, green: 0.49, blue: 0.2)),
        "antidiabetic": .init(label: "🩸 Antidiabetic", icon: "drop", color: Color(red: 0.42, green: 0.11, blue: 0.6)),
        "antihypertensive": .init(label: "❤️ Antihypertensive", icon: "heart", color: Color(red: 0.78, green: 0.16, blue: 0.16)),
        "antihistamine": .init(label: "🤧 Antihistamine", icon: "snowflake", color: Color(red: 0.01, green: 0.47, blue: 0.75)),
        "steroid": .init(label: "⚡ Steroid", icon: "bolt", color: Color(red: 0.98, green: 0.66, blue: 0.15)),
        "antifungal": .init(label: "🛡️ Antifungal", icon: "shield", color: Color(red: 0.31, green: 0.2, blue: 0.18)),
        "antiviral": .init(label: "🦠 Antiviral", icon: "ant", color: Color(red: 0.16, green: 0.21, blue: 0.58)),
        "bronchodilator": .init(label: "🫁 Bronchodilator", icon: "wind", color: Color(red: 0, green: 0.41, blue: 0.36)),
        "laxative": .init(label: "🌿 Laxative", icon: "leaf", color: Color(red: 0.34, green: 0.55, blue: 0.18)),
        "probiotic": .init(label: "🦠 Probiotic", icon: "leaf.fill", color: Color(red: 0.11, green: 0.37, blue: 0.13)),
        "cardiac": .init(label: "❤️ Cardiac", icon: "heart.fill", color: Color(red: 0.72, green: 0.11, blue: 0.11)),
        "antipyretic": .init(label: "🌡️ Antipyretic", icon: "thermometer", color: Color(red: 0.9, green: 0.31, blue: 0)),
        "muscle relaxant": .init(label: "🏋️ Muscle Relaxant", icon: "figure.strengthtraining.functional", color: Color(red: 0.27, green: 0.15, blue: 0.63)),
        "antidepressant": .init(label: "🧠 Antidepressant", icon: "brain.head.profile", color: Color(red: 0, green: 0.51, blue: 0.56)),
        "other": .init(label: "💊 Medicine", icon: "pills.fill", color: Color(red: 0.33, green: 0.43, blue: 0.48)),
        "unknown": .init(label: "❓ Unknown", icon: "questionmark.circle", color: Color(red: 0.47, green: 0.56, blue: 0.61))
    ]

    static func style(for category: String) -> MedicineCategoryStyle {
        styles[category.trimmingCharacters(in: .whitespaces).lowercased()] ?? styles["other"]!
    }
}
