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
        Group {
            if tests.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "testtube.2").font(.system(size: 40)).foregroundColor(.secondary)
                    Text(tr("No pending tests")).foregroundColor(.secondary)
                    Text(tr("Tests your doctor recommends are added here automatically when you scan a report."))
                        .font(.footnote).foregroundColor(.secondary)
                        .multilineTextAlignment(.center).padding(.horizontal, 32)
                }
            } else {
                List {
                    if !pending.isEmpty {
                        Section(tr("Pending Tests")) {
                            ForEach(pending) { test in row(test) }
                                .onDelete { delete($0, from: pending) }
                        }
                    }
                    if !completed.isEmpty {
                        Section(tr("Completed")) {
                            ForEach(completed) { test in row(test) }
                                .onDelete { delete($0, from: completed) }
                        }
                    }
                }
                .searchable(text: $searchText, prompt: Text(tr("Search tests")))
            }
        }
        .navigationTitle(tr("Pending Tests"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
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
