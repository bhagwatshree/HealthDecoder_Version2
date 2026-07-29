import SwiftUI

/// Today's medicine schedule + appointments — port of `ui/RemindersScreen.kt` +
/// `ui/TodaysMedicinesTab.kt`. Slots are shown in time order with the "NOW" badge on whichever
/// slot is currently due, and each medicine can be marked Taken (logged to `med_logs`).
struct RemindersScreen: View {
    @State private var schedules: [MedicineSchedule] = []
    @State private var appointments: [AppointmentSchedule] = []
    @State private var takenToday: Set<String> = []
    @State private var editingMedicine: MedicineSchedule?
    @State private var addingMedicine = false
    @State private var editingAppointment: AppointmentSchedule?
    @State private var addingAppointment = false
    @State private var notificationsDenied = false

    /// Slot accent colours, matching `theme/Color.kt`'s time-of-day tokens.
    private static let slotStyles: [String: (bg: Color, accent: Color, emoji: String)] = [
        "Morning": (Color(hex: 0xFFF8E1), Color(hex: 0xF57F17), "🌅"),
        "Afternoon": (Color(hex: 0xE3F2FD), Color(hex: 0x1565C0), "☀️"),
        "Evening": (Color(hex: 0xE8F5E9), Color(hex: 0x2E7D32), "🌆"),
        "Night": (Color(hex: 0xF3E5F5), Color(hex: 0x6A1B9A), "🌙")
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if notificationsDenied {
                    MedicalCard(padding: 12) {
                        Label(
                            tr("Notifications are off, so reminders won't alert you. Enable them in Settings › Notifications."),
                            systemImage: "bell.slash"
                        )
                        .font(.footnote)
                        .foregroundColor(.statusLow)
                    }
                }

                Text(tr("Today's Meds")).font(.subheadline.bold()).foregroundColor(.secondary)
                if activeSlots.isEmpty {
                    MedicalCard {
                        Text(tr("No medicine reminders set. Tap + to add one."))
                            .foregroundColor(.secondary)
                            .font(.footnote)
                    }
                } else {
                    ForEach(activeSlots, id: \.slot) { entry in
                        slotSection(slot: entry.slot, items: entry.items)
                    }
                }

                Text(tr("Doctor Appointments"))
                    .font(.subheadline.bold()).foregroundColor(.secondary)
                    .padding(.top, 8)
                if appointments.isEmpty {
                    MedicalCard {
                        Text(tr("No appointments scheduled."))
                            .foregroundColor(.secondary)
                            .font(.footnote)
                    }
                } else {
                    ForEach(sortedAppointments) { appointment in
                        Button { editingAppointment = appointment } label: {
                            MedicalCard(padding: 12) { appointmentRow(appointment) }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(16)
        }
        .medicalScreenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { TopBarTitle(title: tr("Reminders")) }
        }
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Button { addingMedicine = true } label: { Label(tr("Add Medicine Reminder"), systemImage: "pills") }
                    Button { addingAppointment = true } label: { Label(tr("Add Appointment"), systemImage: "calendar.badge.plus") }
                } label: {
                    Image(systemName: "plus")
                }
            }
            ToolbarItem(placement: .navigationBarLeading) {
                NavigationLink(value: AppRoute.chat(contextHint: "Reminders")) {
                    Image(systemName: "bubble.left.and.bubble.right")
                }
            }
        }
        .onAppear {
            reload()
            Task {
                let granted = await NotificationManager.requestAuthorization()
                notificationsDenied = !granted
                if granted { NotificationManager.rescheduleAll() }
            }
        }
        .sheet(isPresented: $addingMedicine) {
            MedicineReminderEditSheet(existing: nil) { reload() }
        }
        .sheet(item: $editingMedicine) { schedule in
            MedicineReminderEditSheet(existing: schedule) { reload() }
        }
        .sheet(isPresented: $addingAppointment) {
            AppointmentEditSheet(existing: nil) { reload() }
        }
        .sheet(item: $editingAppointment) { appointment in
            AppointmentEditSheet(existing: appointment) { reload() }
        }
    }

    // MARK: - Derived data

    private struct SlotEntry {
        let slot: String
        let items: [(schedule: MedicineSchedule, config: SlotConfig)]
    }

    /// Enabled slots in time order, each with the medicines due then.
    private var activeSlots: [SlotEntry] {
        MedicineScheduleStore.slotOrder.compactMap { slot in
            let items = schedules.compactMap { schedule -> (MedicineSchedule, SlotConfig)? in
                guard let config = schedule.slots[slot], config.enabled else { return nil }
                return (schedule, config)
            }
            return items.isEmpty ? nil : SlotEntry(slot: slot, items: items)
        }
    }

    private var sortedAppointments: [AppointmentSchedule] {
        appointments.sorted { ($0.date, $0.time) < ($1.date, $1.time) }
    }

    /// The slot whose time is nearest to now without being in the future — gets the NOW badge.
    private var currentSlot: String? {
        let now = Calendar.current.dateComponents([.hour, .minute], from: Date())
        let nowMinutes = (now.hour ?? 0) * 60 + (now.minute ?? 0)
        return activeSlots
            .compactMap { entry -> (String, Int)? in
                guard let config = entry.items.first?.config else { return nil }
                let minutes = config.hour * 60 + config.minute
                return minutes <= nowMinutes ? (entry.slot, minutes) : nil
            }
            .max { $0.1 < $1.1 }?.0
    }

    // MARK: - Rows

    @ViewBuilder
    private func slotSection(slot: String, items: [(schedule: MedicineSchedule, config: SlotConfig)]) -> some View {
        let style = Self.slotStyles[slot] ?? (Color(.secondarySystemBackground), .primary, "💊")
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("\(style.emoji) \(slot)")
                    .font(.subheadline.bold())
                    .foregroundColor(style.accent)
                if let config = items.first?.config {
                    Text(String(format: "%02d:%02d", config.hour, config.minute))
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                if currentSlot == slot {
                    Text(tr("NOW"))
                        .font(.caption2.bold())
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.statusHigh)
                        .foregroundColor(.white)
                        .clipShape(Capsule())
                }
                Spacer()
            }

            ForEach(items, id: \.schedule.id) { item in
                let key = "\(slot)|\(item.schedule.id)"
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.schedule.medicineName).fontWeight(.semibold)
                        let detail = [item.schedule.dosage, item.schedule.patientName]
                            .filter { !$0.isEmpty }.joined(separator: " • ")
                        if !detail.isEmpty {
                            Text(detail).font(.caption).foregroundColor(.secondary)
                        }
                    }
                    Spacer()
                    Button {
                        markTaken(item.schedule, slot: slot, key: key)
                    } label: {
                        Image(systemName: takenToday.contains(key) ? "checkmark.circle.fill" : "circle")
                            .foregroundColor(takenToday.contains(key) ? .statusNormal : .secondary)
                            .font(.title3)
                    }
                    .buttonStyle(.plain)
                    Button { editingMedicine = item.schedule } label: {
                        Image(systemName: "pencil").foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(style.bg)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func appointmentRow(_ appointment: AppointmentSchedule) -> some View {
        HStack(spacing: 12) {
            Text("🩺").font(.title3)
            VStack(alignment: .leading, spacing: 2) {
                Text("Dr. \(appointment.doctorName)").fontWeight(.semibold)
                if !appointment.place.isEmpty {
                    Text(appointment.place).font(.caption).foregroundColor(.secondary)
                }
                HStack(spacing: 6) {
                    Text("\(appointment.date) · \(appointment.time)")
                        .font(.caption)
                        .foregroundColor(.medicalTeal)
                    if appointment.recurrence != "None" {
                        Text(appointment.recurrence)
                            .font(.caption2)
                            .padding(.horizontal, 5).padding(.vertical, 1)
                            .background(Color.medicalSurfaceVariant)
                            .clipShape(Capsule())
                    }
                }
            }
            Spacer()
        }
    }

    // MARK: - Actions

    private func reload() {
        schedules = MedicineScheduleStore.loadAll()
        appointments = AppointmentStore.loadAll()
        loadTakenToday()
    }

    /// Rebuilds today's "taken" ticks from the intake log so they survive leaving the screen.
    private func loadTakenToday() {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        let today = formatter.string(from: Date())
        let logs = LocalRepository.medLogs(onDatePrefix: today, actionType: "TAKEN")
        takenToday = Set(logs.compactMap { log -> String? in
            guard let slot = log.notes, !slot.isEmpty else { return nil }
            return "\(slot)|\(log.patientName.lowercased())|\(log.medicineName.lowercased())"
        })
    }

    private func markTaken(_ schedule: MedicineSchedule, slot: String, key: String) {
        guard !takenToday.contains(key) else { return }
        LocalRepository.saveMedLog(MedLogEntry(
            id: UUID().uuidString,
            patientName: schedule.patientName,
            medicineName: schedule.medicineName,
            actionType: "TAKEN",
            frequency: schedule.frequency,
            notes: slot,
            takenAt: ISO8601DateFormatter().string(from: Date())
        ))
        takenToday.insert(key)
    }
}

