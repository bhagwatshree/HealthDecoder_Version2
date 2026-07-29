import SwiftUI

/// Medication list with dosage history and edit/delete — port of
/// `ui/MedicationTrackerScreen.kt` + the medication-history half of `ai/DashboardEngine.kt`.
/// Medicines are derived from every stored report (chronologically), so a dosage change across
/// two reports shows as "Changed" and one that stopped appearing shows as "Discontinued".
struct MedicationTrackerScreen: View {
    @State private var history: [MedicationHistory] = []
    @State private var searchText = ""
    @State private var editing: MedicationHistory?

    private var filtered: [MedicationHistory] {
        let query = searchText.trimmingCharacters(in: .whitespaces).lowercased()
        guard !query.isEmpty else { return history }
        return history.filter {
            $0.medicineName.lowercased().contains(query) ||
            $0.patientName.lowercased().contains(query)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            if !history.isEmpty {
                MedicalSearchField(text: $searchText, placeholder: tr("Search medicines"))
                    .padding(16)
            }

            if filtered.isEmpty {
                EmptyStateView(
                    icon: "pills",
                    title: history.isEmpty ? tr("No meds") : tr("No matching reports"),
                    description: history.isEmpty
                        ? tr("Scan a prescription to see medicines here.")
                        : tr("Try searching a different name")
                )
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(filtered) { item in
                            Button { editing = item } label: { card(item) }
                                .buttonStyle(.plain)
                        }
                    }
                    .padding(16)
                }
            }
        }
        .medicalScreenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { TopBarTitle(title: tr("Medication Tracker")) }
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink(value: AppRoute.chat(contextHint: "Medication Tracker")) {
                    Image(systemName: "bubble.left.and.bubble.right")
                }
            }
        }
        .onAppear(perform: reload)
        .sheet(item: $editing) { item in
            MedicationDetailSheet(item: item) { reload() }
        }
    }

    private func card(_ item: MedicationHistory) -> some View {
        MedicalCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    statusBadge(item.status)
                    Spacer()
                    if item.isOptional {
                        BadgePill(
                            text: "SOS",
                            background: Color.medicalSurfaceVariant,
                            foreground: .secondary
                        )
                    }
                }
                Text(item.medicineName).font(.headline)
                let detail = [item.currentDosage, item.currentFrequency]
                    .filter { !$0.isEmpty }.joined(separator: " • ")
                if !detail.isEmpty {
                    Text(detail).font(.subheadline).foregroundColor(.secondary)
                }
                HStack(spacing: 6) {
                    Text(item.patientName).font(.caption).foregroundColor(.secondary)
                    if !item.lastUpdated.isEmpty {
                        Text("· \(item.lastUpdated)").font(.caption).foregroundColor(.secondary)
                    }
                }
                // A dosage change is the thing a patient most needs to notice, so it gets
                // its own line rather than being buried in the detail sheet.
                if !item.previousDosage.isEmpty, item.status == "Changed" {
                    Divider().padding(.vertical, 2)
                    Text("\(tr("Back")): \(item.previousDosage) \(item.previousFrequency)")
                        .font(.caption)
                        .foregroundColor(.statusLow)
                }
            }
        }
    }

    private func statusBadge(_ status: String) -> some View {
        let color: Color
        switch status {
        case "Active": color = .statusNormal
        case "Changed": color = .statusLow
        default: color = .statusNeutral
        }
        return BadgePill(
            text: status.uppercased(),
            background: color.opacity(0.18),
            foreground: color,
            bold: true
        )
    }

    private func reload() {
        history = DashboardEngine.medicationHistory(reports: LocalRepository.getAllReports())
    }
}

/// Per-medicine detail: edit name/dosage/frequency (cascading to every report that carries it,
/// mirroring Android), see the intake log, or delete it everywhere.
struct MedicationDetailSheet: View {
    let item: MedicationHistory
    let onChanged: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var dosage: String
    @State private var frequency: String
    @State private var logs: [MedLogEntry] = []
    @State private var showDeleteConfirm = false

    init(item: MedicationHistory, onChanged: @escaping () -> Void) {
        self.item = item
        self.onChanged = onChanged
        _name = State(initialValue: item.medicineName)
        _dosage = State(initialValue: item.currentDosage)
        _frequency = State(initialValue: item.currentFrequency)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section(tr("Details")) {
                    TextField(tr("Medicine Name"), text: $name)
                    TextField(tr("Dosage"), text: $dosage)
                    TextField(tr("Frequency"), text: $frequency)
                }

                if !item.previousDosage.isEmpty {
                    Section(tr("Back")) {
                        Text("\(item.previousDosage) \(item.previousFrequency)")
                            .foregroundColor(.secondary)
                    }
                }

                Section(tr("Intake history")) {
                    if logs.isEmpty {
                        Text(tr("No doses logged yet.")).font(.footnote).foregroundColor(.secondary)
                    } else {
                        ForEach(logs.prefix(30)) { log in
                            HStack {
                                Image(systemName: log.actionType == "TAKEN" ? "checkmark.circle.fill" : "pencil.circle")
                                    .foregroundColor(log.actionType == "TAKEN" ? .statusNormal : .secondary)
                                VStack(alignment: .leading) {
                                    Text(prettyDate(log.takenAt)).font(.subheadline)
                                    if let slot = log.notes, !slot.isEmpty {
                                        Text(slot).font(.caption).foregroundColor(.secondary)
                                    }
                                }
                            }
                        }
                    }
                }

                Section {
                    Button(tr("Delete from all reports"), role: .destructive) { showDeleteConfirm = true }
                }
            }
            .navigationTitle(item.medicineName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(tr("Cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(tr("Save")) { save() }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .onAppear {
                logs = LocalRepository.medLogs(patientName: item.patientName, medicineName: item.medicineName)
            }
            .alert("Delete \(item.medicineName)?", isPresented: $showDeleteConfirm) {
                Button(tr("Delete"), role: .destructive) {
                    LocalRepository.deleteMedicine(patientName: item.patientName, medicineName: item.medicineName)
                    MedicineScheduleStore.delete(medicineName: item.medicineName, patientName: item.patientName)
                    onChanged()
                    dismiss()
                }
                Button(tr("Cancel"), role: .cancel) {}
            } message: {
                Text("This removes it from every report for \(item.patientName), plus its reminder.")
            }
        }
    }

    private func save() {
        let newName = name.trimmingCharacters(in: .whitespaces)
        LocalRepository.updateMedicine(
            patientName: item.patientName,
            oldName: item.medicineName,
            newName: newName,
            dosage: dosage.trimmingCharacters(in: .whitespaces),
            frequency: frequency.trimmingCharacters(in: .whitespaces)
        )
        // Keep the reminder pointing at the corrected name so it doesn't orphan.
        MedicineScheduleStore.rename(
            patientName: item.patientName,
            oldName: item.medicineName,
            newName: newName,
            dosage: dosage,
            frequency: frequency
        )
        onChanged()
        dismiss()
    }

    private func prettyDate(_ iso: String) -> String {
        guard let date = ISO8601DateFormatter().date(from: iso) else { return iso }
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}
