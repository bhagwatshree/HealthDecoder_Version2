import SwiftUI

/// Port of `ui/FamilyUi.kt` — family member management (add/edit/remove) plus the Scan screen's
/// patient picker.

private let avatarChoices = ["👤", "🧑", "👩", "👨", "👵", "👴", "🧒", "👧", "👦", "🧓"]
private let sexChoices = ["Male", "Female", "Other"]

/// Whole years from a YYYY-MM-DD date of birth, or nil if unparseable/blank/future.
func familyAge(_ dob: String) -> Int? {
    let parts = dob.trimmingCharacters(in: .whitespaces).split(separator: "-")
    guard parts.count == 3,
          let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]) else { return nil }
    var components = DateComponents()
    components.year = y; components.month = m; components.day = d
    guard let birth = Calendar.current.date(from: components), birth <= Date() else { return nil }
    guard let age = Calendar.current.dateComponents([.year], from: birth, to: Date()).year else { return nil }
    return (0...130).contains(age) ? age : nil
}

/// One-line "Father • M • 58y" style summary for a profile.
func familySubtitle(_ p: FamilyProfile) -> String {
    var bits: [String] = []
    if !p.relation.isEmpty { bits.append(p.relation) }
    if let first = p.sex.first { bits.append(String(first).uppercased()) }
    if let age = familyAge(p.dateOfBirth) { bits.append("\(age)y") }
    return bits.joined(separator: " • ")
}

/// Lists family members with add/edit/remove. Removing is only offered for people with no
/// records (rename/merge is the tool for a mis-scanned duplicate).
struct FamilyManagerSheet: View {
    let onChanged: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var members: [FamilyProfile] = []
    @State private var editing: FamilyProfile?
    @State private var adding = false
    @State private var blockedDeleteName: String?

    var body: some View {
        NavigationStack {
            List {
                ForEach(members) { member in
                    HStack(spacing: 10) {
                        Text(member.avatarEmoji).font(.system(size: 22))
                        VStack(alignment: .leading) {
                            Text(member.name).fontWeight(.bold)
                            let subtitle = familySubtitle(member)
                            if !subtitle.isEmpty {
                                Text(subtitle).font(.caption).foregroundColor(.secondary)
                            }
                        }
                        Spacer()
                        Button { editing = member } label: { Image(systemName: "pencil") }
                            .buttonStyle(.borderless)
                        Button(role: .destructive) { attemptDelete(member) } label: {
                            Image(systemName: "trash")
                        }
                        .buttonStyle(.borderless)
                    }
                }
                if members.isEmpty {
                    Text(tr("No family members yet. Add one to scope records per person."))
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
            }
            .navigationTitle(tr("Family / Patients"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(tr("DONE")) { dismiss() }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { adding = true } label: { Label(tr("Add"), systemImage: "plus") }
                }
            }
            .onAppear(perform: reload)
            .sheet(isPresented: $adding) {
                FamilyEditSheet(existing: nil) { reload(); onChanged() }
            }
            .sheet(item: $editing) { member in
                FamilyEditSheet(existing: member) { reload(); onChanged() }
            }
            .alert("Can't remove this person", isPresented: Binding(
                get: { blockedDeleteName != nil },
                set: { if !$0 { blockedDeleteName = nil } }
            )) {
                Button(tr("OK"), role: .cancel) {}
            } message: {
                Text("\(blockedDeleteName ?? "This person") still has saved reports. Rename them instead, or delete their reports first.")
            }
        }
    }

    private func reload() { members = AppSettings.familyProfiles }

    private func attemptDelete(_ member: FamilyProfile) {
        if LocalRepository.reportCount(forPatient: member.name) > 0 {
            blockedDeleteName = member.name
        } else {
            AppSettings.familyProfiles.removeAll { $0.id == member.id }
            reload()
            onChanged()
        }
    }
}

/// Add or edit one family member. `existing == nil` = add mode.
struct FamilyEditSheet: View {
    let existing: FamilyProfile?
    let onSaved: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var relation: String
    @State private var sex: String
    @State private var dob: String
    @State private var emoji: String
    @State private var errorMessage: String?