// MARK: - Edit sheets

struct MedicineReminderEditSheet: View {
    let existing: MedicineSchedule?
    let onSaved: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var medicineName: String
    @State private var patientName: String
    @State private var dosage: String
    @State private var frequency: String
    @State private var slots: [String: SlotConfig]

    init(existing: MedicineSchedule?, onSaved: @escaping () -> Void) {
        self.existing = existing
        self.onSaved = onSaved
        _medicineName = State(initialValue: existing?.medicineName ?? "")
        _patientName = State(initialValue: existing?.patientName ?? (AppSettings.activePatient ?? ""))
        _dosage = State(initialValue: existing?.dosage ?? "")
        _frequency = State(initialValue: existing?.frequency ?? "")
        var initial = existing?.slots ?? [:]
        for (slot, time) in MedicineScheduleStore.defaultSlotTimes where initial[slot] == nil {
            initial[slot] = SlotConfig(enabled: false, hour: time.hour, minute: time.minute)
        }
        _slots = State(initialValue: initial)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(tr("Medicine Name"), text: $medicineName)
                    TextField(tr("Patient"), text: $patientName)
                    TextField(tr("Dosage (e.g. 500mg)"), text: $dosage)
                    TextField(tr("Frequency (e.g. Twice daily)"), text: $frequency)
                }
                Section(tr("When to remind")) {
                    ForEach(MedicineScheduleStore.slotOrder, id: \.self) { slot in
                        slotRow(slot)
                    }
                }
                if existing != nil {
                    Section {
                        Button(tr("Delete reminder"), role: .destructive) {
                            MedicineScheduleStore.delete(
                                medicineName: existing!.medicineName,
                                patientName: existing!.patientName
                            )
                            onSaved()
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle(existing == nil ? "Add reminder" : "Edit reminder")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(tr("Cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(tr("Save")) { save() }
                        .disabled(medicineName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }

    @ViewBuilder
    private func slotRow(_ slot: String) -> some View {
        let config = slots[slot] ?? SlotConfig()
        VStack {
            Toggle(slot, isOn: Binding(
                get: { slots[slot]?.enabled ?? false },
                set: { slots[slot, default: config].enabled = $0 }
            ))
            if slots[slot]?.enabled == true {
                DatePicker(
                    "Time",
                    selection: Binding(
                        get: {
                            var components = DateComponents()
                            components.hour = slots[slot]?.hour ?? config.hour
                            components.minute = slots[slot]?.minute ?? config.minute
                            return Calendar.current.date(from: components) ?? Date()
                        },
                        set: { newDate in
                            let parts = Calendar.current.dateComponents([.hour, .minute], from: newDate)
                            slots[slot, default: config].hour = parts.hour ?? 8
                            slots[slot, default: config].minute = parts.minute ?? 0
                        }
                    ),
                    displayedComponents: .hourAndMinute
                )
            }
        }
    }

    private func save() {
        let trimmedName = medicineName.trimmingCharacters(in: .whitespaces)
        let trimmedPatient = patientName.trimmingCharacters(in: .whitespaces)

        // Renaming an existing reminder must not leave the old one firing.
        if let existing, existing.medicineName != trimmedName || existing.patientName != trimmedPatient {
            MedicineScheduleStore.delete(
                medicineName: existing.medicineName, patientName: existing.patientName
            )
        }
        MedicineScheduleStore.upsert(MedicineSchedule(
            medicineName: trimmedName,
            patientName: trimmedPatient,
            dosage: dosage.trimmingCharacters(in: .whitespaces),
            frequency: frequency.trimmingCharacters(in: .whitespaces),
            slots: slots
        ))
        onSaved()
        dismiss()
    }
}

struct AppointmentEditSheet: View {
    let existing: AppointmentSchedule?
    let onSaved: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var doctorName: String
    @State private var place: String
    @State private var recurrence: String
    @State private var when: Date

    init(existing: AppointmentSchedule?, onSaved: @escaping () -> Void) {
        self.existing = existing
        self.onSaved = onSaved
        _doctorName = State(initialValue: existing?.doctorName ?? "")
        _place = State(initialValue: existing?.place ?? "")
        _recurrence = State(initialValue: existing?.recurrence ?? "None")

        var initialDate = Date()
        if let existing {
            let formatter = DateFormatter()
            formatter.dateFormat = "yyyy-MM-dd"
            if let day = formatter.date(from: existing.date) {
                var components = Calendar.current.dateComponents([.year, .month, .day], from: day)
                components.hour = existing.hour
                components.minute = existing.minute
                initialDate = Calendar.current.date(from: components) ?? Date()
            }
        }
        _when = State(initialValue: initialDate)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(tr("Doctor Name"), text: $doctorName)
                    TextField(tr("Place / clinic"), text: $place)
                }
                Section {
                    DatePicker(tr("Date & time"), selection: $when)
                    Picker(tr("Repeats"), selection: $recurrence) {
                        ForEach(AppointmentSchedule.recurrenceOptions, id: \.self) { Text($0).tag($0) }
                    }
                }
                if let existing {
                    Section {
                        Button(tr("Delete Appointment"), role: .destructive) {
                            AppointmentStore.delete(id: existing.id)
                            onSaved()
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle(existing == nil ? "Add appointment" : "Edit appointment")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(tr("Cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(tr("Save")) { save() }
                        .disabled(doctorName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }

    private func save() {
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd"
        let timeFormatter = DateFormatter()
        timeFormatter.dateFormat = "HH:mm"
        let parts = Calendar.current.dateComponents([.hour, .minute], from: when)

        AppointmentStore.upsert(AppointmentSchedule(
            id: existing?.id ?? UUID().uuidString,
            doctorName: doctorName.trimmingCharacters(in: .whitespaces),
            date: dateFormatter.string(from: when),
            time: timeFormatter.string(from: when),
            place: place.trimmingCharacters(in: .whitespaces),
            recurrence: recurrence,
            hour: parts.hour ?? 9,
            minute: parts.minute ?? 0
        ))
        onSaved()
        dismiss()
    }
}
