import SwiftUI

/// Recommended / upcoming tests — port of `ui/PendingTestsScreen.kt`. Rows are auto-created by
/// the scan pipeline from a report's `recommendedTests`, and can also be added by hand.
struct PendingTestsScreen: View {
    @State private var tests: [PendingTest] = []
    @State private var searchText = ""
    @State private var addingTest = false

    private var pending: [PendingTest] { filtered.filter { $0.status != "Completed" } }
    private var completed: [PendingTest] { filtered.filter { $0.status == "Completed" } }

    private var filtered: [PendingTest] {
        let query = searchText.trimmingCharacters(in: .whitespaces).lowercased()
        guard !query.isEmpty else { return tests }
        return tests.filter {
            $0.testName.lowercased().contains(query) || $0.patientName.lowercased().contains(query)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            if !tests.isEmpty {
                MedicalSearchField(text: $searchText, placeholder: tr("Search tests"))
                    .padding(16)
            }

            if filtered.isEmpty {
                EmptyStateView(
                    icon: "testtube.2",
                    title: tr("No pending tests"),
                    description: tr("Tests your doctor recommends are added here automatically when you scan a report.")
                )
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        if !pending.isEmpty {
                            sectionHeader(tr("Pending Tests"))
                            ForEach(pending) { test in card(test) }
                        }
                        if !completed.isEmpty {
                            sectionHeader(tr("Completed")).padding(.top, 8)
                            ForEach(completed) { test in card(test) }
                        }
                    }
                    .padding(16)
                }
            }
        }
        .medicalScreenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { TopBarTitle(title: tr("Pending Tests")) }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { addingTest = true } label: { Image(systemName: "plus") }
            }
            ToolbarItem(placement: .navigationBarLeading) {
                NavigationLink(value: AppRoute.chat(contextHint: "Pending Tests")) {
                    Image(systemName: "bubble.left.and.bubble.right")
                }
            }
        }
        .onAppear(perform: reload)
        .sheet(isPresented: $addingTest) {
            PendingTestEditSheet { reload() }
        }
    }

    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(.subheadline.bold())
            .foregroundColor(.secondary)
    }

    private func card(_ test: PendingTest) -> some View {
        MedicalCard(padding: 12) {
            HStack(spacing: 12) {
                Button {
                    toggleCompleted(test)
                } label: {
                    Image(systemName: test.status == "Completed" ? "checkmark.circle.fill" : "circle")
                        .foregroundColor(test.status == "Completed" ? .statusNormal : .secondary)
                        .font(.title3)
                }
                .buttonStyle(.plain)

                VStack(alignment: .leading, spacing: 4) {
                    Text(test.testName)
                        .fontWeight(.semibold)
                        .strikethrough(test.status == "Completed")
                        .foregroundColor(test.status == "Completed" ? .secondary : .primary)
                    HStack(spacing: 6) {
                        Text(test.patientName).font(.caption).foregroundColor(.secondary)
                        if let due = test.dueDate, !due.isEmpty {
                            BadgePill(
                                text: "\(tr("Due Date")): \(due)",
                                background: isOverdue(due) ? Color.statusHigh.opacity(0.15) : Color.medicalSurfaceVariant,
                                foreground: isOverdue(due) ? .statusHigh : .secondary,
                                size: 11
                            )
                        }
                    }
                }
                Spacer()
                Button(role: .destructive) {
                    LocalRepository.deletePendingTest(id: test.id)
                    reload()
                } label: {
                    Image(systemName: "trash").foregroundColor(.statusHigh)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func row(_ test: PendingTest) -> some View {
        HStack {
            Button {
                toggleCompleted(test)
            } label: {
                Image(systemName: test.status == "Completed" ? "checkmark.circle.fill" : "circle")
                    .foregroundColor(test.status == "Completed" ? .statusNormal : .secondary)
                    .font(.title3)
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 3) {
                Text(test.testName)
                    .fontWeight(.medium)
                    .strikethrough(test.status == "Completed")
                    .foregroundColor(test.status == "Completed" ? .secondary : .primary)
                HStack(spacing: 6) {
                    Text(test.patientName).font(.caption).foregroundColor(.secondary)
                    if let due = test.dueDate, !due.isEmpty {
                        Text("· due \(due)")
                            .font(.caption)
                            .foregroundColor(isOverdue(due) ? .statusHigh : .secondary)
                    }
                }
            }
            Spacer()
        }
    }

    private func isOverdue(_ dueDate: String) -> Bool {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        guard let due = formatter.date(from: dueDate) else { return false }
        return due < Date()
    }

    private func toggleCompleted(_ test: PendingTest) {
        var updated = test
        updated.status = test.status == "Completed" ? "Pending" : "Completed"
        LocalRepository.savePendingTest(updated)
        reload()
    }

    private func delete(_ offsets: IndexSet, from list: [PendingTest]) {
        for index in offsets { LocalRepository.deletePendingTest(id: list[index].id) }
        reload()
    }

    private func reload() { tests = LocalRepository.getAllPendingTests() }
}

struct PendingTestEditSheet: View {
    let onSaved: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var testName = ""
    @State private var patientName = AppSettings.activePatient ?? ""
    @State private var hasDueDate = false
    @State private var dueDate = Date()

    var body: some View {
        NavigationStack {
            Form {
                TextField(tr("Test Name"), text: $testName)
                TextField(tr("Patient"), text: $patientName)
                Toggle(tr("Set a due date"), isOn: $hasDueDate)
                if hasDueDate {
                    DatePicker(tr("Due Date"), selection: $dueDate, displayedComponents: .date)
                }
            }
            .navigationTitle(tr("Add pending test"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(tr("Cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(tr("Add")) { save() }
                        .disabled(testName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }

    private func save() {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        LocalRepository.savePendingTest(PendingTest(
            id: UUID().uuidString,
            patientName: patientName.trimmingCharacters(in: .whitespaces),
            testName: testName.trimmingCharacters(in: .whitespaces),
            dueDate: hasDueDate ? formatter.string(from: dueDate) : nil,
            status: "Pending",
            resolvedReportId: nil,
            createdAt: ISO8601DateFormatter().string(from: Date())
        ))
        onSaved()
        dismiss()
    }
}