    init(existing: FamilyProfile?, onSaved: @escaping () -> Void) {
        self.existing = existing
        self.onSaved = onSaved
        _name = State(initialValue: existing?.name ?? "")
        _relation = State(initialValue: existing?.relation ?? "")
        _sex = State(initialValue: existing?.sex ?? "")
        _dob = State(initialValue: existing?.dateOfBirth ?? "")
        _emoji = State(initialValue: existing?.avatarEmoji ?? "👤")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section(tr("Avatar")) {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(avatarChoices, id: \.self) { choice in
                                Text(choice)
                                    .font(.system(size: 20))
                                    .frame(width: 40, height: 40)
                                    .background(emoji == choice ? Color.medicalSurfaceVariant : Color(.secondarySystemBackground))
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 10)
                                            .stroke(Color.medicalTeal, lineWidth: emoji == choice ? 2 : 0)
                                    )
                                    .clipShape(RoundedRectangle(cornerRadius: 10))
                                    .onTapGesture { emoji = choice }
                            }
                        }
                    }
                }
                Section {
                    TextField(tr("Name"), text: $name)
                    TextField(tr("Relation (e.g. Father, Self)"), text: $relation)
                }
                Section(tr("Gender")) {
                    Picker(tr("Gender"), selection: $sex) {
                        Text("—").tag("")
                        ForEach(sexChoices, id: \.self) { Text($0).tag($0) }
                    }
                    .pickerStyle(.segmented)
                }
                Section {
                    TextField(tr("Birthdate (YYYY-MM-DD)"), text: $dob)
                        .keyboardType(.numbersAndPunctuation)
                    if let age = familyAge(dob) {
                        Text("Age: \(age)").font(.caption).foregroundColor(.secondary)
                    }
                }
                if let errorMessage {
                    Text(errorMessage).foregroundColor(.red).font(.footnote)
                }
            }
            .navigationTitle(existing == nil ? "Add family member" : "Edit \(existing!.name)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(tr("Cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(existing == nil ? "Add" : "Save") { save() }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }

    private func save() {
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        var profiles = AppSettings.familyProfiles

        // Name is the join key to reports, so it must stay unique across members.
        let clashes = profiles.contains {
            $0.id != existing?.id && $0.name.caseInsensitiveCompare(trimmedName) == .orderedSame
        }
        if clashes {
            errorMessage = "A member with that name already exists."
            return
        }

        if let existing {
            if let index = profiles.firstIndex(where: { $0.id == existing.id }) {
                // Renaming cascades to that person's stored records, mirroring Android's
                // LocalRepository.updateFamilyMember.
                if existing.name != trimmedName {
                    LocalRepository.renamePatient(from: existing.name, to: trimmedName)
                }
                profiles[index] = FamilyProfile(
                    id: existing.id, name: trimmedName, relation: relation, avatarEmoji: emoji,
                    sex: sex, dateOfBirth: dob.trimmingCharacters(in: .whitespaces)
                )
            }
        } else {
            profiles.append(FamilyProfile(
                id: UUID().uuidString, name: trimmedName, relation: relation, avatarEmoji: emoji,
                sex: sex, dateOfBirth: dob.trimmingCharacters(in: .whitespaces)
            ))
        }
        AppSettings.familyProfiles = profiles
        onSaved()
        dismiss()
    }
}

/// Patient selector for the Scan screen: pick an existing family member, auto-detect, or a new
/// person. `value` is the chosen patient name; empty = auto-detect.
struct ScanPatientPicker: View {
    @Binding var value: String
    @State private var members: [FamilyProfile] = []
    @State private var manualEntry = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Menu {
                Button(tr("Auto-detect from report")) { manualEntry = false; value = "" }
                ForEach(members) { member in
                    Button {
                        manualEntry = false
                        value = member.name
                    } label: {
                        Text("\(member.avatarEmoji)  \(member.name)")
                    }
                }
                Button(tr("➕  New person…")) { manualEntry = true; value = "" }
            } label: {
                HStack {
                    Image(systemName: "person")
                    Text(manualEntry ? "New person" : (value.isEmpty ? "Auto-detect patient" : value))
                        .lineLimit(1)
                    Spacer()
                    Image(systemName: "chevron.down")
                }
                .padding(12)
                .frame(maxWidth: .infinity)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(.separator)))
                .foregroundColor(.primary)
            }
            if manualEntry {
                TextField(tr("New patient name"), text: $value)
                    .textFieldStyle(.roundedBorder)
            }
        }
        .onAppear { members = AppSettings.familyProfiles }
    }
}
